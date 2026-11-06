package com.alexivanov.receiptvault.scan

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ImageStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `download writes the photo into Pictures - Receipt Vault and publishes it`() = runBlocking {
        val photo = File(context.filesDir, "receipts/test.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val resolver = shadowOf(context.contentResolver)
        val written = ByteArrayOutputStream()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Robolectric hands out "<collection>/1" for the first insert; capture what is written there.
        resolver.registerOutputStream(collection.buildUpon().appendPath("1").build(), written)

        val uri = ImageStore(context).saveToPictures(photo.absolutePath, "2026-11-06 Corner Cafe.jpg")

        val insert = resolver.insertStatements.single()
        assertEquals(collection, insert.uri)
        assertEquals("2026-11-06 Corner Cafe.jpg", insert.contentValues.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("Pictures/Receipt Vault", insert.contentValues.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals("image/jpeg", insert.contentValues.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals(1, insert.contentValues.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(listOf<Byte>(1, 2, 3, 4), written.toByteArray().toList())

        val publish = resolver.updateStatements.single()
        assertEquals(uri, publish.uri)
        assertEquals(0, publish.contentValues.getAsInteger(MediaStore.Images.Media.IS_PENDING))
    }

    @Test
    fun `a missing photo fails with a readable message`() {
        val e = runCatching { runBlocking { ImageStore(context).saveToPictures("/nope/missing.jpg", "x.jpg") } }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertEquals("The photo for this receipt is missing.", e!!.message)
    }
}
