package com.alexivanov.receiptvault.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** A newer build published on GitHub Releases. The user downloads it; nothing installs automatically. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseUrl: String,
    val sizeBytes: Long,
)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @SerialName("size") val size: Long = 0,
)

/**
 * Asks GitHub for the latest release and decides whether it is newer than the
 * running build. Every failure (offline, rate limit, bad JSON, no APK) is a
 * quiet [Outcome.Failed] or [Outcome.UpToDate]; it never blocks the UI.
 */
class UpdateChecker(private val repo: String) {

    sealed interface Outcome {
        data class Newer(val info: UpdateInfo) : Outcome
        data object UpToDate : Outcome
        data object Failed : Outcome
    }

    suspend fun check(currentVersionCode: Int): Outcome = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (conn.responseCode !in 200..299) return@withContext Outcome.Failed
                parse(conn.inputStream.bufferedReader().use { it.readText() }, currentVersionCode)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            Outcome.Failed
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val BODY_VERSION = Regex("""version_code:\s*(\d+)""")
        private val TAG_LAST_NUMBER = Regex("""(\d+)\D*$""")

        /** Pure decision logic, unit tested. */
        fun parse(releaseJson: String, currentVersionCode: Int): Outcome {
            val release = try {
                json.decodeFromString(GitHubRelease.serializer(), releaseJson)
            } catch (_: Exception) {
                return Outcome.Failed
            }
            // "version_code: N" in the release notes wins; else the tag's last number ("v1.0.42" -> 42).
            val code = release.body?.let { BODY_VERSION.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: TAG_LAST_NUMBER.find(release.tagName)?.groupValues?.get(1)?.toIntOrNull()
                ?: return Outcome.Failed
            if (code <= currentVersionCode) return Outcome.UpToDate
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank() }
                ?: return Outcome.UpToDate
            return Outcome.Newer(UpdateInfo(code, release.tagName, apk.browserDownloadUrl, release.htmlUrl, apk.size))
        }
    }
}
