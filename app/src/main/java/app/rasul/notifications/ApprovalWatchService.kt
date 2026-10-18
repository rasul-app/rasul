package app.rasul.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.rasul.AppContainer
import app.rasul.MainActivity
import app.rasul.R
import app.rasul.RasulApp
import app.rasul.bridge.ApprovalItemDto
import app.rasul.bridge.ApprovalsRepository
import app.rasul.bridge.BridgeBotsState
import app.rasul.bridge.bridgeBotId
import app.rasul.security.publicOnly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Intent extra carrying the validated `approvals/{bridge}/{profile}` route for the tap target. */
const val APPROVALS_ROUTE = "approvals_route"

private const val ACTION_STOP = "app.rasul.notifications.APPROVAL_WATCH_STOP"
internal const val APPROVALS_CHANNEL = "approvals"
internal const val WATCH_CHANNEL = "approval_watch"
internal const val WATCH_ID = 4
internal const val APPROVAL_ALERT_ID = 5
internal const val PAUSED_ID = 6
internal const val PAUSED_TAG = "approval_paused"
private const val EXPIRY_SWEEP_MS = 30_000L
private const val FOLLOW_RETRY_MS = 5_000L

internal fun approvalAlertTag(botKey: String, requestId: String) = "$botKey/$requestId"

/** `bridge:<bridgeId>:<profileId>`; profile ids never contain a colon. */
internal fun approvalBotBridge(botKey: String) = botKey.removePrefix("bridge:").substringBeforeLast(':')
internal fun approvalBotProfile(botKey: String) = botKey.substringAfterLast(':')

/** One system notification to post. */
internal data class ApprovalAlert(val tag: String, val bridgeId: String, val profileId: String, val item: ApprovalItemDto)

/**
 * Decides which pending approvals need a system notification, per service run. A snapshot
 * repeating an already-alerted id never posts again; a request that leaves the pending list
 * (resolved, withdrawn or expired) cancels its notification. A bot whose approvals screen is
 * visible is suppressed but not marked alerted, so closing the screen still posts it once if the
 * request stays pending.
 */
internal class ApprovalAlertDecider {
    private val alerted = mutableSetOf<String>()
    fun liveTags(): Set<String> = alerted.toSet()

    fun diff(
        feeds: Map<String, ApprovalsRepository.Feed>,
        visibleBot: String?,
        nowSec: Long,
    ): Pair<List<ApprovalAlert>, List<String>> {
        val live = mutableSetOf<String>()
        val alerts = mutableListOf<ApprovalAlert>()
        for ((botKey, feed) in feeds) {
            for (item in feed.items) {
                if (item.expiresAt <= nowSec) continue
                val tag = approvalAlertTag(botKey, item.requestId)
                live += tag
                if (tag in alerted || botKey == visibleBot) continue
                alerted += tag
                alerts += ApprovalAlert(tag, approvalBotBridge(botKey), approvalBotProfile(botKey), item)
            }
        }
        val gone = alerted.filter { it !in live }
        alerted.removeAll(gone.toSet())
        return alerts to gone
    }
}

/** Starts/stops [ApprovalWatchService] to match (setting ON and at least one paired bridge). */
class ApprovalAlerts(private val container: AppContainer, private val context: Context) {
    suspend fun sync() {
        var enabled = try {
            container.settings.values.first().approvalAlerts
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        val bridges = try {
            container.bridges.bridges.first()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return } // unreadable storage is not "no PC": change nothing
        if (enabled && bridges.isEmpty()) {
            // A stale ON cannot survive the last saved PC: clear it so the toggle matches reality.
            try { container.settings.setApprovalAlerts(false) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            enabled = false
        }
        val intent = Intent(context, ApprovalWatchService::class.java)
        // Denied notifications: nothing could be shown, so do not hold a foreground service. The setting
        // stays as the user left it and works again once notifications are allowed.
        if (enabled && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // Started from the background or the daily dataSync budget is spent; the next
                // foreground sync retries.
                if (Build.VERSION.SDK_INT < 31 || e !is android.app.ForegroundServiceStartNotAllowedException) throw e
            }
        } else {
            context.stopService(intent)
        }
    }
}

/**
 * Opt-in watcher: while enabled it runs the shared approvals follower (one SSE per bridge bot via
 * [ApprovalsRepository.follow]) and posts a high-importance notification for each NEW pending
 * approval. The command text never enters a notification; the public version says only "Approval
 * waiting". No BOOT_COMPLETED receiver: on Android 15+ BOOT_COMPLETED may not launch a dataSync
 * foreground service, so the watcher restarts on the next app open instead.
 */
class ApprovalWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as RasulApp).container
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val decider = ApprovalAlertDecider()
    private var lastStartId = 0
    private var stopping = false
    private var watchedPcs = -1

    override fun onCreate() {
        super.onCreate()
        createApprovalChannels(this)
        val foreground = watchNotification(container.bridgeBots.state.value.groups.size)
        if (Build.VERSION.SDK_INT >= 29) startForeground(WATCH_ID, foreground, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(WATCH_ID, foreground)
        scope.launch { followForever { container.bridgeBots.follow() } }
        scope.launch { followForever { container.approvals.follow() } }
        scope.launch {
            combine(
                container.approvals.state,
                container.bridgeBots.state,
                container.approvalsVisible,
            ) { _, bots, _ -> bots }
                .collect { bots ->
                    publish()
                    if (noBotRemains(bots)) shutdown() // the setting stays ON; the next app open restarts it
                }
        }
        scope.launch {
            container.appLock.state.collect { lock ->
                if (lock.publicOnly()) redactPublishedNotifications(this@ApprovalWatchService)
                publish()
            }
        }
        scope.launch { container.settings.values.collect { if (!it.approvalAlerts) shutdown() } }
        scope.launch {
            try {
                container.bridges.bridges.collect { saved ->
                    if (saved.isEmpty()) {
                        try { container.settings.setApprovalAlerts(false) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { }
                        shutdown()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { } // unreadable storage: keep running, change nothing
        }
        scope.launch { while (currentCoroutineContext().isActive) { delay(EXPIRY_SWEEP_MS); publish() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                try { container.settings.setApprovalAlerts(false) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
                shutdown()
            }
            return START_NOT_STICKY
        }
        publish()
        return START_NOT_STICKY
    }

    /** A follower that returned (storage error) or threw restarts after a pause. */
    private suspend fun followForever(block: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            if (currentCoroutineContext().isActive) delay(FOLLOW_RETRY_MS)
        }
    }

    private fun publish() {
        if (stopping) return
        val groups = container.bridgeBots.state.value.groups
        if (groups.size != watchedPcs) {
            watchedPcs = groups.size
            manager.notify(WATCH_ID, watchNotification(watchedPcs))
        }
        // Not posted means not alerted: with notifications denied nothing is marked, so allowing them
        // later still alerts the requests that are still pending.
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val (alerts, gone) = decider.diff(
            container.approvals.state.value, container.approvalsVisible.value,
            System.currentTimeMillis() / 1000,
        )
        gone.forEach { manager.cancel(it, APPROVAL_ALERT_ID) }
        if (alerts.isEmpty()) return
        val names = botNames()
        val publicOnly = container.appLock.state.value.publicOnly()
        alerts.forEach { alert ->
            manager.notify(alert.tag, APPROVAL_ALERT_ID,
                buildApprovalNotification(this, alert, names[bridgeBotId(alert.bridgeId, alert.profileId)] ?: alert.profileId, publicOnly))
        }
    }

    private fun botNames(): Map<String, String> {
        val names = HashMap<String, String>()
        container.bridgeBots.state.value.groups.forEach { group ->
            group.profiles.forEach { names[bridgeBotId(group.id, it.id)] = it.displayName.ifBlank { group.name } }
        }
        return names
    }

    private fun watchNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, ApprovalWatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, WATCH_CHANNEL)
            .setSmallIcon(R.drawable.rasul_signal_monochrome)
            .setContentTitle(resources.getQuantityString(R.plurals.approval_watching, count, count))
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.approval_watch_stop), stop)
            .build()
    }

    /** The 6 h/day dataSync budget is shared with RunWatchService; keep the setting ON so the
     * next app-open sync restarts the watcher (reaching the foreground resets the budget). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        decider.liveTags().forEach { manager.cancel(it, APPROVAL_ALERT_ID) }
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val open = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(PAUSED_TAG, PAUSED_ID, NotificationCompat.Builder(this, WATCH_CHANNEL)
                .setSmallIcon(R.drawable.rasul_signal_monochrome)
                .setContentTitle(getString(R.string.approval_paused))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build())
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        decider.liveTags().forEach { manager.cancel(it, APPROVAL_ALERT_ID) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** True when the saved PCs have all answered and none offers a bot, so there is nothing to watch. */
internal fun noBotRemains(bots: BridgeBotsState): Boolean =
    bots.loaded && !bots.storageError && bots.groups.isNotEmpty() &&
        bots.groups.all { !it.loading && it.error == null && it.profiles.isEmpty() }

/**
 * One alert notification: bot + surface in the title, redacted description as the text. With
 * [publicOnly] (app lock on, or its state not yet read) only "Approval waiting" is shown.
 */
internal fun buildApprovalNotification(context: Context, alert: ApprovalAlert, botName: String, publicOnly: Boolean = false): Notification {
    val route = "approvals/" + Uri.encode(alert.bridgeId) + "/" + Uri.encode(alert.profileId)
    val open = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("rasul:$route"))
            .putExtra(APPROVALS_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val public = NotificationCompat.Builder(context, APPROVALS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(context.getString(R.string.approval_alert_waiting))
        .setContentIntent(open)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
    if (publicOnly) return public
    val text = alert.item.description.ifBlank { context.getString(R.string.approval_alert_waiting) }.take(600)
    return NotificationCompat.Builder(context, APPROVALS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(context.getString(R.string.approval_alert_title, botName, alert.item.surface))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(public)
        .setCategory(NotificationCompat.CATEGORY_EVENT)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
}

internal fun createApprovalChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(APPROVALS_CHANNEL, context.getString(R.string.approvals_title), NotificationManager.IMPORTANCE_HIGH))
    manager.createNotificationChannel(NotificationChannel(WATCH_CHANNEL, context.getString(R.string.approval_channel_watch), NotificationManager.IMPORTANCE_LOW))
}

/** Strict `approvals/{bridge}/{profile}`: canonical encoded segments, profile per the bridge id contract. */
fun isApprovalsRoute(route: String): Boolean {
    val parts = route.split('/')
    if (parts.size != 3 || parts[0] != "approvals") return false
    val bridge = parts[1]
    val profile = parts[2]
    return bridge.isNotEmpty() && profile.isNotEmpty() &&
        Uri.encode(Uri.decode(bridge)) == bridge && Uri.encode(Uri.decode(profile)) == profile &&
        app.rasul.bridge.APPROVAL_PROFILE_ID.matches(Uri.decode(profile))
}
