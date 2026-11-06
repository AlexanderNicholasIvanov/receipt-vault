package com.alexivanov.receiptvault.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A copied receipt photo plus any GPS position the original carried in EXIF. */
data class StoredImage(val file: File, val exifLatitude: Double?, val exifLongitude: Double?)

/**
 * Copies receipt photos into app-private storage, upright and downscaled so
 * hundreds of receipts do not eat the phone's storage.
 */
class ImageStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "receipts").apply { mkdirs() }

    suspend fun import(uri: Uri): StoredImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var lat: Double? = null
        var lng: Double? = null
        var rotation = 0
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                exif.latLong?.let { lat = it[0]; lng = it[1] }
                rotation = exif.rotationDegrees
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file is not a readable image." }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Could not decode the image.")

        val upright = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        val out = File(dir, "${UUID.randomUUID()}.jpg")
        out.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        upright.recycle()
        StoredImage(out, lat, lng)
    }

    /**
     * Copies a stored receipt photo into the phone's Pictures/Receipt Vault
     * folder, where Gallery and My Files show it. Needs no permission: apps may
     * always add their own files to shared media on Android 10+.
     */
    suspend fun saveToPictures(path: String, displayName: String): Uri = withContext(Dispatchers.IO) {
        val source = File(path)
        require(source.isFile) { "The photo for this receipt is missing." }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$PICTURES_FOLDER")
            // Hidden from other apps until the bytes are fully written.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("The phone's storage refused the file.")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("Couldn't write to the phone's storage.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        uri
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        // Only ever delete files we own.
        if (file.parentFile?.canonicalPath == dir.canonicalPath) file.delete()
    }

    companion object {
        /** Long edge of stored photos; plenty for OCR and zooming in on fine print. */
        private const val MAX_EDGE = 2400

        /** Folder under Pictures that downloaded receipt photos go to. */
        const val PICTURES_FOLDER = "Receipt Vault"
    }
}
