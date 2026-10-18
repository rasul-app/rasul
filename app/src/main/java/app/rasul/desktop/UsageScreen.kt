package app.rasul.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.bridge.DesktopError
import app.rasul.bridge.UsageDayDto
import app.rasul.bridge.UsageTotalsDto
import app.rasul.data.isoText
import app.rasul.ui.FeatureErrorSurface
import app.rasul.ui.text
import app.rasul.ui.components.InsetGroup
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.SectionLabel
import app.rasul.ui.components.SegmentedSwitch
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlin.math.roundToInt

/** Per-PC usage: token chart from the desktop route, rate-limit windows from /api/model/options. */
@Composable
fun UsageScreen(vm: UsageViewModel, targetName: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    UsageContent(state, targetName, onBack, vm::refresh, vm::setDays)
}

@Composable
internal fun UsageContent(
    state: UsageState,
    targetName: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDays: (Int) -> Unit,
) {
    val c = Rasul.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        DesktopHeader(R.string.usage_title, targetName, onBack, onRetry.takeIf { !state.direct })
        when {
            state.direct -> DesktopMessage(stringResource(R.string.usage_needs_bridge))
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = c.accent)
                    Text(stringResource(R.string.usage_loading), style = RasulType.body, color = c.muted)
                }
            }
            state.error != null -> DesktopMessage(stringResource(state.error.message), onRetry)
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                    SegmentedSwitch(
                        options = UsageState.PERIODS.map { it to pluralStringResource(R.plurals.usage_days, it, it) },
                        selected = state.days, onSelect = onDays,
                    )
                }
                val daily = state.report?.daily.orEmpty()
                InsetGroup(contentPadding = PaddingValues(vertical = 16.dp)) {
                    if (daily.isEmpty() || daily.all { (it.inputTokens ?: 0.0) == 0.0 && (it.outputTokens ?: 0.0) == 0.0 }) {
                        Text(
                            stringResource(R.string.usage_empty), style = RasulType.body, color = c.muted,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    } else {
                        UsageBarChart(daily)
                        ChartLegend()
                    }
                }
                state.report?.totals?.let { totals ->
                    SectionLabel(stringResource(R.string.usage_totals), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                    TotalsGroup(totals)
                }
                SectionLabel(stringResource(R.string.usage_rate_limits), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                RateLimitsGroup(state, onRetry)
            }
        }
    }
}

/** Daily bars in reading order: input (accent) first, output second; the x-axis mirrors under RTL like the legend row. */
@Composable
internal fun UsageBarChart(daily: List<UsageDayDto>) {
    val c = Rasul.colors
    val desc = stringResource(R.string.usage_chart_desc)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 16.dp)
            .semantics { contentDescription = desc },
    ) {
        val max = daily.maxOf { maxOf(it.inputTokens ?: 0.0, it.outputTokens ?: 0.0) }
        if (max <= 0.0) return@Canvas
        val slot = size.width / daily.size
        val bar = (slot * 0.30f).coerceIn(2f, 24f)
        daily.forEachIndexed { i, day ->
            val center = i * slot + slot / 2
            listOf(day.inputTokens to c.accent, day.outputTokens to c.muted).forEachIndexed { j, pair ->
                val value = pair.first ?: 0.0
                val height = (value / max * size.height).toFloat()
                if (height > 0f) {
                    val x = center + (j - 0.5f) * (bar + 4f) - bar / 2
                    drawRoundRect(
                        pair.second,
                        Offset(if (rtl) size.width - x - bar else x, size.height - height),
                        Size(bar, height), CornerRadius(6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartLegend() {
    val c = Rasul.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(c.accent to R.string.usage_input, c.muted to R.string.usage_output).forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Text(stringResource(label), style = RasulType.meta, color = c.muted)
            }
        }
    }
}

@Composable
private fun TotalsGroup(totals: UsageTotalsDto) {
    val rows = listOfNotNull(
        totals.inputTokens?.let { R.string.usage_input to countText(it) },
        totals.outputTokens?.let { R.string.usage_output to countText(it) },
        totals.cacheReadTokens?.let { R.string.usage_cache to countText(it) },
        totals.reasoningTokens?.let { R.string.usage_reasoning to countText(it) },
        totals.sessions?.let { R.string.usage_sessions to countText(it) },
        totals.apiCalls?.let { R.string.usage_api_calls to countText(it) },
        totals.estimatedCost?.let { R.string.usage_estimated_cost to costText(it) },
    )
    InsetGroup(contentPadding = PaddingValues(vertical = 16.dp)) {
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.usage_empty), style = RasulType.body, color = Rasul.colors.muted,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        } else rows.forEachIndexed { index, (label, value) ->
            RasulRow(
                last = index == rows.lastIndex, hairlineStart = 20.dp, hairlineEnd = 20.dp, minHeight = 40.dp,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                trailing = { Text(value, style = RasulType.rowTitle, color = Rasul.colors.ink) },
            ) { RowTitle(stringResource(label), Rasul.colors.muted) }
        }
    }
}

@Composable
private fun RateLimitsGroup(state: UsageState, onRetry: () -> Unit) {
    val c = Rasul.colors
    InsetGroup(contentPadding = PaddingValues(vertical = 16.dp)) {
        when {
            state.optionsError != null -> Column(
                Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.optionsError.text(FeatureErrorSurface.ModelOptions), style = RasulType.body, color = c.dangerText)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
            }
            state.windows.isEmpty() && state.limited.isEmpty() -> Text(
                stringResource(R.string.usage_rate_limits_empty), style = RasulType.body, color = c.muted,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                state.limited.forEach { limit ->
                    Text(
                        stringResource(R.string.usage_limited_until, limit.provider, isoText(limit.until)),
                        style = RasulType.bodySmall, color = c.warnText,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                state.windows.forEach { window -> RateWindowRow(window) }
            }
        }
    }
}

@Composable
private fun RateWindowRow(window: RateWindow) {
    val c = Rasul.colors
    val percent = window.usedPercent.coerceIn(0.0, 100.0)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(window.label, style = RasulType.body, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                stringResource(R.string.usage_window_percent, percent.roundToInt()),
                style = RasulType.meta, color = c.muted,
            )
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.fill)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth((percent / 100).toFloat())
                    .background(if (percent >= 90) c.warn else c.accent, RoundedCornerShape(4.dp)),
            )
        }
        window.resetsAt?.let {
            Text(stringResource(R.string.usage_window_reset, isoText(it)), style = RasulType.meta, color = c.muted)
        }
    }
}

/** Compact token counts: 1234567 -> "1.2M"; null -> em dash (aggregate reported no value). */
internal fun countText(v: Double): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000)
    v >= 10_000 -> "%.0fK".format(v / 1_000)
    v >= 1_000 -> "%.1fK".format(v / 1_000)
    else -> v.toLong().toString()
}

internal fun costText(v: Double): String = "$%.2f".format(v)

private val previewDaily = (0..6).map { i ->
    UsageDayDto(
        day = "2025-01-0${i + 1}", inputTokens = listOf(40_000.0, 62_000.0, 28_000.0, 90_000.0, 55_000.0, 71_000.0, 36_000.0)[i],
        outputTokens = listOf(8_000.0, 15_000.0, 6_000.0, 22_000.0, 11_000.0, 19_000.0, 9_000.0)[i],
    )
}

private val previewState = UsageState(
    days = 7, loaded = true,
    report = app.rasul.bridge.UsageReportDto(
        daily = previewDaily,
        totals = UsageTotalsDto(382_000.0, 90_000.0, 120_000.0, 4_000.0, 12.5, 6.0, 41.0),
    ),
    windows = listOf(
        RateWindow("5h", 62.0, "2025-01-08T15:00:00+00:00"),
        RateWindow("Work account · Weekly", 24.0, "2025-01-12T00:00:00+00:00"),
    ),
    limited = listOf(LimitedProvider("Provider B", "2025-01-08T18:30:00+00:00")),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 800, name = "Usage")
@Composable
private fun UsageEn() = RasulTheme(false) { UsageContent(previewState, "My PC", {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 800, locale = "ar", name = "Usage")
@Composable
private fun UsageAr() = RasulTheme(false) { UsageContent(previewState, "My PC", {}, {}, {}) }
