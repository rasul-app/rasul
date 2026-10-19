package app.rasul.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.bridge.DesktopApi
import app.rasul.bridge.DesktopError
import app.rasul.bridge.LOG_LINES_MAX
import app.rasul.bridge.LogFile
import app.rasul.bridge.LogLevel
import app.rasul.bridge.desktopFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LogsState(
    /** No Rasul Bridge on this bot: the screen shows one explanatory line. */
    val direct: Boolean = false,
    val loading: Boolean = false,
    val error: DesktopError? = null,
    val file: LogFile = LogFile.Errors,
    val level: LogLevel = LogLevel.All,
    /** Oldest first, as the PC sends them. */
    val lines: List<String> = emptyList(),
    val loaded: Boolean = false,
    /** Counts finished reads, so the list scrolls to its end again after a refresh that returns the same lines. */
    val reads: Int = 0,
)

/**
 * The Hermes log of one PC. A request goes out when the screen opens and when the file, the level or Refresh is
 * chosen; a failure never starts another one by itself.
 */
class LogsViewModel(private val service: (suspend () -> DesktopApi)?) : ViewModel() {
    private val _state = MutableStateFlow(LogsState(direct = service == null, loading = service != null))
    val state: StateFlow<LogsState> = _state
    private var job: Job? = null

    init { load(LogFile.Errors, LogLevel.All) }

    fun refresh() = load(_state.value.file, _state.value.level)

    fun selectFile(file: LogFile) {
        if (file != _state.value.file) load(file, _state.value.level)
    }

    fun selectLevel(level: LogLevel) {
        if (level != _state.value.level) load(_state.value.file, level)
    }

    private fun load(file: LogFile, level: LogLevel) {
        val api = service ?: return
        job?.cancel()
        val reads = _state.value.reads
        _state.value = LogsState(loading = true, file = file, level = level, reads = reads)
        job = viewModelScope.launch {
            try {
                val answer = api().logs(file, LOG_LINES_MAX, level)
                _state.value = LogsState(file = file, level = level, lines = answer.lines, loaded = true, reads = reads + 1)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.value = LogsState(file = file, level = level, error = desktopFailure(e), reads = reads + 1)
            }
        }
    }
}

/** How a log line is tinted. */
internal enum class LogLineKind { Error, Warning, Plain }

private val LOG_LEVEL_WORD = Regex("""^\S+ \S+ (ERROR|CRITICAL|WARNING)\b""")

/** The level word is the token after the date and time, so a word inside the message does not count. */
internal fun logLineKind(line: String): LogLineKind = when (LOG_LEVEL_WORD.find(line)?.groupValues?.get(1)) {
    "ERROR", "CRITICAL" -> LogLineKind.Error
    "WARNING" -> LogLineKind.Warning
    else -> LogLineKind.Plain
}
