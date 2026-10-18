package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.WindowInsetsControllerCompat
import app.rasul.bridge.*
import app.rasul.data.ActiveTarget
import app.rasul.data.Connection
import app.rasul.data.HermesError
import app.rasul.data.SecretCipher
import app.rasul.settings.MoreScreen
import app.rasul.ui.*
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import androidx.navigation.compose.composable
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class BridgeBotsUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun offlineSavedSelectionKeepsSwitcherAndDirectPcVisible() {
        val bridgePc = Connection("bridge:offline:default", "Offline PC · Default", "", "default")
        val directPc = Connection("direct", "Direct PC", "http://example.invalid", "default")
        val picker = PcPicker(
            pcs = listOf(bridgePc, directPc),
            activeId = bridgePc.id,
            target = ActiveTarget.Failed(HermesError.BridgeUnreachable),
            loaded = true,
            targetLoaded = true,
            bridgeGroups = listOf(
                BridgeGroup("offline", "Offline PC", listOf(BridgeProfile("default", "default", "Default", true, true)), loading = false, error = HermesError.BridgeUnreachable),
            ),
        )
        compose.setContent { RasulTheme(false) { PcSwitcherHost(picker, {}) } }

        compose.onNodeWithText("Offline PC · Default").assertExists().performClick()
        compose.onNodeWithText("Direct PC").assertExists()
        compose.onNodeWithText("Unavailable").assertExists()
    }

    @Test fun moreKeepsForgetAvailableWhileBridgeProfilesAreLoading() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String) = token
        }
        val store = BridgeStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("paired-bridge.preferences_pb") },
            cipher,
        )
        val savedBridge = SavedBridge(
            "studio", "Studio PC", listOf("wss://example.invalid"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), "device", "key",
        )
        store.save(savedBridge)
        val clients = BridgeClients(store, scope, deleteKey = {})
        val bridgeBots = BridgeBotsRepository(store, clients)
        val details = mutableStateOf(false)
        val openedBridge = mutableStateOf<String?>(null)
        val bridgeState = BridgeBotsState(listOf(BridgeGroup(savedBridge.bridgeId, savedBridge.name, loading = true)))
        try {
            compose.setContent {
                RasulTheme(false) {
                    if (details.value) {
                        SavedBridgesScreen(
                            store = store,
                            bridgeBots = bridgeBots,
                            onScan = {},
                            onDirect = {},
                            onClose = {},
                            selectedBridgeId = openedBridge.value,
                        )
                    } else {
                        MoreScreen(
                            pcState = PcPicker(loaded = true, targetLoaded = false),
                            onAdd = {},
                            onEdit = {},
                            onRetry = {},
                            appLockEnabled = false,
                            appLockAvailable = true,
                            onAppLockChanged = {},
                            bridgeState = bridgeState,
                            onBridgeOpen = { openedBridge.value = it; details.value = true },
                        )
                    }
                }
            }

            compose.onNodeWithText(savedBridge.name).assertExists().performClick()
            assertEquals(savedBridge.bridgeId, openedBridge.value)
            compose.waitUntil(timeoutMillis = 5000) {
                compose.onAllNodesWithText("Forget this PC").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Forget this PC").assertExists()
            Unit
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun readyBotHasButtonRoleNameAndCapturedBridgeProfileAction() {
        var opened: Pair<String, String>? = null
        val pickerPage = mutableStateOf(0)
        val botChoices = (0..15).map { ChatBotChoice("bot-$it", "Bot $it") }
        var pickedBot: ChatBotChoice? = null
        val bridgeGroups = (0..15).map { BridgeGroup("bridge-$it", "PC $it") }
        var pickedBridge: BridgeGroup? = null
        compose.setContent {
            RasulTheme(false) {
                when (pickerPage.value) {
                    0 -> BotsListContent(ConnectionsState.Loaded(emptyList()), {}, {}, {}, {},
                        bridges = BridgeBotsState(listOf(BridgeGroup("a", "Bridge", listOf(BridgeProfile("default", "default", "default", true, true)), false))),
                        onBridgeOpen = { bridge, profile -> opened = bridge to profile })
                    1 -> BotPickerSheet(botChoices, {}, { pickedBot = it })
                    else -> BridgePickerSheet(bridgeGroups, {}, { pickedBridge = it })
                }
            }
        }
        compose.onNode(hasText("default") and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        assertEquals("a" to "default", opened)

        pickerPage.value = 1
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Bot 15"))
        compose.onNodeWithText("Bot 15").performClick()
        assertEquals(botChoices.last(), pickedBot)

        pickerPage.value = 2
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("PC 15"))
        compose.onNodeWithText("PC 15").performClick()
        assertEquals(bridgeGroups.last(), pickedBridge)
    }

    @Test fun newChatOpensOneBotAndOffersPickerForSeveral() {
        lateinit var nav: androidx.navigation.NavHostController
        val items = mutableStateOf(listOf(Connection("one", "Research", "", null)))
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            nav = androidx.navigation.compose.rememberNavController()
            androidx.navigation.compose.NavHost(nav, startDestination = "bots") {
                composable("bots") {
                    RasulTheme(false) {
                        ActionPickerHost(ConnectionsState.Loaded(items.value), BridgeBotsState(loaded = true), null, nav) { chat, _ ->
                            androidx.compose.material3.Button(onClick = chat) { androidx.compose.material3.Text("New chat") }
                        }
                    }
                }
                composable("chat/{id}?newSession={newSession}", arguments = listOf(
                    androidx.navigation.navArgument("newSession") { nullable = true; defaultValue = null; type = androidx.navigation.NavType.StringType },
                )) {}
            }
        }
        compose.onNodeWithText("New chat").performClick()
        var firstSession: String? = null
        compose.runOnIdle {
            assertEquals("one", nav.currentBackStackEntry?.arguments?.getString("id"))
            firstSession = nav.currentBackStackEntry?.arguments?.getString("newSession")
            assertTrue(firstSession?.startsWith("rasul-") == true)
            nav.popBackStack()
            items.value = items.value + Connection("two", "Deploys", "", null)
        }
        compose.onNodeWithText("New chat").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Research").assertExists()
        compose.onNodeWithText("Deploys").performClick()
        compose.runOnIdle {
            assertEquals("two", nav.currentBackStackEntry?.arguments?.getString("id"))
            val session = nav.currentBackStackEntry?.arguments?.getString("newSession")
            assertTrue(session?.startsWith("rasul-") == true)
            assertTrue(firstSession != session)
        }
    }

    @Test fun searchJobResultRemovesSearchFromBothTabStacks() {
        lateinit var nav: androidx.navigation.testing.TestNavHostController
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer(context)
        lateinit var owner: androidx.lifecycle.ViewModelStoreOwner
        compose.setContent {
            owner = checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
            nav = androidx.compose.runtime.remember {
                androidx.navigation.testing.TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(androidx.navigation.compose.ComposeNavigator())
                }
            }
            RasulTheme(false) {
                RasulNav(container, false, false, {}, { _, cancel -> cancel() }, nav = nav)
            }
        }
        compose.runOnIdle { nav.navigate("jobs"); nav.navigate("search") }
        compose.waitForIdle()
        val existing = androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = throw AssertionError("Tab data was not shared")
        })
        val sessions = existing[app.rasul.sessions.SessionsViewModel::class.java]
        val jobs = existing[app.rasul.jobs.JobsViewModel::class.java]
        compose.runOnIdle {
            nav.openJobsFromSearch()
            assertEquals("jobs", nav.currentDestination?.route)
            assertFalse(nav.currentBackStack.value.any { it.destination.route == "search" })
        }
        compose.runOnIdle {
            nav.navigate("sessions") { popUpTo("bots") { saveState = true }; restoreState = true; launchSingleTop = true }
            nav.navigate("search")
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.openJobsFromSearch() }
        compose.waitForIdle()
        compose.onNode(hasText("Sessions") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("sessions", nav.currentDestination?.route) }
        org.junit.Assert.assertSame(sessions, existing[app.rasul.sessions.SessionsViewModel::class.java])
        org.junit.Assert.assertSame(jobs, existing[app.rasul.jobs.JobsViewModel::class.java])
        container.appScope.cancel()
    }

    @Test fun searchJobResultClickUsesDestinationCallback() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer(context)
        val snapshots = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        val dataStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = snapshots
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                snapshots.value = transform(snapshots.value)
                return snapshots.value
            }
        }
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String) = token
        }
        val store = app.rasul.data.ConnectionStore(dataStore, cipher)
        store.save(Connection("one", "PC", "https://github.com/rasul-app/rasul#readme"), java.util.UUID.randomUUID().toString())
        val pcs = ActivePcHolder(store, container.appScope)
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"jobs":[{"id":"one","name":"Daily report","enabled":true}]}""".toResponseBody()).build()
        }.build()
        lateinit var nav: androidx.navigation.NavHostController
        try {
            compose.setContent {
                val owner = checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
                androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        app.rasul.jobs.JobsViewModel(store, pcs, app.rasul.jobs.JobsClient(app.rasul.data.HermesClient(http))) as T
                })[app.rasul.jobs.JobsViewModel::class.java]
                nav = androidx.navigation.compose.rememberNavController()
                RasulTheme(false) { RasulNav(container, false, false, {}, { _, cancel -> cancel() }, nav) }
            }
            compose.runOnIdle { nav.navigate("sessions"); nav.navigate("search") }
            compose.onNode(hasSetTextAction()).performTextInput("Daily")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Daily report").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Daily report").performClick()
            compose.runOnIdle {
                assertEquals("jobs", nav.currentDestination?.route)
                assertFalse(nav.currentBackStack.value.any { it.destination.route == "search" })
            }
        } finally {
            container.appScope.cancel()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun searchRestoresQueryAndChip() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            RasulTheme(false) {
                SearchScreen(ConnectionsState.Loaded(listOf(Connection("one", "deploy", "", null))),
                    BridgeBotsState(loaded = true), app.rasul.data.Load.NoConnection, app.rasul.data.Load.NoConnection,
                    {}, { _, _, _ -> }, {}, {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("deploy")
        compose.onNode(hasText("Bots") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("deploy")
        compose.onNode(hasText("Bots") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsSelected()
        compose.onNode(hasText("deploy") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertExists()
    }

    @Test fun newChatWaitsForBridgeLoading() {
        lateinit var nav: androidx.navigation.NavHostController
        compose.setContent {
            nav = androidx.navigation.compose.rememberNavController()
            androidx.navigation.compose.NavHost(nav, startDestination = "bots") {
                composable("bots") {
                    RasulTheme(false) {
                        ActionPickerHost(ConnectionsState.Loaded(emptyList()), BridgeBotsState(), null, nav) { chat, _ ->
                            androidx.compose.material3.Button(onClick = chat) { androidx.compose.material3.Text("New chat") }
                        }
                    }
                }
                composable("pair-guide") {}
            }
        }
        compose.onNodeWithText("New chat").performClick()
        compose.runOnIdle { assertEquals("bots", nav.currentDestination?.route) }
    }

    @Test fun searchShowsNoResultsAlongsideCategoryError() {
        compose.setContent {
            RasulTheme(false) {
                SearchScreen(ConnectionsState.Loaded(emptyList()), BridgeBotsState(loaded = true),
                    app.rasul.data.Load.NoConnection, app.rasul.data.Load.Failed(HermesError.Server(503)),
                    {}, { _, _, _ -> }, {}, {}, initialQuery = "deploy")
            }
        }
        compose.onNodeWithText("No results for “deploy”").assertExists()
        compose.onNodeWithText("Jobs could not be loaded (HTTP 503).").assertExists()
    }

    @Test fun sessionTimesUseRealResourcesForNumericAndIso() {
        compose.setContent {
            RasulTheme(false) {
                app.rasul.sessions.SessionList(listOf(
                    app.rasul.sessions.SessionDto("one", "Numeric", lastActive = kotlinx.serialization.json.JsonPrimitive(1_759_999_700)),
                    app.rasul.sessions.SessionDto("two", "ISO", lastActive = kotlinx.serialization.json.JsonPrimitive("2025-10-09T08:48:20Z")),
                ), "one", 1_760_000_000_000L, onOpen = {})
            }
        }
        compose.onAllNodesWithText("5m").assertCountEquals(2)
    }

    @Test fun newChatPickerNamesPcsAndSelectsTheChosenBridge() {
        lateinit var nav: androidx.navigation.NavHostController
        var selected: Pair<String, String>? = null
        val groups = listOf("Study", "Studio").mapIndexed { i, name ->
            BridgeGroup("pc$i", name, listOf(BridgeProfile("default", "default", "default", true, true)), false)
        }
        compose.setContent {
            nav = androidx.navigation.compose.rememberNavController()
            androidx.navigation.compose.NavHost(nav, startDestination = "bots") {
                composable("bots") {
                    RasulTheme(false) {
                        ActionPickerHost(ConnectionsState.Loaded(emptyList()), BridgeBotsState(groups, loaded = true), null, nav,
                            onSelectBridge = { bridge, profile -> selected = bridge to profile }) { chat, _ ->
                            androidx.compose.material3.Button(onClick = chat) { androidx.compose.material3.Text("New chat") }
                        }
                    }
                }
                composable("bridge-chat/{bridge}/{profile}?newSession={newSession}", arguments = listOf(
                    androidx.navigation.navArgument("newSession") { nullable = true; defaultValue = null; type = androidx.navigation.NavType.StringType },
                )) {}
            }
        }
        compose.onNodeWithText("New chat").performClick()
        compose.onNodeWithText("Studio").assertExists().performClick()
        compose.runOnIdle {
            assertEquals("pc1" to "default", selected)
            val session = nav.currentBackStackEntry?.arguments?.getString("newSession")
            assertTrue(session?.startsWith("rasul-") == true)
        }
    }

    @Test fun newBotKeepsErroredActiveBridgeIdentity() {
        lateinit var nav: androidx.navigation.NavHostController
        val group = BridgeGroup("pc", "Studio", emptyList(), false, HermesError.BridgeUnreachable)
        compose.setContent {
            nav = androidx.navigation.compose.rememberNavController()
            androidx.navigation.compose.NavHost(nav, startDestination = "bots") {
                composable("bots") {
                    RasulTheme(false) {
                        ActionPickerHost(ConnectionsState.Loaded(emptyList()), BridgeBotsState(listOf(group), loaded = true), "bridge:pc:default", nav) { _, bot ->
                            androidx.compose.material3.Button(onClick = bot) { androidx.compose.material3.Text("New bot") }
                        }
                    }
                }
                composable("bridge-create/{bridge}") {}
                composable("edit") {}
            }
        }
        compose.onNodeWithText("New bot").performClick()
        compose.runOnIdle { assertEquals("bridge-create/{bridge}", nav.currentDestination?.route) }
    }

    @Test fun screenshotCoverageIncludesEveryRequestedStateWithoutNumberClashes() {
        val methods = app.rasul.screens.ScreenshotTest.allPreviews().map { it.methodName }
        val required = listOf("PlusMenuAr", "SearchEmptyEn", "SearchNoResultsEn", "SearchLoadingEn", "SearchErrorEn", "BotPickerEn", "AppearanceSheetEn", "PrivacyEn")
        assertTrue("Missing: " + (required - methods.toSet()), methods.containsAll(required))
        val numbered = methods.groupBy { it.removeSuffix("Ar").removeSuffix("En") }.keys.groupBy {
            app.rasul.screens.ScreenshotTest.fileName(it + "En").substringAfterLast('/').substringBefore('-')
        }
        assertTrue("Clashing screen numbers: $numbered", numbered.values.none { it.size > 1 })
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-w390dp-h700dp")
    fun sessionTimesUseArabicResourcesForNumericAndIso() {
        compose.setContent {
            RasulTheme(false) {
                app.rasul.sessions.SessionList(listOf(
                    app.rasul.sessions.SessionDto("one", "Numeric", lastActive = kotlinx.serialization.json.JsonPrimitive(1_759_999_700)),
                    app.rasul.sessions.SessionDto("two", "ISO", lastActive = kotlinx.serialization.json.JsonPrimitive("2025-10-09T08:48:20Z")),
                ), "one", 1_760_000_000_000L, onOpen = {})
            }
        }
        compose.onAllNodesWithText("٥ دقائق").assertCountEquals(2)
    }

    @Test fun previousRunWarningHasSpecificEnglishAndArabicValues() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val arabic = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("ar"))
        })
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(app.rasul.chat.failureText(app.rasul.chat.ChatFailure.PreviousRunActive))
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides arabic) {
                    androidx.compose.material3.Text(app.rasul.chat.failureText(app.rasul.chat.ChatFailure.PreviousRunActive))
                }
            }
        }
        compose.onNodeWithText("A task is still running in your current chat. Return to that chat and stop it before starting a new one.").assertExists()
        compose.onNodeWithText("لا تزال مهمة قيد التشغيل في محادثتك الحالية. ارجع إليها وأوقف المهمة قبل بدء محادثة جديدة.").assertExists()
    }

    @Test fun newChatDoesNotAutoOpenDirectBotWhileBridgeBotsAreLoading() {
        assertEquals(NewChatAction.Loading, newChatAction(listOf(ChatBotChoice("one", "Research")), bridgesLoading = true))
        assertEquals(NewChatAction.Unavailable(HermesError.BridgeTimeout), newChatAction(emptyList(), unavailable = true, error = HermesError.BridgeTimeout))
        assertEquals(NewChatAction.NoBots, newChatAction(emptyList()))
    }

    @Test fun appearanceOptionsMapToSystemLightAndDark() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        applySystemBarAppearance(activity.window, dark = false)
        assertTrue(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
        applySystemBarAppearance(activity.window, dark = true)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)
        activity.finish()
    }

    @Test fun notReadyBotShowsSpecificSetupMessageAndHasNoOpenAction() {
        compose.setContent {
            RasulTheme(false) {
                BotsListContent(ConnectionsState.Loaded(emptyList()), {}, {}, {}, {},
                    bridges = BridgeBotsState(listOf(BridgeGroup("a", "Bridge", listOf(BridgeProfile("default", "default", "default", false, true)), false))))
            }
        }
        compose.onNodeWithText("default").assertHasNoClickAction()
        compose.onNodeWithText("This bot is not ready. Complete its setup on the computer.").assertExists()
    }

    @Test fun revokedBridgeShowsSpecificMessageAndDisablesCachedBot() {
        compose.setContent {
            RasulTheme(false) {
                BotsListContent(ConnectionsState.Loaded(emptyList()), {}, {}, {}, {},
                    bridges = BridgeBotsState(listOf(BridgeGroup("a", "Bridge", listOf(BridgeProfile("default", "default", "default", true, true)), false, HermesError.DeviceRevoked))))
            }
        }
        compose.onNodeWithText("This device no longer has access. Remove the bridge and pair again.").assertExists()
        compose.onNodeWithText("default").assertHasNoClickAction()
    }
    private fun withTranscriptNavigation(sessionSource: String,
        check: (AppContainer, app.rasul.data.ConnectionStore, CoroutineScope, androidx.navigation.NavHostController) -> Unit) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val container = AppContainer(context, appScope = appScope)
        val snapshots = MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        val ds = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = snapshots
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                snapshots.value = transform(snapshots.value)
                return snapshots.value
            }
        }
        val store = app.rasul.data.ConnectionStore(ds, object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String) = token
        })
        runBlocking { store.save(Connection("a", "Bot", "https://example.invalid"), "sample") }
        val session = app.rasul.sessions.SessionDto("session-a", "Sample session", source = sessionSource)
        val sessionList = kotlinx.serialization.json.Json.encodeToString(app.rasul.sessions.SessionListDto.serializer(),
            app.rasul.sessions.SessionListDto(listOf(session)))
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath.endsWith("/api/sessions")) sessionList else "{\"data\":[]}"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("sample").body(body.toResponseBody()).build()
        }.build()
        val pcs = ActivePcHolder(store, pickerScope)
        container.javaClass.getDeclaredField("connections").apply { isAccessible = true }.set(container, store)
        container.javaClass.getDeclaredField("activePc").apply { isAccessible = true }.set(container, pcs)
        container.javaClass.getDeclaredField("sessions").apply { isAccessible = true }
            .set(container, app.rasul.sessions.SessionsClient(app.rasul.data.HermesClient(http)))
        container.javaClass.getDeclaredField("runWatcher").apply { isAccessible = true }
            .set(container, app.rasul.notifications.RunWatcher(container, context, appScope))
        lateinit var nav: androidx.navigation.NavHostController
        try {
            compose.setContent {
                nav = androidx.navigation.compose.rememberNavController()
                RasulTheme(false) { RasulNav(container, false, false, {}, { _, cancel -> cancel() }, nav) }
            }
            compose.runOnIdle { nav.navigate("sessions") }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Sample session").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sample session").performClick()
            lateinit var vm: app.rasul.sessions.TranscriptViewModel
            compose.runOnIdle { vm = androidx.lifecycle.ViewModelProvider(nav.currentBackStackEntry!!)[app.rasul.sessions.TranscriptViewModel::class.java] }
            val settled = CompletableDeferred<Unit>()
            val collector = CoroutineScope(Dispatchers.Default).launch {
                vm.state.collect { if (it !is app.rasul.data.Load.Loading) settled.complete(Unit) }
            }
            try {
                compose.waitUntil(10_000) {
                    compose.onRoot().fetchSemanticsNode()
                    settled.isCompleted
                }
                runBlocking { settled.await() }
                assertTrue("Transcript state: ${vm.state.value}", vm.state.value is app.rasul.data.Load.Ready)
            } finally { collector.cancel() }
            compose.onNodeWithText("This session has no messages.").assertExists()
            check(container, store, pickerScope, nav)
        } finally {
            pickerScope.cancel(); appScope.cancel()
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }

    @Test fun cronSessionSourceHidesContinueThroughNavigation() {
        withTranscriptNavigation("cron") { _, _, _, nav ->
            compose.runOnIdle { assertEquals("cron", nav.currentBackStackEntry?.arguments?.getString("sessionSource")) }
            compose.onNodeWithText("Continue in chat").assertDoesNotExist()
        }
    }

    @Test fun continueThroughNavigationSelectsPointerAndUsesPlainRoute() {
        withTranscriptNavigation("api_server") { _, store, _, nav ->
            compose.onNodeWithText("Continue in chat").performClick()
            compose.waitUntil(10_000) { nav.currentDestination?.route?.startsWith("chat/") == true }
            compose.runOnIdle {
                assertNull(nav.currentBackStackEntry?.arguments?.getString("session"))
                assertNull(nav.currentBackStackEntry?.arguments?.getString("source"))
                assertEquals("a", nav.currentBackStackEntry?.arguments?.getString("id"))
            }
            assertEquals("session-a", runBlocking { store.currentSessionId("a", null) })
        }
    }

    @Test fun continueThroughNavigationRefusesEditDespiteStalePickerHint() {
        withTranscriptNavigation("api_server") { _, store, pickerScope, nav ->
            pickerScope.cancel()
            runBlocking { store.save(Connection("a", "Bot", "https://example.invalid/changed"), null) }
            compose.onNodeWithText("Continue in chat").performClick()
            compose.onNodeWithText("The connection changed since this item was loaded, so your request was not sent.").assertExists()
            compose.runOnIdle { assertTrue(nav.currentDestination?.route?.startsWith("transcript/") == true) }
            assertNull(runBlocking { store.currentSessionId("a", null) })
        }
    }

}
