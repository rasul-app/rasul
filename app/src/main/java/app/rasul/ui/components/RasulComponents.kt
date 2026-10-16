package app.rasul.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulType

// ---------- avatar ----------

enum class AvatarShape { Circle, Square, Pill }

data class AvatarStyle(val shape: AvatarShape, val color: Color)

/** Stable look per bot: crimson circle, violet square, or tan pill, chosen by a hash of the key. */
@Composable
fun avatarStyleFor(key: String): AvatarStyle {
    val c = Rasul.colors
    return when (Math.floorMod(key.hashCode(), 3)) {
        0 -> AvatarStyle(AvatarShape.Circle, c.avatarCrimson)
        1 -> AvatarStyle(AvatarShape.Square, c.avatarViolet)
        else -> AvatarStyle(AvatarShape.Pill, c.avatarTan)
    }
}

/** Bot avatar with white eyes and an optional blue working badge. */
@Composable
fun BotAvatar(style: AvatarStyle, size: Dp = 48.dp, working: Boolean = false, modifier: Modifier = Modifier) {
    val accent = Rasul.colors.accent
    Box(modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val s = this.size.width / 48f
            val h = if (style.shape == AvatarShape.Pill) 29.76f * s else 48f * s
            val top = if (style.shape == AvatarShape.Pill) 9.12f * s else 0f
            val r = when (style.shape) {
                AvatarShape.Circle -> 24f * s
                AvatarShape.Square -> 12.48f * s
                AvatarShape.Pill -> 14.88f * s
            }
            drawRoundRect(style.color, Offset(0f, top), Size(48f * s, h), CornerRadius(r))
            val eyeY = top + (if (style.shape == AvatarShape.Pill) 8.064f else 18f) * s
            for (cx in listOf(18.24f, 29.76f)) {
                drawOval(Color.White, Offset((cx - 2.4f) * s, eyeY - 3.6f * s), Size(4.8f * s, 7.2f * s))
            }
            if (working) {
                val bs = 20.16f * s
                val o = Offset(29.84f * s, 29.84f * s)
                drawCircle(Color.White, bs / 2, o + Offset(bs / 2, bs / 2))
                drawCircle(accent, bs / 2 - 1f * s, o + Offset(bs / 2, bs / 2))
                for (dx in listOf(5.08f, 10.08f, 15.08f)) {
                    drawCircle(Color.White, 1.5f * s, o + Offset(dx * s, 10.08f * s))
                }
            }
        }
    }
}

// ---------- header, glass pill ----------

@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = RasulType.title, color = Rasul.colors.ink, modifier = Modifier.weight(1f, fill = false))
        if (actions != null) GlassPill(content = actions)
    }
}

/**
 * Glass pill: 38 dp high, 1 dp border, soft shadow, 14 dp side padding, and spaced icons.
 * Children carry their own 48 dp touch target; the capsule is drawn 5 dp inside the 48 dp box so its size stays 38.
 */
@Composable
fun GlassPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Rasul.colors
    Box(modifier.heightIn(min = 48.dp)) {
        Box(
            Modifier.matchParentSize().padding(vertical = 5.dp)
                .shadow(8.dp, RasulShapes.glassPill, ambientColor = Color(0x1F000000), spotColor = Color(0x1F000000))
                .background(c.bg, RasulShapes.glassPill)
                .border(1.dp, c.cardBorder, RasulShapes.glassPill),
        )
        Row(
            Modifier.padding(horizontal = 14.dp).align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun GlassIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Icon(
        icon, description, tint = Rasul.colors.ink,
        modifier = Modifier.minimumInteractiveComponentSize().size(22.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
    )
}

// ---------- list rows ----------

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Rasul.colors.hairline))
}

/**
 * Row: 14 dp gap, [leading] + text column + [trailing], then an inset hairline. [hairlineStart] is
 * where the line begins (82 for 48 dp avatars, 74 for 40 dp). [last] drops the line. A non-null [selected]
 * makes the row a radio-style choice for screen readers.
 */
@Composable
fun RasulRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    hairlineStart: Dp = 74.dp,
    last: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    selected: Boolean? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    text: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(
                    when {
                        onClick == null -> Modifier
                        selected != null -> Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)
                        else -> Modifier.clickable(role = Role.Button, onClick = onClick)
                    },
                )
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), content = text)
            trailing?.invoke()
        }
        if (!last) Hairline(Modifier.padding(start = hairlineStart, end = 16.dp))
    }
}

@Composable
fun RowTitle(text: String, color: Color = Rasul.colors.ink) =
    Text(text, style = RasulType.rowTitle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
fun RowSub(text: String, color: Color = Rasul.colors.muted, maxLines: Int = 2) =
    Text(text, style = RasulType.rowSub, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

// ---------- chips, buttons, toggle ----------

enum class ChipKind { Running, Done, Failed, Neutral }

@Composable
fun StatusChip(text: String, kind: ChipKind) {
    val c = Rasul.colors
    val (bg, fg) = when (kind) {
        ChipKind.Running -> c.accentSoft to c.accent
        ChipKind.Done -> c.successSoft to c.successText
        ChipKind.Failed -> c.dangerSoft to c.danger
        ChipKind.Neutral -> c.fill to c.muted
    }
    Text(
        text, style = RasulType.chip, color = fg,
        modifier = Modifier.background(bg, RasulShapes.chip).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val c = Rasul.colors
    Row(
        modifier.clip(RasulShapes.button).background(if (enabled) c.accent else c.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
        Text(text, style = RasulType.button, color = c.onAccent)
    }
}

/** Outlined button with an accent border and rounded corners. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Rasul.colors
    val col = if (enabled) c.accent else c.accent.copy(alpha = 0.4f)
    Box(
        modifier.clip(RasulShapes.button).border(BorderStroke(1.5.dp, col), RasulShapes.button)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = RasulType.button, color = col) }
}

/** Small outlined pill button for job actions. */
@Composable
fun SmallOutlineButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Rasul.colors
    val col = if (enabled) c.accent else c.accent.copy(alpha = 0.4f)
    Box(
        Modifier.minimumInteractiveComponentSize().clip(RasulShapes.filter)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = RasulType.buttonSmall, color = col,
            modifier = Modifier.border(1.dp, col, RasulShapes.filter).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** Toggle switch. [label] is announced by screen readers (e.g. "Pause Backup check"). */
@Composable
fun RasulToggle(checked: Boolean, label: String, enabled: Boolean = true, onCheckedChange: ((Boolean) -> Unit)?) {
    val c = Rasul.colors
    Box(
        Modifier.minimumInteractiveComponentSize()
            .toggleable(
                checked,
                enabled = enabled && onCheckedChange != null,
                role = Role.Switch,
                onValueChange = { onCheckedChange?.invoke(it) },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(51.dp).height(31.dp).clip(RoundedCornerShape(15.5.dp))
                .background(if (checked) c.success else c.toggleOff).padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(27.dp).background(Color.White, CircleShape)) }
    }
}

/** Segmented two-option switch for choosing between values. */
@Composable
fun <T> SegmentedSwitch(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = Rasul.colors
    Row(
        Modifier.background(c.fill, RasulShapes.filter).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (v, label) ->
            val sel = v == selected
            Box(
                Modifier.minimumInteractiveComponentSize().selectable(sel, role = Role.RadioButton) { onSelect(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = RasulType.sectionLabel.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (sel) c.ink else c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(13.dp)).background(if (sel) c.bg else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Filter chip that fills when selected. */
@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Rasul.colors
    Box(
        Modifier.minimumInteractiveComponentSize().selectable(selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = RasulType.chip.copy(fontSize = 13.sp),
            color = if (selected) c.bg else c.ink,
            modifier = Modifier.clip(RasulShapes.filter).background(if (selected) c.ink else c.fill)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

// ---------- PC switcher ----------

/** Connection switcher pill with PC icon, name, and chevron. */
@Composable
fun PcSwitcher(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Rasul.colors
    Row(
        modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
    ) {
        Box(Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = onClick)) {
        Row(
            Modifier.clip(RasulShapes.filter).background(c.fill).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(RasulIcons.Pc, null, tint = c.ink, modifier = Modifier.size(16.dp))
            Text(name, style = RasulType.pill, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(RasulIcons.ChevDown, null, tint = c.muted, modifier = Modifier.size(14.dp))
        }
        }
    }
}

// ---------- tab bar ----------

data class RasulTab(val route: String, val label: String, val icon: ImageVector, val badge: Int = 0)

/** Floating tab bar with a capsule shape and a filled selected tab. */
@Composable
fun RasulTabBar(tabs: List<RasulTab>, selectedRoute: String?, onSelect: (RasulTab) -> Unit, modifier: Modifier = Modifier) {
    val c = Rasul.colors
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .shadow(8.dp, RasulShapes.tabBar, ambientColor = Color(0x1F000000), spotColor = Color(0x1F000000))
            .background(c.bg, RasulShapes.tabBar).border(1.dp, c.cardBorder, RasulShapes.tabBar)
            .padding(6.dp),
    ) {
        tabs.forEach { t ->
            val sel = t.route == selectedRoute
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RasulShapes.tabSelected)
                    .background(if (sel) c.selected else Color.Transparent)
                    .selectable(sel, role = Role.Tab) { onSelect(t) }.padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.size(24.dp)) {
                    Icon(t.icon, null, tint = if (sel) c.accent else c.ink, modifier = Modifier.size(24.dp))
                    if (t.badge > 0) {
                        Text(
                            t.badge.toString(),
                            style = TextStyleBadge, color = Color.White, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-6).dp)
                                .background(c.badge, RoundedCornerShape(10.dp)).padding(horizontal = 4.dp),
                        )
                    }
                }
                Text(t.label, style = RasulType.tab, color = if (sel) c.accent else c.ink, maxLines = 1)
            }
        }
    }
}

private val TextStyleBadge = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)

// ---------- dialog, sheet ----------

/** Confirmation dialog with title, body, and two equally sized actions. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    cancelText: String,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
) {
    val c = Rasul.colors
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.fillMaxWidth().background(c.bg, RasulShapes.dialog).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = RasulType.dialogTitle, color = c.ink)
            Text(body, style = RasulType.body, color = c.muted)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RasulShapes.dialog).background(c.fill)
                        .clickable(role = Role.Button, onClick = onCancel).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(cancelText, style = RasulType.button, color = c.ink) }
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RasulShapes.dialog)
                        .background(if (destructive) c.danger else c.accent)
                        .clickable(role = Role.Button, onClick = onConfirm).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(confirmText, style = RasulType.button, color = c.onAccent) }
            }
        }
    }
}

/** Simple one-button message dialog in the same style. */
@Composable
fun NoticeDialog(text: String, okText: String, onDismiss: () -> Unit) {
    val c = Rasul.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(c.bg, RasulShapes.dialog).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, style = RasulType.body, color = c.ink)
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp).clip(RasulShapes.dialog).background(c.accent)
                    .clickable(role = Role.Button, onClick = onDismiss).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(okText, style = RasulType.button, color = c.onAccent) }
        }
    }
}

/** Language sheet shell with a grabber and title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RasulSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Rasul.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RasulShapes.sheet,
        containerColor = c.bg,
        scrimColor = Color(0x66000000),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 12.dp).width(36.dp).height(5.dp).background(c.hairline, RoundedCornerShape(3.dp)))
        },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 34.dp)) {
            Text(title, style = RasulType.sheetTitle, color = c.ink, modifier = Modifier.padding(bottom = 6.dp))
            content()
        }
    }
}

/** Rounded fill group for rows in More. */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RasulShapes.card).background(Rasul.colors.fill), content = content)
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, style = RasulType.sectionLabel, color = Rasul.colors.muted,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** Empty or message state. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    avatar: AvatarStyle? = null,
    action: (@Composable () -> Unit)? = null,
    footnote: String? = null,
) {
    val c = Rasul.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (avatar != null) BotAvatar(avatar, size = 128.dp)
        Text(title, style = RasulType.emptyTitle, color = c.ink, textAlign = TextAlign.Center)
        Text(body, style = RasulType.body, color = c.muted, textAlign = TextAlign.Center)
        action?.invoke()
        if (footnote != null) Text(footnote, style = RasulType.bodySmall, color = c.muted, textAlign = TextAlign.Center)
    }
}

/** Form navigation bar with leading and trailing text actions. */
@Composable
fun RasulNavBar(title: String, leading: String, onLeading: () -> Unit, trailing: String? = null, onTrailing: () -> Unit = {}, trailingEnabled: Boolean = true) {
    val c = Rasul.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(80.dp)) {
            Text(leading, style = RasulType.navAction, color = c.accent, modifier = Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = onLeading))
        }
        Text(title, style = RasulType.navBar, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.width(80.dp), contentAlignment = Alignment.CenterEnd) {
            if (trailing != null) {
                Text(
                    trailing, style = RasulType.navBar, color = if (trailingEnabled) c.accent else c.muted,
                    modifier = Modifier.minimumInteractiveComponentSize().clickable(enabled = trailingEnabled, role = Role.Button, onClick = onTrailing),
                )
            }
        }
    }
}

/** Text label for a GlassPill without an icon (Refresh on Sessions and Jobs). */
@Composable
fun GlassText(text: String, onClick: () -> Unit) {
    Text(text, style = RasulType.pill, color = Rasul.colors.accent, modifier = Modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable(role = Role.Button, onClick = onClick))
}
