package app.rasul.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.bridge.ChannelDto
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelException
import app.rasul.bridge.ChannelFailure
import app.rasul.bridge.ChannelFieldDto
import app.rasul.bridge.ChannelsApi
import app.rasul.bridge.NumbersProblem
import app.rasul.bridge.SettingProblem
import app.rasul.bridge.TelegramApplyDto
import app.rasul.bridge.WhatsappApplyDto
import app.rasul.bridge.WhatsappMode
import app.rasul.bridge.WhatsappNumbers
import app.rasul.bridge.WhatsappSetupDto
import app.rasul.bridge.WhatsappStatus
import app.rasul.bridge.parseWhatsappNumbers
import app.rasul.bridge.settingsDiff
import app.rasul.bridge.whatsappError
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
internal const val MAX_NUMBERS_TEXT = 20 * 24
internal const val MAX_SETTING_INPUT = 4200
private const val RATE_LIMIT_BACKOFF = 4

/** What a restart asks for when Hermes is busy; "apply" also restarts, so it follows the same rule. */
enum class BusyAction { Restart, Apply, WhatsappApply }

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
    data class SettingsSaved(val name: String) : ChannelNotice
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

sealed interface WhatsappSetup {
    data class Options(
        val mode: WhatsappMode = WhatsappMode.Bot,
        val numbers: String = "",
        val starting: Boolean = false,
        val numbersProblem: NumbersProblem? = null,
        val error: ChannelFailure? = null,
    ) : WhatsappSetup

    /** Installing, starting or waiting for the scan; [qrPayload] is only set while [status] is `waiting`. */
    data class Active(
        val pairingId: String,
        val status: String,
        val qrPayload: String?,
        val expiresAtMs: Long?,
        /** The last poll failed; polling goes on. */
        val pollError: ChannelFailure? = null,
    ) : WhatsappSetup

    data class Connected(val pairingId: String, val accountName: String?, val phoneLast4: String?) : WhatsappSetup

    data class Done(val restartStarted: Boolean, val needsRestart: Boolean) : WhatsappSetup

    /** Error, cancelled or expired: only "Start again" is left. */
    data class Failed(val failure: ChannelFailure) : WhatsappSetup
}

/**
 * The settings form of one channel. [inputs] holds secret text the owner is typing: it lives only in this state, is
 * never saved with the screen and is dropped when the form closes or a save works.
 */
class SettingsForm(
    val channelId: String,
    val channelName: String,
    val fields: List<ChannelFieldDto>,
    val inputs: Map<String, String> = emptyMap(),
    val clearing: Set<String> = emptySet(),
    val saving: Boolean = false,
    val problems: Map<String, SettingProblem> = emptyMap(),
    val tooMany: Boolean = false,
    val error: ChannelFailure? = null,
) {
    fun copy(
        inputs: Map<String, String> = this.inputs,
        clearing: Set<String> = this.clearing,
        saving: Boolean = this.saving,
        problems: Map<String, SettingProblem> = this.problems,
        tooMany: Boolean = this.tooMany,
        error: ChannelFailure? = this.error,
    ) = SettingsForm(channelId, channelName, fields, inputs, clearing, saving, problems, tooMany, error)

    /** Whether a save would send anything. */
    val changed: Boolean get() = inputs.values.any { it.isNotBlank() } || clearing.isNotEmpty()

    override fun toString() = "SettingsForm($channelId, saving=$saving)"
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
    val whatsapp: WhatsappSetup? = null,
    val settings: SettingsForm? = null,
) {
    /** Any request that changes state is running, so the controls wait. */
    val requestRunning: Boolean get() = switchingId != null || testingId != null || acting
    val restartNeeded: Boolean get() = restartFlag || channels.orEmpty().any { it.pendingRestart }
}

/** The telegram channel offers the setup flow while it has no configuration yet. */
internal fun ChannelDto.canConnectTelegram(): Boolean = id == TELEGRAM_ID && !configured

internal const val TELEGRAM_ID = "telegram"
internal const val WHATSAPP_ID = "whatsapp"

/** WhatsApp offers its setup flow on its own row, also to link again after a logout. */
internal fun ChannelDto.canConnectWhatsapp(): Boolean = id == WHATSAPP_ID

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
    private var waPollJob: Job? = null
    private var generation = 0
    private var visible = false

    /** The pairing and ids captured when Apply was tapped; every repeat sends exactly these. */
    private var pendingApply: Pair<String, List<String>>? = null
    private var pendingWhatsappApply: String? = null

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
                    _state.update { it.copy(acting = false, busy = BusyState.None, restartFlag = false, notice = ChannelNotice.Restarted, setup = restartDone(it.setup), whatsapp = restartDoneWhatsapp(it.whatsapp)) }
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
                BusyAction.WhatsappApply -> {
                    val pairing = pendingWhatsappApply ?: run {
                        _state.update { it.copy(acting = false, busy = BusyState.None) }
                        return Attempt.Failed
                    }
                    val result = source().applyWhatsappSetup(pairing, force)
                    pendingWhatsappApply = null
                    whatsappApplied(result)
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
            val gone = action == BusyAction.WhatsappApply &&
                (failure.error == ChannelError.SetupExpired || failure.error == ChannelError.SetupNotFound)
            if (gone) pendingWhatsappApply = null
            _state.update {
                if (gone) it.copy(acting = false, busy = BusyState.None, whatsapp = WhatsappSetup.Failed(failure))
                else it.copy(acting = false, busy = BusyState.None, actionError = failure)
            }
            return Attempt.Failed
        }
    }

    /** A finished setup screen must not offer Restart again once the restart worked. */
    private fun restartDone(setup: TelegramSetup?): TelegramSetup? =
        if (setup is TelegramSetup.Done) setup.copy(restartStarted = true, needsRestart = false) else setup

    private fun restartDoneWhatsapp(setup: WhatsappSetup?): WhatsappSetup? =
        if (setup is WhatsappSetup.Done) setup.copy(restartStarted = true, needsRestart = false) else setup

    private fun whatsappApplied(result: WhatsappApplyDto) {
        _state.update {
            it.copy(
                acting = false, busy = BusyState.None,
                restartFlag = it.restartFlag || result.needsRestart,
                whatsapp = WhatsappSetup.Done(result.restartStarted, result.needsRestart),
            )
        }
    }

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
        val needWaPoll = visible && s.whatsapp is WhatsappSetup.Active
        if (needWaPoll && waPollJob?.isActive != true) waPollJob = viewModelScope.launch { whatsappPollLoop() }
        if (!needWaPoll) { waPollJob?.cancel(); waPollJob = null }
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
        if (service == null || _state.value.let { it.setup != null || it.whatsapp != null || it.settings != null }) return
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

    // ---------- WhatsApp setup ----------

    fun startWhatsapp() {
        if (service == null || _state.value.let { it.setup != null || it.whatsapp != null || it.settings != null }) return
        _state.update { it.copy(whatsapp = WhatsappSetup.Options()) }
    }

    fun setWhatsappMode(mode: WhatsappMode) = _state.update { s ->
        val options = s.whatsapp as? WhatsappSetup.Options
        if (options == null || options.starting) s else s.copy(whatsapp = options.copy(mode = mode))
    }

    fun setWhatsappNumbers(text: String) = _state.update { s ->
        val options = s.whatsapp as? WhatsappSetup.Options
        if (options == null || options.starting) s else s.copy(whatsapp = options.copy(numbers = text, numbersProblem = null, error = null))
    }

    fun beginWhatsapp() {
        val api = service ?: return
        val options = _state.value.whatsapp as? WhatsappSetup.Options ?: return
        if (options.starting) return
        val parsed = if (options.numbers.length > MAX_NUMBERS_TEXT) WhatsappNumbers.Invalid(NumbersProblem.TooLong) else parseWhatsappNumbers(options.numbers)
        val numbers = when (parsed) {
            is WhatsappNumbers.Ok -> parsed.numbers
            is WhatsappNumbers.Invalid -> {
                _state.update { it.copy(whatsapp = options.copy(numbersProblem = parsed.problem)) }
                return
            }
        }
        // This exact object marks this start: an answer that comes after Back and a new form belongs to nobody.
        val starting = options.copy(starting = true, numbersProblem = null, error = null)
        _state.update { it.copy(whatsapp = starting) }
        viewModelScope.launch {
            try {
                val start = api().startWhatsappSetup(options.mode, numbers)
                var mine = false
                _state.update { s ->
                    mine = s.whatsapp === starting
                    if (mine) s.copy(whatsapp = whatsappFrom(start, null)) else s
                }
                // The PC may answer a repeated start with the same pairing: cancelling it would end the live one.
                if (mine) syncJobs() else if (!isLivePairing(start.pairingId)) cancelOrphan(api, start.pairingId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { s ->
                    if (s.whatsapp === starting) s.copy(whatsapp = options.copy(starting = false, error = channelFailure(e))) else s
                }
            }
        }
    }

    private fun isLivePairing(pairingId: String): Boolean = when (val setup = _state.value.whatsapp) {
        is WhatsappSetup.Active -> setup.pairingId == pairingId
        is WhatsappSetup.Connected -> setup.pairingId == pairingId
        else -> false
    }

    /** A pairing the owner already left: the PC drops it too (a failure here changes nothing the owner sees). */
    private suspend fun cancelOrphan(api: suspend () -> ChannelsApi, pairingId: String) {
        try { api().cancelWhatsappSetup(pairingId) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    /** The screen state for what the bridge reported; [expiresAtMs] from an earlier answer is kept when this one has none. */
    private fun whatsappFrom(dto: WhatsappSetupDto, expiresAtMs: Long?): WhatsappSetup = when (dto.status) {
        WhatsappStatus.CONNECTED -> WhatsappSetup.Connected(dto.pairingId, dto.accountName, dto.accountPhoneLast4)
        WhatsappStatus.ERROR -> WhatsappSetup.Failed(ChannelFailure(whatsappError(dto.error)))
        WhatsappStatus.CANCELLED -> WhatsappSetup.Failed(ChannelFailure(ChannelError.SetupCancelled))
        else -> WhatsappSetup.Active(dto.pairingId, dto.status, dto.qrPayload, parseInstantMs(dto.expiresAt) ?: expiresAtMs)
    }

    private suspend fun whatsappPollLoop() {
        var wait = pollMs
        while (_state.value.whatsapp is WhatsappSetup.Active) {
            delay(wait)
            // A rate-limit answer slows the poll down; any other answer goes back to the normal pace.
            wait = if (whatsappPollOnce()) pollMs * RATE_LIMIT_BACKOFF else pollMs
        }
    }

    /** One status read; true when the bridge said it is rate limiting. */
    private suspend fun whatsappPollOnce(): Boolean {
        val active = _state.value.whatsapp as? WhatsappSetup.Active ?: return false
        val api = service ?: return false
        if (active.expiresAtMs != null && now() >= active.expiresAtMs) {
            failWhatsapp(active.pairingId, ChannelFailure(ChannelError.SetupExpired))
            return false
        }
        try {
            val status = api().whatsappSetupStatus(active.pairingId)
            _state.update { s ->
                val current = s.whatsapp as? WhatsappSetup.Active
                if (current == null || current.pairingId != active.pairingId) s else s.copy(whatsapp = whatsappFrom(status, current.expiresAtMs))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = channelFailure(e)
            if (failure.error == ChannelError.SetupExpired || failure.error == ChannelError.SetupNotFound) {
                failWhatsapp(active.pairingId, failure)
            } else {
                _state.update { s ->
                    val current = s.whatsapp as? WhatsappSetup.Active
                    if (current == null || current.pairingId != active.pairingId) s else s.copy(whatsapp = current.copy(pollError = failure))
                }
            }
            return failure.error == ChannelError.RateLimited
        }
        return false
    }

    private fun failWhatsapp(pairingId: String, failure: ChannelFailure) = _state.update { s ->
        val current = s.whatsapp as? WhatsappSetup.Active
        if (current == null || current.pairingId != pairingId) s else s.copy(whatsapp = WhatsappSetup.Failed(failure))
    }

    fun applyWhatsapp() {
        val current = _state.value
        val connected = current.whatsapp as? WhatsappSetup.Connected ?: return
        if (service == null || current.requestRunning || current.busy != BusyState.None) return
        pendingWhatsappApply = connected.pairingId
        launchAction(BusyAction.WhatsappApply, force = false)
    }

    /** Back or Cancel inside the setup: the pairing is dropped on the PC as well. */
    fun cancelWhatsapp() {
        val api = service
        val s = _state.value
        if (s.acting) return
        val pairing = when (val setup = s.whatsapp) {
            is WhatsappSetup.Active -> setup.pairingId
            is WhatsappSetup.Connected -> setup.pairingId
            else -> null
        }
        pendingWhatsappApply = null
        if (s.busy != BusyState.None) { waitJob?.cancel(); waitJob = null }
        _state.update { it.copy(whatsapp = null, busy = BusyState.None) }
        syncJobs()
        if (pairing != null && api != null) {
            viewModelScope.launch {
                try { api().cancelWhatsappSetup(pairing) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    /** "Start again" after an error or an expired code. */
    fun restartWhatsapp() = _state.update { s -> if (s.whatsapp is WhatsappSetup.Failed) s.copy(whatsapp = WhatsappSetup.Options()) else s }

    fun finishWhatsapp() {
        if (_state.value.whatsapp !is WhatsappSetup.Done) return
        _state.update { it.copy(whatsapp = null) }
    }

    // ---------- channel settings ----------

    fun openSettings(id: String) {
        val s = _state.value
        if (service == null || s.requestRunning || s.busy != BusyState.None || s.setup != null || s.whatsapp != null || s.settings != null) return
        val channel = s.channels?.firstOrNull { it.id == id }?.takeIf { it.fields.isNotEmpty() } ?: return
        _state.update { it.copy(settings = SettingsForm(channel.id, channel.name, channel.fields)) }
    }

    fun setSettingInput(key: String, text: String) = _state.update { s ->
        val form = s.settings
        if (form == null || form.saving || form.fields.none { it.key == key }) s
        else s.copy(settings = form.copy(inputs = form.inputs + (key to text.take(MAX_SETTING_INPUT)), problems = form.problems - key, tooMany = false, error = null))
    }

    /** "Clear" on a field that is set; pressed again it takes the clear back. */
    fun toggleSettingClear(key: String) = _state.update { s ->
        val form = s.settings
        if (form == null || form.saving || form.fields.none { it.key == key && it.isSet }) s
        else s.copy(
            settings = form.copy(
                clearing = if (key in form.clearing) form.clearing - key else form.clearing + key,
                inputs = form.inputs - key, problems = form.problems - key, tooMany = false, error = null,
            ),
        )
    }

    fun saveSettings() {
        val api = service ?: return
        val form = _state.value.settings ?: return
        if (form.saving) return
        val diff = settingsDiff(form.fields, form.inputs, form.clearing)
        if (diff.isEmpty && diff.problems.isEmpty()) return
        if (!diff.sendable) {
            _state.update { it.copy(settings = form.copy(problems = diff.problems, tooMany = diff.tooMany)) }
            return
        }
        _state.update { it.copy(settings = form.copy(saving = true, problems = emptyMap(), tooMany = false, error = null)) }
        viewModelScope.launch {
            try {
                val row = api().saveSettings(form.channelId, diff.values, diff.clear)
                _state.update { s ->
                    s.copy(
                        settings = null,
                        channels = s.channels?.map { if (it.id == form.channelId) row.copy(needsRestart = false) else it },
                        restartFlag = s.restartFlag || row.needsRestart,
                        notice = ChannelNotice.SettingsSaved(form.channelName),
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { s -> s.settings?.let { cur -> s.copy(settings = cur.copy(saving = false, error = channelFailure(e))) } ?: s }
            }
        }
    }

    /** Back from the form: whatever was typed is dropped. */
    fun closeSettings() {
        if (_state.value.settings?.saving == true) return
        _state.update { it.copy(settings = null) }
    }
}
