package com.alexivanov.receiptvault.scan

import android.content.Context
import android.net.Uri
import android.util.Log
import com.alexivanov.receiptvault.domain.OcrLine
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlinx.coroutines.withContext

/** The OCR model hasn't finished downloading through Google Play services yet. */
class OcrNotReadyException(cause: Throwable) : Exception("The text recognition model is not downloaded yet", cause)

/**
 * On-device OCR (ML Kit Latin model). The model comes from Google Play services rather than
 * inside the APK, but recognition still runs on the phone: nothing leaves it.
 */
class TextReader(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Asks Play services for the model now, so the first scan doesn't wait for a download. */
    fun prepare() {
        val request = ModuleInstallRequest.newBuilder().addApi(recognizer).build()
        ModuleInstall.getClient(context).installModules(request)
            .addOnFailureListener { Log.w(TAG, "Couldn't request the OCR model", it) }
    }

    suspend fun read(file: File): List<OcrLine> {
        // Decodes the whole photo; a multi-page scan would freeze the screen on the main thread.
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, Uri.fromFile(file)) }
        val result = try {
            recognizer.process(image).await()
        } catch (e: MlKitException) {
            if (e.errorCode == MlKitException.UNAVAILABLE) throw OcrNotReadyException(e) else throw e
        }
        return result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, box.left, box.top, box.right, box.bottom)
            }
        }
    }

    private companion object {
        const val TAG = "TextReader"
    }
}
