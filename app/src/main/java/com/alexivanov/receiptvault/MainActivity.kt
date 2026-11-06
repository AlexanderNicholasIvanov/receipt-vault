package com.alexivanov.receiptvault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
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
import com.alexivanov.receiptvault.domain.AppLockPolicy
import com.alexivanov.receiptvault.ui.AppNav
import com.alexivanov.receiptvault.ui.lock.LockScreen
import com.alexivanov.receiptvault.ui.theme.ReceiptVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    /** Images shared into the app ("Share > Receipt Vault" from Gallery). */
    private val shared = Channel<Uri>(Channel.BUFFERED)

    private var lockEnabled = false
    private var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = (application as ReceiptVaultApp).container
        // Read once, synchronously, so receipts never flash on screen before the lock appears.
        lockEnabled = runBlocking(Dispatchers.IO) { app.settings.current().appLock }
        // Unlocking lasts for this process only: after Android kills the app in the background
        // and restores it, the lock comes back.
        locked = lockEnabled && !AppLockSession.unlocked
        applyRecentsPrivacy()
        lifecycleScope.launch {
            app.settings.settings.collect { s ->
                val wasEnabled = lockEnabled
                lockEnabled = s.appLock
                // Turned on just now from Settings, by the person already using the app: stay open.
                if (!s.appLock) locked = false else if (!wasEnabled) AppLockSession.unlocked = true
                applyRecentsPrivacy()
            }
        }

        if (savedInstanceState == null) handleShare(intent)
        setContent {
            ReceiptVaultTheme {
                Box(Modifier.fillMaxSize()) {
                    // Stays composed while locked so results from the camera or a file picker
                    // still arrive; it's hidden from the screen and from accessibility services.
                    Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
                        AppNav(sharedImages = shared.receiveAsFlow())
                    }
                    if (locked) {
                        LockScreen(onUnlocked = {
                            locked = false
                            AppLockSession.unlocked = true
                            AppLockSession.backgroundedAt = null
                        })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (AppLockPolicy.shouldLockOnReturn(lockEnabled, AppLockSession.backgroundedAt, SystemClock.elapsedRealtime())) {
            locked = true
            AppLockSession.unlocked = false
        }
        AppLockSession.backgroundedAt = null
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppLockSession.backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** With the lock on, the recent-apps screen shows a blank card instead of your receipts. */
    private fun applyRecentsPrivacy() = setRecentsScreenshotEnabled(!lockEnabled)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { shared.trySend(it) }
    }

}

/**
 * Lock state that must outlive the activity (rotation, recreation) but not the
 * process: after Android kills the app in the background, it starts locked.
 */
internal object AppLockSession {
    var unlocked = false
    var backgroundedAt: Long? = null
}
