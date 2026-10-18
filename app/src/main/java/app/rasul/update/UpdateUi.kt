package app.rasul.update

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.components.RasulToggle
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.launch

@StringRes
internal fun UpdateError.messageRes(): Int = when (this) {
    UpdateError.Offline -> R.string.update_err_offline
    UpdateError.RateLimited -> R.string.update_err_rate_limited
    is UpdateError.Http -> R.string.update_err_http
    UpdateError.BadResponse -> R.string.update_err_bad_response
    is UpdateError.NoAsset -> R.string.update_err_no_asset
    UpdateError.BadChecksumFile -> R.string.update_err_bad_checksum_file
    UpdateError.ChecksumMismatch -> R.string.update_err_checksum_mismatch
    UpdateError.SignerMismatch -> R.string.update_err_signer_mismatch
    UpdateError.SignerUnreadable -> R.string.update_err_signer_unreadable
    UpdateError.TooLarge -> R.string.update_err_too_large
    UpdateError.InstallBlocked -> R.string.update_err_install_blocked
    UpdateError.WrongPackage -> R.string.update_err_wrong_package
    UpdateError.NotNewer -> R.string.update_err_not_newer
    UpdateError.InstallNotConfirmed -> R.string.update_err_install_not_confirmed
    is UpdateError.InstallFailed -> R.string.update_err_install_failed
}

/** The one format argument some messages take (error code or version); null when none. */
internal fun UpdateError.messageArg(): Any? = when (this) {
    is UpdateError.Http -> code
    is UpdateError.NoAsset -> version
    else -> null
}

@Composable
internal fun UpdateError.text(): String {
    val arg = messageArg()
    return if (arg == null) stringResource(messageRes()) else stringResource(messageRes(), arg)
}

/** Release notes as plain styled text: headings and bold are weight only, nothing is a link. */
@Composable
internal fun NotesText(notes: String, modifier: Modifier = Modifier) {
    val lines = renderNotes(notes)
    val c = Rasul.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (lines.isEmpty()) Text(stringResource(R.string.update_no_notes), style = RasulType.body, color = c.muted)
        lines.forEach { line ->
            val text: AnnotatedString = buildAnnotatedString {
                if (line.kind == NoteKind.Bullet) append("•  ")
                line.spans.forEach { span ->
                    withStyle(SpanStyle(fontWeight = if (span.bold) FontWeight.Bold else null)) { append(span.text) }
                }
            }
            // Each line takes its own direction, so English notes read left-to-right inside the Arabic UI.
            Text(
                text,
                style = (if (line.kind == NoteKind.Heading) RasulType.rowTitle else RasulType.body)
                    .copy(textDirection = TextDirection.Content),
                color = c.ink,
                modifier = Modifier.fillMaxWidth().padding(top = if (line.kind == NoteKind.Heading) 6.dp else 0.dp),
            )
        }
    }
}

/** Offer, progress and failure for one release. The states are [UpdateState.Available], Working and UpdateFailed. */
@Composable
fun UpdateSheet(
    state: UpdateState,
    onUpdate: (ReleaseInfo) -> Unit,
    onLater: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onOpenSetting: () -> Unit,
) {
    val c = Rasul.colors
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Working -> state.release
        is UpdateState.UpdateFailed -> state.release
        else -> return
    }
    val dismiss = when (state) {
        is UpdateState.Available -> onLater
        is UpdateState.UpdateFailed -> onClose
        else -> ({})
    }
    RasulSheet(stringResource(R.string.update_available_title, release.version.toString()), dismiss) {
        Text(stringResource(R.string.update_whats_new), style = RasulType.sectionLabel, color = c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        NotesText(
            release.notes,
            Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
        )
        when (state) {
            is UpdateState.Working -> {
                val label = when (state.phase) {
                    UpdatePhase.Downloading ->
                        state.progress?.let { stringResource(R.string.update_downloading_pct, (it * 100).toInt()) }
                            ?: stringResource(R.string.update_downloading)
                    UpdatePhase.Verifying -> stringResource(R.string.update_verifying)
                    UpdatePhase.Installing -> stringResource(R.string.update_installing)
                }
                Text(label, style = RasulType.bodySmall, color = c.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                val progress = state.progress
                if (state.phase == UpdatePhase.Downloading && progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.fill)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.fill)
                }
                SecondaryButton(stringResource(R.string.cancel), onCancel, Modifier.fillMaxWidth().padding(top = 16.dp))
            }
            is UpdateState.UpdateFailed -> {
                Text(state.error.text(), style = RasulType.body, color = c.danger, modifier = Modifier.padding(top = 16.dp))
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(stringResource(R.string.update_close), onClose, Modifier.weight(1f))
                    if (state.error == UpdateError.InstallBlocked) {
                        PrimaryButton(stringResource(R.string.update_open_setting), onOpenSetting, Modifier.weight(1f))
                    } else {
                        PrimaryButton(stringResource(R.string.update_try_again), { onUpdate(release) }, Modifier.weight(1f))
                    }
                }
            }
            else -> Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.update_later), onLater, Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.update_action), { onUpdate(release) }, Modifier.weight(1f))
            }
        }
    }
}

/** Shows [UpdateSheet] whenever the controller has something to offer, over any screen. */
@Composable
fun UpdateSheetHost(controller: UpdateController, onOpenSetting: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val visible = when (val s = state) {
        is UpdateState.Available -> s.showSheet
        is UpdateState.Working, is UpdateState.UpdateFailed -> true
        else -> false
    }
    if (visible) {
        UpdateSheet(
            state,
            onUpdate = controller::startUpdate,
            onLater = { scope.launch { controller.later() } },
            onCancel = controller::cancelUpdate,
            onClose = controller::dismissFailure,
            onOpenSetting = onOpenSetting,
        )
    }
}

internal fun updateStatusRes(state: UpdateState, enabled: Boolean): Int = when {
    !enabled -> R.string.update_status_off
    state is UpdateState.Checking -> R.string.update_status_checking
    state is UpdateState.UpToDate -> R.string.update_status_up_to_date
    state is UpdateState.Available || state is UpdateState.Working || state is UpdateState.UpdateFailed -> R.string.update_status_available
    state is UpdateState.CheckFailed -> state.error.messageRes()
    else -> R.string.update_status_idle
}

@Composable
internal fun updateStatusText(state: UpdateState, enabled: Boolean): String {
    val res = updateStatusRes(state, enabled)
    val arg: Any? = when {
        !enabled -> null
        state is UpdateState.Available -> state.release.version.toString()
        state is UpdateState.Working -> state.release.version.toString()
        state is UpdateState.UpdateFailed -> state.release.version.toString()
        state is UpdateState.CheckFailed -> state.error.messageArg()
        else -> null
    }
    return if (arg == null) stringResource(res) else stringResource(res, arg)
}

/** The "App updates" sheet opened from More: the on/off setting, status, and Check now. */
@Composable
fun UpdateSettingsSheet(
    enabled: Boolean,
    state: UpdateState,
    onEnabledChanged: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onView: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Rasul.colors
    RasulSheet(stringResource(R.string.update_title), onDismiss) {
        RasulRow(
            last = true, gap = 10.dp, hairlineStart = 0.dp, hairlineEnd = 0.dp,
            contentPadding = PaddingValues(vertical = 8.dp),
            trailing = {
                RasulToggle(enabled, label = stringResource(R.string.update_check_setting), onCheckedChange = onEnabledChanged)
            },
        ) {
            RowTitle(stringResource(R.string.update_check_setting), maxLines = 2)
            RowSub(stringResource(R.string.update_check_setting_summary), maxLines = 4)
        }
        val failed = state is UpdateState.CheckFailed && enabled
        Text(
            updateStatusText(state, enabled), style = RasulType.body, color = if (failed) c.danger else c.muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        )
        val available = enabled && (state is UpdateState.Available)
        if (available) {
            PrimaryButton(stringResource(R.string.update_view), onView, Modifier.fillMaxWidth())
        } else {
            SecondaryButton(
                stringResource(R.string.update_check_now), onCheckNow, Modifier.fillMaxWidth(),
                enabled = enabled && state !is UpdateState.Checking,
            )
        }
    }
}

private val previewRelease = ReleaseInfo(
    SemVer(0, 2, 6),
    "## Highlights\n- The app can now update itself from GitHub Releases\n- **Check now** lives in More > App updates\n- Fixes for [chat](https://example.invalid/x) attachments",
    "", "",
)

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun UpdateAvailableEn() = RasulTheme(false) { UpdateSheet(UpdateState.Available(previewRelease, true), {}, {}, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun UpdateAvailableAr() = RasulTheme(false) { UpdateSheet(UpdateState.Available(previewRelease, true), {}, {}, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun UpdateDownloadingEn() = RasulTheme(false) {
    UpdateSheet(UpdateState.Working(previewRelease, UpdatePhase.Downloading, 0.4f), {}, {}, {}, {}, {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun UpdateDownloadingAr() = RasulTheme(false) {
    UpdateSheet(UpdateState.Working(previewRelease, UpdatePhase.Downloading, 0.4f), {}, {}, {}, {}, {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun UpdateFailedEn() = RasulTheme(false) {
    UpdateSheet(UpdateState.UpdateFailed(previewRelease, UpdateError.SignerMismatch), {}, {}, {}, {}, {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun UpdateFailedAr() = RasulTheme(false) {
    UpdateSheet(UpdateState.UpdateFailed(previewRelease, UpdateError.SignerMismatch), {}, {}, {}, {}, {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun UpdateSettingsEn() = RasulTheme(false) { UpdateSettingsSheet(true, UpdateState.UpToDate, {}, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun UpdateSettingsAr() = RasulTheme(false) { UpdateSettingsSheet(true, UpdateState.UpToDate, {}, {}, {}, {}) }
