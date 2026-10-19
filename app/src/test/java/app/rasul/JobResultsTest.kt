package app.rasul

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.rasul.jobs.JobDto
import app.rasul.jobs.JobListDto
import app.rasul.jobs.LastRunStatus
import app.rasul.jobs.lastRunStatus
import app.rasul.notifications.JobAlert
import app.rasul.notifications.JOB_FOREGROUND_MIN_GAP_MS
import app.rasul.notifications.JobOutcome
import app.rasul.notifications.JobPollGate
import app.rasul.notifications.JobSeenStore
import app.rasul.notifications.decideJobAlerts
import app.rasul.notifications.isJobRunsRoute
import app.rasul.notifications.jobOutcome
import app.rasul.notifications.jobRunsRoute
import app.rasul.notifications.pruneJobSeen
import app.rasul.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JobResultsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String): JobDto = json.decodeFromString<JobListDto>("""{"jobs":[$body]}""").jobs.single()

    private fun job(
        id: String, runAt: String? = null, status: String? = null, error: String? = null, name: String? = "Job $id",
    ) = JobDto(
        id, name, lastRunAt = runAt?.let(::JsonPrimitive), lastStatus = status,
        lastError = error?.let(::JsonPrimitive),
    )

    private fun fakeStore(): DataStore<Preferences> {
        val data = MutableStateFlow<Preferences>(emptyPreferences())
        return object : DataStore<Preferences> {
            override val data: Flow<Preferences> = data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val next = transform(data.value); data.value = next; return next
            }
        }
    }

    // ---- JobDto parsing and status mapping ----

    @Test fun parsesLastStatusErrorAndRunTime() {
        val j = parse("""{"id":"a1","name":"Backup","last_run_at":"2030-03-17T03:00:00+00:00","last_status":"error","last_error":"disk full","extra":1}""")
        assertEquals("error", j.lastStatus)
        assertEquals("disk full", j.lastErrorText)
        assertEquals(LastRunStatus.Failed, j.lastRun)
        assertEquals(JsonPrimitive("2030-03-17T03:00:00+00:00"), j.lastRunAt)
    }

    @Test fun missingAndNullFieldsParseAsNeverRan() {
        val j = parse("""{"id":"a1","last_status":null,"last_error":null,"last_run_at":null}""")
        assertNull(j.lastRun)
        assertNull(j.lastErrorText)
        val bare = parse("""{"id":"a2"}""")
        assertNull(bare.lastRun)
        assertNull(bare.lastErrorText)
        assertNull(bare.lastRunAt)
    }

    @Test fun oddErrorValuesNeverBreakTheList() {
        assertNull(parse("""{"id":"a1","last_error":{"code":2}}""").lastErrorText)
        assertNull(parse("""{"id":"a1","last_error":"   "}""").lastErrorText)
        assertNull(parse("""{"id":"a1","last_error":7}""").lastErrorText)
    }

    @Test fun statusMapping() {
        assertEquals(LastRunStatus.Succeeded, lastRunStatus("ok"))
        assertEquals(LastRunStatus.Failed, lastRunStatus("error"))
        assertEquals(LastRunStatus.DeliveryFailed, lastRunStatus("delivery_failed"))
        assertEquals(LastRunStatus.Other("timeout"), lastRunStatus(" timeout "))
        assertNull(lastRunStatus(null))
        assertNull(lastRunStatus("  "))
    }

    // ---- the decider ----

    @Test fun firstPollOnlySeedsAndPostsNothing() {
        val poll = decideJobAlerts("b", "p", listOf(job("j1", "2030-01-01T00:00:00+00:00", "ok")), emptyMap())
        assertTrue(poll.alerts.isEmpty())
        assertEquals("2030-01-01T00:00:00+00:00", poll.seen["bridge:b:p:j1"])
        assertTrue("bridge:b:p" in poll.seen)
    }

    private val t1 = "2030-01-01T00:00:00+00:00"
    private val t2 = "2030-01-02T00:00:00+00:00"

    @Test fun aRunTimeThatGoesMissingAndComesBackDoesNotAlertTwice() {
        val seeded = decideJobAlerts("b", "p", listOf(job("j1", t1, "ok")), emptyMap()).seen
        val first = decideJobAlerts("b", "p", listOf(job("j1", t2, "ok")), seeded)
        assertEquals(1, first.alerts.size)
        val gap = decideJobAlerts("b", "p", listOf(job("j1", null, "ok")), first.seen)
        assertTrue(gap.alerts.isEmpty())
        assertEquals(t2, gap.seen["bridge:b:p:j1"])
        assertTrue(decideJobAlerts("b", "p", listOf(job("j1", t2, "ok")), gap.seen).alerts.isEmpty())
    }

    @Test fun anOlderRunTimeNeverReplacesANewerOne() {
        val seeded = decideJobAlerts("b", "p", listOf(job("j1", t1, "ok")), emptyMap()).seen
        val first = decideJobAlerts("b", "p", listOf(job("j1", t2, "ok")), seeded)
        assertEquals(1, first.alerts.size)
        val older = decideJobAlerts("b", "p", listOf(job("j1", t1, "ok")), first.seen)
        assertTrue(older.alerts.isEmpty())
        assertEquals(t2, older.seen["bridge:b:p:j1"])
        assertTrue(decideJobAlerts("b", "p", listOf(job("j1", t2, "ok")), older.seen).alerts.isEmpty())
    }

    @Test fun aFailureTextLosesItsFileLinkToken() {
        val token = "k".repeat(43)
        val failed = job("j1", t2, "error", error = "Could not send [report.pdf (1.2 MB)](rasul-file://$token) today")
        val seeded = decideJobAlerts("b", "p", listOf(job("j1", t1, "ok")), emptyMap()).seen
        val error = decideJobAlerts("b", "p", listOf(failed), seeded).alerts.single().error
        assertEquals("Could not send report.pdf (1.2 MB) today", error)
    }

    @Test fun foregroundJobReadIsSkippedRightAfterAReadAndAllowedAfterAMinute() {
        var now = 5_000_000L
        val gate = JobPollGate { now }
        assertTrue(gate.foregroundDue())
        gate.finished()
        now += 5_000
        assertFalse(gate.foregroundDue())
        now += JOB_FOREGROUND_MIN_GAP_MS
        assertTrue(gate.foregroundDue())
        gate.finished()
        assertFalse(gate.foregroundDue())
    }

    @Test fun aBotWithNoJobsStillCountsAsSeeded() {
        val seeded = decideJobAlerts("b", "p", emptyList(), emptyMap()).seen
        val poll = decideJobAlerts("b", "p", listOf(job("j9", "2030-01-02T00:00:00+00:00", "ok")), seeded)
        assertEquals(listOf("j9"), poll.alerts.map { it.jobId })
    }

    @Test fun newerRunPostsOnceAndTheSameSnapshotPostsNothing() {
        val seed = decideJobAlerts("b", "p", listOf(job("j1", "2030-01-01T00:00:00+00:00", "ok")), emptyMap()).seen
        val later = listOf(job("j1", "2030-01-01T06:00:00+00:00", "ok"))
        val first = decideJobAlerts("b", "p", later, seed)
        assertEquals(1, first.alerts.size)
        assertEquals(JobOutcome.Finished, first.alerts.single().outcome)
        assertEquals("Job j1", first.alerts.single().jobName)
        val again = decideJobAlerts("b", "p", later, first.seen)
        assertTrue(again.alerts.isEmpty())
    }

    @Test fun anOlderOrEqualInstantNeverAlertsEvenInAnotherOffset() {
        val seed = decideJobAlerts("b", "p", listOf(job("j1", "2030-01-01T06:00:00+00:00", "ok")), emptyMap()).seen
        // The same instant written in another zone is not a new run.
        assertTrue(decideJobAlerts("b", "p", listOf(job("j1", "2030-01-01T08:00:00+02:00", "ok")), seed).alerts.isEmpty())
        assertTrue(decideJobAlerts("b", "p", listOf(job("j1", "2030-01-01T05:00:00+00:00", "ok")), seed).alerts.isEmpty())
    }

    @Test fun failureTextComesFromLastErrorAndSuccessHasNone() {
        val seed = decideJobAlerts("b", "p", listOf(job("j1", "2030-01-01T00:00:00+00:00", "ok")), emptyMap()).seen
        val failed = decideJobAlerts("b", "p", listOf(job("j1", "2030-01-02T00:00:00+00:00", "error", "Command exited with code 2")), seed)
        assertEquals(JobOutcome.Failed, failed.alerts.single().outcome)
        assertEquals("Command exited with code 2", failed.alerts.single().error)
        val ok = decideJobAlerts("b", "p", listOf(job("j1", "2030-01-03T00:00:00+00:00", "ok", "stale text")), failed.seen)
        assertNull(ok.alerts.single().error)
    }

    @Test fun longErrorTextIsCut() {
        val seed = decideJobAlerts("b", "p", listOf(job("j1", "2030-01-01T00:00:00+00:00", "ok")), emptyMap()).seen
        val poll = decideJobAlerts("b", "p", listOf(job("j1", "2030-01-02T00:00:00+00:00", "error", "x".repeat(2000))), seed)
        assertEquals(300, poll.alerts.single().error!!.length)
    }

    @Test fun deliveryFailedHasItsOwnOutcome() {
        assertEquals(JobOutcome.DeliveryFailed, jobOutcome(job("j1", "2030-01-01T00:00:00+00:00", "delivery_failed", "no channel")))
        assertEquals(JobOutcome.Failed, jobOutcome(job("j1", "2030-01-01T00:00:00+00:00", "timeout", "took too long")))
        assertEquals(JobOutcome.Finished, jobOutcome(job("j1", "2030-01-01T00:00:00+00:00", "timeout")))
        assertEquals(JobOutcome.Finished, jobOutcome(job("j1", "2030-01-01T00:00:00+00:00", null)))
    }

    @Test fun deletedJobsAreDroppedFromTheMapAndOtherBotsStay() {
        val other = decideJobAlerts("b", "q", listOf(job("k1", "2030-01-01T00:00:00+00:00", "ok")), emptyMap()).seen
        val both = decideJobAlerts("b", "p",
            listOf(job("j1", "2030-01-01T00:00:00+00:00", "ok"), job("j2", "2030-01-01T00:00:00+00:00", "ok")), other).seen
        val after = decideJobAlerts("b", "p", listOf(job("j2", "2030-01-01T00:00:00+00:00", "ok")), both).seen
        assertFalse("bridge:b:p:j1" in after)
        assertTrue("bridge:b:p:j2" in after)
        assertTrue("bridge:b:q:k1" in after)
    }

    @Test fun aJobThatNeverRanIsNotRecordedAndAlertsWhenItFirstRuns() {
        val seed = decideJobAlerts("b", "p", listOf(job("j1")), emptyMap()).seen
        assertFalse("bridge:b:p:j1" in seed)
        val poll = decideJobAlerts("b", "p", listOf(job("j1", "2030-01-02T00:00:00+00:00", "ok")), seed)
        assertEquals(1, poll.alerts.size)
    }

    @Test fun pruneKeepsLiveAndUnreadableBridges() {
        val seen = mapOf(
            "bridge:b:p" to "1", "bridge:b:p:j1" to "t",
            "bridge:gone:p" to "1", "bridge:gone:p:j1" to "t",
            "bridge:slow:p" to "1", "bridge:slow:p:j1" to "t",
        )
        val kept = pruneJobSeen(seen, setOf("bridge:b:p"), listOf("slow"))
        assertEquals(setOf("bridge:b:p", "bridge:b:p:j1", "bridge:slow:p", "bridge:slow:p:j1"), kept.keys)
    }

    // ---- persistence ----

    @Test fun seenMapSurvivesAReloadAndClears() = runBlocking {
        val backing = fakeStore()
        JobSeenStore(backing).save(mapOf("bridge:b:p" to "1", "bridge:b:p:j1" to "2030-01-01T00:00:00+00:00"))
        val reread = JobSeenStore(backing)
        assertEquals("2030-01-01T00:00:00+00:00", reread.load()["bridge:b:p:j1"])
        reread.clear()
        assertTrue(reread.load().isEmpty())
    }

    @Test fun settingsDefaultOffAndPersistBoth() = runBlocking {
        val settings = AppSettings(fakeStore())
        assertFalse(settings.values.first().jobAlerts)
        assertFalse(settings.values.first().jobAlertsHintDismissed)
        settings.setJobAlerts(true)
        settings.setJobAlertsHintDismissed(true)
        val now = settings.values.first()
        assertTrue(now.jobAlerts)
        assertTrue(now.jobAlertsHintDismissed)
        assertFalse(now.approvalAlerts)
        settings.setJobAlerts(false)
        assertFalse(settings.values.first().jobAlerts)
        assertTrue(settings.values.first().jobAlertsHintDismissed)
    }

    @Test fun turnOnFromTheHintAlsoDismissesTheHintForGood() = runBlocking {
        val settings = AppSettings(fakeStore())
        settings.turnOnJobAlerts()
        assertTrue(settings.values.first().jobAlerts)
        assertTrue(settings.values.first().jobAlertsHintDismissed)
        settings.setJobAlerts(false)
        assertTrue(settings.values.first().jobAlertsHintDismissed)
    }

    // ---- the tap route ----

    @Test fun routeBuiltFromAnAlertIsAccepted() {
        val alert = JobAlert("k", "b1", "default", "ab12-cd", "Nightly & weekly", JobOutcome.Finished, null)
        val route = jobRunsRoute(alert)
        assertEquals("job-runs/ab12-cd?name=" + Uri.encode("Nightly & weekly") + "&bridge=b1&profile=default", route)
        assertTrue(isJobRunsRoute(route))
    }

    @Test fun malformedRoutesAreRejected() {
        listOf(
            "job-runs/ab?name=x&bridge=b&profile=default&src=token",
            "job-runs/ab?bridge=b&name=x&profile=default",
            "job-runs/ab?name=x&bridge=b",
            "job-runs/?name=x&bridge=b&profile=default",
            "job-runs/a/b?name=x&bridge=b&profile=default",
            "job-runs/a%2Fb?name=x&bridge=b&profile=default",
            "job-runs/a.b?name=x&bridge=b&profile=default",
            "job-runs/ab?name=x&bridge=&profile=default",
            "job-runs/ab?name=x&bridge=b&profile=Bad%20Profile",
            "job-runs/ab?name=x&bridge=b&profile=",
            "job-runs/ab?name=" + "y".repeat(201) + "&bridge=b&profile=default",
            "approvals/b/default",
            "chat/ab",
            "job-runs/ab",
        ).forEach { assertFalse(it, isJobRunsRoute(it)) }
    }
}
