package com.alexivanov.receiptvault.scan

import android.content.Context
import android.net.Uri
import com.alexivanov.receiptvault.domain.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlinx.coroutines.withContext

/** On-device OCR (ML Kit, bundled Latin model). Nothing leaves the phone. */
class TextReader(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(file: File): List<OcrLine> {
        // Decodes the whole photo; a multi-page scan would freeze the screen on the main thread.
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, Uri.fromFile(file)) }
        val result = recognizer.process(image).await()
        return result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, box.left, box.top, box.right, box.bottom)
            }
        }
    }
}
