package com.alexivanov.receiptvault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.alexivanov.receiptvault.capture.QuickAction
import com.alexivanov.receiptvault.domain.AppLockPolicy
import com.alexivanov.receiptvault.scan.PdfPages
import com.alexivanov.receiptvault.ui.AppNav
import com.alexivanov.receiptvault.ui.lock.LockScreen
import com.alexivanov.receiptvault.ui.theme.ReceiptVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    /** Photos or a PDF shared into the app ("Share > Receipt Vault"), or a PDF opened with it. */
    private val shared = Channel<List<Uri>>(Channel.BUFFERED)

    /** App shortcut, Quick Settings tile or widget taps. */
    private val quickActions = Channel<QuickAction>(Channel.CONFLATED)

    /** Receipts to open, from tapping a reminder notification. */
    private val openReceipts = Channel<Long>(Channel.CONFLATED)

    private var lockEnabled by mutableStateOf(false)

    /** Every open copy of the app reads the same session, so they lock and unlock together. */
    private val locked get() = lockEnabled && !AppLockSession.unlocked

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = (application as ReceiptVaultApp).container
        // Read once, synchronously, so receipts never flash on screen before the lock appears.
        lockEnabled = runBlocking(Dispatchers.IO) { app.settings.current().appLock }
        // Unlocking lasts for this process only: after Android kills the app in the background
        // and restores it, the lock comes back.
        applyRecentsPrivacy()
        lifecycleScope.launch {
            app.settings.settings.collect { s ->
                val wasEnabled = lockEnabled
                lockEnabled = s.appLock
                // Turned on just now from Settings, by the person already using the app: stay open.
                if (s.appLock && !wasEnabled) AppLockSession.unlocked = true
                applyRecentsPrivacy()
            }
        }

        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ReceiptVaultTheme {
                Box(Modifier.fillMaxSize()) {
                    // Stays composed while locked so results from the camera or a file picker
                    // still arrive; it's hidden from the screen and from accessibility services.
                    Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
                        AppNav(
                            sharedImages = shared.receiveAsFlow(),
                            openReceipts = openReceipts.receiveAsFlow(),
                            quickActions = quickActions.receiveAsFlow(),
                        )
                    }
                    if (locked) {
                        LockScreen(onUnlocked = { AppLockSession.unlocked = true })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLockSession.activityStarted(lockEnabled, SystemClock.elapsedRealtime())
    }

    override fun onStop() {
        super.onStop()
        AppLockSession.activityStopped(isChangingConfigurations, SystemClock.elapsedRealtime())
    }

    /** With the lock on, the recent-apps screen shows a blank card instead of your receipts. */
    private fun applyRecentsPrivacy() = setRecentsScreenshotEnabled(!lockEnabled)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getLongExtra(EXTRA_RECEIPT_ID, -1L)?.takeIf { it > 0 }?.let { openReceipts.trySend(it) }
        sharedUris(intent).takeIf { it.isNotEmpty() }?.let { shared.trySend(it) }
        QuickAction.from(intent)?.let { quickActions.trySend(it) }
    }

    companion object {
        const val EXTRA_RECEIPT_ID = "receipt_id"

        /** Pages from a share or "Open with": one photo, several photos of one long receipt, or a PDF. */
        internal fun sharedUris(intent: Intent?): List<Uri> {
            val type = intent?.type.orEmpty()
            val supported = type.startsWith("image/") || type == "application/pdf"
            if (intent == null || !supported) return emptyList()
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                Intent.ACTION_VIEW -> listOfNotNull(intent.data)
                else -> emptyList()
            }
            return uris.take(PdfPages.MAX_PAGES)
        }
    }

}

/**
 * Lock state that must outlive the activity (rotation, recreation) but not the
 * process: after Android kills the app in the background, it starts locked.
 *
 * It is shared because the app can be open more than once: sharing a photo starts
 * a second copy inside the sharing app's task. One copy noticing the time away must
 * lock them all, and only leaving every copy counts as leaving the app.
 */
internal object AppLockSession {
    var unlocked by mutableStateOf(false)
    var backgroundedAt: Long? = null
    private var visibleActivities = 0

    fun activityStarted(enabled: Boolean, now: Long) {
        visibleActivities++
        if (visibleActivities > 1) return
        if (AppLockPolicy.shouldLockOnReturn(enabled, backgroundedAt, now)) unlocked = false
        backgroundedAt = null
    }

    fun activityStopped(changingConfigurations: Boolean, now: Long) {
        visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
        if (visibleActivities == 0 && !changingConfigurations) backgroundedAt = now
    }

    /** Tests share this object across activities they may not have stopped. */
    @VisibleForTesting
    fun reset() {
        unlocked = false
        backgroundedAt = null
        visibleActivities = 0
    }
}
