package app.rasul.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.bridge.BridgeBotsState
import app.rasul.bridge.bridgeBotId
import app.rasul.data.ActiveTarget
import app.rasul.data.Connection
import app.rasul.data.HermesTarget
import app.rasul.data.HermesError
import app.rasul.data.Load
import app.rasul.jobs.JobDto
import app.rasul.jobs.JobState
import app.rasul.sessions.SessionDto
import app.rasul.ui.components.BotAvatar
import app.rasul.ui.components.FilterPill
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulNavBar
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.avatarStyleFor
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.serialization.json.JsonPrimitive

private data class SearchRowModel(
    val entry: SearchEntry,
    val avatarKey: String? = null,
    val preview: String? = null,
    val action: (() -> Unit)? = null,
)

private data class SearchStatus(
    val messageResource: Int? = null,
    val arguments: List<Any> = emptyList(),
    val error: HermesError? = null,
    val isError: Boolean = false,
)

@Composable
fun SearchScreen(
    connections: ConnectionsState,
    bridges: BridgeBotsState,
    sessions: Load<List<SessionDto>>,
    jobs: Load<List<JobDto>>,
    onBack: () -> Unit,
    onOpenChat: (String, ActiveTarget.Ready) -> Unit,
    onOpenBot: (ChatBotChoice) -> Unit,
    onOpenJobs: () -> Unit,
    initialQuery: String = "",
    initialFilter: SearchFilter = SearchFilter.All,
) {
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var filter by rememberSaveable(initialFilter) { mutableStateOf(initialFilter) }
    val source = (sessions as? Load.Ready)?.source
    val rows = buildList {
        (connections as? ConnectionsState.Loaded)?.items?.forEach { connection ->
            val choice = ChatBotChoice(connection.id, connection.name)
            add(SearchRowModel(
                SearchEntry("bot:${connection.id}", SearchCategory.Bot, connection.name),
                avatarKey = connection.id,
                preview = stringResource(R.string.search_filter_bots),
                action = { onOpenBot(choice) },
            ))
        }
        bridges.groups.filter { it.error == null && !it.loading }.forEach { group ->
            group.profiles.forEach { profile ->
                val id = bridgeBotId(group.id, profile.id)
                val choice = ChatBotChoice(id, profile.displayName, group.id, profile.id)
                add(SearchRowModel(
                    SearchEntry("bot:$id", SearchCategory.Bot, profile.displayName, group.name),
                    avatarKey = id,
                    preview = group.name,
                    action = if (profile.ready) ({ onOpenBot(choice) }) else null,
                ))
            }
        }
        (sessions as? Load.Ready)?.value?.forEach { session ->
            val title = session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled)
            add(SearchRowModel(
                SearchEntry("chat:${session.id}", SearchCategory.Chat, title, session.preview),
                avatarKey = source?.id,
                preview = session.preview?.takeIf { it.isNotBlank() },
                action = source?.let { target -> ({ onOpenChat(session.id, target) }) },
            ))
        }
        (jobs as? Load.Ready)?.value?.forEach { job ->
            val title = job.name?.takeIf { it.isNotBlank() } ?: job.id
            add(SearchRowModel(
                SearchEntry("job:${job.id}", SearchCategory.Job, title, job.scheduleDisplay),
                preview = job.scheduleDisplay ?: stringResource(
                    when (job.jobState) {
                        JobState.Scheduled -> R.string.job_scheduled
                        JobState.Paused -> R.string.job_paused
                        JobState.Completed -> R.string.job_completed
                        JobState.Error -> R.string.job_failed
                    },
                ),
                action = onOpenJobs,
            ))
        }
    }
    val entries = rows.map { it.entry }
    val keys = filterSearchEntries(entries, query, filter).mapTo(mutableSetOf()) { it.key }
    val shown = rows.filter { it.entry.key in keys }
    val statusResources = searchStatusResources(filter, connections, bridges, sessions, jobs)

    Column(Modifier.fillMaxSize().background(Rasul.colors.bg).padding(top = 16.dp)) {
        RasulNavBar(stringResource(R.string.search_title), stringResource(R.string.back), onBack, horizontalPadding = 40.dp, leadingAlignment = androidx.compose.ui.Alignment.Center)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 66.dp),
            singleLine = true,
            label = { Text(stringResource(R.string.search_title)) },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(RasulIcons.Search, null) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Rasul.colors.accent,
                unfocusedBorderColor = Rasul.colors.hairline,
                focusedTextColor = Rasul.colors.ink,
                unfocusedTextColor = Rasul.colors.ink,
                focusedLabelColor = Rasul.colors.accent,
                unfocusedLabelColor = Rasul.colors.muted,
                cursorColor = Rasul.colors.accent,
            ),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchFilter.entries.forEach { choice ->
                val label = when (choice) {
                    SearchFilter.All -> stringResource(R.string.filter_all)
                    SearchFilter.Chats -> stringResource(R.string.search_filter_chats)
                    SearchFilter.Bots -> stringResource(R.string.search_filter_bots)
                    SearchFilter.Jobs -> stringResource(R.string.search_filter_jobs)
                }
                FilterPill(label, filter == choice, width = if (choice == SearchFilter.Chats) 84.dp else 74.dp) { filter = choice }
            }
        }
        statusResources.forEach { id ->
            val message = id.error?.text()
                ?: id.messageResource?.let { stringResource(it, *id.arguments.toTypedArray()) }.orEmpty()
            Text(
                message,
                style = RasulType.bodySmall,
                color = if (id.isError || id.error != null) Rasul.colors.danger else Rasul.colors.muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        when {
            query.isBlank() -> Text(
                stringResource(R.string.search_empty_prompt),
                style = RasulType.body,
                color = Rasul.colors.muted,
                modifier = Modifier.padding(24.dp),
            )
            shown.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.entry.key }) { row ->
                    RasulRow(
                        onClick = row.action,
                        hairlineStart = 20.dp, hairlineEnd = 20.dp, textSpacing = 4.dp,
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 19.dp),
                        leading = {
                            if (row.avatarKey != null) BotAvatar(avatarStyleFor(row.avatarKey), size = 40.dp)
                            else Icon(
                                when (row.entry.category) {
                                    SearchCategory.Chat -> RasulIcons.Chat
                                    SearchCategory.Bot -> RasulIcons.Bot
                                    SearchCategory.Job -> RasulIcons.Calendar
                                },
                                null,
                                tint = Rasul.colors.accent,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                    ) {
                        RowTitle(row.entry.title)
                        row.preview?.takeIf { it.isNotBlank() }?.let { RowSub(it, maxLines = 1) }
                    }
                }
            }
            else -> Text(
                stringResource(R.string.search_no_results, query),
                style = RasulType.body,
                color = Rasul.colors.muted,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

private fun searchStatusResources(
    filter: SearchFilter,
    connections: ConnectionsState,
    bridges: BridgeBotsState,
    sessions: Load<List<SessionDto>>,
    jobs: Load<List<JobDto>>,
): List<SearchStatus> = buildList {
    if (filter == SearchFilter.All || filter == SearchFilter.Chats) {
        when (sessions) {
            Load.Loading -> add(SearchStatus(R.string.search_loading_chats))
            is Load.Ready -> Unit
            Load.NoConnection -> add(SearchStatus(R.string.search_no_connection_chats, isError = true))
            is Load.NoKey -> add(SearchStatus(R.string.no_key, listOf(sessions.connection), isError = true))
            is Load.BridgeUnreachable -> add(SearchStatus(R.string.bridge_pc_unreachable, listOf(sessions.pcName), isError = true))
            is Load.BridgeNoReadyBots -> add(SearchStatus(R.string.bridge_no_ready_bots, listOf(sessions.pcName), isError = true))
            is Load.Failed -> add(
                if (sessions.error is HermesError.Server) SearchStatus(
                    R.string.search_http_error_chats, listOf(sessions.error.code), isError = true,
                ) else SearchStatus(error = sessions.error, isError = true),
            )
        }
    }
    if (filter == SearchFilter.All || filter == SearchFilter.Bots) {
        when (connections) {
            ConnectionsState.Loading -> add(SearchStatus(R.string.search_loading_bots))
            is ConnectionsState.Failed -> add(SearchStatus(R.string.search_error_bots, isError = true))
            is ConnectionsState.Loaded -> if (!bridges.loaded || bridges.groups.any { it.loading }) {
                add(SearchStatus(R.string.search_loading_bots))
            }
        }
        if (bridges.storageError) add(SearchStatus(R.string.bridge_bots_storage_error, isError = true))
        bridges.groups.mapNotNull { it.error }.forEach { add(SearchStatus(error = it, isError = true)) }
    }
    if (filter == SearchFilter.All || filter == SearchFilter.Jobs) {
        when (jobs) {
            Load.Loading -> add(SearchStatus(R.string.search_loading_jobs))
            is Load.Ready -> Unit
            Load.NoConnection -> add(SearchStatus(R.string.search_no_connection_jobs, isError = true))
            is Load.NoKey -> add(SearchStatus(R.string.no_key, listOf(jobs.connection), isError = true))
            is Load.BridgeUnreachable -> add(SearchStatus(R.string.bridge_pc_unreachable, listOf(jobs.pcName), isError = true))
            is Load.BridgeNoReadyBots -> add(SearchStatus(R.string.bridge_no_ready_bots, listOf(jobs.pcName), isError = true))
            is Load.Failed -> add(
                if (jobs.error is HermesError.Server) SearchStatus(
                    R.string.search_http_error_jobs, listOf(jobs.error.code), isError = true,
                ) else SearchStatus(error = jobs.error, isError = true),
            )
        }
    }
}.distinct()

private val previewConnection = Connection("sample-bot", "Research", "", "default")
private val previewSource = ActiveTarget.Ready("sample-bot", "Research", HermesTarget("", "default", ""))
private val previewSessions = listOf(
    SessionDto("sample-chat", "Deploy log summary", preview = "The report is ready to review.", startedAt = JsonPrimitive(1_759_999_700)),
)
private val previewJobs = listOf(JobDto("sample-job", "Daily report", "Every day", enabled = true))
private val previewBridges = BridgeBotsState(loaded = true)

private fun previewSessionState() = Load.Ready("Research", previewSessions, previewSource)

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SearchFilteredEn() = RasulTheme(false) {
    SearchScreen(
        ConnectionsState.Loaded(listOf(previewConnection)), previewBridges,
        previewSessionState(), Load.Ready("Research", previewJobs, previewSource), {}, { _, _ -> }, {}, {},
        initialQuery = "deploy", initialFilter = SearchFilter.Chats,
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun SearchFilteredAr() = RasulTheme(false) {
    SearchScreen(
        ConnectionsState.Loaded(listOf(previewConnection)), previewBridges,
        previewSessionState(), Load.Ready("Research", previewJobs, previewSource), {}, { _, _ -> }, {}, {},
        initialQuery = "deploy", initialFilter = SearchFilter.Chats,
    )
}

@Composable
private fun SearchStatePreview(query: String = "", loading: Boolean = false, error: Boolean = false) = RasulTheme(false) {
    SearchScreen(
        if (loading) ConnectionsState.Loading else ConnectionsState.Loaded(emptyList()),
        BridgeBotsState(loaded = !loading),
        if (loading) Load.Loading else if (error) Load.Failed(HermesError.Server(503)) else previewSessionState().copy(value = emptyList()),
        if (loading) Load.Loading else if (error) Load.Failed(HermesError.Server(503)) else Load.Ready("Research", emptyList(), previewSource),
        {}, { _, _ -> }, {}, {}, initialQuery = query,
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SearchEmptyEn() = SearchStatePreview()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun SearchEmptyAr() = SearchStatePreview()

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SearchNoResultsEn() = SearchStatePreview("deploy")

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun SearchNoResultsAr() = SearchStatePreview("deploy")

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SearchLoadingEn() = SearchStatePreview("deploy", loading = true)

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun SearchLoadingAr() = SearchStatePreview("deploy", loading = true)

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SearchErrorEn() = SearchStatePreview("deploy", error = true)

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun SearchErrorAr() = SearchStatePreview("deploy", error = true)
