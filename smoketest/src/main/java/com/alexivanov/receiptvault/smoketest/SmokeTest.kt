package com.alexivanov.receiptvault.smoketest

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern

/**
 * Clicks through the minified app like a user would. Most R8 mistakes (a class or member
 * removed that reflection or a library still needs) crash at runtime, and only here.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.context

    /** Whatever fails, the screen it failed on goes to logcat, which CI keeps per test. */
    @get:Rule
    val dumpOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description?) {
            val xml = ByteArrayOutputStream()
            runCatching { device.dumpWindowHierarchy(xml) }
            xml.toString(Charsets.UTF_8.name()).chunked(3_000).forEach { Log.i("SmokeTest", it) }
        }
    }

    @Before
    fun launch() {
        // R8 problems are what this looks for: the profile-recording build has no R8.
        org.junit.Assume.assumeFalse(BuildConfig.RECORDS_PROFILE)
        device.executeShellCommand("logcat -c -b crash")
        device.pressHome()
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        waitFor(label("Receipts"))
    }

    @After
    fun noCrashes() {
        val crashes = device.executeShellCommand("logcat -d -b crash")
        assertFalse("The app crashed:\n$crashes", crashes.contains(PACKAGE))
    }

    @Test
    fun addAReceiptByHandAndVisitEveryScreen() {
        find(By.desc("Add receipt")).click()
        find(label("Enter manually")).click()
        type("Merchant", "Smoke Test Cafe")
        type("Total", "12.34")
        find(label("Save")).click()
        // A rerun on the same emulator saves the same receipt twice.
        device.wait(Until.findObject(label("Save anyway")), 2_000)?.click()

        find(label("Smoke Test Cafe")).click()
        waitFor(label("Receipt"))
        device.pressBack()

        find(place("Insights")).click()
        waitFor(label("Spending"))
        scrollToEnd()
        device.pressBack()

        find(place("Settings")).click()
        waitFor(label("Scanning"))
        scrollToEnd()
        device.pressBack()

        waitFor(label("Receipts"))
    }

    @Test
    fun aSharedPhotoGoesThroughTextRecognition() {
        val photo = receiptPhoto()
        try {
            val share = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .setPackage(PACKAGE)
                .putExtra(Intent.EXTRA_STREAM, photo)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(share)
            // Reading may succeed or explain that the model is still downloading; either way the
            // review screen opens. A crash fails the test in noCrashes().
            waitFor(label("Save"), timeout = 90_000)
            device.pressBack()
        } finally {
            context.contentResolver.delete(photo, null, null)
        }
    }

    private fun receiptPhoto(): Uri {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; textSize = 40f; isAntiAlias = true }
        listOf("SMOKE TEST MARKET", "11/14/2026", "MILK        3.49", "BREAD       2.99", "TOTAL       6.48")
            .forEachIndexed { i, line -> canvas.drawText(line, 40f, 120f + i * 90f, paint) }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "smoke-receipt.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }

    /**
     * A node whose text is [text], or starts with it followed by more merged text: a list item
     * reports its headline and supporting line together ("Enter manually, No photo, ...").
     */
    private fun label(text: String): BySelector =
        By.text(Pattern.compile(Pattern.quote(text) + """(\W.*)?""", Pattern.DOTALL))

    /** One of the places in the navigation bar: the tappable item holding the label, not the screen title of the same name. */
    private fun place(name: String): BySelector = By.clickable(true).hasDescendant(By.text(name))

    /** Taps the text field labelled [name] and types into it. */
    private fun type(name: String, text: String) {
        // An empty field reports its label as the hint; some versions report it as the text.
        (device.wait(Until.findObject(By.hint(name)), 3_000) ?: find(label(name))).click()
        val field = device.wait(Until.findObject(By.focused(true)), TIMEOUT)
            ?: throw AssertionError("No field took focus after tapping \"$name\"")
        field.text = text
    }

    private fun waitFor(selector: BySelector, timeout: Long = TIMEOUT): UiObject2 =
        device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("Not on screen: $selector\n${screenText()}\n${saveScreen()}")

    /** Saves a screenshot and returns the window hierarchy, so a CI failure can be read afterwards. */
    private fun saveScreen(): String {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { dir ->
            device.takeScreenshot(File(dir, "failure-${System.currentTimeMillis()}.png"))
        }
        val xml = ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(xml) }
        return xml.toString(Charsets.UTF_8.name())
    }

    /** Waits for [selector], scrolling down the screen's list if it is further down. */
    private fun find(selector: BySelector): UiObject2 {
        device.wait(Until.findObject(selector), TIMEOUT)?.let { return it }
        // The list can be replaced while it scrolls (a sheet opening, a recomposition).
        runCatching { device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(selector)) }
            .getOrNull()?.let { return it }
        return waitFor(selector, timeout = 2_000)
    }

    private fun scrollToEnd() {
        val list = device.findObject(By.scrollable(true)) ?: return
        repeat(5) { if (!list.scroll(Direction.DOWN, 1f)) return }
    }

    private fun screenText(): String =
        device.findObjects(By.textContains("")).mapNotNull { it.text }.filter { it.isNotBlank() }.joinToString(" | ")

    companion object {
        const val PACKAGE = "com.alexivanov.receiptvault"
        const val TIMEOUT = 15_000L
    }
}
