package app.rasul.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import app.rasul.AppContainer
import app.rasul.ui.bridgeChatTargetLoader
import app.rasul.ui.bridgeSessionMemory
import app.rasul.chat.newChatRunMemory
import app.rasul.chat.ChatViewModel
import app.rasul.chat.RunMemory
import app.rasul.chat.TargetLoader
import app.rasul.chat.chatRunMemory
import app.rasul.chat.chatTargetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.rasul.chat.ChatLoad
import app.rasul.data.HermesTarget
import app.rasul.data.HermesResult
import kotlinx.coroutines.launch

/** Application-owned chats keep a single follower when their screen leaves or returns. */
class RunWatcher(
    private val container: AppContainer,
    private val context: Context,
    private val scope: CoroutineScope,
    private val createModel: (TargetLoader, RunMemory) -> ChatViewModel = { loader, memory ->
        ChatViewModel(loader, container.runs, container.sessions, memory, container.runWriter, scope = scope)
    },
) {
    data class Chat(val route: String, var title: String, val model: ChatViewModel, var currentTarget: HermesTarget? = null, var currentSession: String? = null)
    data class ReplyTarget(val targetId: String, val profileId: String, val sessionId: String)
    private val cacheLock = Mutex()
    private val memoryLock = Mutex()
    private var foreground = false
    private data class RunKey(val connectionId: String, val profileId: String?, val runId: String?, val sessionId: String? = null) {
        val tag: String get() = listOf(connectionId, profileId.orEmpty(), runId.orEmpty()).joinToString("/") { Uri.encode(it) }
    }
    private val chats = linkedMapOf<RunKey, Chat>()
    private val routes = mutableMapOf<String, Chat>()
    private val keys = mutableMapOf<ChatViewModel, RunKey>()
    private val collectors = mutableMapOf<ChatViewModel, kotlinx.coroutines.Job>()
    private val finishedTags = mutableSetOf<String>()

    private val deletedSessions = context.getSharedPreferences("deleted-chats", Context.MODE_PRIVATE)
    private fun isDeleted(session: String) = deletedSessions.getBoolean(session, false)

    suspend fun invalidateSession(sessionId: String) {
        deletedSessions.edit().putBoolean(sessionId, true).commit()
        memoryLock.withLock { }
        cacheLock.withLock {
            val affected = chats.values.filter { chat ->
                keys[chat.model]?.sessionId == sessionId || chat.model.resolvedSessionId == sessionId ||
                    chat.currentSession == sessionId
            }
            val tags = affected.map(::notificationTag).toSet()
            affected.forEach(::remove)
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.activeNotifications.forEach { entry ->
                val session = entry.notification.extras.getString("session")
                if (entry.tag in tags || session == sessionId) manager.cancel(entry.tag, entry.id)
            }
            changes.value++
        }
    }

    internal fun notificationRoute(chat: Chat): String {
        val session = chat.model.resolvedSessionId ?: chat.currentSession ?: return chat.route
        val path = chat.route.substringBefore('?')
        val query = buildList {
            if (path.startsWith("chat/")) add("run_profile=" + Uri.encode(chat.model.resolvedTarget?.profile.orEmpty()))
            add("session=" + Uri.encode(session))
        }
        return path + query.joinToString("&", "?")
    }

    private fun guardedMemory(memory: RunMemory, session: String?) = object : RunMemory by memory {
        private var selectedSession = session
        override suspend fun sessionId(): String = memory.sessionId().also {
            check(!isDeleted(it))
            selectedSession = it
        }
        override suspend fun newSessionFailure(): app.rasul.chat.ChatFailure? =
            if (selectedSession?.let(::isDeleted) == true) app.rasul.chat.ChatFailure.SessionUnavailable else memory.newSessionFailure()
        override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: app.rasul.chat.Anchor) {
            memoryLock.withLock {
                if (selectedSession?.let(::isDeleted) != true) memory.setActiveRun(runId, lastEventId, anchor)
            }
        }
        override suspend fun setCurrentSessionRun(sessionId: String, runId: String?, lastEventId: Long?, anchor: app.rasul.chat.Anchor) {
            memoryLock.withLock {
                if (!isDeleted(sessionId)) memory.setCurrentSessionRun(sessionId, runId, lastEventId, anchor)
            }
        }
    }

    internal fun notificationTag(chat: Chat): String = keys[chat.model]?.tag ?: chat.route

    internal fun modelForRoute(route: String): ChatViewModel? = routes[route]?.model

    private fun connectionId(route: String): String {
        val parts = route.substringBefore('?').split('/').map(Uri::decode)
        return if (parts.first() == "bridge-chat") "bridge:${parts[1]}:${parts[2]}" else parts[1]
    }
    val changes = MutableStateFlow(0L)
    private var requested = false

    init {
        scope.launch {
            container.appLock.state.collect { lock ->
                if (!lock.loaded || lock.enabled) redactPublishedNotifications(context)
            }
        }
    }

    suspend fun get(route: String, title: String, loader: TargetLoader, memory: RunMemory, restoringRunId: String? = null): ChatViewModel {
        val loaded = loader.load()
        val target = (loaded as? HermesResult.Ok)?.value
        val session = try { if (target != null) memory.sessionId() else null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
        return cacheLock.withLock {
            val key = RunKey(connectionId(route), target?.profile ?: target?.bridge?.profileId, restoringRunId, session)
            if (restoringRunId != null) chats[key]?.let { chat ->
                routes[route] = chat
                return@withLock chat.model
            }
            val existing = chats.entries.firstOrNull { (stored, chat) ->
                    stored.connectionId == key.connectionId && stored.profileId == key.profileId && stored.sessionId == key.sessionId &&
                        (restoringRunId == null || stored.runId == restoringRunId ||
                            (stored.runId == null && chat.model.state.value.load == ChatLoad.Loading))
                }?.value
                ?: chats.entries.firstOrNull { (stored, chat) ->
                    restoringRunId == null && Uri.parse(route).getQueryParameter("newSession") == null &&
                        Uri.parse(route).getQueryParameter("session") == null &&
                        stored.connectionId == key.connectionId && stored.profileId == key.profileId &&
                        chat.model.state.value.let { it.run != null || it.queuedReplies > 0 || it.preparingSend }
                }?.value
                ?: routes[route]?.takeIf { keys[it.model]?.let { stored -> stored.connectionId == key.connectionId && stored.profileId == key.profileId && stored.sessionId == key.sessionId && (restoringRunId == null || stored.runId == restoringRunId) } == true }
            existing?.let { chat ->
                val state = chat.model.state.value
                val changed = if (state.load == ChatLoad.Loading) chat.currentTarget != target || chat.currentSession != session
                    else chat.model.resolvedTarget != target || chat.model.resolvedSessionId != session
                chat.currentTarget = target
                chat.currentSession = session
                val idle = state.run == null && state.queuedReplies == 0 && !state.preparingSend
                if (!idle || (state.load !is ChatLoad.Failed && !changed)) {
                    if (title.isNotBlank() && chat.title != title) { chat.title = title; changes.value++ }
                    routes[route] = chat
                    return@withLock chat.model
                }
            }
            existing?.let(::remove)
            routes[route]?.takeIf { old -> old !== existing && old.model.state.value.let { it.run == null && it.queuedReplies == 0 && !it.preparingSend } }?.let(::remove)
            val model = createModel(TargetLoader { loaded }, guardedMemory(memory, session))
            val chat = Chat(route, title, model, target, session)
            chats[key] = chat
            routes[route] = chat
            keys[model] = key
            model.onFinished = { id, state -> publishFinished(chat, id, state) }
            collectors[model] = scope.launch {
                model.state.collect { state ->
                    val stored = keys[model] ?: return@collect
                    val previous = stored.copy(sessionId = state.sessionId ?: stored.sessionId)
                    val next = when {
                        state.run != null -> previous.copy(runId = state.run.runId)
                        state.preparingSend && state.terminal == null -> previous.copy(runId = null)
                        else -> previous
                    }
                    if (next != stored) {
                        val owner = chats[next]
                        if (owner != null && owner !== chat) {
                            routes.entries.filter { it.value === chat }.forEach { it.setValue(owner) }
                            remove(chat)
                            return@collect
                        }
                        chats.remove(stored)
                        chats[next] = chat
                        keys[model] = next
                    }
                    state.finishedRunId?.takeIf { state.terminal != null || state.terminalFailure != null }?.let { publishFinished(chat, it, state.copy(run = null)) }
                    changes.value++
                    if (state.hasActiveWork()) startService()
                }
            }
            model
        }
    }

    private fun remove(chat: Chat) {
        keys.remove(chat.model)?.let(chats::remove)
        routes.entries.removeAll { it.value === chat }
        collectors.remove(chat.model)?.cancel()
        chat.model.close()
    }

    private fun publishFinished(chat: Chat, id: String, state: app.rasul.chat.ChatState) {
        val key = keys[chat.model] ?: return
        val tag = key.copy(runId = id).tag
        if (!finishedTags.add(tag)) return
        createRunChannels(context)
        val lock = container.appLock.state.value
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        if (key.tag != tag && manager.activeNotifications.any { it.tag == key.tag && it.id == 2 && it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 }) manager.cancel(key.tag, 2)
        manager.cancel(tag, 2)
        manager.notify(tag, 2,
            buildRunNotification(context, this, chat, runNotice(context, state), !lock.loaded || lock.enabled, false, tag))
    }

    fun all(): List<Chat> = chats.values.toList()
    private fun hasWork() = chats.values.any {
        val state = it.model.state.value
        state.hasActiveWork()
    }

    fun serviceStopped() {
        requested = false
        if (foreground && hasWork()) startService()
    }

    fun onBackground() { foreground = false }

    private fun startService() {
        if (!foreground || requested) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, RunWatchService::class.java))
            requested = true
        } catch (e: IllegalStateException) {
            if (android.os.Build.VERSION.SDK_INT < 31 || e !is android.app.ForegroundServiceStartNotAllowedException) throw e
        }
    }

    fun onForeground() {
        foreground = true
        requested = false
        if (hasWork()) startService()
        scope.launch {
            val savedRuns = try { container.connections.persistedRuns() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { return@launch }
            savedRuns.forEach { saved ->
                val runId = try { container.connections.activeRun(saved.id, saved.profile) ?: return@forEach }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { return@forEach }
                if (cacheLock.withLock { chats.keys.any { it.connectionId == saved.id && it.profileId == saved.profile && it.runId == runId } }) return@forEach
                val route = if (saved.id.startsWith("bridge:")) {
                    val bridge = saved.id.removePrefix("bridge:").removeSuffix(":" + saved.profile.orEmpty())
                    "bridge-chat/" + Uri.encode(bridge) + "/" + Uri.encode(saved.profile.orEmpty())
                } else {
                    val current = container.connections.get(saved.id)
                    "chat/" + Uri.encode(saved.id) + if (current?.profile != saved.profile) "?run_profile=" + Uri.encode(saved.profile.orEmpty()) else ""
                }
                try { resolve(route, runId) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* A later foreground entry retries unavailable storage. */ }
            }
        }
    }

    fun replyTarget(route: String, model: ChatViewModel): ReplyTarget? {
        val target = model.resolvedTarget ?: return null
        val session = model.resolvedSessionId ?: return null
        return replyTarget(route, target, session)
    }

    private fun replyTarget(route: String, target: HermesTarget, session: String): ReplyTarget {
        val identity = listOf(route.substringBefore('?'), target.baseUrl, target.apiKey).joinToString("\u0000")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return ReplyTarget(android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP),
            target.profile ?: target.bridge?.profileId.orEmpty(), session)
    }

    suspend fun reply(route: String, expected: ReplyTarget, text: String, allowed: () -> Boolean): Boolean {
        val model = resolve(route) ?: return false
        model.state.first { it.load != ChatLoad.Loading }
        val chat = routes[route] ?: return false
        val current = chat.currentTarget ?: return false
        if (route.startsWith("chat/")) {
            val id = Uri.decode(route.substringBefore('?').substringAfter('/'))
            val selected = chatTargetLoader(container, id).load()
            if (selected !is HermesResult.Ok || selected.value != current) return false
        }
        if (replyTarget(route, model) != expected || replyTarget(route, current, chat.currentSession.orEmpty()) != expected || !allowed()) return false
        model.replyFromNotification(text)
        return true
    }

    suspend fun resolve(route: String, restoringRunId: String? = null): ChatViewModel? {
        if (!isChatRoute(route)) return null
        val parts = route.substringBefore('?').split('/')
        val query = Uri.parse(route).queryParameterNames.associateWith { Uri.parse(route).getQueryParameter(it).orEmpty() }
        val newSession = query["newSession"]
        val session = query["session"] ?: newSession
        var selectedSession = session
        if (session != null && isDeleted(session)) return null
        suspend fun selectedMemory(memory: RunMemory): RunMemory {
            if (session != null) return newChatRunMemory(memory, session)
            return try {
                val current = memory.sessionId()
                selectedSession = current
                newChatRunMemory(memory, current)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { memory }
        }
        val model = when {
            parts.size == 2 && parts[0] == "chat" -> {
                val id = Uri.decode(parts[1])
                val name = try { container.connections.get(id)?.name.orEmpty() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { "" }
                if ("run_profile" in query) {
                    val profile = query["run_profile"]?.ifEmpty { null }
                    get(route, name, chatTargetLoader(container, id, profile), selectedMemory(chatRunMemory(container, id, profile)), restoringRunId)
                } else get(route, name, chatTargetLoader(container, id), selectedMemory(chatRunMemory(container, id)), restoringRunId)
            }
            parts.size == 3 && parts[0] == "bridge-chat" -> {
                val bridge = Uri.decode(parts[1])
                val profile = Uri.decode(parts[2])
                val loaded = bridgeChatTargetLoader(container.bridgeBots, bridge, profile).load()
                val group = container.bridgeBots.state.value.groups.firstOrNull { it.id == bridge }
                val name = group?.profiles?.firstOrNull { it.id == profile }?.displayName.orEmpty()
                get(route, name, TargetLoader { loaded },
                    selectedMemory(bridgeSessionMemory(container, bridge, profile)), restoringRunId)
            }
            else -> null
        }
        val resolvedSession = selectedSession
        if (resolvedSession != null && isDeleted(resolvedSession)) {
            invalidateSession(resolvedSession)
            return null
        }
        return model
    }
}

/** Also covers reply notifications left behind after the service has stopped. */
fun redactPublishedNotifications(context: Context) {
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    manager.activeNotifications.forEach { entry ->
        val public = entry.notification.publicVersion ?: return@forEach
        val builder = androidx.core.app.NotificationCompat.Builder(context, public).clearActions()
        public.actions?.firstOrNull()?.let { builder.addAction(androidx.core.app.NotificationCompat.Action.Builder(it.icon, it.title, it.actionIntent).build()) }
        val redacted = builder.setSilent(true).build()
        manager.notify(entry.tag, entry.id, androidx.core.app.NotificationCompat.Builder(context, redacted)
            .setPublicVersion(redacted).build())
    }
}

fun isChatRoute(route: String): Boolean {
    val path = route.substringBefore('?')
    if (path != route) {
        val query = route.substringAfter('?').split('&')
        val names = mutableSetOf<String>()
        for (parameter in query) {
            val name = parameter.substringBefore('=')
            if ('=' !in parameter || !names.add(name)) return false
            if (name != "newSession" && name != "session" && (name != "run_profile" || !path.startsWith("chat/"))) return false
            val value = parameter.substringAfter('=')
            if ((name == "newSession" || name == "session") && value.isEmpty()) return false
            if ("newSession" in names && "session" in names) return false
            if (Uri.encode(Uri.decode(value)) != value) return false
        }
    }
    val parts = path.split('/')
    val count = when (parts.firstOrNull()) { "chat" -> 2; "bridge-chat" -> 3; else -> return false }
    return parts.size == count && parts.drop(1).all { it.isNotEmpty() && Uri.encode(Uri.decode(it)) == it }
}
