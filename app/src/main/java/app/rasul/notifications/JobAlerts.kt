package app.rasul.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rasul.MainActivity
import app.rasul.R
import app.rasul.bridge.APPROVAL_PROFILE_ID
import app.rasul.bridge.bridgeBotId
import app.rasul.data.timeMillis
import app.rasul.files.FileLinks
import app.rasul.jobs.JobDto
import app.rasul.jobs.LastRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Intent extra carrying the validated `job-runs/{id}?name=&bridge=&profile=` route for the tap target. */
const val JOB_RUNS_ROUTE = "job_runs_route"

internal const val JOB_RESULTS_CHANNEL = "job_results"
internal const val JOB_ALERT_ID = 7
internal const val JOB_SUMMARY_ID = 8
internal const val JOB_SUMMARY_TAG = "job_results_summary"
private const val JOB_GROUP = "job_results"
private const val JOB_ERROR_MAX = 300
private const val JOB_NAME_MAX = 200

/** How often the watcher reads each bot's job list. */
internal const val JOB_POLL_MS = 5 * 60_000L

/** Least time between two job reads started by the app coming to the foreground (rotation, a permission dialog). */
internal const val JOB_FOREGROUND_MIN_GAP_MS = 60_000L

/** Remembers when the last job read finished, so a foreground trigger right after one is skipped. */
internal class JobPollGate(private val clock: () -> Long) {
    private var finishedAt: Long? = null

    fun finished() { finishedAt = clock() }

    fun foregroundDue(): Boolean = finishedAt?.let { clock() - it >= JOB_FOREGROUND_MIN_GAP_MS } ?: true
}

/** How one finished run ended, for the notification title and its public version. */
internal enum class JobOutcome { Finished, Failed, DeliveryFailed }

/** One system notification to post for a run that finished since the last poll. */
internal data class JobAlert(
    val key: String,
    val bridgeId: String,
    val profileId: String,
    val jobId: String,
    val jobName: String,
    val outcome: JobOutcome,
    val error: String?,
)

internal data class JobPoll(val alerts: List<JobAlert>, val seen: Map<String, String>)

/** `bridge:<bridgeId>:<profileId>:<jobId>`: the bot key, a colon, the job id. */
internal fun jobSeenKey(botKey: String, jobId: String) = "$botKey:$jobId"

/**
 * Outcome of the last run: "ok" finished, "error" failed, "delivery_failed" ran but the result was not delivered.
 * Another explicit status counts as failed only when the run also left an error text.
 */
internal fun jobOutcome(job: JobDto): JobOutcome = when (val status = job.lastRun) {
    LastRunStatus.Failed -> JobOutcome.Failed
    LastRunStatus.DeliveryFailed -> JobOutcome.DeliveryFailed
    is LastRunStatus.Other -> if (job.lastErrorText != null) JobOutcome.Failed else JobOutcome.Finished
    LastRunStatus.Succeeded, null -> JobOutcome.Finished
}

private fun runStamp(job: JobDto): String? = (job.lastRunAt as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun stampMillis(stamp: String): Long? = timeMillis(JsonPrimitive(stamp))

private fun isNewer(current: String, previous: String): Boolean {
    if (current == previous) return false
    val a = stampMillis(current)
    val b = stampMillis(previous)
    return if (a != null && b != null) a > b else true
}

/**
 * Decides which jobs of one bot need a notification. [seen] holds, per bot, a marker under the bot key (set after
 * the first poll, which only records what is there so nothing floods) and the last seen `last_run_at` per job.
 * A job missing from [jobs] drops out of the map; a run whose `last_run_at` is newer than the recorded one alerts once.
 */
internal fun decideJobAlerts(bridgeId: String, profileId: String, jobs: List<JobDto>, seen: Map<String, String>): JobPoll {
    val botKey = bridgeBotId(bridgeId, profileId)
    val prefix = "$botKey:"
    val seeded = botKey in seen
    val next = seen.filterKeys { !it.startsWith(prefix) }.toMutableMap()
    next[botKey] = "1"
    val alerts = mutableListOf<JobAlert>()
    for (job in jobs) {
        val key = jobSeenKey(botKey, job.id)
        val previous = seen[key]
        val stamp = runStamp(job)
        if (stamp == null) {
            // No run time this time (a job being rewritten): keep what was recorded so the next read does not alert again.
            if (previous != null) next[key] = previous
            continue
        }
        val newer = previous == null || isNewer(stamp, previous)
        next[key] = if (newer) stamp else previous!!
        if (!seeded || !newer) continue
        val outcome = jobOutcome(job)
        alerts += JobAlert(
            key, bridgeId, profileId, job.id, job.name?.takeIf { it.isNotBlank() } ?: job.id, outcome,
            job.lastErrorText?.let { FileLinks.plainText(it) }?.take(JOB_ERROR_MAX).takeIf { outcome != JobOutcome.Finished },
        )
    }
    return JobPoll(alerts, next)
}

/**
 * Forgets every entry of a bot that is no longer paired or no longer offers the profile. Entries of a bridge that
 * could not be read this time ([unsettledBridgeIds]) stay, so a network blip never reseeds them.
 */
internal fun pruneJobSeen(seen: Map<String, String>, liveBotKeys: Set<String>, unsettledBridgeIds: List<String> = emptyList()): Map<String, String> =
    seen.filterKeys { key ->
        liveBotKeys.any { key == it || key.startsWith("$it:") } || unsettledBridgeIds.any { key.startsWith("bridge:$it:") }
    }

/** The persisted `last_run_at` map. Unreadable or malformed data reads as empty: the next poll only seeds. */
class JobSeenStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("job_alert_seen_v1")
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    suspend fun load(): Map<String, String> = try {
        dataStore.data.first()[key]?.let { Json.decodeFromString(serializer, it) } ?: emptyMap()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { emptyMap() }

    suspend fun save(map: Map<String, String>) {
        dataStore.edit { it[key] = Json.encodeToString(serializer, map) }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }
}

/** Strict `job-runs/{id}?name=&bridge=&profile=` with canonical encoded segments (the shape the Jobs screen builds). */
fun isJobRunsRoute(route: String): Boolean {
    if (!route.startsWith("job-runs/")) return false
    val query = route.indexOf('?')
    if (query < 0) return false
    val id = route.substring("job-runs/".length, query)
    if (id.isEmpty() || !canonical(id) || !JOB_ROUTE_ID.matches(Uri.decode(id))) return false
    val parts = route.substring(query + 1).split('&')
    if (parts.size != 3) return false
    val keys = listOf("name", "bridge", "profile")
    val values = parts.mapIndexed { index, part ->
        if (!part.startsWith(keys[index] + "=")) return false
        part.substring(keys[index].length + 1)
    }
    val (name, bridge, profile) = values
    return canonical(name) && Uri.decode(name).length <= JOB_NAME_MAX &&
        bridge.isNotEmpty() && canonical(bridge) &&
        profile.isNotEmpty() && canonical(profile) && APPROVAL_PROFILE_ID.matches(Uri.decode(profile))
}

private val JOB_ROUTE_ID = Regex("[A-Za-z0-9_-]{1,200}")

private fun canonical(segment: String) = Uri.encode(Uri.decode(segment)) == segment

internal fun jobRunsRoute(alert: JobAlert): String =
    "job-runs/" + Uri.encode(alert.jobId) + "?name=" + Uri.encode(alert.jobName.take(JOB_NAME_MAX)) +
        "&bridge=" + Uri.encode(alert.bridgeId) + "&profile=" + Uri.encode(alert.profileId)

private fun jobOpenIntent(context: Context, alert: JobAlert): PendingIntent {
    val route = jobRunsRoute(alert)
    return PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("rasul:$route"))
            .putExtra(JOB_RUNS_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

private fun jobTitle(context: Context, alert: JobAlert): String = context.getString(
    when (alert.outcome) {
        JobOutcome.Finished -> R.string.job_alert_finished
        JobOutcome.Failed -> R.string.job_alert_failed
        JobOutcome.DeliveryFailed -> R.string.job_alert_delivery_failed
    },
    alert.jobName,
)

/** The lock-screen text never names the job or carries the error: only whether it finished or failed. */
internal fun jobPublicTitle(context: Context, outcome: JobOutcome): String = context.getString(
    if (outcome == JobOutcome.Finished) R.string.job_alert_public_finished else R.string.job_alert_public_failed,
)

/**
 * One job-result notification. With [publicOnly] (app lock on, or its state not yet read) only the public text is
 * shown, and the job name and error never enter the notification.
 */
internal fun buildJobNotification(context: Context, alert: JobAlert, publicOnly: Boolean = false): Notification {
    val open = jobOpenIntent(context, alert)
    val public = NotificationCompat.Builder(context, JOB_RESULTS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(jobPublicTitle(context, alert.outcome))
        .setContentIntent(open)
        .setAutoCancel(true)
        .setGroup(JOB_GROUP)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
    if (publicOnly) return public
    val builder = NotificationCompat.Builder(context, JOB_RESULTS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(jobTitle(context, alert))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(public)
        .setCategory(NotificationCompat.CATEGORY_EVENT)
        .setContentIntent(open)
        .setAutoCancel(true)
        .setGroup(JOB_GROUP)
    alert.error?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
    return builder.build()
}

/** The one summary that folds several results into a group. */
internal fun buildJobSummary(context: Context, count: Int): Notification {
    val open = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(context, JOB_RESULTS_CHANNEL)
        .setSmallIcon(R.drawable.rasul_signal_monochrome)
        .setContentTitle(context.resources.getQuantityString(R.plurals.job_alert_summary, count, count))
        .setContentIntent(open)
        .setAutoCancel(true)
        .setGroup(JOB_GROUP)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
}

/** Posts [alerts] and keeps one summary while two or more results are showing. */
internal fun postJobAlerts(context: Context, alerts: List<JobAlert>, publicOnly: Boolean) {
    if (alerts.isEmpty()) return
    val manager = context.getSystemService(NotificationManager::class.java)
    alerts.forEach { manager.notify(it.key, JOB_ALERT_ID, buildJobNotification(context, it, publicOnly)) }
    val showing = manager.activeNotifications.count { it.id == JOB_ALERT_ID }
    if (showing >= 2) manager.notify(JOB_SUMMARY_TAG, JOB_SUMMARY_ID, buildJobSummary(context, showing))
}

internal fun cancelJobNotifications(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.activeNotifications.filter { it.id == JOB_ALERT_ID }.forEach { manager.cancel(it.tag, it.id) }
    manager.cancel(JOB_SUMMARY_TAG, JOB_SUMMARY_ID)
}
