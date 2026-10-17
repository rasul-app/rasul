package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(token: String) = token.removePrefix("enc:").reversed()
    }

    private fun store(name: String = "a.preferences_pb"): ConnectionStore {
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            tmp.root.resolve(name)
        }
        return ConnectionStore(ds, fake)
    }

    @Test fun roundTripEditKeepKeyDelete() = runBlocking {
        val s = store()
        assertEquals(emptyList<Connection>(), s.connections.first())

        val id = s.save(Connection("", " Home ", "http://192.0.2.5:8642", " alice "), "k-123")
        val got = s.connections.first().single()
        assertEquals(Connection(id, "Home", "http://192.0.2.5:8642", "alice"), got)
        assertEquals("k-123", s.apiKey(id))

        // edit with null key keeps the old key
        s.save(Connection(id, "Home2", "http://192.0.2.5:8642", ""), null)
        assertEquals("Home2", s.get(id)?.name)
        assertNull(s.get(id)?.profile)
        assertEquals("k-123", s.apiKey(id))

        s.delete(id)
        assertEquals(emptyList<Connection>(), s.connections.first())
        assertNull(s.apiKey(id))
    }

    @Test fun keyIsNotStoredInPlaintext() = runBlocking {
        val s = store("b.preferences_pb")
        s.save(Connection("", "n", "http://localhost:8642"), "PLAINSECRET")
        s.connections.first()
        val bytes = tmp.root.resolve("b.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("PLAINSECRET"))
    }

    @Test fun sessionIdIsStablePerConnectionAndProfileAndActiveRunSurvives() = runBlocking {
        val s = store("c.preferences_pb")
        val a = s.sessionId("c1", "bob")
        assertEquals(a, s.sessionId("c1", "bob"))
        assert(a != s.sessionId("c1", "alice"))
        assert(a != s.sessionId("c2", "bob"))
        assertNull(s.activeRun("c1", "bob"))
        s.setActiveRun("c1", "bob", "run_5", 42L)
        assertEquals("run_5", s.activeRun("c1", "bob"))
        assertEquals(42L, s.lastEventId("c1", "bob"))
        s.setActiveRun("c1", "bob", null)
        assertNull(s.activeRun("c1", "bob"))
        assertNull(s.lastEventId("c1", "bob"))

        s.setCurrentSessionRun("c1", "bob", "session-new", "run_6", null, "")
        assertEquals("session-new", s.sessionId("c1", "bob"))
        assertEquals("run_6", s.activeRun("c1", "bob"))
        assertNull(s.lastEventId("c1", "bob"))
        assertEquals("", s.runAnchor("c1", "bob"))
    }

    @Test fun activeRunIsKeyedByConnectionAndProfile() = runBlocking {
        val s = store("d.preferences_pb")
        s.setActiveRun("c1", "A", "run_A", 6L)
        assertNull(s.activeRun("c1", "B")) // switched to B: nothing there
        assertNull(s.lastEventId("c1", "B"))
        s.setActiveRun("c1", "B", null) // B's clear touches only B
        assertEquals("run_A", s.activeRun("c1", "A"))
        assertEquals(6L, s.lastEventId("c1", "A"))
        assertNull(s.activeRun("c1", null))
        assertNull(s.activeRun("c2", "A"))
    }

    @Test fun runAnchorDistinguishesOldEmptyAndKnownBreadcrumbsAndClearsAtomically() = runBlocking {
        val s = store()
        s.setActiveRun("c1", null, "run_1", 0L)
        assertNull(s.runAnchor("c1", null))
        s.setActiveRun("c1", null, "run_1", 1L, "")
        assertEquals("", s.runAnchor("c1", null))
        s.setActiveRun("c1", null, "run_1", 2L, "42")
        assertEquals("42", s.runAnchor("c1", null))
        assertEquals(2L, s.lastEventId("c1", null))
        assertNull(s.runAnchor("c1", "B"))
        assertNull(s.runAnchor("c2", null))
        s.setActiveRun("c1", null, null)
        assertNull(s.activeRun("c1", null))
        assertNull(s.lastEventId("c1", null))
        assertNull(s.runAnchor("c1", null))
    }
}
