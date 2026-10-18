package app.rasul.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.bridge.ChannelDto
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelException
import app.rasul.bridge.ChannelFailure
import app.rasul.bridge.ChannelsApi
import app.rasul.bridge.TelegramApplyDto
import app.rasul.bridge.channelFailure
import app.rasul.bridge.telegramOpenLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

internal const val BUSY_RETRY_MS = 15_000L
internal const val SETUP_POLL_MS = 3_000L
internal const val MAX_USER_IDS = 20
internal const val MAX_BOT_NAME = 64

/** What a restart asks for when Hermes is busy; "apply" also restarts, so it follows the same rule. */
enum class BusyAction { Restart, Apply }

sealed interface BusyState {
    data object None : BusyState

    /** Hermes refused because agents are working; [activeAgents] is null when the count is unknown. */
    data class Asking(val action: BusyAction, val activeAgents: Int?) : BusyState

    /** The owner chose to wait: the request is repeated every [BUSY_RETRY_MS] while the screen is open. */
    data class Waiting(val action: BusyAction) : BusyState
}

sealed interface ChannelNotice {
    data class Test(val name: String, val ok: Boolean, val message: String?) : ChannelNotice
    data object Restarted : ChannelNotice
}

enum class LinkProblem { Rejected, NoApp }

sealed interface TelegramSetup {
    data class Name(val botName: String = "", val starting: Boolean = false, val error: ChannelFailure? = null) : TelegramSetup

    data class Waiting(
        val pairingId: String,
        val deepLink: String,
        val suggestedUsername: String?,
        val expiresAtMs: Long?,
        val linkProblem: LinkProblem? = null,
        /** The last poll failed; polling goes on. */
        val pollError: ChannelFailure? = null,
    ) : TelegramSetup

    data class Ready(
        val pairingId: String,
        val botUsername: String?,
        val userIds: String,
        val idsInvalid: Boolean = false,
    ) : TelegramSetup

    data class Done(val botUsername: String?, val restartStarted: Boolean, val needsRestart: Boolean) : TelegramSetup

    /** Expired or unknown setup: only "Start again" is left. */
    data class Failed(val failure: ChannelFailure) : TelegramSetup
}

data class ChannelsState(
    /** No Rasul Bridge on this bot: one explanatory line. */
    val direct: Boolean = false,
    val loading: Boolean = false,
    /** A read failure; [channels] stays at the last good list. */
    val error: ChannelFailure? = null,
    /** Null until the first read ends. */
    val channels: List<ChannelDto>? = null,
    /** The channel whose switch request is in flight. */
    val switchingId: String? = null,
    val testingId: String? = null,
    /** A restart or apply request is in flight. */
    val acting: Boolean = false,
    /** Set by a switch or an apply that said Hermes must restart. */
    val restartFlag: Boolean = false,
    val busy: BusyState = BusyState.None,
    val notice: ChannelNotice? = null,
    val actionError: ChannelFailure? = null,
    val setup: TelegramSetup? = null,
) {
    /** Any request that changes state is running, so the controls wait. */
    val requestRunning: Boolean get() = switchingId != null || testingId != null || acting
    val restartNeeded: Boolean get() = restartFlag || channels.orEmpty().any { it.pendingRestart }
}

/** The telegram channel offers the setup flow while it has no configuration yet. */
internal fun ChannelDto.canConnectTelegram(): Boolean = id == TELEGRAM_ID && !configured

internal const val TELEGRAM_ID = "telegram"

/**
 * User IDs the owner typed, as the bridge wants them (1 to 20 numeric strings, repeats dropped), or null when the text
 * is not that. Digits typed on an Arabic keyboard and the Arabic comma are accepted.
 */
internal fun parseUserIds(text: String): List<String>? {
    val ids = text.map { ch -> if (ch == '،') ',' else if (ch.isDigit()) Character.forDigit(Character.digit(ch, 10), 10) else ch }
        .joinToString("").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (ids.isEmpty() || ids.any { id -> id.length > 20 || !id.all { it in '0'..'9' } }) return null
    return ids.distinct().takeIf { it.size <= MAX_USER_IDS }
}

private fun parseInstantMs(text: String?): Long? =
    text?.let { try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (_: Exception) { null } }

/**
 * The messaging-channels screen of one PC. Background work exists only while the screen is visible: the repeat of a
 * waiting restart (every [busyRetryMs]) and the poll of a Telegram setup (every [pollMs]).
 */
class ChannelsViewModel(
    private val service: (suspend () -> ChannelsApi)?,
    private val busyRetryMs: Long = BUSY_RETRY_MS,
    private val pollMs: Long = SETUP_POLL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ChannelsState(direct = service == null, loading = service != null))
    val state: StateFlow<ChannelsState> = _state
    private var loadJob: Job? = null
    private var waitJob: Job? = null
    private var pollJob: Job? = null
    private var generation = 0
    private var visible = false

    /** The pairing and ids captured when Apply was tapped; every repeat sends exactly these. */
    private var pendingApply: Pair<String, List<String>>? = null

    init { refresh() }

    // ---------- list ----------

    fun refresh() {
        val api = service ?: return
        loadJob?.cancel()
        val gen = ++generation
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch { read(api, gen) }
    }

    private suspend fun read(source: suspend () -> ChannelsApi, gen: Int) {
        try {
            val list = source().channels()
            if (gen == generation) _state.update { it.copy(loading = false, error = null, channels = list) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (gen == generation) _state.update { it.copy(loading = false, error = channelFailure(e)) }
        }
    }

    private fun reloadQuietly() {
        val api = service ?: return
        val gen = ++generation
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val list = api().channels()
                if (gen == generation) _state.update { it.copy(channels = list, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* the list on screen stays; Refresh reports the reason */ }
        }
    }

    fun toggle(id: String, enabled: Boolean) {
        val api = service ?: return
        val current = _state.value
        if (current.requestRunning || current.channels == null || current.busy != BusyState.None) return
        _state.update { it.copy(switchingId = id, actionError = null) }
        viewModelScope.launch {
            try {
                val row = api().setEnabled(id, enabled)
                _state.update { s ->
                    s.copy(
                        switchingId = null,
                        channels = s.channels?.map { if (it.id == id) row else it },
                        restartFlag = s.restartFlag || row.needsRestart,
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(switchingId = null, actionError = channelFailure(e)) }
            }
        }
    }

    fun test(id: String) {
        val api = service ?: return
        val current = _state.value
        if (current.requestRunning || current.busy != BusyState.None) return
        val name = current.channels?.firstOrNull { it.id == id }?.name ?: id
        _state.update { it.copy(testingId = id, actionError = null) }
        viewModelScope.launch {
            try {
                val result = api().test(id)
                _state.update { it.copy(testingId = null, notice = ChannelNotice.Test(name, result.ok, result.message?.takeIf { m -> m.isNotBlank() })) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(testingId = null, actionError = channelFailure(e)) }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }
    fun dismissActionError() = _state.update { it.copy(actionError = null) }

    // ---------- restart and apply (busy rule) ----------

    fun restart() {
        val current = _state.value
        if (service == null || current.requestRunning || current.busy != BusyState.None) return
        launchAction(BusyAction.Restart, force = false)
    }

    /** "Wait": repeat the request every [busyRetryMs] while the screen is open. */
    fun waitForIdle() {
        val asking = _state.value.busy as? BusyState.Asking ?: return
        _state.update { it.copy(busy = BusyState.Waiting(asking.action)) }
        syncJobs()
    }

    /** "Restart now": the same request with force. */
    fun restartNow() {
        val asking = _state.value.busy as? BusyState.Asking ?: return
        launchAction(asking.action, force = true)
    }

    /** Closes the question or stops the waiting; nothing was sent. */
    fun cancelBusy() {
        if (_state.value.busy == BusyState.None) return
        waitJob?.cancel()
        waitJob = null
        _state.update { it.copy(busy = BusyState.None, acting = false) }
    }

    private fun launchAction(action: BusyAction, force: Boolean) {
        _state.update { it.copy(acting = true, busy = BusyState.None, actionError = null) }
        viewModelScope.launch { attempt(action, force) }
    }

    private enum class Attempt { Done, Busy, Failed }

    private suspend fun attempt(action: BusyAction, force: Boolean): Attempt {
        val source = service ?: return Attempt.Failed
        try {
            when (action) {
                BusyAction.Restart -> {
                    source().restart(force)
                    _state.update { it.copy(acting = false, busy = BusyState.None, restartFlag = false, notice = ChannelNotice.Restarted, setup = restartDone(it.setup)) }
                }
                BusyAction.Apply -> {
                    val (pairing, ids) = pendingApply ?: run {
                        _state.update { it.copy(acting = false, busy = BusyState.None) }
                        return Attempt.Failed
                    }
                    val result = source().applyTelegramSetup(pairing, ids, force)
                    pendingApply = null
                    applied(result)
                }
            }
            reloadQuietly()
            return Attempt.Done
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = channelFailure(e)
            if (e is ChannelException && e.error == ChannelError.Busy && !force) {
                _state.update {
                    it.copy(acting = false, busy = if (it.busy is BusyState.Waiting) it.busy else BusyState.Asking(action, e.activeAgents))
                }
                return Attempt.Busy
            }
            _state.update { it.copy(acting = false, busy = BusyState.None, actionError = failure) }
            return Attempt.Failed
        }
    }

    /** A finished setup screen must not offer Restart again once the restart worked. */
    private fun restartDone(setup: TelegramSetup?): TelegramSetup? =
        if (setup is TelegramSetup.Done) setup.copy(restartStarted = true, needsRestart = false) else setup

    private fun applied(result: TelegramApplyDto) {
        _state.update {
            it.copy(
                acting = false, busy = BusyState.None,
                restartFlag = it.restartFlag || result.needsRestart,
                setup = TelegramSetup.Done(result.botUsername, result.restartStarted, result.needsRestart),
            )
        }
    }

    // ---------- visibility and background jobs ----------

    fun onVisible() {
        visible = true
        syncJobs()
    }

    fun onHidden() {
        visible = false
        syncJobs()
    }

    private fun syncJobs() {
        val s = _state.value
        val needWait = visible && s.busy is BusyState.Waiting
        val needPoll = visible && s.setup is TelegramSetup.Waiting
        if (needWait && waitJob?.isActive != true) waitJob = viewModelScope.launch { waitLoop() }
        if (!needWait) { waitJob?.cancel(); waitJob = null }
        if (needPoll && pollJob?.isActive != true) pollJob = viewModelScope.launch { pollLoop() }
        if (!needPoll) { pollJob?.cancel(); pollJob = null }
    }

    private suspend fun waitLoop() {
        while (true) {
            delay(busyRetryMs)
            val waiting = _state.value.busy as? BusyState.Waiting ?: return
            _state.update { it.copy(acting = true) }
            val result = try {
                attempt(waiting.action, force = false)
            } catch (e: CancellationException) {
                // Leaving the screen mid-request: the request is dropped, the wait resumes when the screen returns.
                _state.update { it.copy(acting = false) }
                throw e
            }
            if (result != Attempt.Busy) return
        }
    }

    private suspend fun pollLoop() {
        while (_state.value.setup is TelegramSetup.Waiting) {
            delay(pollMs)
            pollOnce()
        }
    }

    // ---------- Telegram setup ----------

    fun startSetup() {
        if (service == null || _state.value.setup != null) return
        _state.update { it.copy(setup = TelegramSetup.Name()) }
    }

    fun setBotName(text: String) = _state.update { s ->
        val name = s.setup as? TelegramSetup.Name
        if (name == null || name.starting) s else s.copy(setup = name.copy(botName = text.take(MAX_BOT_NAME), error = null))
    }

    fun beginTelegram() {
        val api = service ?: return
        val name = _state.value.setup as? TelegramSetup.Name ?: return
        if (name.starting) return
        val botName = name.botName.trim().takeIf { it.isNotEmpty() }
        _state.update { it.copy(setup = name.copy(starting = true, error = null)) }
        viewModelScope.launch {
            try {
                val start = api().startTelegramSetup(botName)
                _state.update { s ->
                    if (s.setup !is TelegramSetup.Name) s else s.copy(
                        setup = TelegramSetup.Waiting(start.pairingId, start.deepLink, start.suggestedUsername?.takeIf { u -> u.isNotBlank() }, parseInstantMs(start.expiresAt)),
                    )
                }
                syncJobs()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { s -> if (s.setup is TelegramSetup.Name) s.copy(setup = name.copy(starting = false, error = channelFailure(e))) else s }
            }
        }
    }

    /** The address to open for "Open Telegram", or null when it fails the in-app check (the screen then shows the error). */
    fun openTelegramLink(): String? {
        val waiting = _state.value.setup as? TelegramSetup.Waiting ?: return null
        val link = telegramOpenLink(waiting.deepLink)
        _state.update { s ->
            val w = s.setup as? TelegramSetup.Waiting
            if (w == null) s else s.copy(setup = w.copy(linkProblem = if (link == null) LinkProblem.Rejected else null))
        }
        return link
    }

    /** No app could open the link. */
    fun telegramLinkUnavailable() = _state.update { s ->
        val w = s.setup as? TelegramSetup.Waiting
        if (w == null) s else s.copy(setup = w.copy(linkProblem = LinkProblem.NoApp))
    }

    private suspend fun pollOnce() {
        val waiting = _state.value.setup as? TelegramSetup.Waiting ?: return
        val api = service ?: return
        if (waiting.expiresAtMs != null && now() >= waiting.expiresAtMs) {
            failSetup(waiting.pairingId, ChannelFailure(ChannelError.SetupExpired))
            return
        }
        try {
            val status = api().telegramSetupStatus(waiting.pairingId)
            _state.update { s ->
                val w = s.setup as? TelegramSetup.Waiting
                when {
                    w == null || w.pairingId != waiting.pairingId -> s
                    status.ready -> s.copy(setup = TelegramSetup.Ready(w.pairingId, status.botUsername, status.ownerUserId.orEmpty()))
                    else -> s.copy(setup = w.copy(pollError = null))
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = channelFailure(e)
            if (failure.error == ChannelError.SetupExpired || failure.error == ChannelError.SetupNotFound) {
                failSetup(waiting.pairingId, failure)
            } else {
                _state.update { s ->
                    val w = s.setup as? TelegramSetup.Waiting
                    if (w == null || w.pairingId != waiting.pairingId) s else s.copy(setup = w.copy(pollError = failure))
                }
            }
        }
    }

    private fun failSetup(pairingId: String, failure: ChannelFailure) = _state.update { s ->
        val w = s.setup as? TelegramSetup.Waiting
        if (w == null || w.pairingId != pairingId) s else s.copy(setup = TelegramSetup.Failed(failure))
    }

    fun setUserIds(text: String) = _state.update { s ->
        val ready = s.setup as? TelegramSetup.Ready
        if (ready == null || s.acting || s.busy != BusyState.None) s else s.copy(setup = ready.copy(userIds = text.take(MAX_USER_IDS * 22), idsInvalid = false))
    }

    fun applyTelegram() {
        val current = _state.value
        val ready = current.setup as? TelegramSetup.Ready ?: return
        if (service == null || current.requestRunning || current.busy != BusyState.None) return
        val ids = parseUserIds(ready.userIds)
        if (ids == null) {
            _state.update { it.copy(setup = ready.copy(idsInvalid = true)) }
            return
        }
        pendingApply = ready.pairingId to ids
        launchAction(BusyAction.Apply, force = false)
    }

    /** Back or Cancel inside the setup: the pairing is dropped on the PC as well. */
    fun cancelSetup() {
        val api = service
        val s = _state.value
        if (s.acting) return
        val pairing = when (val setup = s.setup) {
            is TelegramSetup.Waiting -> setup.pairingId
            is TelegramSetup.Ready -> setup.pairingId
            else -> null
        }
        pendingApply = null
        if (s.busy != BusyState.None) { waitJob?.cancel(); waitJob = null }
        _state.update { it.copy(setup = null, busy = BusyState.None) }
        syncJobs()
        if (pairing != null && api != null) {
            viewModelScope.launch {
                try { api().cancelTelegramSetup(pairing) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    /** "Start again" after an expired setup. */
    fun restartSetup() = _state.update { s -> if (s.setup is TelegramSetup.Failed) s.copy(setup = TelegramSetup.Name()) else s }

    /** Leaves the finished setup; the list was read again when the apply succeeded. */
    fun finishSetup() {
        if (_state.value.setup !is TelegramSetup.Done) return
        _state.update { it.copy(setup = null) }
    }
}
