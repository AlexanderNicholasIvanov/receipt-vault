package com.alexivanov.receiptvault.ui.home

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.alexivanov.receiptvault.ui.review.CaptureSource
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

/** The three ways to get a receipt photo. */
class CaptureActions(
    val scan: () -> Unit,
    val camera: () -> Unit,
    val gallery: () -> Unit,
)

@Composable
fun rememberCaptureActions(
    onCaptured: (Uri, CaptureSource) -> Unit,
    onError: (String) -> Unit,
): CaptureActions {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val page = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.firstOrNull()
            if (page != null) onCaptured(page.imageUri, CaptureSource.SCAN) else onError("The scanner returned no page.")
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) onCaptured(uri.toUri(), CaptureSource.CAMERA)
        pendingCameraUri = null
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onCaptured(uri, CaptureSource.GALLERY)
    }

    fun launchCamera() {
        val uri = newCameraUri(context)
        pendingCameraUri = uri.toString()
        try {
            cameraLauncher.launch(uri)
        } catch (_: Exception) {
            onError("No camera app is available.")
        }
    }

    fun launchScanner() {
        if (activity == null) return launchCamera()
        // Created on demand: the client needs Google Play services, which only the scanner path uses.
        val scanner = GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(1)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build(),
        )
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                // Scanner module unavailable (no Play services, still downloading): use the camera app.
                onError("Document scanner unavailable, opening the camera instead.")
                launchCamera()
            }
    }

    return CaptureActions(
        scan = ::launchScanner,
        camera = ::launchCamera,
        gallery = {
            pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    )
}

private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "receipt-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
