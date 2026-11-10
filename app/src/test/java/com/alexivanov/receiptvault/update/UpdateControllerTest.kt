package com.alexivanov.receiptvault.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {

    private val info = UpdateInfo(
        versionCode = 12, versionName = "1.0.12", apkUrl = "https://x/ReceiptVault.apk",
        releaseUrl = "https://x/releases/v1.0.12", sizeBytes = 100, sha256 = "ab".repeat(32),
    )
    private val apk = File("ReceiptVault-12.apk")

    private class FakeSource(var outcome: UpdateChecker.Outcome) : ReleaseSource {
        var calls = 0
        override suspend fun check(currentVersionCode: Int): UpdateChecker.Outcome {
            calls++
            return outcome
        }
    }

    private fun TestScope.controller(
        source: ReleaseSource,
        downloader: ApkDownloader = ApkDownloader { _, _ -> apk },
    ) = UpdateController(backgroundScope, source, downloader, currentVersionCode = 5)

    @Test
    fun `manual check reports every outcome`() = runTest {
        val source = FakeSource(UpdateChecker.Outcome.UpToDate)
        val c = controller(source)

        c.check(manual = true)
        runCurrent()
        assertEquals(UpdateState.UpToDate, c.state.value)

        source.outcome = UpdateChecker.Outcome.Failed
        c.check(manual = true)
        runCurrent()
        assertEquals(UpdateState.CheckFailed, c.state.value)

        source.outcome = UpdateChecker.Outcome.Newer(info)
        c.check(manual = true)
        runCurrent()
        assertEquals(UpdateState.Available(info), c.state.value)
        assertEquals(info, c.offered)
    }

    @Test
    fun `automatic check runs once per process and stays quiet`() = runTest {
        val source = FakeSource(UpdateChecker.Outcome.Failed)
        val c = controller(source)

        c.checkAutomatically()
        runCurrent()
        assertEquals(UpdateState.Idle, c.state.value)
        assertNull(c.offered)

        c.checkAutomatically()
        runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun `download reports progress, then is ready and auto-installs once`() = runTest {
        val seen = mutableListOf<Float?>()
        val c = controller(FakeSource(UpdateChecker.Outcome.Newer(info))) { _, onProgress ->
            onProgress(0.5f)
            onProgress(1.5f) // clamped
            apk
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.collect { s -> if (s is UpdateState.Downloading) seen += s.progress }
        }
        c.check()
        runCurrent()

        c.download()
        runCurrent()
        assertEquals(UpdateState.ReadyToInstall(info, apk), c.state.value)
        assertEquals(listOf(null, 0.5f, 1f), seen)
        assertTrue(c.takeAutoInstall())
        assertFalse(c.takeAutoInstall())
    }

    @Test
    fun `download failures keep the release on offer with a readable message`() = runTest {
        var error: Exception = UpdateException("The download didn't match GitHub's checksum.")
        val c = controller(FakeSource(UpdateChecker.Outcome.Newer(info))) { _, _ -> throw error }
        c.check()
        runCurrent()

        c.download()
        runCurrent()
        assertEquals(UpdateState.DownloadFailed(info, "The download didn't match GitHub's checksum."), c.state.value)

        error = IOException("timeout")
        c.download() // Retry
        runCurrent()
        assertEquals(UpdateState.DownloadFailed(info, "Download failed. Check your internet connection and try again."), c.state.value)
        assertEquals(info, c.offered)
    }

    @Test
    fun `cancel returns to available and does not auto-install`() = runTest {
        val never = CompletableDeferred<File>()
        val c = controller(FakeSource(UpdateChecker.Outcome.Newer(info))) { _, _ -> never.await() }
        c.check()
        runCurrent()

        c.download()
        runCurrent()
        assertEquals(UpdateState.Downloading(info, null), c.state.value)

        c.cancelDownload()
        runCurrent()
        assertEquals(UpdateState.Available(info), c.state.value)
        assertFalse(c.takeAutoInstall())
    }

    @Test
    fun `checking again never discards a downloaded update`() = runTest {
        val source = FakeSource(UpdateChecker.Outcome.Newer(info))
        val c = controller(source)
        c.check()
        runCurrent()
        c.download()
        runCurrent()

        source.outcome = UpdateChecker.Outcome.UpToDate
        c.check(manual = true)
        runCurrent()
        assertEquals(UpdateState.ReadyToInstall(info, apk), c.state.value)
        assertEquals(1, source.calls)
    }

    @Test
    fun `checker reads the sha256 digest GitHub publishes for the asset`() {
        val hex = "0123456789abcdef".repeat(4)
        val json = """{"tag_name":"v1.0.12","html_url":"https://x","body":"version_code: 12",
            "assets":[{"name":"ReceiptVault.apk","browser_download_url":"https://x/a.apk","size":10,
            "digest":"sha256:${hex.uppercase()}"}]}"""
        val newer = UpdateChecker.parse(json, currentVersionCode = 5) as UpdateChecker.Outcome.Newer
        assertEquals(hex, newer.info.sha256)
        assertEquals(10L, newer.info.sizeBytes)

        val noDigest = UpdateChecker.parse(json.replace("\"digest\":\"sha256:${hex.uppercase()}\"", "\"digest\":null"), 5)
        assertNull((noDigest as UpdateChecker.Outcome.Newer).info.sha256)
    }
}

