package app.rasul.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.bridge.BridgeStatusDto
import app.rasul.bridge.DesktopError
import app.rasul.bridge.StatusHermesDto
import app.rasul.bridge.StatusProfileDto
import app.rasul.bridge.StatusUrlsDto
import app.rasul.ui.components.InsetGroup
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

/** A string resource with its arguments; resolved where the screen draws it. */
data class CheckText(val res: Int, val args: List<Any> = emptyList())

/** [ok] is null for lines that only report a value; [advice] is the one sentence shown when [ok] is false. */
data class CheckLine(val title: CheckText, val detail: CheckText?, val ok: Boolean?, val advice: Int?)

/** Pure mapping from the bridge's self-check to the lines the screen shows (also used by tests). */
internal fun checkLines(status: BridgeStatusDto): List<CheckLine> = buildList {
    add(CheckLine(CheckText(R.string.check_bridge), status.bridgeVersion?.takeIf { it.isNotBlank() }
        ?.let { CheckText(R.string.check_version, listOf(it)) } ?: CheckText(R.string.hupd_unknown), null, null))
    add(CheckLine(CheckText(R.string.check_hermes_home), null, status.hermesHomeFound, R.string.check_fix_hermes_home.takeIf { !status.hermesHomeFound }))
    add(CheckLine(
        CheckText(R.string.check_hermes), status.hermes?.version?.takeIf { it.isNotBlank() }
            ?.let { CheckText(R.string.check_version, listOf(it)) } ?: CheckText(R.string.hupd_unknown), null, null,
    ))
    add(CheckLine(
        CheckText(R.string.check_agents),
        status.hermes?.activeAgents?.let { CheckText(R.string.check_count, listOf(it)) } ?: CheckText(R.string.hupd_unknown), null, null,
    ))
    add(
        CheckLine(
            CheckText(R.string.check_dashboard),
            if (status.dashboardTimedOut) CheckText(R.string.check_dashboard_timeout) else null,
            status.dashboardFound,
            when {
                status.dashboardFound -> null
                status.dashboardTimedOut -> R.string.check_fix_dashboard_timeout
                else -> R.string.check_fix_dashboard
            },
        ),
    )
    add(CheckLine(CheckText(R.string.check_approvals), null, status.approvalsListener, R.string.check_fix_approvals.takeIf { !status.approvalsListener }))
    status.profiles.forEach { profile ->
        add(CheckLine(
            CheckText(R.string.check_bot_key, listOf(profile.id)), null, profile.apiKeyConfigured,
            R.string.check_fix_key.takeIf { !profile.apiKeyConfigured },
        ))
        add(CheckLine(
            CheckText(R.string.check_bot_gateway, listOf(profile.id)), null, profile.gatewayReachable,
            R.string.check_fix_gateway.takeIf { !profile.gatewayReachable },
        ))
    }
    status.urls?.let {
        add(CheckLine(
            CheckText(R.string.check_urls), CheckText(R.string.check_urls_counts, listOf(it.lan, it.tailscale, it.publicUrls)), null, null,
        ))
    }
}

/** "Check connection" of one PC, from the bridge's own GET /v1/status. */
@Composable
fun PcCheckScreen(vm: PcCheckViewModel, targetName: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    PcCheckContent(state, targetName, onBack, vm::refresh)
}

@Composable
internal fun PcCheckContent(state: PcCheckState, targetName: String, onBack: () -> Unit, onRetry: () -> Unit) {
    val c = Rasul.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        DesktopHeader(R.string.check_title, targetName, onBack, onRetry.takeIf { !state.direct })
        val status = state.status
        when {
            state.direct -> DesktopMessage(stringResource(R.string.check_needs_bridge))
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = c.accent)
                    Text(stringResource(R.string.check_loading), style = RasulType.body, color = c.muted)
                }
            }
            status == null -> DesktopMessage(stringResource((state.error ?: DesktopError.DesktopFailed).message), onRetry)
            else -> {
                val lines = checkLines(status)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
                    InsetGroup {
                        lines.forEachIndexed { index, line -> CheckRow(line, last = index == lines.lastIndex) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckText.text(): String = stringResource(res, *args.toTypedArray())

@Composable
private fun CheckRow(line: CheckLine, last: Boolean) {
    val c = Rasul.colors
    RasulRow(
        last = last, hairlineStart = 16.dp, hairlineEnd = 16.dp, gap = 12.dp, textSpacing = 3.dp,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
        leading = {
            when (line.ok) {
                true -> Icon(RasulIcons.Check, null, tint = c.successText, modifier = Modifier.size(20.dp))
                false -> Icon(RasulIcons.X, null, tint = c.danger, modifier = Modifier.size(20.dp))
                null -> Icon(RasulIcons.Info, null, tint = c.muted, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        RowTitle(line.title.text(), maxLines = 2)
        line.detail?.let { RowSub(it.text()) }
        line.advice?.let { Text(stringResource(it), style = RasulType.bodySmall, color = c.dangerText) }
    }
}

// ---------- previews ----------

private val okStatus = BridgeStatusDto(
    bridgeVersion = "0.2.6", hermesHomeFound = true,
    profiles = listOf(StatusProfileDto("default", true, true), StatusProfileDto("research", true, true)),
    hermes = StatusHermesDto("0.22.0", 0), dashboardFound = true, dashboardCheck = "ok", approvalsListener = true,
    urls = StatusUrlsDto(1, 1, 0),
)

private val problemStatus = okStatus.copy(
    profiles = listOf(StatusProfileDto("default", true, true), StatusProfileDto("research", false, false)),
    dashboardFound = false, dashboardCheck = "timeout", approvalsListener = false,
)

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "PcCheckOk")
@Composable
private fun PcCheckOkEn() = RasulTheme(false) { PcCheckContent(PcCheckState(status = okStatus), "My PC", {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "PcCheckOk")
@Composable
private fun PcCheckOkAr() = RasulTheme(false) { PcCheckContent(PcCheckState(status = okStatus), "My PC", {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "PcCheckProblems")
@Composable
private fun PcCheckProblemsEn() = RasulTheme(false) { PcCheckContent(PcCheckState(status = problemStatus), "My PC", {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "PcCheckProblems")
@Composable
private fun PcCheckProblemsAr() = RasulTheme(false) { PcCheckContent(PcCheckState(status = problemStatus), "My PC", {}, {}) }
