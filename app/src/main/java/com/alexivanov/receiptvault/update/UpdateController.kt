package com.alexivanov.receiptvault.update

import com.alexivanov.receiptvault.domain.Problems
import com.alexivanov.receiptvault.domain.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where releases come from. [UpdateChecker] in the app, a fake in tests. */
fun interface ReleaseSource {
    suspend fun check(currentVersionCode: Int): UpdateChecker.Outcome
}

/** Fetches and verifies an APK. Throws [UpdateException] with a user-facing message on failure. */
fun interface ApkDownloader {
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File
}

class UpdateException(message: String) : UserFacingException(message)

/** Everything the update UI (home banner, Settings) needs to render. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val info: UpdateInfo, val progress: Float?) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateState
    data class DownloadFailed(val info: UpdateInfo, val message: String) : UpdateState
}

/**
 * The update flow: check GitHub -> download the APK in the app -> verify it ->
 * hand it to Android's installer. Installing always needs the user's tap on
 * the system dialog; nothing happens silently.
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val source: ReleaseSource,
    private val downloader: ApkDownloader,
    private val currentVersionCode: Int,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var autoChecked = false
    private var installWhenReady = false

    /** The release currently on offer, whatever stage it is at. */
    val offered: UpdateInfo?
        get() = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Downloading -> s.info
            is UpdateState.ReadyToInstall -> s.info
            is UpdateState.DownloadFailed -> s.info
            else -> null
        }

    /** Launch-time check: at most once per process, and silent when nothing is found. */
    fun checkAutomatically() {
        if (autoChecked) return
        autoChecked = true
        check(manual = false)
    }

    /** "Check for updates". A manual check reports up-to-date / failure; an automatic one stays quiet. */
    fun check(manual: Boolean = true) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        // Never throw away a downloaded APK just because the user checked again.
        if (_state.value is UpdateState.ReadyToInstall) return
        checkJob = scope.launch {
            val before = _state.value
            if (manual) _state.value = UpdateState.Checking
            _state.value = when (val outcome = source.check(currentVersionCode)) {
                is UpdateChecker.Outcome.Newer -> UpdateState.Available(outcome.info)
                UpdateChecker.Outcome.UpToDate -> if (manual) UpdateState.UpToDate else before
                UpdateChecker.Outcome.Failed -> if (manual) UpdateState.CheckFailed else before
            }
        }
    }

    fun download() {
        val info = offered ?: return
        if (downloadJob?.isActive == true) return
        installWhenReady = true
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(info, null)
            _state.value = try {
                val apk = downloader.download(info) { p -> _state.value = UpdateState.Downloading(info, p.coerceIn(0f, 1f)) }
                UpdateState.ReadyToInstall(info, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                UpdateState.DownloadFailed(info, e.message ?: "Download failed.")
            } catch (e: Exception) {
                UpdateState.DownloadFailed(info, Problems.message("Download failed", e, online = true))
            }
        }
    }

    /**
     * True once after a user-started download finishes, so the UI opens the
     * installer straight away instead of making the user tap twice.
     */
    fun takeAutoInstall(): Boolean = installWhenReady.also { installWhenReady = false }

    fun cancelDownload() {
        val info = offered ?: return
        installWhenReady = false
        downloadJob?.cancel()
        _state.value = UpdateState.Available(info)
    }
}
