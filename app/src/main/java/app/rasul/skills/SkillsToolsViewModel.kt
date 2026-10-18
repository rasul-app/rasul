package app.rasul.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.bridge.CatalogSkillDto
import app.rasul.bridge.DesktopApi
import app.rasul.bridge.DesktopError
import app.rasul.bridge.DesktopSkillDto
import app.rasul.bridge.McpServerDto
import app.rasul.bridge.desktopFailure
import app.rasul.data.ActiveTarget
import app.rasul.data.CapabilitiesDto
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.SkillDto
import app.rasul.data.ToolsetDto
import app.rasul.settings.skillsToolsEntryVisible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal const val INSTALL_POLL_MS = 10_000L
internal const val INSTALL_WINDOW_MS = 120_000L
internal const val CATALOG_FRESH_MS = 45_000L

/** v1 API-server reads the screen still needs (capabilities gate, direct-bots skills, toolsets). */
interface SkillsHostApi {
    suspend fun capabilities(target: HermesTarget): HermesResult<CapabilitiesDto>
    suspend fun skills(target: HermesTarget): HermesResult<List<SkillDto>>
    suspend fun toolsets(target: HermesTarget): HermesResult<List<ToolsetDto>>
}

class HermesSkillsHostApi(private val client: HermesClient) : SkillsHostApi {
    override suspend fun capabilities(target: HermesTarget) = client.capabilities(target)
    override suspend fun skills(target: HermesTarget) = client.skills(target)
    override suspend fun toolsets(target: HermesTarget) = client.toolsets(target)
}

/** An error a section can render: a v1 API-server failure, or a desktop call failure. */
sealed interface SectionError {
    data class Host(val error: HermesError) : SectionError
    data class Desktop(val error: DesktopError) : SectionError
}

sealed interface ListState<out T> {
    data object Empty : ListState<Nothing>
    data class Ready<T>(val values: List<T>) : ListState<T>
    data class Error(val error: SectionError) : ListState<Nothing>
}

private fun <T> List<T>.toListState(): ListState<T> =
    takeIf { it.isNotEmpty() }?.let { ListState.Ready(it) } ?: ListState.Empty

/** [enabled] is null for direct connections: no desktop switch is offered there. */
data class SkillRow(
    val name: String,
    val description: String?,
    val category: String?,
    val enabled: Boolean?,
    val essential: Boolean,
)

private fun DesktopSkillDto.toRow() = SkillRow(name, description, category, enabled, isEssential)

/** The v1 DTO carries no switch state, so these rows render read-only. */
private fun SkillDto.toRow() = SkillRow(name, description, category, null, false)

sealed interface CatalogState {
    data object Closed : CatalogState
    data object Loading : CatalogState
    data class Error(val error: DesktopError) : CatalogState
    /** [readAt] is when this list was fetched; the bridge only honors installs from a <60 s-old read. */
    data class Open(val skills: List<CatalogSkillDto>, val readAt: Long) : CatalogState
}

sealed interface SkillsToolsState {
    data object Loading : SkillsToolsState
    data class Error(val error: HermesError) : SkillsToolsState
    data class Content(
        val direct: Boolean,
        val skills: ListState<SkillRow>,
        val toolsets: ListState<ToolsetDto>,
        val mcp: ListState<McpServerDto>?,
        /** Set when the desktop skills read failed and the v1 list shows read-only instead. */
        val skillsNotice: DesktopError? = null,
        val busySkill: String? = null,
        val actionError: DesktopError? = null,
        /** Identifier whose install request was accepted and is being polled for. */
        val installing: String? = null,
        /** Identifier whose 2-minute poll elapsed without the skill appearing. */
        val checkAgain: String? = null,
        val catalog: CatalogState = CatalogState.Closed,
    ) : SkillsToolsState
}

/**
 * The service is bound to the exact [target] connection the screen was opened for; a null service
 * means a direct connection, where every affordance that needs the desktop app collapses to one notice line.
 */
class SkillsToolsViewModel(
    private val target: ActiveTarget.Ready,
    private val api: SkillsHostApi,
    private val service: (suspend () -> DesktopApi)?,
    private val pollIntervalMs: Long = INSTALL_POLL_MS,
    private val installWindowMs: Long = INSTALL_WINDOW_MS,
    private val catalogFreshMs: Long = CATALOG_FRESH_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow<SkillsToolsState>(SkillsToolsState.Loading)
    val state: StateFlow<SkillsToolsState> = _state
    private var generation = 0
    private var loadJob: Job? = null

    val targetName: String get() = target.name

    init { load() }

    fun load() {
        val current = _state.value
        if (current is SkillsToolsState.Content && (current.busySkill != null || current.installing != null)) return
        val gen = ++generation
        _state.value = SkillsToolsState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            when (val caps = api.capabilities(target.target)) {
                is HermesResult.Err -> if (gen == generation) _state.value = SkillsToolsState.Error(caps.error)
                is HermesResult.Ok -> {
                    if (!skillsToolsEntryVisible(caps.value)) {
                        if (gen == generation) _state.value = SkillsToolsState.Error(HermesError.Incompatible(listOf("skills", "toolsets")))
                    } else loadContent(gen)
                }
            }
        }
    }

    private suspend fun loadContent(gen: Int) {
        val desktopSkills = service?.let { desktopRead { it().skills() } }
        val desktopSkillsError = desktopSkills?.exceptionOrNull()?.let(::desktopFailure)
        // No desktop app on the PC (Unavailable) or a bridge that predates those routes
        // (NotFound) still leaves the v1 list, shown read-only under the desktop message.
        val legacySkills = if (service == null || desktopSkillsError == DesktopError.Unavailable || desktopSkillsError == DesktopError.NotFound) {
            api.skills(target.target)
        } else null
        val toolsets = api.toolsets(target.target)
        val mcp = service?.let { desktopRead { it().mcpServers() } }
        if (gen != generation) return
        val skills = when {
            desktopSkills != null -> desktopSkills.fold(
                onSuccess = { list -> list.map { it.toRow() }.toListState() },
                onFailure = {
                    when (legacySkills) {
                        is HermesResult.Ok -> legacySkills.value.map { s -> s.toRow() }.toListState()
                        is HermesResult.Err -> ListState.Error(SectionError.Host(legacySkills.error))
                        null -> ListState.Error(SectionError.Desktop(desktopFailure(it)))
                    }
                },
            )
            else -> when (legacySkills) {
                is HermesResult.Err -> ListState.Error(SectionError.Host(legacySkills.error))
                is HermesResult.Ok -> legacySkills.value.map { s -> s.toRow() }.toListState()
                null -> ListState.Empty
            }
        }
        val toolsetState = when (toolsets) {
            is HermesResult.Err -> ListState.Error(SectionError.Host(toolsets.error))
            is HermesResult.Ok -> toolsets.value.toListState()
        }
        val mcpState = mcp?.fold(
            onSuccess = { it.toListState() },
            onFailure = { ListState.Error(SectionError.Desktop(desktopFailure(it))) },
        )
        _state.value = SkillsToolsState.Content(
            direct = service == null, skills = skills, toolsets = toolsetState, mcp = mcpState,
            skillsNotice = desktopSkillsError?.takeIf { legacySkills is HermesResult.Ok },
        )
    }

    /** The user-chosen name and desired state are captured before the suspending PUT; re-read after. */
    fun toggle(name: String, enabled: Boolean) {
        val api = service ?: return
        val content = _state.value as? SkillsToolsState.Content ?: return
        if (content.busySkill != null || content.installing != null) return
        val capturedName = name
        val capturedEnabled = enabled
        _state.value = content.copy(busySkill = capturedName, actionError = null)
        val gen = generation
        viewModelScope.launch {
            try {
                val desktop = api()
                desktop.toggleSkill(capturedName, capturedEnabled)
                val fresh = desktop.skills()
                contentUpdate(gen) { it.copy(busySkill = null, skillsNotice = null, skills = fresh.map { s -> s.toRow() }.toListState()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                contentUpdate(gen) { it.copy(busySkill = null, actionError = desktopFailure(e)) }
            }
        }
    }

    fun openCatalog() {
        val api = service ?: return
        val content = _state.value as? SkillsToolsState.Content ?: return
        _state.value = content.copy(catalog = CatalogState.Loading)
        val gen = generation
        viewModelScope.launch {
            try {
                val skills = api().skillCatalog()
                contentUpdate(gen) { it.copy(catalog = CatalogState.Open(skills, now())) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                contentUpdate(gen) { it.copy(catalog = CatalogState.Error(desktopFailure(e))) }
            }
        }
    }

    fun closeCatalog() = contentUpdate(generation) { it.copy(catalog = CatalogState.Closed) }

    /**
     * Only identifiers from the currently shown catalog read are ever sent; anything else is refused.
     * The bridge honors an identifier for 60 s after its catalog read, so a read older than
     * [catalogFreshMs] is fetched again first and the install only proceeds when the same identifier
     * is still listed and not installed — otherwise a specific catalog-changed message is shown.
     */
    fun install(skill: CatalogSkillDto) {
        val api = service ?: return
        val content = _state.value as? SkillsToolsState.Content ?: return
        val open = content.catalog as? CatalogState.Open ?: return
        val captured = open.skills.firstOrNull { it.identifier == skill.identifier && it.name == skill.name } ?: return
        if (captured.installed || content.installing != null || content.busySkill != null) return
        val capturedId = captured.identifier
        val capturedName = captured.name
        val capturedReadAt = open.readAt
        _state.value = content.copy(installing = capturedId, checkAgain = null, actionError = null)
        val gen = generation
        viewModelScope.launch {
            try {
                if (now() - capturedReadAt > catalogFreshMs) {
                    val fresh = api().skillCatalog()
                    val stillListed = fresh.firstOrNull { it.identifier == capturedId }
                    contentUpdate(gen) { it.copy(catalog = CatalogState.Open(fresh, now())) }
                    if (stillListed == null || stillListed.installed) {
                        contentUpdate(gen) {
                            it.copy(installing = null, actionError = DesktopError.CatalogChanged)
                        }
                        return@launch
                    }
                }
                api().installSkill(capturedId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                contentUpdate(gen) { it.copy(installing = null, actionError = desktopFailure(e)) }
                return@launch
            }
            pollInstall(gen, capturedId, capturedName)
        }
    }

    /** Re-reads the skills list at most every 10 s, for 2 minutes, then leaves a Check-again affordance. */
    private suspend fun pollInstall(gen: Int, identifier: String, name: String) {
        val api = service ?: return
        val deadline = now() + installWindowMs
        while (now() < deadline) {
            pause(pollIntervalMs)
            if (gen != generation) return
            val installed = try {
                api().skills()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { continue }
            val found = installed.any { it.name == name }
            contentUpdate(gen) { it.copy(installing = if (found) null else identifier, skillsNotice = null, skills = installed.map { s -> s.toRow() }.toListState()) }
            if (found) {
                refreshCatalog(gen)
                return
            }
        }
        contentUpdate(gen) { it.copy(installing = null, checkAgain = identifier) }
    }

    /** One manual re-read after the poll window elapsed; success refreshes the catalog too. */
    fun checkAgain(identifier: String) {
        val api = service ?: return
        val content = _state.value as? SkillsToolsState.Content ?: return
        if (content.installing != null || content.checkAgain != identifier) return
        val name = (content.catalog as? CatalogState.Open)?.skills?.firstOrNull { it.identifier == identifier }?.name ?: return
        _state.value = content.copy(installing = identifier, checkAgain = null, actionError = null)
        val gen = generation
        viewModelScope.launch {
            val installed = try {
                api().skills()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                contentUpdate(gen) { it.copy(installing = null, actionError = desktopFailure(e)) }
                return@launch
            }
            val found = installed.any { it.name == name }
            contentUpdate(gen) {
                it.copy(installing = null, checkAgain = if (found) null else identifier,
                    skillsNotice = null, skills = installed.map { s -> s.toRow() }.toListState())
            }
            if (found) refreshCatalog(gen)
        }
    }

    private suspend fun refreshCatalog(gen: Int) {
        val api = service ?: return
        if ((_state.value as? SkillsToolsState.Content)?.catalog !is CatalogState.Open) return
        val skills = try {
            api().skillCatalog()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return }
        contentUpdate(gen) { it.copy(catalog = CatalogState.Open(skills, now())) }
    }

    fun dismissActionError() = contentUpdate(generation) { it.copy(actionError = null) }

    private fun contentUpdate(gen: Int, f: (SkillsToolsState.Content) -> SkillsToolsState.Content) {
        if (gen != generation) return
        val current = _state.value as? SkillsToolsState.Content ?: return
        _state.value = f(current)
    }

    private suspend fun <T> desktopRead(call: suspend () -> T): Result<T> = try {
        Result.success(call())
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }
}
