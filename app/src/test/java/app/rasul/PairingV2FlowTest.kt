package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.bridge.*
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.SecretCipher
import app.rasul.pairing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingV2FlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(token: String) = token.reversed()
    }
    private fun v2Code(): String {
        fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        val json = buildJsonObject {
            put("v", 2); put("host_id", UUID.randomUUID().toString()); put("name", "Computer")
            putJsonArray("urls") { add("wss://example.invalid") }
            put("pin", random()); put("inv", UUID.randomUUID().toString().replace("-", "")); put("sec", random())
            put("exp", System.currentTimeMillis() / 1000 + 120)
        }
        return "rasul-pair:2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
    }
    private fun device(): DeviceIdentity {
        val cert = HeldCertificate.Builder().ecdsa256().build()
        return object : DeviceIdentity {
            override val privateKey = cert.keyPair.private
            override val certificateChain = arrayOf(cert.certificate)
        }
    }

    @Test fun withTheSwitchOffAV2CodeShowsTheNewerMessageAndSendsNothing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = SupervisorJob()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { tmp.root.resolve("off.preferences_pb") }
        val store = BridgeStore(ds, cipher)
        var enrolls = 0
        var identities = 0
        val vm = PairingViewModel(store, object : PairingService {
            override suspend fun enroll(invitation: PairingInvitation, identity: DeviceIdentity, onCode: (String) -> Unit): PairingEnrollment {
                enrolls++; error("not expected")
            }
        }, { identities++; device() }, {}, v2Enabled = false)
        try {
            vm.scan(v2Code())
            advanceUntilIdle()
            assertEquals(PairingState.Failed(R.string.pair_newer_host), vm.state.value)
            assertEquals(0, enrolls)
            assertEquals(0, identities)
            assertTrue(store.bridges.first().isEmpty())
        } finally { job.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun aTransportThatThrowsAnIoErrorShowsTheConnectionMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = SupervisorJob()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { tmp.root.resolve("io.preferences_pb") }
        val store = BridgeStore(ds, cipher)
        val vm = PairingViewModel(store, PairingClient(transport = { _, _ -> throw java.io.IOException("down") }), { device() }, {}, v2Enabled = true)
        try {
            vm.scan(v2Code())
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it is PairingState.Failed } } }
            assertEquals(PairingState.Failed(R.string.pair_connection), vm.state.value)
            assertTrue(store.bridges.first().isEmpty())
        } finally { job.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun withTheSwitchOnAV2CodeEnrollsAndSavesProtocolTwoWithTheRole() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = SupervisorJob()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { tmp.root.resolve("on.preferences_pb") }
        val store = BridgeStore(ds, cipher)
        val seen = CompletableDeferred<PairingInvitation>()
        val vm = PairingViewModel(store, object : PairingService {
            override suspend fun enroll(invitation: PairingInvitation, identity: DeviceIdentity, onCode: (String) -> Unit): PairingEnrollment {
                seen.complete(invitation)
                return PairingEnrollment(UUID.randomUUID().toString().replace("-", ""), null, "viewer")
            }
        }, { device() }, {}, v2Enabled = true)
        try {
            vm.scan(v2Code())
            val invitation = seen.await()
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it is PairingState.Paired } } }
            assertEquals(2, invitation.protocol)
            val saved = store.get(invitation.bridgeId)!!
            assertEquals(2, saved.protocol)
            assertEquals("viewer", saved.role)
        } finally { job.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun aNewerHostErrorHasItsOwnMessageEverywhereItIsShown() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(context.getString(R.string.pair_newer_host),
            app.rasul.notifications.failureText(context, app.rasul.chat.ChatFailure.Api(HermesError.NewerHost)))
        assertEquals(HermesError.NewerHost, bridgeError(NewerHostException()))
        assertEquals(HermesError.NewerHost, bridgeError(java.io.IOException("wrapped", NewerHostException())))
    }

    @Test fun aNewerHostIsShownAsOneNoticeAndNotAsNoReadyBots() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("notice.preferences_pb") }
        val store = BridgeStore(ds, cipher)
        val saved = SavedBridge(UUID.randomUUID().toString(), "Computer", listOf("wss://example.invalid"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            protocol = 2, role = "owner")
        val clients = BridgeClients(store, scope, { _, _, _ -> error("not expected") }, {})
        val repository = BridgeBotsRepository(store, clients) { error("not expected") }
        val connections = app.rasul.data.ConnectionStore(ds, cipher)
        try {
            store.save(saved)
            val following = launch { repository.follow() }
            val holder = app.rasul.ui.ActivePcHolder(connections, scope, repository)
            try {
                val picker = withTimeout(10_000) { holder.state.first { it.bridgeNotice != null } }
                assertEquals(app.rasul.ui.PcBridgeNotice.Failed(HermesError.NewerHost), picker.bridgeNotice)
            } finally { following.cancelAndJoin() }
        } finally { job.cancelAndJoin() }
    }

    @Test fun aProtocolTwoPcIsNeverGivenAV1Transport() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("guard.preferences_pb") }
        val store = BridgeStore(ds, cipher)
        val saved = SavedBridge(UUID.randomUUID().toString(), "Computer", listOf("wss://example.invalid"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            protocol = 2, role = "owner")
        var created = 0
        var listed = 0
        val clients = BridgeClients(store, scope, { _, _, _ -> created++; error("not expected") }, {})
        val api = object : ProfilesApi {
            override suspend fun list(): ProfileSnapshot { listed++; error("not expected") }
            override fun events() = flow<ProfileSnapshot> { listed++; error("not expected") }
        }
        val repository = BridgeBotsRepository(store, clients) { listed++; api }
        try {
            store.save(saved)
            try { clients.client(saved); fail("expected refusal") } catch (_: NewerHostException) { }
            val following = launch { repository.follow() }
            try {
                withTimeout(10_000) { repository.state.first { it.groups.singleOrNull()?.newerHost == true } }
            } finally { following.cancelAndJoin() }
            assertEquals(HermesError.NewerHost, (repository.target(saved.bridgeId, "default") as HermesResult.Err).error)
            assertEquals(0, created)
            assertEquals(0, listed)
        } finally { job.cancelAndJoin() }
    }
}
