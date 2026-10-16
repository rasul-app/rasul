package app.rasul.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rasul.R
import app.rasul.data.AddressVerdict
import app.rasul.data.HermesError
import app.rasul.data.RejectReason
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulNavBar
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

@Composable
internal fun HermesError.text(): String = when (this) {
    HermesError.BridgeUnreachable -> stringResource(R.string.bridge_unreachable)
    HermesError.DeviceRevoked -> stringResource(R.string.bridge_revoked)
    HermesError.DeviceKeyRepair -> stringResource(R.string.bridge_key_repair)
    HermesError.BridgeTimeout -> stringResource(R.string.bridge_timeout)
    HermesError.ProfileNotReady -> stringResource(R.string.bridge_profile_not_ready)
    HermesError.BridgeResponseInvalid -> stringResource(R.string.bridge_response_invalid)
    HermesError.Unauthorized -> stringResource(R.string.err_unauthorized)
    HermesError.NotFound -> stringResource(R.string.err_not_found)
    HermesError.ResourceNotFound -> stringResource(R.string.err_resource_not_found)
    HermesError.ConnectionChanged -> stringResource(R.string.err_connection_changed)
    is HermesError.Storage -> stringResource(R.string.connections_load_error, detail)
    is HermesError.ProfileUnknown -> stringResource(R.string.err_profile_unknown, profile)
    is HermesError.Unreachable -> stringResource(R.string.err_unreachable, detail)
    is HermesError.Incompatible -> stringResource(R.string.err_incompatible, missing.joinToString())
    is HermesError.Server -> stringResource(R.string.err_server, code)
    is HermesError.InvalidAddress -> stringResource(R.string.addr_invalid)
    HermesError.AddressRejected -> stringResource(R.string.addr_cleartext_rejected)
    HermesError.InvalidKey -> stringResource(R.string.err_invalid_key)
    is HermesError.Redirected -> stringResource(R.string.err_redirected, code)
}

/** Add/edit form with labelled fields, network hint, test action, and result card. */
@Composable
fun EditConnectionScreen(vm: EditConnectionViewModel, onClose: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.saved) { if (s.saved) onClose() }
    EditConnectionContent(
        s, onClose,
        onName = { vm.edit(name = it) }, onUrl = { vm.edit(url = it) },
        onProfile = { vm.edit(profile = it) }, onKey = { vm.edit(key = it) },
        onTest = vm::test, onSave = vm::save,
    )
}

@Composable
fun EditConnectionContent(
    s: EditState,
    onClose: () -> Unit,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onProfile: (String) -> Unit,
    onKey: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val c = Rasul.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        RasulNavBar(
            title = stringResource(if (s.hasStoredKey) R.string.edit_connection_title else R.string.add_pc),
            leading = stringResource(R.string.cancel), onLeading = onClose,
            trailing = stringResource(R.string.save), onTrailing = onSave, trailingEnabled = s.canSave,
        )
        if (s.loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field(stringResource(R.string.field_name), s.name, onName)
            Field(
                stringResource(R.string.field_url), s.url, onUrl,
                placeholder = stringResource(R.string.field_url_placeholder),
                keyboardType = KeyboardType.Uri,
            )
            when (val v = s.verdict) {
                is AddressVerdict.Rejected -> Text(
                    stringResource(
                        if (v.reason == RejectReason.InvalidUrl) R.string.addr_invalid else R.string.addr_cleartext_rejected,
                    ),
                    color = c.danger, style = RasulType.bodySmall,
                )
                AddressVerdict.AllowedPublicHttps -> Text(stringResource(R.string.addr_public_warning), color = c.warnText, style = RasulType.bodySmall)
                else -> Unit
            }
            Field(stringResource(R.string.field_profile), s.profile, onProfile)
            Field(
                stringResource(R.string.field_key), s.key, onKey,
                leading = RasulIcons.Key, password = true, keyboardType = KeyboardType.Password,
                hint = if (s.hasStoredKey) stringResource(R.string.key_keep_hint) else null,
            )

            Row(
                Modifier.fillMaxWidth().background(c.accentSoft, RasulShapes.input).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(RasulIcons.Info, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.tailscale_hint), style = RasulType.bodySmall, color = c.accentHintText, modifier = Modifier.weight(1f))
            }

            SecondaryButton(stringResource(R.string.test_connection), onTest, Modifier.fillMaxWidth(), enabled = s.canTest)
            when (val t = s.test) {
                TestState.Idle -> Unit
                TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.testing), style = RasulType.body, color = c.muted)
                }
                is TestState.Success -> ResultCard(
                    ok = true, title = stringResource(R.string.test_ok),
                    body = stringResource(
                        R.string.test_ok_summary,
                        t.caps.platform ?: "-", t.caps.model ?: "-", t.caps.enabledFeatures().size,
                    ),
                )
                is TestState.Failure -> ResultCard(ok = false, title = t.error.text(), body = null)
            }

            s.loadError?.let { Text(stringResource(R.string.edit_load_error, it), color = c.danger, style = RasulType.bodySmall) }
            s.saveError?.let {
                Text(
                    stringResource(if (it == SaveError.Encryption) R.string.save_error_encryption else R.string.save_error_storage),
                    color = c.danger, style = RasulType.bodySmall,
                )
            }
        }
    }
}

/** Filled text field with a muted label, rounded background, and optional hint. */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    leading: ImageVector? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: String? = null,
) {
    val c = Rasul.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = RasulType.sectionLabel, color = c.muted)
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = label }, singleLine = true,
            textStyle = RasulType.body.copy(fontSize = 16.sp, color = c.ink),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().background(c.fill, RasulShapes.input).padding(horizontal = 14.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leading != null) Icon(leading, null, tint = c.muted, modifier = Modifier.size(20.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, style = RasulType.body, color = c.muted)
                        inner()
                    }
                }
            },
        )
        if (hint != null) Text(hint, style = RasulType.bodySmall, color = c.muted)
    }
}

/** Tinted result card with a status badge, title, and optional body. */
@Composable
private fun ResultCard(ok: Boolean, title: String, body: String?) {
    val c = Rasul.colors
    Row(
        Modifier.fillMaxWidth().background(if (ok) c.successSoft else c.dangerSoft, RasulShapes.result).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).background(if (ok) c.success else c.danger, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (ok) RasulIcons.Check else RasulIcons.X, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = RasulType.rowTitle.copy(fontSize = 15.sp), color = if (ok) c.successText else c.dangerText)
            if (body != null) Text(body, style = RasulType.bodySmall, color = if (ok) c.successText else c.dangerText)
        }
    }
}

@Composable
private fun EditPreviewBody() = RasulTheme(false) {
    EditConnectionContent(
        EditState(name = "Home PC", url = "https://example.com", profile = "default", key = "secret", test = TestState.Failure(HermesError.Unreachable("timed out"))),
        {}, {}, {}, {}, {}, {}, {},
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun EditEn() = EditPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 800, locale = "ar")
@Composable
private fun EditAr() = EditPreviewBody()
