package app.rasul.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.rasul.AppContainer
import app.rasul.MainActivity
import app.rasul.R
import app.rasul.RasulApp
import app.rasul.bridge.ApprovalItemDto
import app.rasul.bridge.ApprovalsError
import app.rasul.bridge.ApprovalsRepository
import app.rasul.bridge.BridgeBotsState
import app.rasul.bridge.bridgeBotId
import app.rasul.data.HermesResult
import app.rasul.security.publicOnly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
private const val LOCK_STATE_WAIT_MS = 5_000L
private const val BOTS_WAIT_MS = 20_000L

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

/**
 * Starts/stops [ApprovalWatchService] to match (approval alerts or job results ON, and at least one paired bridge).
 * One service serves both settings and only does the work of the ones that are ON.
 */
class ApprovalAlerts(private val container: AppContainer, private val context: Context) {
    suspend fun sync() {
        val settings = try {
            container.settings.values.first()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        var enabled = settings != null && (settings.approvalAlerts || settings.jobAlerts)
        val bridges = try {
            container.bridges.bridges.first()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return } // unreadable storage is not "no PC": change nothing
        if (enabled && bridges.isEmpty()) {
            // A stale ON cannot survive the last saved PC: clear it so the toggle matches reality.
            clearAlertSettings(container)
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

/** Turns both alert settings off and forgets the job-run map, so a later opt-in starts from a fresh seed. */
internal suspend fun clearAlertSettings(container: AppContainer) {
    try { container.settings.setApprovalAlerts(false) }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { }
    try { container.settings.setJobAlerts(false) }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { }
    try { container.jobSeen.clear() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { }
}

/**
 * Opt-in watcher: while enabled it runs the shared approvals follower (one SSE per bridge bot via
 * [ApprovalsRepository.follow]) and posts a high-importance notification for each NEW pending
 * approval. The command text never enters a notification; the public version says only "Approval
 * waiting". With job results ON it also polls each paired bot's job list every [JOB_POLL_MS] (and once when the
 * app comes to the foreground) and posts one notification per newly finished run. No BOOT_COMPLETED receiver: on Android 15+ BOOT_COMPLETED may not launch a dataSync
 * foreground service, so the watcher restarts on the next app open instead.
 */
class ApprovalWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as RasulApp).container
    private val manager get() = getSystemService(NotificationManager::class.java)
    private var decider = ApprovalAlertDecider()
    private var approvalsOn = false
    private var jobsOn = false
    private var jobsPolling = false
    private val jobTrigger = Channel<Unit>(Channel.CONFLATED)
    private val jobGate = JobPollGate(SystemClock::elapsedRealtime)

    /** What each live alert needs to be posted again, e.g. after a failed answer. */
    private val posted = HashMap<String, Pair<ApprovalAlert, String>>()
    private val answerer = ApprovalAnswerer(
        { bridgeId, profileId -> container.approvals.api(bridgeId, profileId) },
        object : ApprovalAnswerUi {
            override fun sending(tag: String) = showSending(tag)
            override fun finished(tag: String) = manager.cancel(tag, APPROVAL_ALERT_ID)
            override fun settledElsewhere(tag: String, textRes: Int) { manager.cancel(tag, APPROVAL_ALERT_ID); showNotice(tag, textRes) }
            override fun failed(tag: String, error: ApprovalsError) = repost(tag, getString(error.message))
        },
    )
    private var lastStartId = 0
    private var stopping = false
    private var watchedPcs = -1
    private var watchedMode = -1

    override fun onCreate() {
        super.onCreate()
        createApprovalChannels(this)
        val foreground = watchNotification(container.bridgeBots.state.value.groups.size)
        if (Build.VERSION.SDK_INT >= 29) startForeground(WATCH_ID, foreground, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(WATCH_ID, foreground)
        scope.launch { followForever { container.bridgeBots.follow() } }
        scope.launch {
            container.bridgeBots.state.collect { bots ->
                publishWatch()
                if (noBotRemains(bots)) shutdown() // the settings stay ON; the next app open restarts it
            }
        }
        scope.launch {
            container.settings.values.map { it.approvalAlerts }.distinctUntilChanged().collectLatest { on ->
                approvalsOn = on
                if (!on) {
                    withdrawApprovalAlerts()
                    return@collectLatest
                }
                coroutineScope {
                    launch { followForever { container.approvals.follow() } }
                    launch {
                        combine(
                            container.approvals.state,
                            container.bridgeBots.state,
                            container.approvalsVisible,
                        ) { _, bots, _ -> bots }.collect { publish() }
                    }
                    launch { while (currentCoroutineContext().isActive) { delay(EXPIRY_SWEEP_MS); publish() } }
                }
            }
        }
        scope.launch {
            container.settings.values.map { it.jobAlerts }.distinctUntilChanged().collectLatest { on ->
                jobsOn = on
                publishWatch()
                if (!on) {
                    cancelJobNotifications(this@ApprovalWatchService)
                    return@collectLatest
                }
                pollJobsForever()
            }
        }
        scope.launch {
            container.appLock.state.collect { lock ->
                if (lock.publicOnly()) redactPublishedNotifications(this@ApprovalWatchService)
                publish()
            }
        }
        scope.launch { container.settings.values.collect { if (!it.approvalAlerts && !it.jobAlerts) shutdown() } }
        scope.launch {
            try {
                container.bridges.bridges.collect { saved ->
                    if (saved.isEmpty()) {
                        clearAlertSettings(container)
                        shutdown()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { } // unreadable storage: keep running, change nothing
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_APPROVAL_ANSWER) {
            val request = parseApprovalAnswer(intent)
            if (request != null) scope.launch { answerIfAllowed(request) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                clearAlertSettings(container)
                shutdown()
            }
            return START_NOT_STICKY
        }
        publish()
        // The app came to the foreground: read the jobs once now instead of waiting for the next tick.
        if (jobsPolling && jobGate.foregroundDue()) jobTrigger.trySend(Unit)
        return START_NOT_STICKY
    }

    /**
     * Sends the answer only with the app lock off and the phone unlocked. The lock state may still be
     * loading right after process death, so wait for it. Another app or a watch can fire the button's
     * PendingIntent with the phone locked: then nothing is sent and the alert is left as it is.
     */
    private suspend fun answerIfAllowed(request: ApprovalAnswerRequest) {
        val lock = withTimeoutOrNull(LOCK_STATE_WAIT_MS) { container.appLock.state.first { it.loaded } }
        if (stopping || lock == null || lock.publicOnly()) return
        // Nothing was changed on the alert yet: it is still in the shade, so leave it alone.
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        answerer.handle(request)
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

    /** Keeps the ongoing notification's PC count (and wording) current. */
    private fun publishWatch() {
        if (stopping) return
        val groups = container.bridgeBots.state.value.groups
        val mode = (if (approvalsOn) 1 else 0) + (if (jobsOn) 2 else 0)
        if (groups.size != watchedPcs || mode != watchedMode) {
            watchedPcs = groups.size
            watchedMode = mode
            manager.notify(WATCH_ID, watchNotification(watchedPcs))
        }
    }

    private fun withdrawApprovalAlerts() {
        decider.liveTags().forEach { manager.cancel(it, APPROVAL_ALERT_ID) }
        posted.clear()
        decider = ApprovalAlertDecider()
        publishWatch()
    }

    private fun publish() {
        if (stopping) return
        publishWatch()
        if (!approvalsOn) return
        // Not posted means not alerted: with notifications denied nothing is marked, so allowing them
        // later still alerts the requests that are still pending.
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val (alerts, gone) = decider.diff(
            container.approvals.state.value, container.approvalsVisible.value,
            System.currentTimeMillis() / 1000,
        )
        gone.forEach { manager.cancel(it, APPROVAL_ALERT_ID); posted.remove(it) }
        if (alerts.isEmpty()) return
        val names = botNames()
        val publicOnly = container.appLock.state.value.publicOnly()
        alerts.forEach { alert ->
            val name = names[bridgeBotId(alert.bridgeId, alert.profileId)] ?: alert.profileId
            posted[alert.tag] = alert to name
            manager.notify(alert.tag, APPROVAL_ALERT_ID, buildApprovalNotification(this, alert, name, publicOnly, withActions = !publicOnly))
        }
    }

    /** One read of every paired bot now, then again every [JOB_POLL_MS] or when the app returns to the foreground. */
    private suspend fun pollJobsForever() {
        jobsPolling = true
        try {
            while (currentCoroutineContext().isActive) {
                try { pollJobsOnce() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { } // a failed read leaves the map as it is; the next tick retries
                jobGate.finished()
                withTimeoutOrNull(JOB_POLL_MS) { jobTrigger.receive() }
            }
        } finally {
            jobsPolling = false
        }
    }

    /**
     * Reads GET /api/jobs per ready bot. A bot that cannot be read now keeps its recorded runs. Alerts are posted
     * before the map is saved, so a crash in between repeats a notification rather than losing one.
     */
    private suspend fun pollJobsOnce() {
        if (stopping || !NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val bots = container.bridgeBots
        withTimeoutOrNull(BOTS_WAIT_MS) { bots.state.first { it.loaded && it.groups.none { group -> group.loading } } }
        val groups = bots.state.value.groups
        var seen = container.jobSeen.load()
        val alerts = mutableListOf<JobAlert>()
        val liveBots = mutableSetOf<String>()
        val unsettled = mutableListOf<String>()
        for (group in groups) {
            if (group.loading || group.error != null) unsettled += group.id
            for (profile in group.profiles) {
                liveBots += bridgeBotId(group.id, profile.id)
                val target = (bots.target(group.id, profile.id) as? HermesResult.Ok)?.value ?: continue
                val jobs = (container.jobs.list(target) as? HermesResult.Ok)?.value ?: continue
                val poll = decideJobAlerts(group.id, profile.id, jobs, seen)
                seen = poll.seen
                alerts += poll.alerts
            }
        }
        if (stopping || !jobsOn) return
        postJobAlerts(this, alerts, container.appLock.state.value.publicOnly())
        container.jobSeen.save(pruneJobSeen(seen, liveBots, unsettled))
    }

    private fun showSending(tag: String) {
        manager.notify(tag, APPROVAL_ALERT_ID, buildApprovalSendingNotification(this))
    }

    /**
     * Puts a failed alert back with its buttons. A request this run knows has left the list is dropped.
     * One this run never alerted (fresh process, feed not loaded yet) gets a plain alert with the reason.
     */
    private fun repost(tag: String, errorText: String) {
        val entry = posted[tag]
        if (entry == null) {
            val bot = tag.substringBeforeLast('/')
            manager.notify(tag, APPROVAL_ALERT_ID,
                buildApprovalFailedNotification(this, approvalBotBridge(bot), approvalBotProfile(bot), errorText))
            return
        }
        if (tag !in decider.liveTags()) { manager.cancel(tag, APPROVAL_ALERT_ID); return }
        val (alert, name) = entry
        val publicOnly = container.appLock.state.value.publicOnly()
        manager.notify(tag, APPROVAL_ALERT_ID, buildApprovalNotification(this, alert, name, publicOnly,
            withActions = !publicOnly, errorText = errorText.takeUnless { publicOnly }, onlyAlertOnce = true))
    }

    private fun showNotice(tag: String, textRes: Int) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        manager.notify(tag, APPROVAL_NOTICE_ID, buildApprovalNotice(this, getString(textRes)))
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
            .setContentTitle(resources.getQuantityString(
                if (approvalsOn || !jobsOn) R.plurals.approval_watching else R.plurals.job_watching, count, count))
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
                .setContentTitle(getString(if (approvalsOn || !jobsOn) R.string.approval_paused else R.string.job_alerts_paused))
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
internal fun buildApprovalNotification(
    context: Context,
    alert: ApprovalAlert,
    botName: String,
    publicOnly: Boolean = false,
    withActions: Boolean = false,
    errorText: String? = null,
    onlyAlertOnce: Boolean = false,
): Notification {
    val open = approvalOpenIntent(context, alert.bridgeId, alert.profileId)
    val public = NotificationCompat.Builder(context, APPROVALS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(context.getString(R.string.approval_alert_waiting))
        .setContentIntent(open)
        .setAutoCancel(true)
        .setOnlyAlertOnce(onlyAlertOnce)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
    if (publicOnly) return public
    val described = alert.item.description.ifBlank { context.getString(R.string.approval_alert_waiting) }.take(600)
    val text = if (errorText == null) described else "$errorText\n$described"
    val builder = NotificationCompat.Builder(context, APPROVALS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(context.getString(R.string.approval_alert_title, botName, alert.item.surface))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(public)
        .setCategory(NotificationCompat.CATEGORY_EVENT)
        .setContentIntent(open)
        .setAutoCancel(true)
        .setOnlyAlertOnce(onlyAlertOnce)
    // setAuthenticationRequired only takes effect on Android 12+; below that the alert is tap-to-open only.
    // The buttons stay on the phone (a watch could not unlock it); without them the alert is as before.
    if (withActions && Build.VERSION.SDK_INT >= 31) {
        builder.setLocalOnly(true)
        builder.addAction(answerAction(context, alert, "once", R.string.approval_action_once))
        builder.addAction(answerAction(context, alert, "deny", R.string.approval_action_deny))
    }
    return builder.build()
}

private fun approvalOpenIntent(context: Context, bridgeId: String, profileId: String): PendingIntent {
    val route = "approvals/" + Uri.encode(bridgeId) + "/" + Uri.encode(profileId)
    return PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("rasul:$route"))
            .putExtra(APPROVALS_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** A failed answer for an alert this run never posted: the reason, tap to open the approvals, no buttons. */
internal fun buildApprovalFailedNotification(context: Context, bridgeId: String, profileId: String, errorText: String): Notification =
    NotificationCompat.Builder(context, APPROVALS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(context.getString(R.string.approval_alert_waiting))
        .setContentText(errorText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(errorText))
        .setContentIntent(approvalOpenIntent(context, bridgeId, profileId))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setTimeoutAfter(APPROVAL_NOTICE_TIMEOUT_MS)
        .build()

/** An explicit, immutable intent to the watcher service; the unlock requirement applies to every button. */
private fun answerAction(context: Context, alert: ApprovalAlert, choice: String, label: Int): NotificationCompat.Action {
    val request = alert.item.requestId
    val intent = Intent(context, ApprovalWatchService::class.java)
        .setAction(ACTION_APPROVAL_ANSWER)
        .setData(Uri.parse("rasul:approval-answer/" + Uri.encode(choice) + "/" + Uri.encode(alert.bridgeId) + "/" +
            Uri.encode(alert.profileId) + "/" + Uri.encode(request)))
        .putExtra(EXTRA_APPROVAL_BRIDGE, alert.bridgeId)
        .putExtra(EXTRA_APPROVAL_PROFILE, alert.profileId)
        .putExtra(EXTRA_APPROVAL_REQUEST, request)
        .putExtra(EXTRA_APPROVAL_CHOICE, choice)
    val pending = PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Action.Builder(0, context.getString(label), pending)
        .setAuthenticationRequired(true).build()
}

/** The alert while its answer is on the wire: no buttons, no command text. */
internal fun buildApprovalSendingNotification(context: Context): Notification =
    NotificationCompat.Builder(context, APPROVALS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(context.getString(R.string.approval_sending))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setTimeoutAfter(APPROVAL_SENDING_TIMEOUT_MS)
        .build()

/** A short silent notice that removes itself after [APPROVAL_NOTICE_TIMEOUT_MS]. */
internal fun buildApprovalNotice(context: Context, text: String): Notification =
    NotificationCompat.Builder(context, APPROVALS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(text)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setSilent(true)
        .setAutoCancel(true)
        .setTimeoutAfter(APPROVAL_NOTICE_TIMEOUT_MS)
        .build()

internal fun createApprovalChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(APPROVALS_CHANNEL, context.getString(R.string.approvals_title), NotificationManager.IMPORTANCE_HIGH))
    manager.createNotificationChannel(NotificationChannel(WATCH_CHANNEL, context.getString(R.string.approval_channel_watch), NotificationManager.IMPORTANCE_LOW))
    manager.createNotificationChannel(NotificationChannel(JOB_RESULTS_CHANNEL, context.getString(R.string.job_alerts_channel), NotificationManager.IMPORTANCE_DEFAULT))
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
