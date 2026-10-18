package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
        assertNull(s.activeRun("c1", "bob", a))
        s.setActiveRun("c1", "bob", a, "run_5", 42L)
        assertEquals("run_5", s.activeRun("c1", "bob", a))
        assertEquals(42L, s.lastEventId("c1", "bob", a))
        s.setActiveRun("c1", "bob", a, null)
        assertNull(s.activeRun("c1", "bob", a))
        assertNull(s.lastEventId("c1", "bob", a))

        s.setCurrentSessionRun("c1", "bob", "session-new", "run_6", null, "")
        // The pointer only moves by user intent: a run write for another session never re-points it.
        assertEquals(a, s.currentSessionId("c1", "bob"))
        assertEquals("run_6", s.activeRun("c1", "bob", "session-new"))
        assertNull(s.lastEventId("c1", "bob", "session-new"))
        assertEquals("", s.runAnchor("c1", "bob", "session-new"))
    }

    @Test fun activeRunIsKeyedByConnectionProfileAndSession() = runBlocking {
        val s = store("d.preferences_pb")
        s.setActiveRun("c1", "A", "session-1", "run_A", 6L)
        assertNull(s.activeRun("c1", "A", "session-2")) // another session of the same profile: nothing there
        assertNull(s.lastEventId("c1", "A", "session-2"))
        assertNull(s.activeRun("c1", "B", "session-1")) // switched to B: nothing there
        s.setActiveRun("c1", "B", "session-1", null) // B's clear touches only B/session-1
        assertEquals("run_A", s.activeRun("c1", "A", "session-1"))
        assertEquals(6L, s.lastEventId("c1", "A", "session-1"))
        assertEquals(setOf("session-1"), s.activeRunSessions("c1", "A"))
        assertNull(s.activeRun("c1", null, "session-1"))
        assertNull(s.activeRun("c2", "A", "session-1"))
    }

    @Test fun runAnchorDistinguishesOldEmptyAndKnownBreadcrumbsAndClearsAtomically() = runBlocking {
        val s = store()
        s.setActiveRun("c1", null, "s1", "run_1", 0L)
        assertNull(s.runAnchor("c1", null, "s1"))
        s.setActiveRun("c1", null, "s1", "run_1", 1L, "")
        assertEquals("", s.runAnchor("c1", null, "s1"))
        s.setActiveRun("c1", null, "s1", "run_1", 2L, "42")
        assertEquals("42", s.runAnchor("c1", null, "s1"))
        assertEquals(2L, s.lastEventId("c1", null, "s1"))
        assertNull(s.runAnchor("c1", "B", "s1"))
        assertNull(s.runAnchor("c2", null, "s1"))
        assertNull(s.runAnchor("c1", null, "s2"))
        s.setActiveRun("c1", null, "s1", null)
        assertNull(s.activeRun("c1", null, "s1"))
        assertNull(s.lastEventId("c1", null, "s1"))
        assertNull(s.runAnchor("c1", null, "s1"))
    }
    @Test fun addressKeyAndProfileEditsClearAllProfilesButRenameKeepsState() = runBlocking {
        val s = store()
        var row = Connection("a", "Bot", "https://example.invalid")
        s.save(row, "sample")
        s.save(Connection("b", "Bot", "https://example.invalid"), "sample")
        s.setCurrentSessionRun("b", null, "session-b", "run-b", 9, "anchor")
        s.continueSession("b", null, s.revision("b"), "session-b")
        for (edit in listOf("address", "key", "profile")) {
            for (profile in listOf(null, "profile-a")) s.setCurrentSessionRun("a", profile, "session-a", "run-1", 7, "anchor")
            s.continueSession("a", null, s.revision("a"), "session-a")
            val before = s.revision("a")
            row = row.copy(name = "Renamed")
            s.save(row, null)
            assertEquals(before, s.revision("a"))
            assertEquals("session-a", s.currentSessionId("a", null))
            row = when (edit) {
                "address" -> row.copy(baseUrl = "https://example.invalid/changed")
                "profile" -> row.copy(profile = "profile-a")
                else -> row
            }
            s.save(row, if (edit == "key") "sample" else null)
            assertFalse(before == s.revision("a"))
            for (profile in listOf(null, "profile-a")) {
                assertNull(s.currentSessionId("a", profile))
                assertNull(s.activeRun("a", profile, "session-a"))
                assertNull(s.lastEventId("a", profile, "session-a"))
                assertNull(s.runAnchor("a", profile, "session-a"))
            }
            assertEquals("session-b", s.currentSessionId("b", null))
            assertEquals("run-b", s.activeRun("b", null, "session-b"))
        }
    }

    @Test fun continueAndLateWritesCheckRevisionInsideTransaction() = runBlocking {
        val s = store()
        val row = Connection("a", "Bot", "https://example.invalid")
        s.save(row, "sample")
        val old = s.revision("a")
        s.setCurrentSessionRun("a", null, "session-a", "run-1", 7, "anchor")
        // Another session holding a run refuses the pointer move.
        assertEquals(ConnectionStore.ContinueResult.Busy, s.continueSession("a", null, old, "session-b"))
        assertEquals("run-1", s.activeRun("a", null, "session-a"))
        s.setActiveRun("a", null, "session-a", null)
        assertEquals(ConnectionStore.ContinueResult.Selected, s.continueSession("a", null, old, "session-b"))
        assertEquals("session-b", s.currentSessionId("a", null))
        assertNull(s.activeRun("a", null, "session-a"))
        assertNull(s.activeRun("a", null, "session-b"))
        s.save(row.copy(baseUrl = "https://example.invalid/changed"), null)
        // Stale-revision writes are skipped entirely: no run keys, no pointer claim.
        s.setCurrentSessionRun("a", null, "session-new", "run-new", 9, "anchor", expectedRev = old)
        s.setActiveRun("a", null, "session-new", "run-new", 9, "anchor", expectedRev = old)
        assertNull(s.currentSessionId("a", null))
        assertNull(s.activeRun("a", null, "session-new"))
        // Current-revision writes land under their own session; a plain write never claims the pointer.
        s.setCurrentSessionRun("a", null, "session-new", "run-new", 9, "anchor")
        assertNull(s.currentSessionId("a", null))
        assertEquals("run-new", s.activeRun("a", null, "session-new"))
        assertEquals(9L, s.lastEventId("a", null, "session-new"))
        // Later writes for other sessions leave the pointer alone.
        s.setActiveRun("a", null, "session-c", "run-c")
        assertNull(s.currentSessionId("a", null))
        assertEquals("run-c", s.activeRun("a", null, "session-c"))
    }

    @Test fun sessionRunWritesStayOnTheirOwnSessionAndNeverMoveThePointer() = runBlocking {
        val s = store("moved.preferences_pb")
        s.save(Connection("a", "Bot", "https://example.invalid"), "sample")
        val old = s.sessionId("a", null)
        // A late writer for another session stores under its own keys only; the pointer stays.
        s.setCurrentSessionRun("a", null, "session-b", "run-2", 9, "anchor-b")
        assertEquals(old, s.currentSessionId("a", null))
        assertEquals("run-2", s.activeRun("a", null, "session-b"))
        assertEquals(9L, s.lastEventId("a", null, "session-b"))
        assertEquals("anchor-b", s.runAnchor("a", null, "session-b"))
        assertNull(s.activeRun("a", null, old))
        // The same for a third session and for writes carrying no run.
        s.setCurrentSessionRun("a", null, "session-c", "run-3", expectedRev = s.revision("a"))
        assertEquals(old, s.currentSessionId("a", null))
        assertEquals("run-3", s.activeRun("a", null, "session-c"))
        s.setCurrentSessionRun("a", null, "session-b", null)
        assertEquals(old, s.currentSessionId("a", null))
        assertNull(s.activeRun("a", null, "session-b"))
        // A run write never claims the pointer: only user intent (a new-chat claim, a Continue) moves it.
        s.setCurrentSessionRun("b", null, "session-b", "run-b")
        assertNull(s.currentSessionId("b", null))
        assertEquals("run-b", s.activeRun("b", null, "session-b"))
        s.setCurrentSessionRun("b", null, "session-c", "run-c")
        assertNull(s.currentSessionId("b", null))
        assertEquals("run-c", s.activeRun("b", null, "session-c"))
    }

    @Test fun newChatPointerClaimMovesPointerOnlyWhenUnmovedAndNoOtherRun() = runBlocking {
        val s = store()
        s.save(Connection("a", "Bot", "https://example.invalid"), "sample")
        val old = s.sessionId("a", null)
        val claim = ConnectionStore.PointerClaim(old)
        // A clear-run write never claims, even armed.
        s.setCurrentSessionRun("a", null, "session-b", null, claim = claim)
        assertEquals(old, s.currentSessionId("a", null))
        // Another session's stored run blocks the claim.
        s.setActiveRun("a", null, "session-c", "run-c")
        s.setCurrentSessionRun("a", null, "session-b", "run-b", claim = claim)
        assertEquals(old, s.currentSessionId("a", null))
        s.setActiveRun("a", null, "session-c", null)
        // Pointer still equals the recorded value: the run write claims it.
        s.setCurrentSessionRun("a", null, "session-b", "run-b", claim = claim)
        assertEquals("session-b", s.currentSessionId("a", null))
        // A claim armed for a pointer that has since moved does not steal it back.
        s.setCurrentSessionRun("a", null, "session-d", "run-d", claim = ConnectionStore.PointerClaim(old))
        assertEquals("session-b", s.currentSessionId("a", null))
        // An empty recorded pointer claims on the first run write too.
        s.save(Connection("b", "Bot", "https://example.invalid"), "sample")
        s.setCurrentSessionRun("b", null, "session-z", "run-z", claim = ConnectionStore.PointerClaim(null))
        assertEquals("session-z", s.currentSessionId("b", null))
    }

    @Test fun continueSameSessionKeepsItsRunRestoreState() = runBlocking {
        val s = store()
        s.save(Connection("a", "Bot", "https://example.invalid"), "sample")
        val rev = s.revision("a")
        s.setCurrentSessionRun("a", null, "session-a", "run-1", 7, "anchor")
        assertEquals(ConnectionStore.ContinueResult.Selected, s.continueSession("a", null, rev, "session-a"))
        assertEquals("session-a", s.currentSessionId("a", null))
        assertEquals("run-1", s.activeRun("a", null, "session-a"))
        assertEquals(7L, s.lastEventId("a", null, "session-a"))
        assertEquals("anchor", s.runAnchor("a", null, "session-a"))
    }

    @Test fun clearSessionPointersDropsEveryRunKeyOfThatSessionOnly() = runBlocking {
        val s = store()
        s.save(Connection("a", "Bot", "https://example.invalid"), "sample")
        s.setCurrentSessionRun("a", null, "session-a", "run-a", 1, "anchor-a")
        s.setCurrentSessionRun("a", "p", "session-a", "run-ap")
        s.setActiveRun("a", null, "session-b", "run-b")
        s.clearSessionPointers("session-a")
        assertNull(s.currentSessionId("a", null))
        assertNull(s.currentSessionId("a", "p"))
        assertNull(s.activeRun("a", null, "session-a"))
        assertNull(s.lastEventId("a", null, "session-a"))
        assertNull(s.runAnchor("a", null, "session-a"))
        assertNull(s.activeRun("a", "p", "session-a"))
        assertEquals("run-b", s.activeRun("a", null, "session-b"))
    }

    @Test fun legacyUnkeyedRunKeysMigrateToTheStoredSessionAtomically() = runBlocking {
        val file = tmp.root.resolve("migrate.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { file }
        val s = ConnectionStore(ds, fake)
        // Old format on disk: pointer plus unkeyed run triple, plus an orphan nobody owns.
        ds.edit { p ->
            p[stringPreferencesKey("session_c1_bob")] = "session-old"
            p[stringPreferencesKey("run_c1_bob")] = "run-old"
            p[longPreferencesKey("run_event_c1_bob")] = 7L
            p[stringPreferencesKey("run_anchor_c1_bob")] = "anchor-old"
            p[stringPreferencesKey("run_c2_")] = "run-orphan"
        }
        assertEquals("run-old", s.activeRun("c1", "bob", "session-old"))
        assertEquals(7L, s.lastEventId("c1", "bob", "session-old"))
        assertEquals("anchor-old", s.runAnchor("c1", "bob", "session-old"))
        val p = ds.data.first()
        assertFalse(p.contains(stringPreferencesKey("run_c1_bob")))
        assertFalse(p.contains(longPreferencesKey("run_event_c1_bob")))
        assertFalse(p.contains(stringPreferencesKey("run_anchor_c1_bob")))
        assertFalse(p.contains(stringPreferencesKey("run_c2_")))
        assertNull(s.activeRun("c1", "bob", "session-other"))
        // A second store over the same DataStore does not re-run or lose the migrated keys.
        assertEquals("run-old", ConnectionStore(ds, fake).activeRun("c1", "bob", "session-old"))
    }

}
