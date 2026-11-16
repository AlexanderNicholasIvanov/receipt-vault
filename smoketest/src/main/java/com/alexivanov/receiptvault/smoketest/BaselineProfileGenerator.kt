package com.alexivanov.receiptvault.smoketest

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which classes and methods the app uses to start and open its main screens. Shipped as
 * app/src/main/baseline-prof.txt, they are compiled ahead of time on install instead of being
 * interpreted at first, so the app starts and scrolls faster from the first run.
 *
 * Runs against the app's un-minified "profile" build, since a profile needs real class names:
 * `./gradlew :smoketest:connectedProfileAndroidTest`. CI does this on every build and keeps the
 * result with the test outputs (BaselineProfileGenerator_generate-baseline-prof.txt); copy it over
 * the app's file to update it.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        assumeTrue("Only against the un-minified profile build", BuildConfig.RECORDS_PROFILE)
        record()
    }

    private fun record() = rule.collect(packageName = SmokeTest.PACKAGE) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Receipts")), SmokeTest.TIMEOUT)

        // Insights, the heaviest screen, and back.
        device.findObject(By.desc("Insights"))?.let {
            it.click()
            device.wait(Until.hasObject(By.text("Insights")), SmokeTest.TIMEOUT)
            device.pressBack()
            device.wait(Until.hasObject(By.text("Receipts")), SmokeTest.TIMEOUT)
        }
    }
}
