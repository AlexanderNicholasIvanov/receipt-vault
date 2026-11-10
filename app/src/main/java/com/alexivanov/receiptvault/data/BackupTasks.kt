package com.alexivanov.receiptvault.data

import com.alexivanov.receiptvault.domain.Problems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a backup or restore in the app's scope, so leaving Settings or rotating the phone
 * doesn't stop it halfway. Settings shows that one is running and how it ended; an outcome
 * nobody is there to see goes to [onUnseenResult] (a toast in the app).
 */
class BackupTasks(
    private val scope: CoroutineScope,
    private val onUnseenResult: (String) -> Unit,
) {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _result = MutableStateFlow<String?>(null)

    /** How the last task ended, until the Settings screen has shown it. */
    val result: StateFlow<String?> = _result

    private val watchers = AtomicInteger()

    /** Called while the Settings screen is open; the returned function says it closed. */
    fun watch(): () -> Unit {
        watchers.incrementAndGet()
        return { watchers.decrementAndGet() }
    }

    /** Starts [task], which returns the message to show. False if one is already running. */
    fun run(task: suspend () -> String): Boolean {
        if (!_busy.compareAndSet(expect = false, update = true)) return false
        scope.launch {
            val message = try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Backup problems explain themselves; anything else gets plain words.
                Problems.message("Something went wrong", e)
            } finally {
                _busy.value = false
            }
            if (watchers.get() > 0) _result.value = message else onUnseenResult(message)
        }
        return true
    }

    fun resultShown() {
        _result.value = null
    }
}
