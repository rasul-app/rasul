package app.rasul.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.rasul.MainActivity
import app.rasul.R
import app.rasul.RasulApp
import app.rasul.security.publicOnly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

const val CHAT_ROUTE = "chat_route"
const val REPLY_TEXT = "reply_text"
internal const val REPLY_TARGET = "reply_target"
internal const val REPLY_PROFILE = "reply_profile"
internal const val REPLY_SESSION = "reply_session"
private const val ACTIVITY_CHANNEL = "bot_activity"
private const val REPLIES_CHANNEL = "bot_replies"
private const val SERVICE_ID = 1
internal const val REPLY_TAG = "reply_tag"
private const val RUN_GROUP = "runs"

class RunWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as RasulApp).container
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val seen = mutableMapOf<String, RunNotice>()
    private val watched = mutableSetOf<String>()
    private val shownTitles = mutableMapOf<String, String>()
    private val throttles = mutableMapOf<String, UpdateThrottle>()
    private var started = false
    private var lastStartId = 0
    private var stopping = false
    private var replyInFlight = 0
    private var ticker: Job? = null
    private val publishedTags = mutableMapOf<app.rasul.chat.ChatViewModel, String>()
    private data class Refusal(val model: app.rasul.chat.ChatViewModel?, val state: app.rasul.chat.ChatState?)
    private val refusals = mutableMapOf<String, Refusal>()

    override fun onCreate() {
        super.onCreate()
        createRunChannels(this)
        val foreground = NotificationCompat.Builder(this, ACTIVITY_CHANNEL)
            .setSmallIcon(R.drawable.rasul_signal_monochrome)
            .setContentTitle(getString(R.string.run_channel_activity))
            .setContentText(getString(R.string.status_thinking))
            .setGroup(RUN_GROUP).setGroupSummary(true).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOngoing(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(SERVICE_ID, foreground, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(SERVICE_ID, foreground)
        scope.launch { container.runWatcher.changes.collect { publish() } }
        scope.launch { container.appLock.state.collect { publish(forcePrivacy = true) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        stopping = false
        lastStartId = startId
        val originTag = intent?.getStringExtra(REPLY_TAG)
        val route = intent?.getStringExtra(CHAT_ROUTE)
        val reply = intent?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(REPLY_TEXT)?.toString() }
        if (route != null && !reply.isNullOrBlank()) {
            val expected = RunWatcher.ReplyTarget(intent.getStringExtra(REPLY_TARGET).orEmpty(),
                intent.getStringExtra(REPLY_PROFILE).orEmpty(), intent.getStringExtra(REPLY_SESSION).orEmpty())
            val lockAtArrival = container.appLock.state.value
            replyInFlight++
            scope.launch {
                try {
                    val initialLock = if (lockAtArrival.loaded) lockAtArrival else container.appLock.state.first { it.loaded }
                    fun allowed(): Boolean = container.appLock.state.value.let { it.loaded && !it.enabled }
                    val sent = !initialLock.enabled && allowed() && container.runWatcher.reply(route, expected, reply, ::allowed)
                    if (!sent) {
                        val message = if (initialLock.enabled || !allowed()) R.string.run_open_to_reply else R.string.run_chat_changed
                        val notice = RunNotice(false, getString(message), openOnly = true)
                        val model = container.runWatcher.modelForRoute(route)
                        refusals[route] = Refusal(model, model?.state?.value)
                        val open = PendingIntent.getActivity(this@RunWatchService, 0,
                            Intent(this@RunWatchService, MainActivity::class.java)
                                .setData(Uri.parse("rasul:chat/" + Uri.encode(route))).putExtra(CHAT_ROUTE, route),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        manager.notify(route, 3, NotificationCompat.Builder(this@RunWatchService, REPLIES_CHANNEL)
                            .setSmallIcon(R.drawable.rasul_signal_monochrome).setContentTitle(getString(R.string.app_name))
                            .setContentText(notice.text).setContentIntent(open).addAction(0, getString(R.string.run_open), open)
                            .setOngoing(false).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
                    } else {
                        refusals.remove(route)
                        manager.cancel(route, 3)
                    }
                } finally {
                    replyInFlight--
                    publish()
                    manager.activeNotifications.firstOrNull { it.tag == originTag && it.id == 2 }?.let { origin ->
                        manager.notify(origin.tag, origin.id, NotificationCompat.Builder(this@RunWatchService, origin.notification).setRemoteInputHistory(null).setOnlyAlertOnce(true).build())
                    }
                }
            }
        }
        publish()
        return START_NOT_STICKY
    }

    private fun publish(forcePrivacy: Boolean = false) {
        if (stopping) return
        val all = container.runWatcher.all()
        val lock = container.appLock.state.value
        val publicOnly = lock.publicOnly()
        refusals.entries.removeAll { (route, refusal) ->
            val changed = refusal.model?.state?.value != refusal.state
            if (changed) manager.cancel(route, 3)
            changed
        }
        all.forEach { chat ->
            val tag = container.runWatcher.notificationTag(chat)
            val oldTag = publishedTags.put(chat.model, tag)
            if (oldTag != null && oldTag != tag) {
                cancelOngoing(oldTag)
                seen.remove(oldTag)
                watched.remove(oldTag)
                shownTitles.remove(oldTag)
                throttles.remove(oldTag)
            }
            val state = chat.model.state.value
            if (state.run != null || state.queuedReplies > 0 || state.preparingSend) watched += tag
            if (tag !in watched) return@forEach
            if (state.run == null && state.finishedRunId != null && (state.terminal != null || state.terminalFailure != null)) return@forEach
            val notice = runNotice(this, state)
            val previous = seen[tag]
            if (!forcePrivacy && previous == notice && shownTitles[tag] == chat.title) return@forEach
            if (forcePrivacy && !notice.active && manager.activeNotifications.none { it.tag == tag }) return@forEach
            if (!forcePrivacy && notice.active && !throttles.getOrPut(tag) { UpdateThrottle() }.allow(SystemClock.elapsedRealtime())) return@forEach
            val id = 2
            val completed = previous?.active == true && !notice.active
            if (completed) manager.cancel(tag, id)
            manager.notify(tag, id, buildNotification(chat, notice, publicOnly, silent = !completed || forcePrivacy))
            seen[tag] = notice
            shownTitles[tag] = chat.title
        }
        val active = replyInFlight > 0 || all.any { it.model.state.value.hasActiveWork() }
        if (active && ticker?.isActive != true) {
            ticker = scope.launch { while (isActive) { delay(1_000); publish() } }
        } else if (!active) {
            ticker?.cancel()
            ticker = null
        }
        if (started && !active && stopSelfResult(lastStartId)) {
            stopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    internal fun buildNotification(chat: RunWatcher.Chat, notice: RunNotice, publicOnly: Boolean, silent: Boolean): Notification =
        buildRunNotification(this, container.runWatcher, chat, notice, publicOnly, silent)

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        ticker?.cancel()
        ticker = null
        clearOngoing()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private fun cancelOngoing(tag: String) {
        if (manager.activeNotifications.any { it.tag == tag && it.id == 2 && it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 }) manager.cancel(tag, 2)
    }

    private fun clearOngoing() { seen.keys.forEach(::cancelOngoing) }

    override fun onDestroy() {
        clearOngoing()
        scope.cancel()
        container.runWatcher.serviceStopped()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

internal fun buildRunNotification(context: android.content.Context, watcher: RunWatcher, chat: RunWatcher.Chat, notice: RunNotice, publicOnly: Boolean, silent: Boolean, tag: String = watcher.notificationTag(chat)): Notification {
    val route = watcher.notificationRoute(chat)
    val title = chat.title.ifBlank { context.getString(R.string.app_name) }
    val open = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setData(Uri.parse("rasul:chat/" + Uri.encode(route)))
            .putExtra(CHAT_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val publicText = if (notice.openOnly) notice.text else context.getString(R.string.run_public_reply, title)
    val target = watcher.replyTarget(route, chat.model)
    val channel = if (notice.active) ACTIVITY_CHANNEL else REPLIES_CHANNEL
    fun replyAction(authenticate: Boolean): NotificationCompat.Action {
        val reply = PendingIntent.getForegroundService(context, 0,
            Intent(context, RunWatchService::class.java)
                .setData(Uri.parse("rasul:reply/" + Uri.encode(route) + "/" + Uri.encode(target?.targetId.orEmpty())
                    + "/" + Uri.encode(target?.profileId.orEmpty()) + "/" + Uri.encode(target?.sessionId.orEmpty()) + "/" + Uri.encode(tag)))
                .putExtra(REPLY_TAG, tag).putExtra(CHAT_ROUTE, route).putExtra(REPLY_TARGET, target?.targetId)
                .putExtra(REPLY_PROFILE, target?.profileId).putExtra(REPLY_SESSION, target?.sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Action.Builder(0, context.getString(R.string.run_reply), reply)
            .addRemoteInput(RemoteInput.Builder(REPLY_TEXT).setLabel(context.getString(R.string.run_reply)).build())
            .setAuthenticationRequired(authenticate).setAllowGeneratedReplies(false).build()
    }
    val publicBuilder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setGroup(if (notice.active) RUN_GROUP else null).setContentTitle(title).setContentText(publicText).setContentIntent(open)
        .addAction(0, context.getString(R.string.run_open), open)
        .addExtras(android.os.Bundle().apply { putString("session", chat.model.resolvedSessionId ?: chat.currentSession) })
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(notice.active).setAutoCancel(!notice.active).setSilent(true)
    if (!publicOnly && !notice.approval && !notice.openOnly) publicBuilder.addAction(replyAction(authenticate = true))
    val public = publicBuilder.build()
    val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setGroup(if (notice.active) RUN_GROUP else null).setContentTitle(title).setContentText(notificationText(publicText, notice.text, publicOnly))
        .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText(publicText, notice.text.take(600), publicOnly)))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
        .addExtras(android.os.Bundle().apply { putString("session", chat.model.resolvedSessionId ?: chat.currentSession) })
        .setContentIntent(open).setOngoing(notice.active).setAutoCancel(!notice.active)
        .setOnlyAlertOnce(notice.active).setSilent(silent)
        .addAction(0, context.getString(R.string.run_open), open)
    if (!publicOnly && !notice.approval && !notice.openOnly) builder.addAction(replyAction(authenticate = publicOnly))
    return builder.build()
}


internal fun createRunChannels(context: android.content.Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(ACTIVITY_CHANNEL, context.getString(R.string.run_channel_activity), NotificationManager.IMPORTANCE_LOW))
    manager.createNotificationChannel(NotificationChannel(REPLIES_CHANNEL, context.getString(R.string.run_channel_replies), NotificationManager.IMPORTANCE_DEFAULT))
}
