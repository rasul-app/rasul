package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.bridge.*
import app.rasul.data.SecretCipher
import app.rasul.pairing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingStateTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun invitation(name: String): String {
        fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        val json = buildJsonObject {
            put("v", 1); put("bridge_id", UUID.randomUUID().toString()); put("name", name)
            putJsonArray("urls") { add(("wss://example.invalid" + "/v1/link")) }
            put("pin", random()); put("inv", UUID.randomUUID().toString().replace("-", "")); put("sec", random())
            put("exp", System.currentTimeMillis() / 1000 + 120)
        }
        return "rasul-pair:1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
    }
    @Test fun suspendedEnrollmentKeepsTheScannedValuesAndBlocksChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storageJob = SupervisorJob()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(storageJob + Dispatchers.IO)) { tmp.root.resolve("state.preferences_pb") }
        val store = BridgeStore(ds, object : SecretCipher {
            override fun encrypt(plain: String) = plain.reversed()
            override fun decrypt(token: String) = token.reversed()
        })
        val started = CompletableDeferred<PairingInvitation>()
        val finish = CompletableDeferred<PairingEnrollment>()
        val cert = HeldCertificate.Builder().ecdsa256().build()
        val identity = object : DeviceIdentity {
            override val privateKey = cert.keyPair.private
            override val certificateChain = arrayOf(cert.certificate)
        }
        val viewModels = androidx.lifecycle.ViewModelStore()
        val deleted = mutableListOf<String>()
        val vm = PairingViewModel(store, object : PairingService {
            override suspend fun enroll(invitation: PairingInvitation, identity: DeviceIdentity, onCode: (String) -> Unit): PairingEnrollment {
                onCode("123456"); started.complete(invitation); return finish.await()
            }
        }, { identity }, { deleted.add(it) })
        viewModels.put("pairing", vm)
        try {
            vm.scan(invitation("First"))
            val captured = started.await()
            vm.reset(); vm.scan(invitation("Second"))
            assertEquals(PairingState.Sending("First", "123456"), vm.state.value)
            finish.complete(PairingEnrollment(UUID.randomUUID().toString().replace("-", ""), captured.urls.single()))
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it is PairingState.Paired } } }
            assertEquals("First", store.get(captured.bridgeId)?.name)
            assertEquals(captured.urls.single(), store.get(captured.bridgeId)?.lastWorkingUrl)
            assertEquals(1, store.bridges.first().size)
            assertTrue(deleted.isEmpty())
        } finally { viewModels.clear(); storageJob.cancelAndJoin(); Dispatchers.resetMain() }
    }
}
