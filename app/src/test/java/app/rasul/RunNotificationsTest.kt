package app.rasul

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import app.rasul.chat.ActiveRun
import app.rasul.chat.ApprovalUi
import app.rasul.chat.ChatFailure
import app.rasul.chat.ChatMessage
import app.rasul.chat.ChatState
import app.rasul.chat.ChatViewModel
import app.rasul.chat.MessageKind
import app.rasul.chat.OrderedWriter
import app.rasul.chat.RunMemory
import app.rasul.chat.StatusLabel
import app.rasul.chat.TargetLoader
import app.rasul.data.ApprovalChoice
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.RunEvent
import app.rasul.data.RunFrame
import app.rasul.data.RunStatus
import app.rasul.data.RunUpdate
import app.rasul.data.RunsApi
import app.rasul.data.StartedRun
import app.rasul.notifications.RunNotice
import app.rasul.notifications.RunWatchService
import app.rasul.notifications.RunWatcher
import app.rasul.notifications.UpdateThrottle
import app.rasul.notifications.notificationText
import app.rasul.notifications.runNotice
import app.rasul.notifications.hasActiveWork
import app.rasul.sessions.SessionMessageDto
import app.rasul.sessions.SessionsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class RunNotificationsTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val frames = MutableSharedFlow<RunUpdate>(extraBufferCapacity = 32)
    private val starts = mutableListOf<Pair<String, String?>>()
    private val steers = mutableListOf<String>()
    private var followers = 0
    private var history = emptyList<SessionMessageDto>()
    private var statusResult: HermesResult<RunStatus> = HermesResult.Ok(RunStatus("running", null, null))
    private var steerResult: HermesResult<Unit> = HermesResult.Ok(Unit)
    private var historyGate: CompletableDeferred<Unit>? = null
    private var historyFailure: HermesError? = null
    private var historyEntered: CompletableDeferred<Unit>? = null
    private val memory = object : RunMemory {
        override suspend fun sessionId() = "session-a"
        override suspend fun activeRun(): String? = null
        override suspend fun lastEventId(): Long? = null
        override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: app.rasul.chat.Anchor) = Unit
    }
    private val api = object : RunsApi {
        override suspend fun startRun(target: HermesTarget, input: String, sessionId: String?, idempotencyKey: String?): HermesResult<StartedRun> {
            starts += input to sessionId
            return HermesResult.Ok(StartedRun("run-${starts.size}"))
        }
        override suspend fun status(target: HermesTarget, runId: String) = statusResult
        override suspend fun stop(target: HermesTarget, runId: String) = HermesResult.Ok(Unit)
        override suspend fun steer(target: HermesTarget, runId: String, text: String): HermesResult<Unit> {
            steers += text
            return steerResult
        }
        override suspend fun approve(target: HermesTarget, runId: String, choice: ApprovalChoice, requestId: String?) = HermesResult.Ok(Unit)
        override fun events(target: HermesTarget, runId: String, lastSeq: Long?): Flow<RunFrame> = error("unused")
    }
    private val sessions = object : SessionsApi {
        override suspend fun messages(target: HermesTarget, sessionId: String): HermesResult<List<SessionMessageDto>> {
            historyEntered?.complete(Unit)
            historyGate?.await()
            historyFailure?.let { return HermesResult.Err(it) }
            return HermesResult.Ok(history)
        }
    }
    private val loader = TargetLoader { HermesResult.Ok(HermesTarget("https://example.invalid", null, "")) }
    private fun model(loader: TargetLoader = this.loader, memory: RunMemory = this.memory): ChatViewModel = ChatViewModel(loader, api, sessions, memory, OrderedWriter(scope),
        follow = { _, _, _ -> followers++; frames }, scope = scope)
    private fun idle() = scheduler.runCurrent()
    private fun start(model: ChatViewModel) { model.onDraft("first"); model.send(); idle() }
    private fun emit(event: RunEvent) { assertTrue(frames.tryEmit(RunUpdate.Event(event))); idle() }

    private val container get() = (context.applicationContext as RasulApp).container
    private lateinit var connectionStore: app.rasul.data.ConnectionStore
    private var persistedRun: String? = null
    private val persistedMemory = object : RunMemory by memory {
        override suspend fun activeRun() = persistedRun
    }

    private fun completeReply(service: RunWatchService) {
        val field = service.javaClass.getDeclaredField("replyInFlight").apply { isAccessible = true }
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                while (field.getInt(service) != 0) { idle(); kotlinx.coroutines.delay(1) }
            }
        }
        idle()
    }

    private fun setLock(enabled: Boolean) {
        val field = container.appLock.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(container.appLock) as kotlinx.coroutines.flow.MutableStateFlow<app.rasul.security.AppLockState>
        state.value = app.rasul.security.AppLockState(loaded = true, enabled = enabled, locked = enabled)
    }

    private fun install(watcher: RunWatcher) {
        container.javaClass.getDeclaredField("runWatcher").apply { isAccessible = true }.set(container, watcher)
    }

    private fun get(watcher: RunWatcher, loader: TargetLoader = this.loader, memory: RunMemory = this.memory): ChatViewModel =
        kotlinx.coroutines.runBlocking { watcher.get("chat/a", "Bot", loader, memory) }

    private fun replyIntent(service: RunWatchService, model: ChatViewModel): android.content.Intent {
        val notification = service.buildNotification(RunWatcher.Chat("chat/a", "Bot", model), RunNotice(false, "answer"), false, false)
        val intent = android.content.Intent(org.robolectric.Shadows.shadowOf(notification.actions[1].actionIntent).savedIntent)
        val results = android.os.Bundle().apply { putCharSequence(app.rasul.notifications.REPLY_TEXT, "second") }
        androidx.core.app.RemoteInput.addResultsToIntent(arrayOf(androidx.core.app.RemoteInput.Builder(app.rasul.notifications.REPLY_TEXT).build()), intent, results)
        return intent
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val data = kotlinx.coroutines.flow.MutableStateFlow<androidx.datastore.preferences.core.Preferences>(androidx.datastore.preferences.core.emptyPreferences())
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data: Flow<androidx.datastore.preferences.core.Preferences> = data
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                val next = transform(data.value); data.value = next; return next
            }
        }
        connectionStore = app.rasul.data.ConnectionStore(store, object : app.rasul.data.SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String) = token
        })
        container.javaClass.getDeclaredField("connections").apply { isAccessible = true }.set(container, connectionStore)
        kotlinx.coroutines.runBlocking { connectionStore.save(app.rasul.data.Connection("a", "Bot", "https://example.invalid"), "") }
        kotlinx.coroutines.runBlocking { container.appLock.state.first { it.loaded } }
        setLock(false)
    }
    @After fun cleanup() { scope.cancel(); Dispatchers.resetMain() }

    @Test fun serviceCreationWaitsForStartCommandBeforeStopping() {
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        idle()
        assertFalse(org.robolectric.Shadows.shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun mapsThinkingToolTextApprovalAndCompletion() {
        val model = model(); idle(); start(model)
        assertEquals(context.getString(R.string.status_thinking), runNotice(context, model.state.value).text)
        emit(RunEvent.ToolStarted("search", null))
        assertEquals(context.getString(R.string.status_tool, "search"), runNotice(context, model.state.value).text)
        emit(RunEvent.MessageDelta("first line\nlast line"))
        assertEquals("last line", runNotice(context, model.state.value).text)
        emit(RunEvent.ApprovalRequest("approval-a", "action", null, listOf("once", "deny")))
        assertTrue(runNotice(context, model.state.value).approval)
        assertEquals(context.getString(R.string.status_waiting_approval), runNotice(context, model.state.value).text)
        emit(RunEvent.ApprovalResponded("deny", "approval-a"))
        emit(RunEvent.Completed("answer"))
        assertEquals(RunNotice(false, "answer"), runNotice(context, model.state.value))
    }

    @Test fun mapsSpecificFailuresStoppedAndInterrupted() {
        val failed = ChatState(terminal = RunEvent.Failed("detail"))
        assertEquals(context.getString(R.string.chat_err_run_failed, "detail"), runNotice(context, failed).text)
        assertEquals(context.getString(R.string.run_reply_stopped), runNotice(context, ChatState(terminal = RunEvent.Cancelled())).text)
        assertEquals(context.getString(R.string.chat_err_run_interrupted, "detail"), runNotice(context, ChatState(terminal = RunEvent.Interrupted("detail"))).text)
        assertEquals(context.getString(R.string.bridge_timeout), runNotice(context, ChatState(terminalFailure = ChatFailure.Api(HermesError.BridgeTimeout))).text)
        assertEquals(context.getString(R.string.run_reply_complete), runNotice(context, ChatState(messages = listOf(ChatMessage(1, MessageKind.Error, error = ChatFailure.Api(HermesError.BridgeTimeout))))).text)
    }

    @Test fun progressIsOngoingSilentAndCompletionUsesRepliesChannel() {
        val service = Robolectric.buildService(RunWatchService::class.java).get()
        val chat = RunWatcher.Chat("chat/a", "Bot", model())
        val active = service.buildNotification(chat, RunNotice(true, "activity"), false, true)
        assertEquals("bot_activity", active.channelId)
        assertTrue(active.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNull(active.sound)
        assertEquals(Notification.VISIBILITY_PRIVATE, active.visibility)
        val finished = service.buildNotification(chat, RunNotice(false, "a".repeat(900)), false, false)
        assertEquals("bot_replies", finished.channelId)
        assertEquals(0, finished.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(600, finished.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)!!.length)
        val approval = service.buildNotification(chat, RunNotice(true, "approval", true), false, true)
        assertEquals(1, approval.actions.size)
        assertNull(NotificationCompat.getAction(approval, 0)!!.remoteInputs)
        val other = service.buildNotification(chat.copy(route = "chat/b"), RunNotice(false, "answer"), false, false)
        assertNotEquals(finished.contentIntent, other.contentIntent)
        assertNotEquals(finished.actions[1].actionIntent, other.actions[1].actionIntent)
    }

    @Test fun coldNotificationTapResolvesChatBeforeNavigation() = kotlinx.coroutines.test.runTest(dispatcher) {
        val route = "bridge-chat/bridge-a/profile-a"
        val intent = android.content.Intent(context, MainActivity::class.java)
            .putExtra(app.rasul.notifications.CHAT_ROUTE, route)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        var firstLoad: kotlinx.coroutines.Job? = null
        try {
            idle()
            val container = (controller.get().application as RasulApp).container
            assertTrue(container.runWatcher.all().isEmpty())
            assertNull(container.pendingChat.value)
            firstLoad = scope.launch { container.bridgeBots.follow() }
            container.bridgeBots.state.first { it.loaded }
            container.runWatcher.changes.first { container.runWatcher.all().any { it.route == route } }
            assertTrue(container.runWatcher.all().any { it.route == route })
            assertFalse(controller.get().intent.hasExtra(app.rasul.notifications.CHAT_ROUTE))
        } finally { firstLoad?.cancel(); controller.destroy() }
    }

    @Test fun notificationRoutesRequireWholeCanonicalSegments() {
        assertTrue(app.rasul.notifications.isChatRoute("chat/connection-a"))
        assertTrue(app.rasul.notifications.isChatRoute("chat/connection-a?run_profile=profile-a"))
        assertTrue(app.rasul.notifications.isChatRoute("chat/connection-a?run_profile="))
        assertFalse(app.rasul.notifications.isChatRoute("chat/connection-a?run_profile=profile-a&run_profile=profile-b"))
        assertTrue(app.rasul.notifications.isChatRoute("bridge-chat/bridge-a/profile-a"))
        listOf("chat/", "chat/a/extra", "chat/a?other=1", "chat/%61", "bridge-chat/a", "other/a").forEach {
            assertFalse(it, app.rasul.notifications.isChatRoute(it))
        }
    }

    @Test fun throttleAllowsAtMostOneProgressUpdatePerSecond() {
        val throttle = UpdateThrottle()
        assertTrue(throttle.allow(0))
        (1L..999L).forEach { assertFalse(throttle.allow(it)) }
        assertTrue(throttle.allow(1_000))
        assertFalse(throttle.allow(1_999))
        assertTrue(throttle.allow(2_000))
    }

    @Test fun lockOffPreservesPrivateContentAndLockOnUsesOnlyPublicText() {
        assertEquals("answer", notificationText("public", "answer", false))
        assertEquals("public", notificationText("public", "answer", true))
        val model = model()
        val service = Robolectric.buildService(RunWatchService::class.java).get()
        val chat = RunWatcher.Chat("chat/a", "Bot", model)
        val private = service.buildNotification(chat, RunNotice(false, "answer"), false, false)
        assertEquals(Notification.VISIBILITY_PRIVATE, private.visibility)
        assertEquals("answer", private.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(context.getString(R.string.run_public_reply, "Bot"), private.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(2, private.actions.size)
        assertEquals("Reply", NotificationCompat.getAction(private, 1)!!.remoteInputs!!.single().label)
        val locked = service.buildNotification(chat, RunNotice(false, "answer"), true, false)
        assertEquals(context.getString(R.string.run_public_reply, "Bot"), locked.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(1, locked.actions.size)
        assertEquals(1, locked.publicVersion.actions.size)
        assertFalse(locked.extras.toString().contains("answer"))
    }

    @Test fun publicVersionOffersOnlyOpenWhenAppLockIsEnabled() {
        val service = Robolectric.buildService(RunWatchService::class.java).get()
        val chat = RunWatcher.Chat("chat/a", "Bot", model())
        val notification = service.buildNotification(chat, RunNotice(false, "private answer"), true, false)
        assertEquals(1, notification.actions.size)
        assertEquals(1, notification.publicVersion.actions.size)
        assertFalse(notification.extras.toString().contains("private answer"))
        assertFalse(notification.publicVersion.extras.toString().contains("private answer"))
        assertEquals(context.getString(R.string.run_open), notification.actions.single().title)
    }

    @Test fun finishedReplyUsesSameSessionAndNewMessagePath() {
        val model = model(); idle(); start(model)
        history = listOf(SessionMessageDto(role = "user", content = JsonPrimitive("first")), SessionMessageDto(role = "assistant", content = JsonPrimitive("answer")))
        emit(RunEvent.Completed("answer"))
        model.replyFromNotification("second"); idle()
        assertEquals(listOf("first" to "session-a", "second" to "session-a"), starts)
        assertEquals("run-2", model.state.value.run?.runId)
        assertEquals(2, followers)
    }

    @Test fun activeReplySteersAndRejectedReplyWaitsForCompletion() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle(); start(model)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        controller.get().onStartCommand(null, 0, 1); idle()
        model.replyFromNotification("accepted"); idle()
        assertEquals(listOf("accepted"), steers)
        assertEquals(1, starts.size)
        steerResult = HermesResult.Err(HermesError.Server(409))
        model.replyFromNotification("queued"); idle()
        assertEquals(1, starts.size)
        history = listOf(SessionMessageDto(role = "user", content = JsonPrimitive("first")), SessionMessageDto(role = "assistant", content = JsonPrimitive("answer")))
        emit(RunEvent.Completed("answer"))
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val answer = manager.activeNotifications.single { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT) == "answer" }.notification
        assertNull(answer.group) // outside the service summary group, so stopping the service cannot cancel it
        controller.destroy()
        assertEquals("queued" to "session-a", starts.last())
        assertEquals(2, starts.size)
    }

    @Test fun notificationInputIsCapturedBeforeSuspendedHistoryRead() {
        val model = model(); idle()
        historyGate = CompletableDeferred()
        historyEntered = CompletableDeferred()
        model.replyFromNotification("captured"); idle()
        assertTrue(historyEntered!!.isCompleted)
        model.onDraft("changed")
        historyGate!!.complete(Unit); idle()
        assertEquals("captured" to "session-a", starts.single())
    }

    @Test fun replyWaitsForExistingSuspendedPreparation() {
        val model = model(); idle()
        historyGate = CompletableDeferred()
        model.onDraft("first"); model.send(); idle()
        model.replyFromNotification("queued"); idle()
        assertTrue(starts.isEmpty())
        historyGate!!.complete(Unit); idle()
        assertEquals(listOf("first" to "session-a"), starts)
        emit(RunEvent.Cancelled())
        assertEquals(listOf("first" to "session-a", "queued" to "session-a"), starts)
    }

    @Test fun historyFailureFromReplyShowsSpecificNotificationText() {
        val model = model(); idle()
        historyFailure = HermesError.BridgeTimeout
        model.replyFromNotification("message"); idle()
        assertEquals(context.getString(R.string.bridge_timeout), runNotice(context, model.state.value).text)
    }

    @Test fun otherRejectedSteerAlsoQueuesAfterRun() {
        val model = model(); idle(); start(model)
        steerResult = HermesResult.Err(HermesError.Server(503))
        model.replyFromNotification("queued"); idle()
        assertEquals(1, starts.size)
        emit(RunEvent.Cancelled())
        assertEquals("queued" to "session-a", starts.last())
    }

    @Test fun existingComposerSurvivesNotificationSend() {
        val model = model(); idle()
        model.onDraft("unsent draft")
        model.replyFromNotification("notification message"); idle()
        assertEquals("unsent draft", model.state.value.draft)
        assertEquals("notification message" to "session-a", starts.single())
    }

    @Test fun suspendedHistorySendBlocksModelChangesUntilSendingEnds() {
        val model = model(); idle()
        assertTrue(model.state.value.canChooseModel)
        historyGate = CompletableDeferred()
        historyEntered = CompletableDeferred()
        model.onDraft("first"); model.send(); idle()
        assertTrue(historyEntered!!.isCompleted)
        assertTrue(model.state.value.preparingSend)
        assertFalse(model.state.value.canChooseModel)
        assertTrue(starts.isEmpty())
        historyGate!!.complete(Unit); idle()
        assertFalse(model.state.value.canChooseModel)
        emit(RunEvent.Cancelled())
        assertTrue(model.state.value.canChooseModel)
    }

    @Test fun activeDefaultChatKeepsOwnerWhenPersistentSessionChanges() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val first = get(watcher); idle(); start(first)
        val changedMemory = object : RunMemory by memory {
            override suspend fun sessionId() = "session-b"
        }
        assertSame(first, get(watcher, memory = changedMemory))
        assertEquals(1, followers)
        val next = kotlinx.coroutines.runBlocking {
            watcher.get("chat/a?newSession=session-b", "Bot", loader, changedMemory)
        }
        idle()
        assertNotSame(first, next)
        assertSame(first, watcher.modelForRoute("chat/a"))
    }

    @Test fun newSessionKeepsActiveChatAndPreviousRunProtection() = kotlinx.coroutines.test.runTest(dispatcher) {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val original = watcher.resolve("chat/a")!!; idle(); start(original)
        val next = watcher.resolve("chat/a?newSession=session-b")!!; idle()
        assertNotSame(original, next)
        assertEquals("session-b", next.resolvedSessionId)
        next.onDraft("second"); next.send(); idle()
        assertEquals(1, starts.size)
        assertEquals(ChatFailure.PreviousRunActive, next.state.value.messages.last().error)
        assertSame(original, watcher.resolve("chat/a"))
        assertSame(next, watcher.resolve("chat/a?newSession=session-b"))
        assertEquals(1, followers)
        emit(RunEvent.Cancelled())
        next.send(); idle()
        assertEquals("second" to "session-b", starts.last())
        assertSame(next, watcher.resolve("chat/a"))
        assertEquals(1, frames.subscriptionCount.value)
    }

    @Test fun newSessionAndRunProfileKeepOriginalProfileBinding() = kotlinx.coroutines.test.runTest(dispatcher) {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val route = "chat/a?run_profile=profile-a&newSession=session-b"
        val first = watcher.resolve(route)!!; idle(); start(first)
        assertEquals("profile-a", first.resolvedTarget?.profile)
        assertEquals("session-b", first.resolvedSessionId)
        assertSame(first, watcher.resolve(route))
        assertSame(first, watcher.resolve("chat/a?run_profile=profile-a"))
        assertEquals(1, followers)
    }

    @Test fun newSessionRouteAllowsOnlyCanonicalUniqueParameters() {
        assertTrue(app.rasul.notifications.isChatRoute("chat/a?session=session-a&run_profile="))
        assertTrue(app.rasul.notifications.isChatRoute("bridge-chat/a/default?session=session-a"))
        listOf("chat/a?session=", "chat/a?session=x&session=y", "chat/a?session=x&%73ession=y",
            "chat/a?session=x&newSession=y", "chat/a?newSession=x&session=y", "chat/a?session=%61",
            "chat/a?session=x&other=y").forEach { assertFalse(it, app.rasul.notifications.isChatRoute(it)) }
        assertTrue(app.rasul.notifications.isChatRoute("chat/a?newSession=session-b"))
        assertTrue(app.rasul.notifications.isChatRoute("bridge-chat/a/default?newSession=session-b"))
        assertTrue(app.rasul.notifications.isChatRoute("chat/a?newSession=session-b&run_profile=profile-a"))
        listOf("chat/a?newSession=", "chat/a?newSession=x&newSession=y",
            "chat/a?run_profile=x&run_profile=y", "chat/a?newSession=x&other=y",
            "chat/a?newSession=x&%6EewSession=y", "bridge-chat/a/default?run_profile=x",
            "chat/a?newSession=x%26run_profile%3Dy&run_profile=z&newSession=q").forEach {
            assertFalse(it, app.rasul.notifications.isChatRoute(it))
        }
    }

    @Test fun returningToChatUsesSameModelAndOneFollower() {
        val container = (context.applicationContext as RasulApp).container
        val watcher = RunWatcher(container, context, scope, createModel = { _, _ -> model() })
        val first = kotlinx.coroutines.runBlocking { watcher.get("chat/a", "Bot", loader, memory) }; idle(); start(first)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("chat", first); store.clear()
        val returned = kotlinx.coroutines.runBlocking { watcher.get("chat/a", "Bot", loader, memory) }
        assertSame(first, returned)
        emit(RunEvent.MessageDelta("after return"))
        assertEquals("after return", returned.state.value.messages.last().text)
        assertEquals(1, followers)
    }

    @Test fun enablingLockRedactsPreviouslyFinishedNotification() {
        val service = Robolectric.buildService(RunWatchService::class.java).get()
        val chat = RunWatcher.Chat("chat/a", "Bot", model())
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.notify("chat/a", 2, service.buildNotification(chat, RunNotice(false, "private answer"), false, false))
        app.rasul.notifications.redactPublishedNotifications(context)
        val published = manager.activeNotifications.single { it.tag == "chat/a" }.notification
        assertEquals(context.getString(R.string.run_public_reply, "Bot"), published.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertFalse(published.extras.toString().contains("private answer"))
        assertEquals(1, published.actions.size)
        assertEquals(1, published.publicVersion.actions.size)
        manager.cancelAll()
    }

    @Test fun serviceStopDoesNotRestartFromBackgroundProgress() {
        val starts = mutableListOf<android.content.Intent>()
        val serviceContext = object : android.content.ContextWrapper(context) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
                starts += service
                return service.component
            }
        }
        val container = (context.applicationContext as RasulApp).container
        val watcher = RunWatcher(container, serviceContext, scope, createModel = { _, _ -> model() })
        watcher.onForeground()
        val model = kotlinx.coroutines.runBlocking { watcher.get("chat/a", "Bot", loader, memory) }; idle(); start(model)
        assertEquals(1, starts.size)
        watcher.onBackground()
        watcher.serviceStopped()
        emit(RunEvent.MessageDelta("after service stop"))
        assertEquals(1, starts.size)
        watcher.onForeground()
        assertEquals(2, starts.size)
        assertEquals(1, followers)
    }


    @Test fun staleReplyWithAppLockEnabledDoesNotSendAndShowsOpenNotice() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle()
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        val service = controller.get()
        val intent = replyIntent(service, model)
        setLock(true)
        service.onStartCommand(intent, 0, 1); completeReply(service)
        assertTrue(starts.isEmpty()); assertTrue(steers.isEmpty())
        val notification = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == 3 }.notification
        assertEquals("Open Rasul to reply", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(1, notification.actions.size)
        controller.destroy()
    }

    @Test fun staleSessionReplyDoesNotSend() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle()
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        val intent = replyIntent(controller.get(), model).putExtra(app.rasul.notifications.REPLY_SESSION, "different-session")
        controller.get().onStartCommand(intent, 0, 1); completeReply(controller.get())
        assertTrue(starts.isEmpty()); assertTrue(steers.isEmpty())
        val notification = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == 3 }.notification
        assertEquals("This chat changed. Open it to reply.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        controller.destroy()
    }

    @Test fun lockOffMatchingReplySendsToOriginalSession() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher, memory = app.rasul.chat.chatRunMemory(container, "a")); idle()
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        controller.get().onStartCommand(replyIntent(controller.get(), model), 0, 1); completeReply(controller.get())
        assertEquals(listOf("second" to model.resolvedSessionId), starts)
        controller.destroy()
    }

    @Test fun lockSettingStillLoadingWaitsAndPreservesDisabledReplyPath() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher, memory = app.rasul.chat.chatRunMemory(container, "a")); idle()
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        val field = container.appLock.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val lock = field.get(container.appLock) as kotlinx.coroutines.flow.MutableStateFlow<app.rasul.security.AppLockState>
        lock.value = app.rasul.security.AppLockState()
        controller.get().onStartCommand(replyIntent(controller.get(), model), 0, 1); idle()
        assertTrue(starts.isEmpty())
        setLock(false); completeReply(controller.get())
        assertEquals(listOf("second" to model.resolvedSessionId), starts)
        controller.destroy()
    }

    @Test fun oldProfileIntentKeepsItsBindingAfterNotificationReplacement() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val first = get(watcher, memory = app.rasul.chat.chatRunMemory(container, "a")); idle()
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        val old = replyIntent(controller.get(), first)
        kotlinx.coroutines.runBlocking { connectionStore.save(app.rasul.data.Connection("a", "Bot", "https://example.invalid", "profile-b"), null) }
        val next = kotlinx.coroutines.runBlocking { watcher.resolve("chat/a") }!!; idle()
        val newer = replyIntent(controller.get(), next)
        assertNotEquals(old.data, newer.data)
        controller.get().onStartCommand(old, 0, 1); completeReply(controller.get())
        assertTrue(starts.isEmpty()); assertTrue(steers.isEmpty())
        controller.destroy()
    }

    @Test fun restoreCompletingInBackgroundDoesNotStartService() {
        val calls = mutableListOf<android.content.Intent>()
        val serviceContext = object : android.content.ContextWrapper(context) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? { calls += service; return service.component }
        }
        val gate = CompletableDeferred<Unit>()
        val delayedMemory = object : RunMemory by memory {
            override suspend fun activeRun(): String? { gate.await(); return "run-1" }
        }
        val watcher = RunWatcher(container, serviceContext, scope, createModel = { l, m -> model(l, m) })
        watcher.onForeground()
        get(watcher, memory = delayedMemory); idle()
        watcher.onBackground()
        gate.complete(Unit); idle()
        assertTrue(calls.isEmpty()); assertEquals(1, followers)
        watcher.onForeground(); idle()
        assertEquals(1, calls.size)
    }

    @Test fun rejectedForegroundStartDoesNotCrashAndCanRetryOnForeground() {
        var calls = 0
        val serviceContext = object : android.content.ContextWrapper(context) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
                calls++; throw android.app.ForegroundServiceStartNotAllowedException("restricted")
            }
        }
        persistedRun = "run-1"
        val watcher = RunWatcher(container, serviceContext, scope, createModel = { l, m -> model(l, m) })
        watcher.onForeground(); get(watcher, memory = persistedMemory); idle()
        assertTrue(calls > 0)
        val before = calls
        watcher.onForeground(); idle()
        assertTrue(calls > before)
    }

    @Test fun lostRunStopsServiceAndPublishesNormalRecoveryNotification() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle(); start(model)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        controller.get().onStartCommand(null, 0, 1); idle()
        assertTrue(frames.tryEmit(RunUpdate.Lost(HermesError.Unauthorized))); idle()
        assertTrue(org.robolectric.Shadows.shadowOf(controller.get()).isStoppedBySelf)
        val notification = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == 2 }.notification
        assertFalse(model.state.value.copy(queuedReplies = 1).hasActiveWork())
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals("Connection lost. Open the chat to resume.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        controller.destroy()
    }

    @Test fun idleConnectionChangeCreatesNewModelButActiveModelIsKept() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val first = get(watcher); idle()
        val changed = TargetLoader { HermesResult.Ok(HermesTarget("https://example.invalid/changed", null, "")) }
        val next = get(watcher, changed); idle()
        assertNotSame(first, next)
        start(next)
        assertSame(next, get(watcher))
        emit(RunEvent.Cancelled())
        assertNotSame(next, get(watcher))
    }

    @Test fun failedInitializationCreatesNewModelOnNextGet() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val first = get(watcher, TargetLoader { HermesResult.Err(HermesError.Unauthorized) }); idle()
        assertTrue(first.state.value.load is app.rasul.chat.ChatLoad.Failed)
        val next = get(watcher); idle()
        assertNotSame(first, next)
        assertEquals(app.rasul.chat.ChatLoad.Ready, next.state.value.load)
    }

    @Test fun activityStartRestoresPersistedActiveRunsWithoutOpeningChats() = kotlinx.coroutines.test.runTest(dispatcher) {
        kotlinx.coroutines.runBlocking {
            connectionStore.sessionId("a", null)
            connectionStore.setActiveRun("a", null, "run-1")
            connectionStore.sessionId("a", "profile-a")
            connectionStore.setActiveRun("a", "profile-a", "run-2")
            connectionStore.save(app.rasul.data.Connection("b", "Bot", "https://example.invalid"), "")
            connectionStore.sessionId("b", null)
            connectionStore.setActiveRun("b", null, "run-3")
        }
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        assertTrue(watcher.all().isEmpty())
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        idle()
        watcher.changes.first { watcher.all().count { it.model.state.value.run != null } == 3 }
        frames.subscriptionCount.first { it == 3 }
        assertEquals(setOf("run-1", "run-2", "run-3"), watcher.all().map { it.model.state.value.run?.runId }.toSet())
        assertEquals(setOf("chat/a", "chat/a?run_profile=profile-a", "chat/b"), watcher.all().map { it.route }.toSet())
        assertEquals(3, followers)
        controller.stop().destroy()
    }


    @Test fun sessionStorageFailureRemainsAChatFailureInsteadOfCrashingLookup() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val broken = object : RunMemory by memory { override suspend fun sessionId(): String = error("unavailable") }
        val model = get(watcher, memory = broken); idle()
        assertEquals(ChatFailure.SessionUnavailable, model.state.value.historyFailure)
    }

    @Test fun persistedRunEnumerationKeepsEveryConnectionAndProfileAndSkipsEventKeys() = kotlinx.coroutines.test.runTest(dispatcher) {
        connectionStore.save(app.rasul.data.Connection("b", "Bot", "https://example.invalid", "profile-b"), "")
        listOf("a" to null, "a" to "profile-a", "b" to "profile-b", "bridge:bridge-a:profile_a" to "profile_a").forEach { (id, profile) ->
            connectionStore.sessionId(id, profile)
            connectionStore.setActiveRun(id, profile, "run-1", 7, "anchor")
        }
        assertEquals(setOf(app.rasul.data.ConnectionStore.PersistedRun("a", null), app.rasul.data.ConnectionStore.PersistedRun("a", "profile-a"),
            app.rasul.data.ConnectionStore.PersistedRun("b", "profile-b"), app.rasul.data.ConnectionStore.PersistedRun("bridge:bridge-a:profile_a", "profile_a")), connectionStore.persistedRuns().toSet())
    }

    @Test fun profileChangeRestoreKeepsOneFollowerAndOneCompletionNotice() = kotlinx.coroutines.test.runTest(dispatcher) {
        connectionStore.save(app.rasul.data.Connection("a", "Bot", "https://example.invalid", "profile-a"), null)
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = watcher.resolve("chat/a")!!; idle(); start(model)
        connectionStore.activeRun("a", "profile-a").let { assertEquals("run-1", it) }
        connectionStore.save(app.rasul.data.Connection("a", "Bot", "https://example.invalid", "profile-b"), null)
        assertNotSame(model, watcher.resolve("chat/a")); idle()
        watcher.onBackground(); watcher.onForeground(); idle()
        assertSame(model, watcher.resolve("chat/a?run_profile=profile-a")); idle()
        assertEquals(2, watcher.all().size)
        assertEquals(1, followers)
        assertEquals(1, frames.subscriptionCount.value)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        try {
            controller.get().onStartCommand(null, 0, 1); idle()
            emit(RunEvent.Completed("answer"))
            val notices = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.filter { it.id == 2 }
            assertEquals(1, notices.size)
            assertEquals("answer", notices.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        } finally { controller.destroy() }
    }

    @Test fun expiredEventsStopServiceAndTimerAndPublishRecoveryNotice() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle(); start(model)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(null, 0, 1); idle()
            assertTrue(frames.tryEmit(RunUpdate.Lost(HermesError.NotFound))); idle()
            assertEquals(StatusLabel.LiveUpdatesUnavailable, model.state.value.run?.status)
            assertFalse(model.state.value.copy(queuedReplies = 1).hasActiveWork())
            assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf)
            val notification = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == 2 }.notification
            assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            assertEquals("Live updates stopped. Open the chat to see the result.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            val timer = service.javaClass.getDeclaredField("ticker").apply { isAccessible = true }.get(service) as kotlinx.coroutines.Job?
            assertTrue(timer == null || !timer.isActive)
        } finally { controller.destroy() }
    }

    @Test fun refusedReplyDoesNotReplaceProgressOrCompletionAnswer() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle(); start(model)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        try {
            val service = controller.get()
            val intent = replyIntent(service, model).putExtra(app.rasul.notifications.REPLY_SESSION, "different-session")
            service.onStartCommand(intent, 0, 1); completeReply(service)
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            assertEquals("This chat changed. Open it to reply.", manager.activeNotifications.single { it.id == 3 }.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(context.getString(R.string.status_thinking), manager.activeNotifications.single { it.id == 2 }.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            emit(RunEvent.Completed("answer"))
            assertEquals("answer", manager.activeNotifications.single { it.id == 2 }.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertTrue(manager.activeNotifications.none { it.id == 3 })
        } finally { controller.destroy() }
    }

    @Test fun previousRunFailureHasEnglishAndArabicNotificationText() {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag("ar"))
        val ar = context.createConfigurationContext(config)
        assertEquals("A task is still running in your current chat. Return to that chat and stop it before starting a new one.", app.rasul.notifications.failureText(context, ChatFailure.PreviousRunActive))
        assertEquals("لا تزال مهمة قيد التشغيل في محادثتك الحالية. ارجع إليها وأوقف المهمة قبل بدء محادثة جديدة.", app.rasul.notifications.failureText(ar, ChatFailure.PreviousRunActive))
    }

    @Test fun recoveryMessagesHaveEnglishAndArabicValues() {
        val ids = listOf(R.string.run_open_to_reply, R.string.run_chat_changed, R.string.run_connection_lost, R.string.run_live_updates_stopped)
        val english = listOf("Open Rasul to reply", "This chat changed. Open it to reply.", "Connection lost. Open the chat to resume.", "Live updates stopped. Open the chat to see the result.")
        val arabic = listOf("افتح رسول للرد", "تغيّرت هذه المحادثة. افتحها للرد.", "انقطع الاتصال. افتح المحادثة للاستئناف.", "توقفت التحديثات المباشرة. افتح المحادثة لرؤية النتيجة.")
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag("ar"))
        val ar = context.createConfigurationContext(config)
        ids.forEachIndexed { index, id -> assertEquals(english[index], context.getString(id)); assertEquals(arabic[index], ar.getString(id)) }
    }

    @Test fun screenStoreClearingDoesNotCancelApplicationFollower() {
        val model = model(); idle(); start(model)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("chat", model)
        store.clear()
        emit(RunEvent.MessageDelta("still following"))
        assertEquals("still following", model.state.value.messages.last().text)
        assertEquals(1, followers)
    }
    @Test fun restoredProfileWinsOverIdleRouteModel() = kotlinx.coroutines.test.runTest(dispatcher) {
        connectionStore.save(app.rasul.data.Connection("a", "Bot", "https://example.invalid", "profile-b"), null)
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val idleModel = watcher.resolve("chat/a")!!; idle()
        connectionStore.sessionId("a", "profile-a")
        connectionStore.setActiveRun("a", "profile-a", "run-1")
        val restored = watcher.resolve("chat/a?run_profile=profile-a", "run-1")!!; idle()
        connectionStore.save(app.rasul.data.Connection("a", "Bot", "https://example.invalid", "profile-a"), null)
        assertSame(restored, watcher.resolve("chat/a")); idle()
        assertNotSame(idleModel, restored)
        assertEquals(1, followers)
        assertEquals(1, frames.subscriptionCount.value)
    }

    @Test fun completionAfterTimeoutPublishesAnswerAndClearsOngoing() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle(); start(model)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        controller.get().onStartCommand(null, 0, 1); idle()
        watcher.onBackground()
        controller.get().onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.none { it.id == 2 && it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 })
        controller.destroy()
        emit(RunEvent.Completed("answer"))
        val finished = manager.activeNotifications.single { it.id == 2 }.notification
        assertEquals("answer", finished.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(0, finished.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test fun backgroundRestoreCompletionPublishesWithoutService() {
        persistedRun = "run-1"
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        get(watcher, memory = persistedMemory); idle()
        emit(RunEvent.Completed("answer"))
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertEquals("answer", manager.activeNotifications.single { it.id == 2 }.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun missingRunUsesHistoryAnswerInsteadOfEarlierError() {
        val model = model(); idle(); start(model)
        assertTrue(frames.tryEmit(RunUpdate.Lost(HermesError.BridgeTimeout))); idle()
        history = listOf(SessionMessageDto(role = "user", content = JsonPrimitive("first")), SessionMessageDto(role = "assistant", content = JsonPrimitive("answer")))
        model.retryHistory(); idle()
        statusResult = HermesResult.Err(HermesError.NotFound)
        model.reconnect(); idle()
        assertTrue(frames.tryEmit(RunUpdate.Lost(HermesError.NotFound))); idle()
        assertNull(model.state.value.run)
        assertEquals("answer", runNotice(context, model.state.value).text)
    }

    @Test fun serviceNotificationIsSilentSummaryInRunGroup() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle(); start(model)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        controller.get().onStartCommand(null, 0, 1); idle()
        val notifications = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
        val summary = notifications.single { it.id == 1 }.notification
        val run = notifications.single { it.id == 2 }.notification
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertNotNull(summary.group)
        assertEquals(summary.group, run.group)
        assertNull(summary.sound)
        controller.destroy()
    }

    private fun assertReplyRepostsOrigin(refused: Boolean) {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher, memory = app.rasul.chat.chatRunMemory(container, "a")); idle(); start(model)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        val service = controller.get(); service.onStartCommand(null, 0, 1); idle()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val origin = manager.activeNotifications.single { it.id == 2 }
        val intent = android.content.Intent(org.robolectric.Shadows.shadowOf(origin.notification.actions[1].actionIntent).savedIntent)
        if (refused) intent.putExtra(app.rasul.notifications.REPLY_SESSION, "different-session")
        val results = android.os.Bundle().apply { putCharSequence(app.rasul.notifications.REPLY_TEXT, "second") }
        androidx.core.app.RemoteInput.addResultsToIntent(arrayOf(androidx.core.app.RemoteInput.Builder(app.rasul.notifications.REPLY_TEXT).build()), intent, results)
        service.onStartCommand(intent, 0, 2); completeReply(service)
        assertNotSame(origin.notification, manager.activeNotifications.single { it.tag == origin.tag && it.id == 2 }.notification)
        controller.destroy()
    }

    @Test fun acceptedSteerRepostsOriginEvenWithUnchangedNotice() = assertReplyRepostsOrigin(false)
    @Test fun refusedReplyRepostsOrigin() = assertReplyRepostsOrigin(true)

    @Test fun foregroundServiceStopRestartsForActiveWork() {
        var calls = 0
        val serviceContext = object : android.content.ContextWrapper(context) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? { calls++; return service.component }
        }
        val watcher = RunWatcher(container, serviceContext, scope, createModel = { l, m -> model(l, m) })
        watcher.onForeground()
        val model = get(watcher); idle(); start(model)
        assertEquals(1, calls)
        watcher.serviceStopped()
        assertEquals(2, calls)
    }

    @Test fun suspendedTargetLoadDoesNotBlockOtherChatLookup() = kotlinx.coroutines.test.runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val slow = async { watcher.get("chat/a", "Bot", TargetLoader { gate.await(); loader.load() }, memory) }
        idle()
        val other = async { watcher.get("chat/b", "Bot", loader, memory) }
        idle()
        val independent = other.isCompleted
        gate.complete(Unit); slow.await(); other.await()
        assertTrue(independent)
    }

    @Test fun replacedIdleModelCancelsSuspendedInitialization() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        historyGate = CompletableDeferred()
        val old = get(watcher); idle()
        val changed = TargetLoader { HermesResult.Ok(HermesTarget("https://example.invalid/changed", null, "")) }
        val replacement = get(watcher, changed); idle()
        assertNotSame(old, replacement)
        history = listOf(SessionMessageDto(role = "assistant", content = JsonPrimitive("answer")))
        historyGate!!.complete(Unit); idle()
        assertTrue(old.state.value.messages.isEmpty())
        assertEquals("answer", replacement.state.value.messages.single().text)
    }

    @Test fun rekeyCollisionCancelsNewcomerFollower() = kotlinx.coroutines.test.runTest(dispatcher) {
        persistedRun = "run-1"
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val original = watcher.get("chat/a", "Bot", loader, persistedMemory, "run-1"); idle()
        watcher.get("chat/a?run_profile=", "Bot", loader, persistedMemory, "run-2"); idle()
        assertSame(original, watcher.all().single().model)
        assertEquals(1, frames.subscriptionCount.value)
    }

    @Test fun serviceStopsUsingLatestDeliveredStartId() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle(); start(model)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 4)
        service.onStartCommand(null, 0, 7); idle()
        emit(RunEvent.Completed("answer"))
        assertEquals(7, org.robolectric.Shadows.shadowOf(service).stopSelfResultId)
        controller.destroy()
    }

    @Test fun destructionClearsOngoingAndAppStillPublishesCompletion() {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) }); install(watcher)
        val model = get(watcher); idle(); start(model)
        val controller = Robolectric.buildService(RunWatchService::class.java).create()
        controller.get().onStartCommand(null, 0, 1); idle()
        watcher.onBackground()
        controller.destroy()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.none { it.id == 2 && it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 })
        emit(RunEvent.Completed("answer"))
        val answer = manager.activeNotifications.single { it.id == 2 }.notification
        assertEquals("answer", answer.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(0, answer.flags and Notification.FLAG_ONGOING_EVENT)
    }

    private fun notificationRoute(): String {
        val notice = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .single { it.id == 2 && it.notification.extras.getCharSequence(Notification.EXTRA_TEXT) == "answer" }
        return org.robolectric.Shadows.shadowOf(notice.notification.contentIntent).savedIntent
            .getStringExtra(app.rasul.notifications.CHAT_ROUTE)!!
    }

    private suspend fun installBridge() {
        val snapshots = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        val dataStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = snapshots
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                snapshots.value = transform(snapshots.value)
                return snapshots.value
            }
        }
        val store = app.rasul.bridge.BridgeStore(dataStore, object : app.rasul.data.SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String) = token
        })
        store.save(app.rasul.bridge.SavedBridge("one", "PC", listOf("wss://example.invalid"), "", "", ""))
        val clients = app.rasul.bridge.BridgeClients(store, scope, { _, urls, _ ->
            app.rasul.bridge.BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") })
        }, {})
        val repository = app.rasul.bridge.BridgeBotsRepository(store, clients)
        val field = repository.javaClass.getDeclaredField("mutable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(repository) as kotlinx.coroutines.flow.MutableStateFlow<app.rasul.bridge.BridgeBotsState>
        state.value = app.rasul.bridge.BridgeBotsState(listOf(app.rasul.bridge.BridgeGroup("one", "PC",
            listOf(app.rasul.bridge.BridgeProfile("default", "default", "Default", true, true)), loading = false)), loaded = true)
        container.javaClass.getDeclaredField("bridgeBots").apply { isAccessible = true }.set(container, repository)
    }

    private suspend fun assertCompletedNotificationKeepsSession(route: String) {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val original = watcher.resolve(route)!!; idle(); start(original)
        val session = original.resolvedSessionId
        emit(RunEvent.Completed("answer"))
        val notification = notificationRoute()
        val next = watcher.resolve("$route?newSession=session-b")!!; idle(); start(next)
        assertEquals("first" to "session-b", starts.last())
        assertSame(original, watcher.resolve(notification)); idle()
        assertEquals(session, watcher.resolve(notification)!!.resolvedSessionId)
        assertEquals(session, android.net.Uri.parse(notification).getQueryParameter("session"))
        history = listOf(SessionMessageDto(role = "user", content = JsonPrimitive("first")),
            SessionMessageDto(role = "assistant", content = JsonPrimitive("answer")))
        val cold = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val reopened = cold.resolve(notification)!!; idle()
        assertEquals(session, reopened.resolvedSessionId)
        assertEquals("session-b", next.resolvedSessionId)
    }

    @Test fun completedDirectNotificationOpensOriginalSessionAfterNewChatSends() = kotlinx.coroutines.test.runTest(dispatcher) {
        assertCompletedNotificationKeepsSession("chat/a")
    }

    @Test fun completedBridgeNotificationOpensOriginalSessionAfterNewChatSends() = kotlinx.coroutines.test.runTest(dispatcher) {
        installBridge()
        assertCompletedNotificationKeepsSession("bridge-chat/one/default")
    }

    @Test fun deletedNewChatNotificationCannotRestoreOrSendItsSession() = kotlinx.coroutines.test.runTest(dispatcher) {
        val watcher = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        val route = "chat/a?newSession=session-a"
        val original = watcher.resolve(route)!!; idle(); start(original)
        emit(RunEvent.Completed("answer"))
        val notification = notificationRoute()
        val alias = watcher.resolve("chat/a?run_profile=profile-a&newSession=session-a")!!; idle()
        assertNotSame(original, alias)
        historyGate = CompletableDeferred()
        historyEntered = CompletableDeferred()
        original.onDraft("second"); original.send(); idle()
        assertTrue(historyEntered!!.isCompleted)
        connectionStore.save(app.rasul.data.Connection("a", "Bot", "https://example.invalid"), java.util.UUID.randomUUID().toString())
        val holder = app.rasul.ui.ActivePcHolder(connectionStore, scope)
        idle()
        val api = object : app.rasul.sessions.SessionsManagementApi {
            override suspend fun list(target: HermesTarget, limit: Int) = HermesResult.Ok(listOf(app.rasul.sessions.SessionDto("session-a")))
            override suspend fun rename(target: HermesTarget, sessionId: String, title: String) = error("unused")
            override suspend fun delete(target: HermesTarget, sessionId: String) = HermesResult.Ok(Unit)
        }
        val vm = app.rasul.sessions.SessionsViewModel(connectionStore, holder, api, watcher::invalidateSession)
        idle()
        val ready = vm.state.value as app.rasul.data.Load.Ready
        vm.delete(ready.source, ready.value.single()); idle()
        assertNull(watcher.resolve(notification))
        assertNull(watcher.resolve(route))
        assertNull(watcher.modelForRoute(route))
        assertTrue(watcher.all().none { it.model.resolvedSessionId == "session-a" })
        assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id == 2 })
        historyGate!!.complete(Unit)
        original.onDraft("second"); original.send()
        alias.onDraft("second"); alias.send(); idle()
        assertEquals(1, starts.size)
        assertNull(connectionStore.currentSessionId("a", null))
        assertNull(connectionStore.currentSessionId("a", "profile-a"))
        val cold = RunWatcher(container, context, scope, createModel = { l, m -> model(l, m) })
        assertNull(cold.resolve(notification))
        assertNull(cold.resolve(route))
    }

    @Test fun deletionWaitsForSuspendedMemoryWriteBeforeClearingPointers() = kotlinx.coroutines.test.runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var persisted: String? = "session-a"
        var suspendWrite = false
        val persistent = object : RunMemory by memory {
            override suspend fun currentSessionId(): String? {
                if (suspendWrite) { suspendWrite = false; entered.complete(Unit); gate.await() }
                return persisted
            }
            override suspend fun setCurrentSessionRun(sessionId: String, runId: String?, lastEventId: Long?, anchor: app.rasul.chat.Anchor) {
                persisted = sessionId
            }
        }
        val runs = object : RunsApi by api {
            override suspend fun startRun(target: HermesTarget, input: String, sessionId: String?, idempotencyKey: String?): HermesResult<StartedRun> {
                val result = api.startRun(target, input, sessionId, idempotencyKey)
                suspendWrite = true
                return result
            }
        }
        val watcher = RunWatcher(container, context, scope, createModel = { l, m ->
            ChatViewModel(l, runs, sessions, m, OrderedWriter(scope), follow = { _, _, _ -> frames }, scope = scope)
        })
        val original = watcher.get("chat/a?newSession=session-a", "Bot", loader,
            app.rasul.chat.newChatRunMemory(persistent, "session-a")); idle(); start(original)
        assertTrue(entered.isCompleted)
        val deletion = launch { watcher.invalidateSession("session-a"); persisted = null }
        idle()
        val returnedEarly = deletion.isCompleted
        gate.complete(Unit); deletion.join(); idle()
        assertFalse(returnedEarly)
        assertNull(persisted)
    }

}
