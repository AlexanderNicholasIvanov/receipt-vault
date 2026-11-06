package com.alexivanov.receiptvault.capture

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.MainActivity
import com.alexivanov.receiptvault.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppWidgetManager
import android.appwidget.AppWidgetManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class QuickCaptureTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `intents name the action and open the app`() {
        val intent = QuickCapture.intent(app, QuickAction.SCAN)
        assertEquals("com.alexivanov.receiptvault.action.SCAN", intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(QuickAction.SCAN, QuickAction.from(intent))
        assertEquals(QuickAction.MANUAL, QuickAction.from(Intent("com.alexivanov.receiptvault.action.ADD_MANUALLY")))
        assertNull(QuickAction.from(Intent(Intent.ACTION_MAIN)))
        assertNull(QuickAction.from(null))
    }

    @Test
    fun `widget buttons start a scan or a manual receipt`() {
        val widgets: ShadowAppWidgetManager = shadowOf(AppWidgetManager.getInstance(app))
        val id = widgets.createWidget(QuickCaptureWidget::class.java, R.layout.widget_quick_capture)
        val view = widgets.getViewFor(id)

        view.findViewById<android.view.View>(R.id.widget_scan).performClick()
        assertEquals(QuickAction.SCAN.intentAction, shadowOf(app).nextStartedActivity?.action)

        view.findViewById<android.view.View>(R.id.widget_manual).performClick()
        assertEquals(QuickAction.MANUAL.intentAction, shadowOf(app).nextStartedActivity?.action)
    }

    // Robolectric only fakes the Android 13 form of startActivityAndCollapse (an Intent); 14+ passes a
    // PendingIntent through a system binder it doesn't have. Both build the intent with QuickCapture.
    @Test
    @Config(sdk = [33])
    fun `the tile opens the scanner`() {
        val tile = Robolectric.setupService(ScanTileService::class.java)
        tile.onClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(QuickAction.SCAN.intentAction, started?.action)
    }
}
