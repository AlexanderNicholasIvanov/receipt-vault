package com.alexivanov.receiptvault.smoketest

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start of the minified app, with and without the baseline profile, so a change that slows
 * startup shows up in the numbers. On CI's emulator the times are rough; the gap between the two
 * is what matters. Results are in the test outputs as JSON.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Start-up is measured on the minified build users get, not the profile-recording one. */
    @Before
    fun minifiedOnly() = assumeFalse(BuildConfig.RECORDS_PROFILE)

    @Test
    fun coldStartWithoutProfile() = start(CompilationMode.None())

    @Test
    fun coldStartWithProfile() = start(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun start(mode: CompilationMode) = rule.measureRepeated(
        packageName = SmokeTest.PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Receipts")), SmokeTest.TIMEOUT)
    }
}
