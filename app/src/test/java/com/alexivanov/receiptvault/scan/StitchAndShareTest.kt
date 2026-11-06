package com.alexivanov.receiptvault.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.MainActivity
import com.alexivanov.receiptvault.domain.PageLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StitchAndShareTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ImageStore(context)

    private fun page(name: String, width: Int, height: Int, color: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return File(context.cacheDir, name).also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
    }

    private fun near(expected: Int, actual: Int) =
        abs(Color.red(expected) - Color.red(actual)) < 40 &&
            abs(Color.green(expected) - Color.green(actual)) < 40 &&
            abs(Color.blue(expected) - Color.blue(actual)) < 40

    @Test
    fun `pages are stacked top to bottom into one photo in the receipts folder`() = runBlocking {
        val first = page("p1.jpg", 400, 300, Color.RED)
        val second = page("p2.jpg", 200, 100, Color.BLUE)

        val out = store.stitch(listOf(first, second))

        assertEquals(File(context.filesDir, "receipts").canonicalPath, out.parentFile!!.canonicalPath)
        val joined = BitmapFactory.decodeFile(out.path)
        assertEquals(400, joined.width)
        assertEquals(300 + PageLayout.GAP + 200, joined.height)
        assertTrue("first page on top", near(Color.RED, joined.getPixel(200, 150)))
        assertTrue("white gap", near(Color.WHITE, joined.getPixel(200, 300 + PageLayout.GAP / 2)))
        assertTrue("second page below, scaled up", near(Color.BLUE, joined.getPixel(200, 300 + PageLayout.GAP + 100)))
        assertTrue("pages are left in place for the caller", first.exists() && second.exists())
    }

    @Test
    fun `one page is just copied`() = runBlocking {
        val only = page("only.jpg", 50, 80, Color.GREEN)
        val out = store.stitch(listOf(only))
        assertTrue(only.readBytes().contentEquals(out.readBytes()))
    }

    // ------------------------------------------------------------------ shares and "Open with"

    private val a = Uri.parse("content://photos/1")
    private val b = Uri.parse("content://photos/2")

    @Test
    fun `a shared photo or PDF becomes one page`() {
        val photo = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, a)
        assertEquals(listOf(a), MainActivity.sharedUris(photo))
        val pdf = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, b)
        assertEquals(listOf(b), MainActivity.sharedUris(pdf))
    }

    @Test
    fun `several shared photos are pages of one receipt, up to the page limit`() {
        val uris = ArrayList(List(12) { Uri.parse("content://photos/$it") })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        assertEquals(uris.take(PdfPages.MAX_PAGES), MainActivity.sharedUris(intent))
    }

    @Test
    fun `opening a PDF with the app imports it`() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(b, "application/pdf")
        assertEquals(listOf(b), MainActivity.sharedUris(intent))
    }

    @Test
    fun `other shares are ignored`() {
        assertTrue(MainActivity.sharedUris(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, a)).isEmpty())
        assertTrue(MainActivity.sharedUris(Intent(Intent.ACTION_MAIN)).isEmpty())
        assertTrue(MainActivity.sharedUris(null).isEmpty())
    }

    @Test
    fun `PDFs are recognised by type or name`() {
        val file = File(context.cacheDir, "invoice.PDF").apply { writeText("%PDF-1.4") }
        assertTrue(PdfPages.isPdf(context, Uri.fromFile(file)))
        assertFalse(PdfPages.isPdf(context, Uri.fromFile(File(context.cacheDir, "receipt.jpg"))))
    }
}
