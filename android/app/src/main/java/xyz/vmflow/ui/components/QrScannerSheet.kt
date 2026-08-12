package xyz.vmflow.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import xyz.vmflow.R
import java.util.concurrent.Executors

/**
 * Scans a single QR code and hands its raw text to [onResult].
 *
 * Deliberately generic — it returns the raw string rather than a parsed
 * server config, so the warehouse barcode work can reuse it unchanged.
 * The camera permission is requested here, at the moment of scanning,
 * rather than at app start.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGetImage::class)
@Composable
fun QrScannerSheet(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.qr_scan),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.qr_scan_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            if (!hasPermission) {
                Text(stringResource(R.string.qr_permission_needed))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.qr_grant_permission))
                }
            } else {
                val executor = remember { Executors.newSingleThreadExecutor() }
                var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
                DisposableEffect(Unit) {
                    onDispose {
                        // Unbind before shutting the executor down so the camera
                        // stops (and the Android 12+ indicator light turns off)
                        // as soon as the sheet closes, instead of staying bound
                        // to the Activity lifecycle until it stops.
                        provider?.unbindAll()
                        executor.shutdown()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                val cameraProvider = providerFuture.get()
                                provider = cameraProvider
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val scanner = BarcodeScanning.getClient()
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .build()
                                    .also { it.setAnalyzer(executor) { proxy ->
                                        val media = proxy.image
                                        if (media == null) {
                                            proxy.close()
                                        } else {
                                            val image = InputImage.fromMediaImage(
                                                media,
                                                proxy.imageInfo.rotationDegrees
                                            )
                                            scanner.process(image)
                                                .addOnSuccessListener { codes ->
                                                    codes.firstOrNull { code ->
                                                        code.format == Barcode.FORMAT_QR_CODE
                                                    }?.rawValue?.let(onResult)
                                                }
                                                .addOnCompleteListener { proxy.close() }
                                        }
                                    } }

                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                    )
                }
            }
        }
    }
}
