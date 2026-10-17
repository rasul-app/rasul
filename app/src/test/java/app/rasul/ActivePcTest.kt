package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.data.ActiveTarget
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.SecretCipher
import app.rasul.data.activeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActivePcTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(token: String) = token.removePrefix("enc:").reversed()
    }

    private fun store(name: String, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)): ConnectionStore {
        val ds = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.root.resolve(name)
        }
        return ConnectionStore(ds, fake)
    }

    @Test fun pickedPcIsActiveAndSurvivesNewStoreInstance() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = store("p.preferences_pb", scope)
        val a = s.save(Connection("", "Home", "http://192.0.2.5:8642"), "ka")
        val b = s.save(Connection("", "Laptop", "http://192.0.2.9:8642"), "kb")

        // nothing picked: the first saved one
        assertEquals(a, s.active()?.id)

        s.setActive(b)
        assertEquals(b, s.active()?.id)
        val t = s.activeTarget() as ActiveTarget.Ready
        assertEquals("Laptop", t.name)
        assertEquals("kb", t.target.apiKey)

        // same file, new store object (process restart): the choice is persisted
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertEquals(b, store("p.preferences_pb").active()?.id)
    }

    @Test fun deletedPickFallsBackToFirst() = runBlocking {
        val s = store("q.preferences_pb")
        val a = s.save(Connection("", "Home", "http://192.0.2.5:8642"), "ka")
        val b = s.save(Connection("", "Laptop", "http://192.0.2.9:8642"), "kb")
        s.setActive(b)
        s.delete(b)
        assertEquals(a, s.active()?.id)
        assertTrue(s.activeTarget() is ActiveTarget.Ready)
    }
}
