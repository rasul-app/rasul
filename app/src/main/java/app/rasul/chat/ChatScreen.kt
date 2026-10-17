package app.rasul.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rasul.R
import app.rasul.data.ApprovalChoice
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.ModelOptionsDto
import app.rasul.data.RunModelSelection
import app.rasul.data.RunsClient
import app.rasul.data.TokenUsageSummary
import app.rasul.ui.components.BotAvatar
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.avatarStyleFor
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import app.rasul.ui.theme.RasulMonoFontFamily
import kotlinx.coroutines.delay
import java.util.Locale

private val ChatBubbleShape = RoundedCornerShape(18.dp)
private val ChatCardShape = RoundedCornerShape(18.dp)
private val ChatCodeShape = RoundedCornerShape(12.dp)
private val ChatChipShape = RoundedCornerShape(8.dp)

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    title: String,
    onBack: () -> Unit,
    runs: RunsClient,
    modelOptionsLoader: suspend (Boolean) -> HermesResult<ModelOptionsDto>,
    onInstructions: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = androidx.compose.runtime.remember { context.getSharedPreferences("notification_permission", android.content.Context.MODE_PRIVATE) }
    var rationale by androidx.compose.runtime.remember { mutableStateOf(false) }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    if (rationale) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rationale = false },
            text = { Text(stringResource(R.string.run_permission_rationale)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                rationale = false
                if (android.os.Build.VERSION.SDK_INT >= 33) request.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }) { Text(stringResource(R.string.run_permission_allow)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { rationale = false }) { Text(stringResource(R.string.run_permission_later)) } },
        )
    }
    val usageBySession by runs.usageBySession.collectAsState()
    var selectedModel by remember(state.sessionId) {
        mutableStateOf(state.sessionId?.let(runs::selectedModelForChat))
    }
    ChatScreenContent(
        state = state,
        title = title,
        onBack = onBack,
        onInstructions = onInstructions,
        onDraft = vm::onDraft,
        onSend = {
            if (android.os.Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("asked", false) && state.draft.isNotBlank()) {
                preferences.edit().putBoolean("asked", true).apply()
                rationale = true
            }
            vm.send()
        },
        onStop = vm::stop,
        onReconnect = vm::reconnect,
        onRefresh = vm::refresh,
        onAnswerApproval = vm::answerApproval,
        onRetryHistory = vm::retryHistory,
        modelOptionsLoader = modelOptionsLoader,
        selectedModel = selectedModel,
        tokenUsage = state.sessionId?.let(usageBySession::get),
        onSelectModel = { selection ->
            val current = vm.state.value
            val session = current.sessionId
            if (session != null && session == state.sessionId && current.canChooseModel) {
                runs.selectModelForChat(session, selection)
                selectedModel = selection
            }
        },
    )
}

@Composable
internal fun ChatScreenContent(
    state: ChatState,
    title: String,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onRefresh: () -> Unit,
    onAnswerApproval: (String?, ApprovalChoice) -> Unit,
    onRetryHistory: () -> Unit,
    modelOptionsLoader: suspend (Boolean) -> HermesResult<ModelOptionsDto>,
    selectedModel: RunModelSelection?,
    tokenUsage: TokenUsageSummary?,
    onSelectModel: (RunModelSelection?) -> Unit,
    onInstructions: () -> Unit = {},
) {
    val colors = Rasul.colors
    var hiddenFor by remember { mutableStateOf<ApprovalUi?>(null) }
    var modelPickerOpen by remember(state.sessionId) { mutableStateOf(false) }
    var tokenUsageOpen by remember { mutableStateOf(false) }
    var modelRequestVersion by remember(state.sessionId) { mutableIntStateOf(0) }
    var refreshModelOptions by remember(state.sessionId) { mutableStateOf(false) }
    var modelPickerState by remember(state.sessionId) { mutableStateOf<ModelPickerUiState>(ModelPickerUiState.Loading) }
    val modelPickerEnabled = state.canChooseModel
    LaunchedEffect(modelPickerOpen, modelRequestVersion, state.sessionId) {
        if (modelPickerOpen && modelPickerEnabled) {
            val shouldRefresh = refreshModelOptions
            val loadOptions = modelOptionsLoader
            refreshModelOptions = false
            modelPickerState = ModelPickerUiState.Loading
            modelPickerState = when (val result = loadOptions(shouldRefresh)) {
                is HermesResult.Err -> ModelPickerUiState.Error(result.error)
                is HermesResult.Ok -> ModelPickerUiState.Ready(result.value)
            }
        }
    }
    val approval = state.approval
    val reviewHidden = approval != null && hiddenFor == approval

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        ChatTopBar(
            title = title,
            active = state.run != null,
            selectedModel = selectedModel?.model,
            modelPickerEnabled = modelPickerEnabled,
            onBack = onBack,
            onChooseModel = { modelPickerOpen = true },
            onShowUsage = { tokenUsageOpen = true },
            onInstructions = onInstructions,
        )
        when (val load = state.load) {
            ChatLoad.Loading -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.accent)
            }
            is ChatLoad.Failed -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    failureText(load.failure),
                    color = MaterialTheme.colorScheme.error,
                    style = RasulType.body,
                    modifier = Modifier.padding(24.dp),
                )
            }
            ChatLoad.Ready -> Column(Modifier.weight(1f).fillMaxWidth()) {
                state.historyFailure?.let { failure ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            failureText(failure),
                            modifier = Modifier.weight(1f),
                            color = colors.dangerText,
                            style = RasulType.bodySmall,
                        )
                        TextButton(onClick = onRetryHistory) {
                            Text(stringResource(R.string.retry), style = RasulType.buttonSmall)
                        }
                    }
                }
                MessageList(state.messages, Modifier.weight(1f))
                state.run?.let { run ->
                    RunCard(
                        run = run,
                        onStop = onStop,
                        onReconnect = onReconnect,
                        onRefresh = onRefresh,
                        onReview = if (approval != null && reviewHidden) ({ hiddenFor = null }) else null,
                    )
                }
            }
        }
        if (state.load == ChatLoad.Ready) {
            Composer(
                draft = state.draft,
                onDraft = onDraft,
                onSend = onSend,
                sending = state.run != null && state.run?.runId == null,
                steering = state.run?.runId != null,
                modelName = selectedModel?.model ?: stringResource(R.string.ux3b_model_server_default),
                modelPickerEnabled = modelPickerEnabled,
                onChooseModel = { modelPickerOpen = true },
            )
        }
    }

    if (modelPickerOpen && modelPickerEnabled) {
        ModelPickerSheet(
            state = modelPickerState,
            selectedModel = selectedModel,
            onDismiss = { modelPickerOpen = false },
            onChoose = { model ->
                if (state.canChooseModel) {
                    onSelectModel(model)
                    modelPickerOpen = false
                }
            },
            onRefresh = {
                refreshModelOptions = true
                modelRequestVersion += 1
            },
        )
    }
    if (tokenUsageOpen) TokenUsageDialog(tokenUsage, onDismiss = { tokenUsageOpen = false })

    approval?.let { pending ->
        // Outside tap or Back hides this sheet; swipe cannot hide it. Review on the card opens it again.
        if (!reviewHidden) {
            ApprovalSheet(
                approval = pending,
                busy = (pending.requestId ?: "") in state.answering,
                onOnce = { onAnswerApproval(pending.requestId, ApprovalChoice.Once) },
                onDeny = { onAnswerApproval(pending.requestId, ApprovalChoice.Deny) },
                onHide = { hiddenFor = pending },
            )
        }
    }
}

@Composable
internal fun ChatTopBar(
    title: String,
    active: Boolean,
    selectedModel: String?,
    modelPickerEnabled: Boolean,
    onBack: () -> Unit,
    onChooseModel: () -> Unit,
    onShowUsage: () -> Unit,
    onInstructions: () -> Unit = {},
) {
    val colors = Rasul.colors
    val backIcon = RasulIcons.Back
    val headerName = title.ifBlank { stringResource(R.string.instructions_title) }
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                backIcon,
                contentDescription = stringResource(R.string.back),
                tint = colors.accent,
                modifier = Modifier.size(26.dp).testTag(backIcon.name),
            )
        }
        BotAvatar(
            style = avatarStyleFor(title),
            size = 38.dp,
            working = active,
        )
        Column(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .semantics { contentDescription = headerName }
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onInstructions),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Text(
                title,
                style = RasulType.rowTitle.copy(fontSize = 17.sp),
                color = colors.ink,
                maxLines = 1,
            )
            if (active) {
                Text(
                    stringResource(R.string.chat_working),
                    style = RasulType.meta,
                    color = colors.accent,
                    maxLines = 1,
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    RasulIcons.Dots,
                    contentDescription = stringResource(R.string.ux3b_chat_options),
                    tint = colors.ink,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.ux3b_model_picker_menu))
                            Text(
                                selectedModel ?: stringResource(R.string.ux3b_model_server_default),
                                style = RasulType.meta,
                                color = colors.muted,
                                maxLines = 1,
                            )
                        }
                    },
                    enabled = modelPickerEnabled,
                    onClick = {
                        menuExpanded = false
                        onChooseModel()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ux3b_token_usage_menu)) },
                    onClick = {
                        menuExpanded = false
                        onShowUsage()
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (messages.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.chat_empty),
                        style = RasulType.body,
                        color = Rasul.colors.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
        items(messages, key = { it.id }) { MessageBubble(it) }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val colors = Rasul.colors
    val mine = message.kind == MessageKind.User || message.kind == MessageKind.UserSteer
    val align = if (mine) Alignment.End else Alignment.Start
    val lineHeight = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 24.sp else 21.sp

    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        when (message.kind) {
            MessageKind.Error -> Text(
                message.error?.let { failureText(it) }.orEmpty(),
                color = colors.danger,
                style = RasulType.body.copy(lineHeight = lineHeight),
            )
            MessageKind.Info -> Text(
                when (message.text) {
                    ChatViewModel.INFO_STOPPED -> stringResource(R.string.chat_stopped)
                    ChatViewModel.INFO_REPLAY_TRUNCATED -> stringResource(R.string.chat_replay_truncated)
                    ChatViewModel.INFO_APPROVAL_GONE -> stringResource(R.string.chat_approval_gone)
                    ChatViewModel.INFO_STEER_RETURNED -> stringResource(R.string.chat_steer_returned)
                    else -> message.text
                },
                style = RasulType.bodySmall,
                color = colors.muted,
            )
            else -> Surface(
                modifier = Modifier.widthIn(max = 278.dp),
                shape = ChatBubbleShape,
                color = if (mine) colors.accent else colors.fill,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.kind == MessageKind.UserSteer) {
                        Text(
                            stringResource(R.string.chat_steered),
                            style = RasulType.chip,
                            color = colors.onAccent,
                        )
                    }
                    Text(
                        message.text,
                        style = RasulType.body.copy(lineHeight = lineHeight),
                        color = if (mine) colors.onAccent else colors.ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunCard(
    run: ActiveRun,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onRefresh: () -> Unit,
    onReview: (() -> Unit)?,
) {
    val colors = Rasul.colors
    var elapsed by remember(run.startedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(run.startedAtMs) {
        while (true) {
            elapsed = ((System.currentTimeMillis() - run.startedAtMs) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    val activeStep = when (run.status) {
        StatusLabel.Starting, StatusLabel.Thinking, StatusLabel.Writing, StatusLabel.Helper -> 0
        is StatusLabel.Tool -> 1
        StatusLabel.WaitingApproval -> 2
        StatusLabel.Stopping -> 0
        is StatusLabel.Reconnecting, StatusLabel.Lost, StatusLabel.LiveUpdatesUnavailable -> 1
    }
    val toolName = (run.status as? StatusLabel.Tool)?.name

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = ChatCardShape,
        color = colors.bg,
        contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.cardBorder),
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (run.status == StatusLabel.Lost) ConnectionLostBanner(onReconnect)
            if (run.status == StatusLabel.LiveUpdatesUnavailable) {
                Text(
                    statusText(run.label),
                    style = RasulType.bodySmall,
                    color = colors.muted,
                )
            }
            ProgressHeader(run, elapsed)
            ProgressStep(
                label = stringResource(R.string.chat_step_thinking),
                index = 0,
                activeStep = activeStep,
            )
            ProgressStep(
                label = stringResource(R.string.chat_step_running_tool),
                index = 1,
                activeStep = activeStep,
                chip = toolName,
            )
            ProgressStep(
                label = stringResource(R.string.chat_step_approval),
                index = 2,
                activeStep = activeStep,
                approvalStep = true,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = run.runId != null && !run.stopRequested,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = ChatBubbleShape,
                    border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.35f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.danger),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp).background(colors.danger, RoundedCornerShape(2.dp)),
                        )
                        Text(stringResource(R.string.chat_stop), style = RasulType.buttonSmall)
                    }
                }
                if (run.status == StatusLabel.LiveUpdatesUnavailable) {
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.chat_refresh), style = RasulType.buttonSmall)
                    }
                }
                if (onReview != null) {
                    PrimaryButton(
                        text = stringResource(R.string.approval_review),
                        onClick = onReview,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

// Code, commands, paths and monospace text always lay out left-to-right, even in the Arabic UI.
internal fun codeTextStyle(base: TextStyle): TextStyle = base.copy(textDirection = TextDirection.Ltr)

@Composable
private fun CodeText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(text, modifier = modifier, style = codeTextStyle(style), color = color)
    }
}

@Composable
private fun ProgressHeader(run: ActiveRun, elapsed: Long) {
    val colors = Rasul.colors
    val waiting = run.status == StatusLabel.WaitingApproval
    val lost = run.status == StatusLabel.Lost
    val headline = when {
        run.stopRequested -> statusText(run.label)
        run.status is StatusLabel.Tool || lost -> stringResource(R.string.chat_step_running_tool)
        else -> statusText(run.label)
    }
    val headlineColor = when {
        waiting -> colors.warn
        lost -> colors.muted
        else -> colors.accent
    }
    val badgeColor = when {
        waiting -> colors.warn.copy(alpha = 0.12f)
        lost -> colors.fill
        else -> colors.accentSoft
    }
    val timeText = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp).background(badgeColor, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (waiting) RasulIcons.Pause else RasulIcons.Clock,
                contentDescription = null,
                tint = headlineColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            headline,
            modifier = Modifier.weight(1f),
            style = RasulType.rowTitle,
            color = headlineColor,
        )
        CodeText(
            timeText,
            style = RasulType.meta.copy(fontFamily = RasulMonoFontFamily, fontWeight = FontWeight.Medium),
            color = colors.muted,
        )
    }
}

@Composable
private fun ProgressStep(
    label: String,
    index: Int,
    activeStep: Int,
    chip: String? = null,
    approvalStep: Boolean = false,
) {
    val colors = Rasul.colors
    val complete = index < activeStep
    val current = index == activeStep
    val tint = when {
        complete -> colors.success
        current && approvalStep -> colors.warn
        current -> colors.accent
        else -> colors.hairline
    }
    val icon = when {
        complete -> RasulIcons.Check
        current && approvalStep -> RasulIcons.Info
        else -> RasulIcons.Clock
    }
    val textColor = if (index > activeStep) colors.muted else colors.ink

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.weight(1f), style = RasulType.sectionLabel, color = textColor)
        chip?.let {
            CodeText(
                it,
                modifier = Modifier.background(colors.fill, ChatChipShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = RasulType.chip.copy(fontFamily = RasulMonoFontFamily),
                color = colors.ink,
            )
        }
    }
}

@Composable
private fun ConnectionLostBanner(onReconnect: () -> Unit) {
    val colors = Rasul.colors
    val label = stringResource(R.string.chat_connection_lost_banner)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(colors.dangerSoft, ChatCodeShape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onReconnect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(RasulIcons.Info, contentDescription = null, tint = colors.dangerText, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.chat_connection_lost_banner),
            modifier = Modifier.weight(1f),
            style = RasulType.sectionLabel,
            color = colors.dangerText,
        )
        Icon(RasulIcons.Chev, contentDescription = null, tint = colors.dangerText, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
    steering: Boolean,
    modelName: String,
    modelPickerEnabled: Boolean,
    onChooseModel: () -> Unit,
) {
    val colors = Rasul.colors
    val sendLabel = stringResource(R.string.chat_send)
    val chipAccessibilityName = stringResource(R.string.ux3b_model_chip_accessibility, modelName)
    val unavailableLabel = stringResource(R.string.ux3b_model_selection_unavailable)
    val enabled = draft.isNotBlank() && !sending
    Column(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chipMaxWidth = minOf(280.dp, maxWidth * 0.7f)
            Row(
                Modifier.minimumInteractiveComponentSize().widthIn(max = chipMaxWidth)
                    .clip(RasulShapes.chip)
                    .background(colors.fill)
                    .clickable(enabled = modelPickerEnabled, role = Role.Button, onClick = onChooseModel)
                    .semantics(mergeDescendants = true) {
                        contentDescription = chipAccessibilityName
                        if (!modelPickerEnabled) {
                            disabled()
                            stateDescription = unavailableLabel
                        }
                    }
                    .testTag("ux3b_model_chip")
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modelName,
                    modifier = Modifier.weight(1f, fill = false)
                        .testTag("ux3b_model_chip_label"),
                    style = RasulType.chip,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(RasulIcons.ChevDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp).testTag("ux3b_model_chip_chevron"))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp).testTag("chat_message_field")
                    .background(colors.fill, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                textStyle = RasulType.body.copy(fontSize = 15.sp, color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                maxLines = 5,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text(
                                stringResource(if (steering) R.string.chat_hint_steer else R.string.chat_hint),
                                style = RasulType.body.copy(fontSize = 15.sp),
                                color = colors.muted,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Box(
                Modifier.size(48.dp)
                    .semantics { contentDescription = sendLabel }
                    .clickable(enabled = enabled, role = Role.Button, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(40.dp).background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u2191", style = RasulType.button.copy(fontSize = 22.sp), color = colors.onAccent)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(
    approval: ApprovalUi,
    busy: Boolean,
    onOnce: () -> Unit,
    onDeny: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = Rasul.colors
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = onHide,
        sheetState = sheetState,
        shape = RasulShapes.sheet,
        containerColor = colors.bg,
        scrimColor = colors.scrim,
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 5.dp).background(colors.hairline, RoundedCornerShape(3.dp)))
            }
        },
    ) {
        app.rasul.WindowHapticFeedbackProvider {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(44.dp).background(colors.warn.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(RasulIcons.Info, contentDescription = null, tint = colors.warn, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.approval_title), style = RasulType.dialogTitle, color = colors.ink)
                        approval.description?.let { Text(it, style = RasulType.rowSub, color = colors.muted) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.approval_command_label),
                        style = RasulType.chip,
                        color = colors.muted,
                    )
                    Column(
                        Modifier.fillMaxWidth().background(colors.ink, ChatCodeShape)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        CodeText(
                            approval.command,
                            style = RasulType.body.copy(fontFamily = RasulMonoFontFamily, fontSize = 13.sp),
                            color = colors.bg,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(colors.dangerSoft, ChatCodeShape).padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(RasulIcons.Info, contentDescription = null, tint = colors.danger, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.approval_warning),
                        modifier = Modifier.weight(1f),
                        style = RasulType.bodySmall.copy(lineHeight = 18.sp),
                        color = colors.dangerText,
                    )
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = stringResource(R.string.approval_once),
                        onClick = onOnce,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 51.dp),
                        enabled = !busy,
                    )
                    OutlinedButton(
                        onClick = onDeny,
                        enabled = approval.canDeny && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = ChatBubbleShape,
                        border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.danger),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.approval_deny), style = RasulType.button)
                    }
                }
                Text(
                    stringResource(R.string.approval_applies_only),
                    modifier = Modifier.fillMaxWidth(),
                    style = RasulType.bodySmall,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun statusText(status: StatusLabel): String = when (status) {
    StatusLabel.Starting -> stringResource(R.string.status_starting)
    StatusLabel.Thinking -> stringResource(R.string.status_thinking)
    StatusLabel.Writing -> stringResource(R.string.status_writing)
    is StatusLabel.Tool -> stringResource(R.string.status_tool, status.name)
    StatusLabel.Helper -> stringResource(R.string.status_helper)
    StatusLabel.WaitingApproval -> stringResource(R.string.status_waiting_approval)
    StatusLabel.Stopping -> stringResource(R.string.status_stopping)
    is StatusLabel.Reconnecting -> stringResource(R.string.status_reconnecting, status.attempt, status.max)
    StatusLabel.Lost -> stringResource(R.string.status_lost)
    StatusLabel.LiveUpdatesUnavailable -> stringResource(R.string.status_live_unavailable)
}

@Composable
internal fun failureText(failure: ChatFailure): String = when (failure) {
    is ChatFailure.Api -> when (val error = failure.error) {
        HermesError.BridgeUnreachable -> stringResource(R.string.bridge_unreachable)
        HermesError.DeviceRevoked -> stringResource(R.string.bridge_revoked)
        HermesError.DeviceKeyRepair -> stringResource(R.string.bridge_key_repair)
        HermesError.BridgeTimeout -> stringResource(R.string.bridge_timeout)
        HermesError.ProfileNotReady -> stringResource(R.string.bridge_profile_not_ready)
        HermesError.BridgeResponseInvalid -> stringResource(R.string.bridge_response_invalid)
        HermesError.Unauthorized -> stringResource(R.string.err_unauthorized)
        HermesError.NotFound -> stringResource(R.string.chat_err_run_gone)
        HermesError.ResourceNotFound -> stringResource(R.string.err_resource_not_found)
        HermesError.ConnectionChanged -> stringResource(R.string.err_connection_changed)
        is HermesError.Storage -> stringResource(R.string.connections_load_error, error.detail)
        is HermesError.ProfileUnknown -> stringResource(R.string.err_profile_unknown, error.profile)
        is HermesError.Unreachable -> stringResource(R.string.err_unreachable, error.detail)
        is HermesError.Incompatible -> stringResource(R.string.err_incompatible, error.missing.joinToString())
        is HermesError.Server -> stringResource(R.string.err_server, error.code)
        is HermesError.InvalidAddress -> stringResource(R.string.addr_invalid)
        HermesError.AddressRejected -> stringResource(R.string.addr_cleartext_rejected)
        HermesError.InvalidKey -> stringResource(R.string.err_invalid_key)
        is HermesError.Redirected -> stringResource(R.string.err_redirected, error.code)
    }
    ChatFailure.SteerRejected -> stringResource(R.string.chat_err_steer_rejected)
    is ChatFailure.RunFailed -> stringResource(R.string.chat_err_run_failed, failure.detail ?: "-")
    is ChatFailure.RunInterrupted -> stringResource(R.string.chat_err_run_interrupted, failure.detail ?: "-")
    ChatFailure.ConnectionMissing -> stringResource(R.string.chat_err_connection_missing)
    ChatFailure.KeyMissing -> stringResource(R.string.chat_err_key_missing)
    ChatFailure.PreviousRunActive -> stringResource(R.string.chat_err_previous_run_active)
    ChatFailure.SessionUnavailable -> stringResource(R.string.chat_err_session_unavailable)
    ChatFailure.AnswerUnsaved -> stringResource(R.string.chat_err_answer_unsaved)
}

private enum class ChatPreviewKind {
    Idle,
    Thinking,
    Tool,
    Approval,
    Lost,
    HistoryError,
}

@Composable
private fun ChatPreviewBody(kind: ChatPreviewKind) = RasulTheme(dark = false) {
    val title = stringResource(R.string.chat_preview_title)
    val messages = listOf(
        ChatMessage(1, MessageKind.User, stringResource(R.string.chat_preview_user_message)),
        ChatMessage(2, MessageKind.Assistant, stringResource(R.string.chat_preview_assistant_message)),
    )
    val run = when (kind) {
        ChatPreviewKind.Idle -> null
        ChatPreviewKind.Thinking -> ActiveRun("preview", System.currentTimeMillis() - 42_000L, StatusLabel.Thinking)
        ChatPreviewKind.Tool -> ActiveRun("preview", System.currentTimeMillis() - 42_000L, StatusLabel.Tool("terminal"))
        ChatPreviewKind.Approval -> ActiveRun("preview", System.currentTimeMillis() - 42_000L, StatusLabel.WaitingApproval)
        ChatPreviewKind.Lost -> ActiveRun("preview", System.currentTimeMillis() - 42_000L, StatusLabel.Lost)
        ChatPreviewKind.HistoryError -> null
    }
    val approvals = if (kind == ChatPreviewKind.Approval) {
        listOf(
            ApprovalUi(
                requestId = "preview",
                command = stringResource(R.string.chat_preview_command),
                description = stringResource(R.string.chat_preview_approval_description),
            ),
        )
    } else {
        emptyList()
    }
    ChatScreenContent(
        state = ChatState(
            load = ChatLoad.Ready,
            sessionId = if (kind == ChatPreviewKind.Idle) "sample-session" else null,
            messages = messages,
            run = run,
            approvals = approvals,
            historyFailure = if (kind == ChatPreviewKind.HistoryError) ChatFailure.Api(HermesError.Server(503)) else null,
        ),
        title = title,
        onBack = {},
        onDraft = {},
        onSend = {},
        onStop = {},
        onReconnect = {},
        onRefresh = {},
        onAnswerApproval = { _, _ -> },
        onRetryHistory = {},
        modelOptionsLoader = { HermesResult.Err(HermesError.NotFound) },
        selectedModel = if (kind == ChatPreviewKind.Idle) RunModelSelection("example", "Example model") else null,
        tokenUsage = null,
        onSelectModel = {},
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Chat idle EN")
@Composable
private fun ChatIdleEn() = ChatPreviewBody(ChatPreviewKind.Idle)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "ar", name = "Chat idle AR")
@Composable
private fun ChatIdleAr() = ChatPreviewBody(ChatPreviewKind.Idle)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Chat thinking EN")
@Composable
private fun ChatThinkingEn() = ChatPreviewBody(ChatPreviewKind.Thinking)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "ar", name = "Chat thinking AR")
@Composable
private fun ChatThinkingAr() = ChatPreviewBody(ChatPreviewKind.Thinking)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Chat tool EN")
@Composable
private fun ChatToolEn() = ChatPreviewBody(ChatPreviewKind.Tool)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "ar", name = "Chat tool AR")
@Composable
private fun ChatToolAr() = ChatPreviewBody(ChatPreviewKind.Tool)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Chat approval EN")
@Composable
private fun ChatApprovalEn() = ChatPreviewBody(ChatPreviewKind.Approval)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "ar", name = "Chat approval AR")
@Composable
private fun ChatApprovalAr() = ChatPreviewBody(ChatPreviewKind.Approval)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Chat lost EN")
@Composable
private fun ChatLostEn() = ChatPreviewBody(ChatPreviewKind.Lost)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "ar", name = "Chat lost AR")
@Composable
private fun ChatLostAr() = ChatPreviewBody(ChatPreviewKind.Lost)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Chat history error EN")
@Composable
private fun ChatHistoryErrorEn() = ChatPreviewBody(ChatPreviewKind.HistoryError)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "ar", name = "Chat history error AR")
@Composable
private fun ChatHistoryErrorAr() = ChatPreviewBody(ChatPreviewKind.HistoryError)
