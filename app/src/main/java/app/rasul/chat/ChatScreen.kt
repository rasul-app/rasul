package app.rasul.chat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import app.rasul.R
import app.rasul.data.ApprovalChoice
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.ModelOptionsDto
import app.rasul.data.PendingAttachment
import app.rasul.data.RunInputLimits
import app.rasul.data.SentAttachment
import app.rasul.data.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.rasul.data.RunModelSelection
import app.rasul.data.RunsClient
import app.rasul.data.TokenUsageSummary
import app.rasul.data.chatSeparatorText
import app.rasul.ui.components.BotAvatar
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.avatarStyleFor
import app.rasul.ui.rateLimitMessage
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
    voiceInputFactory: (android.content.Context, ChatViewModel, () -> IntRange, (Int) -> Unit) -> VoiceInputController? = ::defaultVoiceInputController,
    /** Opens the chat for a freshly forked session id; the target's revision is read from [vm]. */
    onOpenFork: (sessionId: String, revision: String) -> Unit = { _, _ -> },
    onApprovals: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // null until the field reports a caret; treated as a caret at the end of the current draft.
    val composerSelection = remember { mutableStateOf<TextRange?>(null) }
    val voiceController = remember(context, vm) {
        voiceInputFactory(
            context,
            vm,
            {
                composerSelection.value?.toVoiceSelection()
                    ?: vm.state.value.draft.let { it.length..it.length - 1 }
            },
            { end -> composerSelection.value = TextRange(end) },
        )
    }
    val voiceStateState = voiceController?.state?.collectAsState()
        ?: remember { mutableStateOf<VoiceInputState>(VoiceInputState.Idle) }
    val voiceState by voiceStateState
    val voiceLiveTextState = voiceController?.liveText?.collectAsState()
        ?: remember { mutableStateOf("") }
    val voiceLiveText by voiceLiveTextState
    DisposableEffect(voiceController) {
        onDispose { voiceController?.close() }
    }
    val requestRecordAudio = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Nothing pending survives rotation anyway: a grant starts listening on the current draft.
        onVoicePermissionResult(granted, state.load == ChatLoad.Ready, voiceController, speechLanguageTag(context))
    }
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
    val chatModels by runs.chatModels.collectAsState()
    val selectedModel = state.sessionId?.let(chatModels::get)
    // A fork the server created is opened even if this screen was left and came back while it was being made.
    val forked = state.forkedSessionId
    androidx.compose.runtime.LaunchedEffect(forked) {
        if (forked != null) {
            state.sessionId?.let(runs::selectedModelForChat)?.let { runs.selectModelForChat(forked, it) }
            onOpenFork(forked, vm.resolvedTarget?.revision ?: "0")
            vm.forkOpened()
        }
    }
    // System pickers: no storage permission; the ViewModel reads+processes results on its own scope,
    // so a recreation or a send in flight can never drop a picked attachment.
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(RunInputLimits.MAX_PHOTOS),
    ) { uris -> vm.loadPhotos(uris) }
    // */* so providers that mislabel .log/.kt still let the user pick; isTextFile gives the specific refuse message.
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.loadFile(uri) }
    // Any file type, several at once: only a bridge chat offers it; the ViewModel checks the 4-file and 50 MB limits.
    val anyFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.addFiles(uris) }
    ChatScreenContent(
        state = state,
        title = title,
        onBack = onBack,
        onInstructions = onInstructions,
        onDraft = {
            voiceController?.clearError()
            vm.onDraft(it)
        },
        onSend = {
            voiceController?.cancel()
            voiceController?.clearError()
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
        onPickPhotos = {
            photoPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        },
        onPickFile = { filePicker.launch(arrayOf("*/*")) },
        onPickAnyFile = { anyFilePicker.launch(arrayOf("*/*")) },
        onRetryUpload = vm::retryUpload,
        filesViaBridge = vm.resolvedTarget?.bridge != null,
        onRemoveAttachment = vm::removeAttachment,
        modelOptionsLoader = modelOptionsLoader,
        selectedModel = selectedModel,
        tokenUsage = state.sessionId?.let(usageBySession::get),
        onSelectModel = { selection ->
            val current = vm.state.value
            val session = current.sessionId
            if (session != null && session == state.sessionId && current.canChooseModel) runs.selectModelForChat(session, selection)
        },
        voiceAvailable = voiceController != null,
        voiceState = voiceState,
        voiceLiveText = voiceLiveText,
        composerSelection = composerSelection.value,
        onSelectionChange = { composerSelection.value = it },
        onVoiceToggle = {
            when (voiceState) {
                VoiceInputState.Listening, VoiceInputState.Stopping ->
                    voiceController?.toggle(speechLanguageTag(context))
                else -> {
                    val capturedLanguage = speechLanguageTag(context)
                    val permission = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO,
                    )
                    if (permission == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        voiceController?.start(capturedLanguage)
                    } else {
                        // A denial (immediate when "don't ask again") lands in the launcher result.
                        requestRecordAudio.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        },
        onOpenVoiceSettings = {
            voiceController?.clearError()
            val settings = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(settings)
        },
        onDismissVoiceError = { voiceController?.clearError() },
        onVoiceRelease = { voiceController?.release() },
        onFork = vm::fork,
        onDismissAction = vm::dismissActionFailure,
        onApprovals = onApprovals,
    )
}

/** The mic-permission launcher result: a grant starts listening on the current draft, nothing pending. */
internal fun onVoicePermissionResult(
    granted: Boolean,
    ready: Boolean,
    controller: VoiceInputController?,
    languageTag: String,
) {
    if (granted) {
        if (ready) controller?.start(languageTag)
    } else {
        controller?.permissionDenied()
    }
}

/** The caret/selection the draft field reports, as a closed char range; empty means a caret at `first`. */
private fun TextRange.toVoiceSelection(): IntRange = min..max - 1

private fun defaultVoiceInputController(
    context: android.content.Context,
    vm: ChatViewModel,
    selection: () -> IntRange,
    onInsertCursor: (Int) -> Unit,
): VoiceInputController? {
    val available = runCatching {
        android.speech.SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)
    if (!available) return null
    return runCatching {
        VoiceInputController(
            AndroidSpeechRecognizerGateway(context),
            { vm.state.value.draft },
            selection,
            vm::onDraft,
            onInsertCursor = onInsertCursor,
        )
    }.getOrNull()
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
    onPickPhotos: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onPickAnyFile: () -> Unit = {},
    onRetryUpload: (Long) -> Unit = {},
    /** True in a bridge chat: the File option is on. A direct connection shows it off, with the reason. */
    filesViaBridge: Boolean = false,
    onRemoveAttachment: (Long) -> Unit = {},
    modelOptionsLoader: suspend (Boolean) -> HermesResult<ModelOptionsDto>,
    selectedModel: RunModelSelection?,
    tokenUsage: TokenUsageSummary?,
    onSelectModel: (RunModelSelection?) -> Unit,
    onInstructions: () -> Unit = {},
    voiceAvailable: Boolean = true,
    voiceState: VoiceInputState = VoiceInputState.Idle,
    voiceLiveText: String = "",
    composerSelection: TextRange? = null,
    onSelectionChange: (TextRange) -> Unit = {},
    onVoiceToggle: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit = {},
    onDismissVoiceError: () -> Unit = {},
    onVoiceRelease: () -> Unit = {},
    onFork: () -> Unit = {},
    onDismissAction: () -> Unit = {},
    onApprovals: (() -> Unit)? = null,
) {
    val colors = Rasul.colors
    // Home/background leaves this composition alive, so dispose never runs; release the mic on ON_STOP.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val releaseVoice by rememberUpdatedState(onVoiceRelease)
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) releaseVoice()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
    // Measured header height (status bar + 48 dp buttons + padding, any font scale); first frame uses the fixed estimate.
    var headerPx by remember { mutableIntStateOf(0) }
    val headerHeight = if (headerPx == 0) chatHeaderHeight() else with(LocalDensity.current) { headerPx.toDp() }
    val modelLabel = selectedModel?.let { listOfNotNull(it.model, effortText(it.effort)).joinToString(" ") }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
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
                    state.actionFailure?.let { failure ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = headerHeight)
                                .padding(horizontal = 16.dp, vertical = 4.dp).testTag("chat_action_failure"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                failureText(failure),
                                modifier = Modifier.weight(1f),
                                color = colors.dangerText,
                                style = RasulType.bodySmall,
                            )
                            TextButton(onClick = onDismissAction, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.chat_action_dismiss), style = RasulType.buttonSmall)
                            }
                        }
                    }
                    state.historyFailure?.let { failure ->
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(top = if (state.actionFailure != null) 0.dp else headerHeight)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    MessageList(state.messages, Modifier.weight(1f), if (state.historyFailure != null || state.actionFailure != null) 0.dp else headerHeight)
                    state.run?.let { run ->
                        RunProgressCard(
                            run = run,
                            onStop = onStop,
                            onReconnect = onReconnect,
                            onRefresh = onRefresh,
                            onReview = if (approval != null && reviewHidden) ({ hiddenFor = null }) else null,
                        )
                    }
                    if (state.run == null) {
                        state.summary?.takeIf { it.runId == state.finishedRunId }?.let { RunSummaryLine(it) }
                    }
                }
            }
            if (state.load == ChatLoad.Ready) {
                Composer(
                    draft = state.draft,
                    onDraft = onDraft,
                    onSend = onSend,
                    sending = (state.run != null && state.run?.runId == null) || state.pendingAttachments > 0,
                    steering = state.run?.runId != null,
                    botName = title,
                    modelName = selectedModel?.model ?: stringResource(R.string.ux3b_model_server_default),
                    effortLabel = selectedModel?.effort?.let { effortText(it) },
                    modelPickerEnabled = modelPickerEnabled,
                    onChooseModel = { modelPickerOpen = true },
                    voiceAvailable = voiceAvailable,
                    voiceState = voiceState,
                    voiceLiveText = voiceLiveText,
                    composerSelection = composerSelection,
                    onSelectionChange = onSelectionChange,
                    onVoiceToggle = onVoiceToggle,
                    attachments = state.attachments,
                    preparing = state.pendingAttachments > 0,
                    onPickPhotos = onPickPhotos,
                    onPickFile = onPickFile,
                    onPickAnyFile = onPickAnyFile,
                    onRetryUpload = onRetryUpload,
                    filesViaBridge = filesViaBridge,
                    onRemoveAttachment = onRemoveAttachment,
                )
            }
        }
        ChatTopBar(
            title = title,
            active = state.run != null,
            selectedModel = modelLabel,
            modelPickerEnabled = modelPickerEnabled,
            onBack = onBack,
            onChooseModel = { modelPickerOpen = true },
            onShowUsage = { tokenUsageOpen = true },
            onInstructions = onInstructions,
            forkTitle = state.forkTitle,
            forkVisible = state.supportsFork,
            forkEnabled = state.canFork,
            forking = state.forking,
            onFork = onFork,
            onApprovals = onApprovals,
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerPx = it.height },
        )
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
            onChooseEffort = { effort ->
                if (state.canChooseModel) selectedModel?.let { onSelectModel(it.copy(effort = effort)) }
            },
            onRefresh = {
                refreshModelOptions = true
                modelRequestVersion += 1
            },
        )
    }
    if (tokenUsageOpen) TokenUsageDialog(tokenUsage, onDismiss = { tokenUsageOpen = false })
    if (voiceState == VoiceInputState.Error(VoiceInputError.Permission)) {
        AlertDialog(
            onDismissRequest = onDismissVoiceError,
            title = { Text(stringResource(R.string.chat_voice_permission_title)) },
            text = { Text(stringResource(R.string.chat_voice_error_permission)) },
            confirmButton = {
                TextButton(onClick = onOpenVoiceSettings) {
                    Text(stringResource(R.string.chat_voice_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissVoiceError) {
                    Text(stringResource(R.string.chat_voice_dismiss))
                }
            },
        )
    }

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

/** Vertical space the floating header occupies: status bar inset + button row + breathing room. */
@Composable
internal fun chatHeaderHeight(): androidx.compose.ui.unit.Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp

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
    forkTitle: String? = null,
    forkVisible: Boolean = false,
    forkEnabled: Boolean = false,
    forking: Boolean = false,
    onFork: () -> Unit = {},
    onApprovals: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = Rasul.colors
    val backIcon = RasulIcons.Back
    val headerName = title.ifBlank { stringResource(R.string.instructions_title) }
    val workingLabel = stringResource(R.string.chat_working)
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().testTag("chat_header").windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(colors.selected)
                .semantics(mergeDescendants = true) {}
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                backIcon,
                contentDescription = stringResource(R.string.back),
                tint = colors.ink,
                modifier = Modifier.size(22.dp).testTag(backIcon.name),
            )
        }
        Box(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier.heightIn(min = 48.dp).clip(CircleShape).background(colors.selected)
                    .semantics(mergeDescendants = true) {
                        contentDescription = headerName
                        if (active) stateDescription = workingLabel
                    }
                    .testTag("chat_header_name")
                    .clickable(role = Role.Button, onClick = onInstructions)
                    .padding(start = 7.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotAvatar(
                    style = avatarStyleFor(title),
                    size = 26.dp,
                    working = active,
                )
                Text(
                    title,
                    style = RasulType.rowTitle.copy(fontSize = 20.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 9.dp).weight(1f, fill = false),
                )
                if (forkTitle != null && !active) {
                    Text(
                        if (forkTitle.isBlank()) stringResource(R.string.chat_fork_badge) else stringResource(R.string.chat_fork_title, forkTitle),
                        modifier = Modifier.widthIn(max = 100.dp).padding(start = 8.dp).testTag("chat_fork_title"),
                        style = RasulType.meta,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(colors.selected)
                    .semantics(mergeDescendants = true) {}
                    .clickable(role = Role.Button, onClick = { menuExpanded = true }),
                contentAlignment = Alignment.Center,
            ) {
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
                if (forkVisible) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (forking) R.string.chat_fork_in_progress else R.string.chat_fork_menu)) },
                        enabled = forkEnabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("chat_fork_menu_item"),
                        onClick = {
                            menuExpanded = false
                            onFork()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ux3b_token_usage_menu)) },
                    onClick = {
                        menuExpanded = false
                        onShowUsage()
                    },
                )
                if (onApprovals != null) DropdownMenuItem(
                    text = { Text(stringResource(R.string.approvals_title)) },
                    onClick = {
                        menuExpanded = false
                        onApprovals()
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier, topPadding: androidx.compose.ui.unit.Dp) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }
    val zone = remember { java.time.ZoneId.systemDefault() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val bubbleMax = maxWidth * 0.82f
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = 12.dp),
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
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                val prev = messages.getOrNull(index - 1)
                val separator = needsDaySeparator(prev, message, zone)
                Column(
                    Modifier.fillMaxWidth().padding(
                        top = when {
                            index == 0 || separator -> 0.dp
                            sameSenderGroup(prev, message) -> 8.dp
                            else -> 12.dp
                        },
                    ),
                ) {
                    if (separator) {
                        Text(
                            chatSeparatorText(message.atMillis!!, zone, LocalConfiguration.current.locales[0]),
                            style = RasulType.bodySmall,
                            color = Rasul.colors.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                        )
                    }
                    MessageBubble(message, bubbleMax)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, maxWidth: androidx.compose.ui.unit.Dp) {
    val colors = Rasul.colors
    val mine = message.kind == MessageKind.User || message.kind == MessageKind.UserSteer
    val align = if (mine) Alignment.End else Alignment.Start
    val lineHeight = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 28.sp else 25.sp

    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        when (message.kind) {
            MessageKind.Error -> Text(
                message.error?.let { failureText(it) }.orEmpty(),
                color = colors.danger,
                style = RasulType.body.copy(lineHeight = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 24.sp else 21.sp),
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
                modifier = Modifier.widthIn(max = maxWidth),
                shape = RasulShapes.bubble,
                color = if (mine) colors.bubbleUser else colors.bubbleAssistant,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (message.kind == MessageKind.UserSteer) {
                        Text(
                            stringResource(R.string.chat_steered),
                            style = RasulType.chip,
                            color = colors.muted,
                        )
                    }
                    if (message.attachments.isNotEmpty()) {
                        Column(
                            Modifier.padding(bottom = if (message.text.isNotEmpty()) 6.dp else 0.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            message.attachments.forEach { SentAttachmentView(it, mine) }
                        }
                    }
                    if (message.text.isNotEmpty()) {
                        if (message.kind == MessageKind.Assistant) {
                            app.rasul.files.AssistantText(message.text, RasulType.chatBody.copy(lineHeight = lineHeight), colors.ink, Modifier.copyOnLongPress(message.text))
                        } else {
                            Text(
                                message.text,
                                style = RasulType.chatBody.copy(lineHeight = lineHeight),
                                color = colors.ink,
                                modifier = Modifier.copyOnLongPress(message.text),
                            )
                        }
                    }
                }
            }
        }
    }
}

// Code, commands, paths and monospace text always lay out left-to-right, even in the Arabic UI.
internal fun codeTextStyle(base: TextStyle): TextStyle = base.copy(textDirection = TextDirection.Ltr)

@Composable
internal fun CodeText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(text, modifier = modifier, style = codeTextStyle(style), color = color)
    }
}

@Composable
internal fun ConnectionLostBanner(onReconnect: () -> Unit) {
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

/**
 * One rounded box: the text field on top, a control row underneath: the attach "+" menu, the model label in the
 * middle, the mic when voice is available, and the send circle at the end. Staged attachments and the live voice
 * strip sit above the box.
 */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
    steering: Boolean,
    botName: String,
    modelName: String,
    effortLabel: String?,
    modelPickerEnabled: Boolean,
    onChooseModel: () -> Unit,
    voiceAvailable: Boolean,
    voiceState: VoiceInputState,
    voiceLiveText: String,
    composerSelection: TextRange?,
    onSelectionChange: (TextRange) -> Unit,
    onVoiceToggle: () -> Unit,
    attachments: List<PendingAttachment>,
    preparing: Boolean,
    onPickPhotos: () -> Unit,
    onPickFile: () -> Unit,
    onPickAnyFile: () -> Unit,
    onRetryUpload: (Long) -> Unit,
    filesViaBridge: Boolean,
    onRemoveAttachment: (Long) -> Unit,
) {
    val colors = Rasul.colors
    val uploading = attachments.any { it is PendingAttachment.UploadFile && it.state is UploadState.Uploading }
    val filesNotReady = attachments.any { it is PendingAttachment.UploadFile && it.state !is UploadState.Ready }
    val sendLabel = if (uploading) stringResource(R.string.chat_uploading) else stringResource(R.string.chat_send)
    val modelAccessibilityName = stringResource(
        R.string.ux3b_model_chip_accessibility,
        listOfNotNull(modelName, effortLabel).joinToString(" "),
    )
    val unavailableLabel = stringResource(R.string.ux3b_model_selection_unavailable)
    val voiceLabel = stringResource(R.string.chat_voice_mic)
    val listeningLabel = stringResource(R.string.chat_voice_listening)
    val stoppingLabel = stringResource(R.string.chat_voice_stopping)
    val voiceError = (voiceState as? VoiceInputState.Error)?.cause?.let { error ->
        stringResource(voiceErrorString(error))
    }
    val enabled = (draft.isNotBlank() || attachments.isNotEmpty()) && !sending && !filesNotReady
    var attachMenu by remember { mutableStateOf(false) }
    var fieldValue by remember { mutableStateOf(TextFieldValue(draft, selection = TextRange(draft.length))) }
    // The draft is what Send sends: outside changes (voice insert, send) keep the caret they
    // requested, and edits the model ignored (e.g. while a send is prepared) are reverted here.
    if (fieldValue.text != draft) {
        val caret = composerSelection?.start?.coerceIn(0, draft.length) ?: draft.length
        fieldValue = TextFieldValue(draft, selection = TextRange(caret))
        onSelectionChange(fieldValue.selection)
    }
    Column(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (voiceState == VoiceInputState.Listening || voiceState == VoiceInputState.Stopping || voiceLiveText.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth()
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .testTag("chat_voice_live_strip"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (voiceState == VoiceInputState.Stopping) stoppingLabel else listeningLabel,
                    color = colors.accent,
                    style = RasulType.bodySmall,
                )
                if (voiceLiveText.isNotBlank()) {
                    Text(
                        voiceLiveText,
                        color = colors.ink,
                        style = RasulType.chatBody,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        if (attachments.isNotEmpty() || preparing) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("chat_attachment_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                var photoN = 0
                attachments.forEach { attachment ->
                    val name = when (attachment) {
                        is PendingAttachment.Photo -> stringResource(R.string.chat_photo_number, ++photoN)
                        is PendingAttachment.TextFile -> attachment.name
                        is PendingAttachment.UploadFile -> attachment.name
                    }
                    if (attachment is PendingAttachment.UploadFile) UploadFileChip(attachment, onRemoveAttachment, onRetryUpload)
                    else AttachmentChip(attachment, name, onRemoveAttachment)
                }
                if (preparing) PreparingChip()
                if (uploading) UploadingNote()
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RasulShapes.composer).background(colors.fill),
        ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { new ->
                    val changed = new.text != fieldValue.text
                    fieldValue = new
                    onSelectionChange(new.selection)
                    if (changed) onDraft(new.text)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("chat_message_field")
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                textStyle = RasulType.chatBody.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text(
                                when {
                                    steering -> stringResource(R.string.chat_hint_steer)
                                    botName.isBlank() -> stringResource(R.string.chat_hint)
                                    else -> stringResource(R.string.chat_hint_ask, botName)
                                },
                                style = RasulType.chatBody,
                                color = colors.muted,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(
                        onClick = { attachMenu = true },
                        modifier = Modifier.size(48.dp).testTag("chat_attach_button"),
                    ) {
                        Icon(
                            RasulIcons.Paperclip,
                            contentDescription = stringResource(R.string.chat_attach),
                            tint = colors.muted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        AttachMenuItems(
                            filesViaBridge = filesViaBridge,
                            onPickPhotos = { attachMenu = false; onPickPhotos() },
                            onPickAnyFile = { attachMenu = false; onPickAnyFile() },
                            onPickFile = { attachMenu = false; onPickFile() },
                        )
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(
                        Modifier.minimumInteractiveComponentSize()
                            .clip(RasulShapes.chip)
                            .clickable(enabled = modelPickerEnabled, role = Role.Button, onClick = onChooseModel)
                            .semantics(mergeDescendants = true) {
                                contentDescription = modelAccessibilityName
                                if (!modelPickerEnabled) {
                                    disabled()
                                    stateDescription = unavailableLabel
                                }
                            }
                            .testTag("chat_model_label")
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modelName,
                            modifier = Modifier.weight(1f, fill = false)
                                .testTag("chat_model_label_name"),
                            style = RasulType.chatBody.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (effortLabel != null) {
                            Text(
                                " $effortLabel",
                                style = RasulType.chatBody,
                                color = colors.ink,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (voiceAvailable) {
                    val listening = voiceState == VoiceInputState.Listening
                    val stopping = voiceState == VoiceInputState.Stopping
                    val micEnabled = !sending && !stopping
                    Box(
                        Modifier.size(48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = voiceLabel
                                if (listening) stateDescription = listeningLabel
                                if (stopping) stateDescription = stoppingLabel
                                if (!micEnabled) disabled()
                            }
                            .clickable(enabled = micEnabled, role = Role.Button, onClick = onVoiceToggle)
                            .testTag("chat_voice_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (listening) {
                            ListeningPulse()
                        }
                        Box(
                            Modifier.size(40.dp)
                                .background(if (listening) colors.accent else colors.fill, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (stopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.muted,
                                )
                            } else {
                                Icon(
                                    RasulIcons.Mic,
                                    contentDescription = null,
                                    tint = if (listening) colors.onAccent else colors.muted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
                Box(
                    Modifier.size(48.dp)
                        .semantics { contentDescription = sendLabel }
                        .clickable(enabled = enabled, role = Role.Button, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(40.dp)
                            .background(if (enabled) colors.ink else colors.toggleOff, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "↑",
                            style = RasulType.button.copy(fontSize = 22.sp),
                            color = if (enabled) colors.bg else colors.muted,
                        )
                    }
                }
            }
        }
        voiceError?.let { message ->
            Text(
                message,
                color = colors.dangerText,
                style = RasulType.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * The rows of the attach menu. A bridge chat gets Photos and File (any type); a direct connection keeps Photos and the
 * text file, and shows File switched off with the reason.
 */
@Composable
internal fun AttachMenuItems(
    filesViaBridge: Boolean,
    onPickPhotos: () -> Unit,
    onPickAnyFile: () -> Unit,
    onPickFile: () -> Unit,
) {
    val colors = Rasul.colors
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_attach_photos)) },
        onClick = onPickPhotos,
    )
    if (filesViaBridge) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_attach_any_file)) },
            onClick = onPickAnyFile,
            modifier = Modifier.testTag("chat_attach_any_file"),
        )
    } else {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_attach_file)) },
            onClick = onPickFile,
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.chat_attach_any_file))
                    Text(stringResource(R.string.chat_attach_needs_bridge), style = RasulType.bodySmall, color = colors.muted)
                }
            },
            enabled = false,
            onClick = {},
            modifier = Modifier.testTag("chat_attach_any_file"),
        )
    }
}

@Composable
private fun ListeningPulse() {
    val colors = Rasul.colors
    val pulseTransition = rememberInfiniteTransition(label = "voice input pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(animation = tween(720), repeatMode = RepeatMode.Reverse),
        label = "voice input pulse scale",
    )
    Box(
        Modifier.size(40.dp)
            .testTag("chat_voice_pulse")
            .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
            .background(colors.accent.copy(alpha = 0.2f), CircleShape),
    )
}

internal fun voiceErrorString(error: VoiceInputError): Int = when (error) {
    VoiceInputError.NoMatch -> R.string.chat_voice_error_no_match
    VoiceInputError.Network -> R.string.chat_voice_error_network
    VoiceInputError.Busy -> R.string.chat_voice_error_busy
    VoiceInputError.Permission -> R.string.chat_voice_error_permission
    VoiceInputError.Unavailable -> R.string.chat_voice_error_unavailable
    VoiceInputError.Language -> R.string.chat_voice_error_language
    VoiceInputError.Microphone -> R.string.chat_voice_error_microphone
    VoiceInputError.SendInFlight -> R.string.chat_voice_error_send_in_flight
    VoiceInputError.Unknown -> R.string.chat_voice_error_unknown
}

private fun speechLanguageTag(context: android.content.Context): String =
    if (context.resources.configuration.locales[0]?.language == "ar") "ar" else "en"

/** Shown while a picked attachment is still being read and shrunk, so a disabled Send has a visible reason. */
@Composable
private fun PreparingChip() {
    val colors = Rasul.colors
    val label = stringResource(R.string.chat_attachment_preparing)
    Row(
        Modifier.heightIn(min = 48.dp).clip(RasulShapes.chip).background(colors.fill).padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag("chat_attachment_preparing"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
        Text(label, style = RasulType.chip, color = colors.ink, maxLines = 1)
    }
}

/** One staged attachment above the composer: photo thumbnail or file name, plus a remove control. */
@Composable
private fun AttachmentChip(attachment: PendingAttachment, name: String, onRemove: (Long) -> Unit) {
    val colors = Rasul.colors
    val removeLabel = stringResource(R.string.chat_attachment_remove_named, name)
    Row(
        Modifier.clip(RasulShapes.chip).background(colors.fill).padding(4.dp).testTag("chat_attachment_chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (attachment) {
            is PendingAttachment.Photo -> {
                val thumb by produceState<ImageBitmap?>(null, attachment.key) {
                    value = withContext(Dispatchers.Default) { decodeThumb(attachment.thumb)?.asImageBitmap() }
                }
                thumb?.let {
                    Image(
                        it,
                        contentDescription = stringResource(R.string.chat_attachment_photo),
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            // Bridge files have their own chip ([UploadFileChip]).
            is PendingAttachment.UploadFile -> Unit
            is PendingAttachment.TextFile -> {
                Icon(RasulIcons.FileDoc, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                Text(
                    attachment.name,
                    style = RasulType.chip,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
        }
        Box(
            Modifier.size(48.dp)
                .semantics { contentDescription = removeLabel }
                .clickable(role = Role.Button) { onRemove(attachment.key) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(RasulIcons.X, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        }
    }
}

/** An attachment on a sent/history bubble: photo thumbnail, or file icon + name. */
@Composable
private fun SentAttachmentView(attachment: SentAttachment, mine: Boolean) {
    val colors = Rasul.colors
    when (attachment) {
        is SentAttachment.LocalPhoto -> {
            val thumb by produceState<ImageBitmap?>(null, attachment.thumb) {
                value = withContext(Dispatchers.Default) { decodeThumb(attachment.thumb)?.asImageBitmap() }
            }
            thumb?.let {
                Image(
                    it,
                    contentDescription = stringResource(R.string.chat_attachment_photo),
                    modifier = Modifier.size(160.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        is SentAttachment.RemotePhoto -> {
            val thumb by produceState<ImageBitmap?>(null, attachment.dataUrl) {
                value = withContext(Dispatchers.Default) { decodeRemoteThumb(attachment.dataUrl)?.asImageBitmap() }
            }
            thumb?.let {
                Image(
                    it,
                    contentDescription = stringResource(R.string.chat_attachment_photo),
                    modifier = Modifier.size(160.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } ?: Text(
                stringResource(R.string.chat_photo_label),
                style = RasulType.chip,
                color = if (mine) colors.onAccent else colors.muted,
            )
        }
        is SentAttachment.SentFile -> SentFileCard(attachment.name, attachment.size, mine)
        is SentAttachment.NamedFile -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                RasulIcons.FileDoc,
                contentDescription = null,
                tint = if (mine) colors.onAccent else colors.muted,
                modifier = Modifier.size(16.dp),
            )
            Text(
                attachment.name,
                style = RasulType.chip,
                color = if (mine) colors.onAccent else colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalSheet(
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
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
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
                        Modifier.fillMaxWidth().heightIn(max = 240.dp).background(colors.ink, ChatCodeShape)
                            .verticalScroll(rememberScrollState())
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
internal fun statusText(status: StatusLabel): String = when (status) {
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
        HermesError.BridgeLinkDropped -> stringResource(R.string.bridge_link_dropped)
        HermesError.BridgeSecure -> stringResource(R.string.bridge_secure_failed)
        HermesError.BridgeAddressUnknown -> stringResource(R.string.bridge_address_unknown)
        HermesError.BridgeRefused -> stringResource(R.string.bridge_refused)
        HermesError.ProfileNotReady -> stringResource(R.string.bridge_profile_not_ready)
        HermesError.BridgeResponseInvalid -> stringResource(R.string.bridge_response_invalid)
        HermesError.Unauthorized -> stringResource(R.string.err_unauthorized)
        HermesError.NotFound -> stringResource(R.string.chat_err_run_gone)
        HermesError.ResourceNotFound -> stringResource(R.string.err_resource_not_found)
        HermesError.ConnectionChanged -> stringResource(R.string.err_connection_changed)
        HermesError.ChatBusy -> stringResource(R.string.err_chat_busy)
        is HermesError.Storage -> stringResource(R.string.connections_load_error, error.detail)
        is HermesError.ProfileUnknown -> stringResource(R.string.err_profile_unknown, error.profile)
        is HermesError.Unreachable -> stringResource(R.string.err_unreachable, error.detail)
        is HermesError.Incompatible -> stringResource(R.string.err_incompatible, error.missing.joinToString())
        is HermesError.Server -> rateLimitMessage(error, LocalContext.current) ?: stringResource(R.string.err_server, error.code)
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
    ChatFailure.AttachmentTooLarge -> stringResource(R.string.chat_err_attach_too_large)
    ChatFailure.FileTypeUnsupported -> stringResource(R.string.chat_err_attach_file_type)
    ChatFailure.FileTooLarge -> stringResource(R.string.chat_err_attach_file_too_large)
    ChatFailure.PhotoTooLarge -> stringResource(R.string.chat_err_attach_photo_too_large)
    ChatFailure.PhotoUnreadable -> stringResource(R.string.chat_err_attach_photo_unreadable)
    ChatFailure.FileUnreadable -> stringResource(R.string.chat_err_attach_file_unreadable)
    ChatFailure.AttachmentKindMixed -> stringResource(R.string.chat_err_attach_mixed)
    ChatFailure.AttachmentTooManyPhotos -> stringResource(R.string.chat_err_attach_too_many)
    ChatFailure.AttachmentRunActive -> stringResource(R.string.chat_err_attach_run_active)
    ChatFailure.ReplyKept -> stringResource(R.string.chat_err_reply_kept)
    ChatFailure.FilesNeedBridge -> stringResource(R.string.chat_attach_needs_bridge)
    is ChatFailure.UploadTooLarge -> stringResource(R.string.chat_err_upload_too_large, failure.name)
    is ChatFailure.UploadEmpty -> stringResource(R.string.chat_err_upload_empty, failure.name)
    ChatFailure.TooManyFiles -> stringResource(R.string.chat_err_upload_too_many)
    ChatFailure.FilesMixed -> stringResource(R.string.chat_err_upload_mixed)
    ChatFailure.BridgeNeedsUpdate -> stringResource(R.string.chat_err_upload_old_bridge)
    ChatFailure.PcDiskFull -> stringResource(R.string.chat_err_upload_disk_full)
    ChatFailure.TooManyOpenUploads -> stringResource(R.string.chat_err_upload_open_limit)
    ChatFailure.PcSaveFailed -> stringResource(R.string.chat_err_upload_save_failed)
    ChatFailure.UploadBotMissing -> stringResource(R.string.chat_err_upload_bot_missing)
    ChatFailure.RunInputRefused -> stringResource(R.string.chat_err_run_input_refused)
    is ChatFailure.FilesGone ->
        if (failure.names.size == 1) stringResource(R.string.chat_err_upload_gone, failure.names.single())
        else stringResource(R.string.chat_err_upload_gone_many, failure.names.joinToString(", "))
    is ChatFailure.ForkFailed -> sessionActionMessage(LocalContext.current, failure.error)
        ?: failureText(ChatFailure.Api(failure.error))
    is ChatFailure.ShareItemsDropped -> pluralStringResource(R.plurals.share_dropped, failure.count, failure.count) +
        failure.other?.let { " " + failureText(it) }.orEmpty()
}

/**
 * Specific text for a failed fork request. 404 on the route itself (no server message) means the
 * installed Hermes does not have it; null = no special text, the caller uses the general mapping.
 */
internal fun sessionActionMessage(context: android.content.Context, error: HermesError): String? = when (error) {
    HermesError.NotFound -> context.getString(R.string.chat_err_update_hermes)
    HermesError.ResourceNotFound -> context.getString(R.string.chat_err_fork_missing)
    is HermesError.Server -> rateLimitMessage(error, context) ?: when (error.serverCode ?: error.message) {
        "session_exists" -> context.getString(R.string.chat_err_fork_exists)
        else -> null
    }
    else -> null
}

/** Fixed stamp (Thu 2026-10-15 20:46 UTC) so goldens show the day separator deterministically. */
private const val CHAT_PREVIEW_AT = 1_792_097_160_000L

/** About 4000 characters: the approval sheet must keep both answer buttons reachable. */
private val CHAT_PREVIEW_LONG_COMMAND = (1..80).joinToString("\n") { "step $it: sudo find /var/log/old-builds -name '*.log' -mtime +30 -delete" }

private enum class ChatPreviewKind {
    Idle,
    Thinking,
    Tool,
    Approval,
    ApprovalLong,
    Lost,
    HistoryError,
    Fork,
}

@Composable
private fun ChatPreviewBody(
    kind: ChatPreviewKind,
    voiceState: VoiceInputState = VoiceInputState.Idle,
    voiceLiveText: String = "",
) = RasulTheme(dark = isSystemInDarkTheme()) {
    val title = stringResource(R.string.chat_preview_title)
    val messages = listOf(
        ChatMessage(1, MessageKind.User, stringResource(R.string.chat_preview_user_message), atMillis = CHAT_PREVIEW_AT),
        ChatMessage(2, MessageKind.Assistant, stringResource(R.string.chat_preview_assistant_message), atMillis = CHAT_PREVIEW_AT),
    )
    val started = System.currentTimeMillis() - 42_000L
    val steps = listOf(
        RunStep("web_search", "gradle release notes", started + 2_000L, started + 9_000L, 7_000L),
        RunStep("read_file", null, started + 10_000L, started + 11_000L, 1_000L),
    )
    fun preview(status: StatusLabel, phase: RunPhase, shown: List<RunStep> = steps) = ActiveRun(
        "preview", started, status,
        progress = RunProgress(started, phase, shown, stepCount = shown.size, lastEventAt = started + 20_000L),
    )
    val run = when (kind) {
        ChatPreviewKind.Idle -> null
        ChatPreviewKind.Thinking -> preview(StatusLabel.Thinking, RunPhase.Thinking)
        ChatPreviewKind.Tool -> preview(StatusLabel.Tool("terminal"), RunPhase.Tool("terminal", "./gradlew test"))
        ChatPreviewKind.Approval, ChatPreviewKind.ApprovalLong -> preview(StatusLabel.WaitingApproval, RunPhase.WaitingApproval)
        ChatPreviewKind.Lost -> preview(StatusLabel.Lost, RunPhase.Thinking, steps.take(1))
        ChatPreviewKind.HistoryError -> null
        ChatPreviewKind.Fork -> null
    }
    val example = RunModelSelection("example", "Example model")
    val approvals = if (kind == ChatPreviewKind.Approval || kind == ChatPreviewKind.ApprovalLong) {
        listOf(
            ApprovalUi(
                requestId = "preview",
                command = if (kind == ChatPreviewKind.ApprovalLong) CHAT_PREVIEW_LONG_COMMAND else stringResource(R.string.chat_preview_command),
                description = stringResource(R.string.chat_preview_approval_description),
            ),
        )
    } else {
        emptyList()
    }
    ChatScreenContent(
        state = ChatState(
            load = ChatLoad.Ready,
            sessionId = if (kind == ChatPreviewKind.Idle || kind == ChatPreviewKind.Fork) "sample-session" else null,
            messages = messages,
            supportsFork = kind == ChatPreviewKind.Fork,
            forkTitle = if (kind == ChatPreviewKind.Fork) "Example fork" else null,
            actionFailure = if (kind == ChatPreviewKind.Fork) ChatFailure.ForkFailed(HermesError.NotFound) else null,
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
        selectedModel = if (kind == ChatPreviewKind.Idle || kind == ChatPreviewKind.Fork) example else null,
        tokenUsage = null,
        onSelectModel = {},
        voiceAvailable = true,
        voiceState = voiceState,
        voiceLiveText = voiceLiveText,
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Chat idle EN")
@Composable
private fun ChatIdleEn() = ChatPreviewBody(ChatPreviewKind.Idle)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "ar", name = "Chat idle AR")
@Composable
private fun ChatIdleAr() = ChatPreviewBody(ChatPreviewKind.Idle)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Voice listening EN")
@Composable
private fun VoiceListeningEn() = ChatPreviewBody(
    ChatPreviewKind.Idle,
    VoiceInputState.Listening,
    stringResource(R.string.chat_voice_preview_words),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "ar", name = "Voice listening AR")
@Composable
private fun VoiceListeningAr() = ChatPreviewBody(
    ChatPreviewKind.Idle,
    VoiceInputState.Listening,
    stringResource(R.string.chat_voice_preview_words),
)

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

@Preview(showBackground = true, widthDp = 360, heightDp = 640, name = "Chat approval long EN")
@Composable
private fun ChatApprovalLongEn() = ChatPreviewBody(ChatPreviewKind.ApprovalLong)

@Preview(showBackground = true, widthDp = 360, heightDp = 640, locale = "ar", name = "Chat approval long AR")
@Composable
private fun ChatApprovalLongAr() = ChatPreviewBody(ChatPreviewKind.ApprovalLong)

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

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Chat fork EN")
@Composable
private fun ChatForkEn() = ChatPreviewBody(ChatPreviewKind.Fork)

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "ar", name = "Chat fork AR")
@Composable
private fun ChatForkAr() = ChatPreviewBody(ChatPreviewKind.Fork)
