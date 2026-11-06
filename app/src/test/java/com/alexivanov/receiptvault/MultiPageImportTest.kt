package com.alexivanov.receiptvault

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.domain.PageLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Sharing several photos into the app makes one receipt whose photo is the pages stacked. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiPageImportTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun page(name: String, width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(file)
    }

    @Test
    fun `two shared pages open one review with one stacked photo`() {
        val pages = arrayListOf(page("top.jpg", 600, 800), page("bottom.jpg", 600, 400))
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND_MULTIPLE)
            .setType("image/jpeg")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, pages)

        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("Merchant"), 20_000)

            // The page copies are gone; only the joined photo is kept for the receipt.
            val stored = File(context.filesDir, "receipts").listFiles().orEmpty().filter { it.extension == "jpg" }
            assertEquals(
                stored.joinToString { f ->
                    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, b)
                    "${f.name} ${b.outWidth}x${b.outHeight} ${f.lastModified()}"
                },
                1, stored.size,
            )
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(stored.single().path, o)
            assertEquals(600, o.outWidth)
            assertEquals(800 + PageLayout.GAP + 400, o.outHeight)
        }
    }
}
