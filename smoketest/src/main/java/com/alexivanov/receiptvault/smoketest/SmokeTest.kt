package com.alexivanov.receiptvault.smoketest

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Clicks through the minified app like a user would. Most R8 mistakes (a class or member
 * removed that reflection or a library still needs) crash at runtime, and only here.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.context

    @Before
    fun launch() {
        device.executeShellCommand("logcat -c -b crash")
        device.pressHome()
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        waitFor(By.text("Receipts"))
    }

    @After
    fun noCrashes() {
        val crashes = device.executeShellCommand("logcat -d -b crash")
        assertFalse("The app crashed:\n$crashes", crashes.contains(PACKAGE))
    }

    @Test
    fun addAReceiptByHandAndVisitEveryScreen() {
        find(By.text("Add receipt")).click()
        find(By.text("Enter manually")).click()
        type("Merchant", "Smoke Test Cafe")
        type("Total", "12.34")
        find(By.text("Save")).click()
        // A rerun on the same emulator saves the same receipt twice.
        device.wait(Until.findObject(By.text("Save anyway")), 2_000)?.click()

        find(By.text("Smoke Test Cafe")).click()
        waitFor(By.text("Receipt"))
        device.pressBack()

        find(By.desc("Insights")).click()
        waitFor(By.text("Insights"))
        scrollToEnd()
        device.pressBack()

        find(By.desc("Settings")).click()
        waitFor(By.text("Settings"))
        scrollToEnd()
        device.pressBack()

        waitFor(By.text("Receipts"))
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
            waitFor(By.text("Save"), timeout = 90_000)
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

    /** Taps the text field labelled [label] and types into it. */
    private fun type(label: String, text: String) {
        find(By.text(label)).click()
        val field = device.wait(Until.findObject(By.focused(true)), TIMEOUT)
            ?: throw AssertionError("No field took focus after tapping \"$label\"")
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
        val list = device.findObject(By.scrollable(true))
        list?.scrollUntil(Direction.DOWN, Until.findObject(selector))?.let { return it }
        return waitFor(selector)
    }

    private fun scrollToEnd() {
        val list = device.findObject(By.scrollable(true)) ?: return
        repeat(5) { if (!list.scroll(Direction.DOWN, 1f)) return }
    }

    private fun screenText(): String =
        device.findObjects(By.textContains("")).mapNotNull { it.text }.filter { it.isNotBlank() }.joinToString(" | ")

    private companion object {
        const val PACKAGE = "com.alexivanov.receiptvault"
        const val TIMEOUT = 15_000L
    }
}
