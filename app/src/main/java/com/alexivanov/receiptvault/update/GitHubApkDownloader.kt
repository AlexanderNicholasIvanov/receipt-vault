package com.alexivanov.receiptvault.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a release APK into app-private cache and refuses to hand it to
 * the installer unless it is exactly what GitHub advertised (size, SHA-256)
 * and is a newer build of this app signed with the same key.
 */
class GitHubApkDownloader(private val context: Context) : ApkDownloader {

    private val dir: File get() = File(context.cacheDir, "updates").apply { mkdirs() }

    override suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "ReceiptVault-${info.versionCode}.apk")
        val partial = File(dir, target.name + ".part")

        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true // GitHub redirects release assets to its CDN.
        }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (conn.responseCode !in 200..299) throw UpdateException("GitHub returned HTTP ${conn.responseCode}. Try again later.")
            val expected = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var read = 0L
            conn.inputStream.use { input: InputStream ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (expected > 0) onProgress(read.toFloat() / expected)
                    }
                }
            }
            if (info.sizeBytes > 0 && read != info.sizeBytes) {
                throw UpdateException("Download was incomplete ($read of ${info.sizeBytes} bytes). Try again.")
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            conn.disconnect()
        }

        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256 != null && info.sha256 != actualSha) {
            partial.delete()
            throw UpdateException("The download didn't match GitHub's checksum, so it wasn't installed. Try again.")
        }
        if (!partial.renameTo(target)) throw UpdateException("Couldn't save the update.")
        verifyPackage(target, info)
        target
    }

    private fun verifyPackage(apk: File, info: UpdateInfo) {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: fail(apk, "The downloaded file isn't a valid app package.")
        if (archive.packageName != context.packageName) fail(apk, "The download is a different app (${archive.packageName}).")
        if (archive.longVersionCode <= installedVersionCode()) fail(apk, "The download isn't newer than the installed version.")

        val newSigners = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val installedSigners = runCatching {
            pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners?.toSet()
        }.getOrNull().orEmpty()
        // Android would reject a key mismatch anyway; catching it here gives a clear message instead of
        // a generic "App not installed".
        if (newSigners.isNotEmpty() && installedSigners.isNotEmpty() && newSigners.intersect(installedSigners).isEmpty()) {
            fail(
                apk,
                "This update is signed with a different key than the app you have installed " +
                    "(for example a debug build). Back up in Settings, uninstall, install ${info.versionName}, then restore.",
            )
        }
    }

    private fun installedVersionCode(): Long =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode

    private fun fail(apk: File, message: String): Nothing {
        apk.delete()
        throw UpdateException(message)
    }

    /** Removes downloaded APKs; called on launch so old updates don't sit in storage. */
    fun clearDownloads() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
