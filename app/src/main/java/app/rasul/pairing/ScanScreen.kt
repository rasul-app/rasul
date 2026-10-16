package app.rasul.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview as UiPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rasul.R
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.BarcodeFormat
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

@Composable
fun ScanScreen(vm: PairingViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    fun refresh() { permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it; denied = !it
    }
    DisposableEffect(owner) {
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val state by vm.state.collectAsState()
    ScanContent(permission, denied, state, { launcher.launch(Manifest.permission.CAMERA) }, {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
    }, vm::reset, onClose) {
        CameraScanner(vm::scan, { vm.cameraFailed() })
    }
}

@Composable
fun ScanContent(
    permission: Boolean, denied: Boolean, state: PairingState,
    onPermission: () -> Unit = {}, onSettings: () -> Unit = {}, onRetry: () -> Unit = {}, onClose: () -> Unit = {},
    camera: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(360.dp).background(Rasul.colors.fill), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(stringResource(R.string.pair_scan_view), color = Rasul.colors.muted)
        }
    },
) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.pair_scan_title), style = RasulType.title, color = Rasul.colors.ink)
        when (state) {
            PairingState.Scanning -> if (!permission) {
                Text(stringResource(if (denied) R.string.pair_permission_denied else R.string.pair_permission_needed), color = Rasul.colors.ink)
                PrimaryButton(stringResource(R.string.pair_allow_camera), onPermission)
                if (denied) SecondaryButton(stringResource(R.string.pair_open_settings), onSettings, modifier = Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.pair_scan_hint), color = Rasul.colors.ink)
                camera()
            }
            is PairingState.Sending -> {
                Text(state.name, color = Rasul.colors.ink)
                Text(stringResource(R.string.pair_compare), style = RasulType.body, color = Rasul.colors.ink)
                val spokenCode = state.code?.toCharArray()?.joinToString(" ")
                Text(state.code ?: stringResource(R.string.pair_connecting),
                    style = RasulType.title.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    color = Rasul.colors.accent,
                    modifier = Modifier.semantics { if (spokenCode != null) contentDescription = spokenCode })
                Text(stringResource(R.string.pair_wait_owner), color = Rasul.colors.muted)
            }
            is PairingState.Failed -> {
                Text(stringResource(state.message), color = Rasul.colors.danger)
                PrimaryButton(stringResource(R.string.pair_scan_again), onRetry)
            }
            is PairingState.Paired -> {
                Text(stringResource(R.string.pair_success, state.name), color = Rasul.colors.ink)
            }
        }
        SecondaryButton(stringResource(R.string.back), onClose, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CameraScanner(onScan: (String) -> Unit, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val view = remember { PreviewView(context) }
    val label = stringResource(R.string.pair_scan_view)
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(360.dp).semantics { contentDescription = label })
    DisposableEffect(lifecycle, view) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { image ->
            try {
                val result = decodeQr(image)
                if (result != null) main.execute { if (!disposed) onScan(result) }
            } finally { image.close() }
        }
        future.addListener({
            if (!disposed) try {
                provider = future.get()
                provider?.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { onFailure() }
        }, main)
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            executor.shutdown()
        }
    }
}

internal fun decodeQr(image: ImageProxy): String? {
    val plane = image.planes.first()
    val buffer = plane.buffer.duplicate()
    val offset = buffer.position()
    val crop = image.cropRect
    val width = crop.width()
    val height = crop.height()
    val bytes = ByteArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        bytes[y * width + x] = buffer.get(offset + (crop.top + y) * plane.rowStride + (crop.left + x) * plane.pixelStride)
    }
    return try {
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
    } catch (_: com.google.zxing.ReaderException) { null }
}

@UiPreview(widthDp = 390, heightDp = 700)
@Composable private fun ScanPermissionEn() = RasulTheme(false) { ScanContent(false, false, PairingState.Scanning) }
@UiPreview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun ScanPermissionAr() = RasulTheme(false) { ScanContent(false, false, PairingState.Scanning) }
@UiPreview(widthDp = 390, heightDp = 700)
@Composable private fun ScanningEn() = RasulTheme(false) { ScanContent(true, false, PairingState.Scanning) }
@UiPreview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun ScanningAr() = RasulTheme(false) { ScanContent(true, false, PairingState.Scanning) }
@UiPreview(widthDp = 390, heightDp = 700)
@Composable private fun CodeConfirmEn() = RasulTheme(false) { ScanContent(true, false, PairingState.Sending("Bridge", "123456")) }
@UiPreview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun CodeConfirmAr() = RasulTheme(false) { ScanContent(true, false, PairingState.Sending("Bridge", "123456")) }
