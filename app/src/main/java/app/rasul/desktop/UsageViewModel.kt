package app.rasul.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.bridge.DesktopApi
import app.rasul.bridge.DesktopError
import app.rasul.bridge.UsageReportDto
import app.rasul.bridge.desktopFailure
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.ModelOptionsDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One subscription window rendered as a progress bar; [usedPercent] is 0-100 from the server. */
data class RateWindow(val label: String, val usedPercent: Double, val resetsAt: String?)

/** A benched provider: its credential pool (or some of its models) recovers at [until] (ISO text). */
data class LimitedProvider(val provider: String, val until: String)

data class UsageState(
    /** No Rasul Bridge on this bot: the bridge-only screen shows one explanatory line. */
    val direct: Boolean = false,
    val loading: Boolean = false,
    val error: DesktopError? = null,
    val days: Int = DEFAULT_DAYS,
    val report: UsageReportDto? = null,
    val windows: List<RateWindow> = emptyList(),
    val limited: List<LimitedProvider> = emptyList(),
    val optionsError: HermesError? = null,
    val loaded: Boolean = false,
) {
    companion object {
        const val DEFAULT_DAYS = 7
        val PERIODS = listOf(7, 30, 90)
    }
}

/** Usage chart data comes from the desktop routes; rate windows still come from /api/model/options. */
class UsageViewModel(
    private val target: HermesTarget,
    private val options: suspend (HermesTarget, Boolean) -> HermesResult<ModelOptionsDto>,
    private val service: (suspend () -> DesktopApi)?,
) : ViewModel() {
    private val _state = MutableStateFlow(UsageState(direct = service == null, loading = service != null))
    val state: StateFlow<UsageState> = _state
    private var job: Job? = null

    init { refresh() }

    fun refresh() = load(_state.value.days)

    fun setDays(days: Int) {
        if (days !in UsageState.PERIODS || days == _state.value.days) return
        load(days)
    }

    private fun load(days: Int) {
        val api = service ?: return
        job?.cancel()
        _state.value = UsageState(days = days, loading = true)
        job = viewModelScope.launch {
            try {
                val report = api().usage(days)
                val (info, optionsError) = rateLimits()
                _state.value = UsageState(
                    days = days, report = report, windows = info.windows, limited = info.limited,
                    optionsError = optionsError, loaded = true,
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = UsageState(days = days, error = desktopFailure(e)) }
        }
    }

    private suspend fun rateLimits(): Pair<RateLimitInfo, HermesError?> = when (val r = options(target, false)) {
        is HermesResult.Err -> RateLimitInfo(emptyList(), emptyList()) to r.error
        is HermesResult.Ok -> rateLimitInfo(r.value) to null
    }
}

data class RateLimitInfo(val windows: List<RateWindow>, val limited: List<LimitedProvider>)

/** Model-options payload -> progress-bar rows, one row per reported window (and per pooled account). */
internal fun rateLimitInfo(options: ModelOptionsDto): RateLimitInfo {
    val windows = mutableListOf<RateWindow>()
    val limited = mutableListOf<LimitedProvider>()
    options.providers.forEach { provider ->
        provider.limit?.let { limit ->
            when (limit.scope) {
                "account" -> limit.resetsAt?.let { limited += LimitedProvider(provider.name, it) }
                "models" -> limit.models.values.minOrNull()?.let { limited += LimitedProvider(provider.name, it) }
            }
        }
        provider.usage?.let { usage ->
            usage.windows.forEach { w ->
                windows += RateWindow(w.label?.takeIf { it.isNotBlank() } ?: provider.name, w.usedPercent ?: 0.0, w.resetsAt)
            }
            usage.accounts.forEach { account ->
                if (account.state == "limited") {
                    account.resetsAt?.let { limited += LimitedProvider(account.label ?: provider.name, it) }
                }
                account.windows.forEach { w ->
                    val label = listOfNotNull(
                        account.label?.takeIf { it.isNotBlank() },
                        w.label?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { provider.name }
                    windows += RateWindow(label, w.usedPercent ?: 0.0, w.resetsAt)
                }
            }
        }
    }
    return RateLimitInfo(windows, limited)
}
