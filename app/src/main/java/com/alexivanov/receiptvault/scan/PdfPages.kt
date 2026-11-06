package com.alexivanov.receiptvault.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Turns a PDF receipt (an emailed invoice, a saved order) into page images for OCR and storage. */
class PdfPages(private val context: Context) {

    /** Renders up to [maxPages] pages as JPEGs in the cache; the caller deletes them. */
    suspend fun render(uri: Uri, maxPages: Int = MAX_PAGES): List<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "pdf-pages").apply { mkdirs() }
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Couldn't open that PDF.")
        descriptor.use { fd ->
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                throw IllegalArgumentException("That PDF is password-protected.", e)
            }
            renderer.use { pdf ->
                require(pdf.pageCount > 0) { "That PDF has no pages." }
                (0 until minOf(pdf.pageCount, maxPages)).map { index ->
                    pdf.openPage(index).use { page ->
                        // PDF sizes are in points (1/72 in); render at a width OCR reads well.
                        val scale = (TARGET_WIDTH.toFloat() / page.width).coerceIn(1f, 4f)
                        val bitmap = createBitmap((page.width * scale).toInt(), (page.height * scale).toInt())
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            File(dir, "page-${System.nanoTime()}-$index.jpg").also { out ->
                                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_PAGES = 10
        private const val TARGET_WIDTH = 1600

        fun isPdf(context: Context, uri: Uri): Boolean =
            context.contentResolver.getType(uri) == "application/pdf" || uri.lastPathSegment.orEmpty().endsWith(".pdf", ignoreCase = true)
    }
}
