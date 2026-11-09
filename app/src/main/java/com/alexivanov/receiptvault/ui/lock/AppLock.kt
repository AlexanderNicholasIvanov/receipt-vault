package com.alexivanov.receiptvault.ui.lock

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alexivanov.receiptvault.R

/** Fingerprint, face or the phone's PIN/pattern/password, via Android's own prompt. */
object Authenticator {
    private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Null when the lock can be used, otherwise why not, in words for the user. */
    fun unavailableReason(context: Context): String? {
        val manager = context.getSystemService(BiometricManager::class.java) ?: return "This phone can't lock apps."
        return when (manager.canAuthenticate(ALLOWED)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "Set up a screen lock (PIN, pattern, fingerprint or face) in the phone's Settings first."
            else -> "This phone's screen lock isn't available right now."
        }
    }

    fun authenticate(context: Context, title: String, onResult: (success: Boolean, message: String?) -> Unit) {
        try {
            BiometricPrompt.Builder(context)
                .setTitle(title)
                .setSubtitle("Use your fingerprint, face or screen lock")
                .setAllowedAuthenticators(ALLOWED)
                .build()
                .authenticate(
                    CancellationSignal(),
                    context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true, null)
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                            onResult(false, errString.toString().takeIf { errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED })
                    },
                )
        } catch (e: RuntimeException) {
            onResult(false, "Couldn't show the unlock prompt: ${e.message ?: e::class.java.simpleName}")
        }
    }
}

/**
 * True while the lock screen is up. Dialogs, sheets and menus are separate windows that
 * would sit on top of it, so they check this and stay away until the app is unlocked.
 */
val LocalAppLocked = compositionLocalOf { false }

/**
 * Covers the app while it's locked. The app keeps running underneath (so a
 * camera or file-picker result isn't lost), but nothing of it is visible or
 * readable by accessibility services until unlocked.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    fun prompt() = Authenticator.authenticate(context, "Unlock Receipt Vault") { ok, msg ->
        if (ok) onUnlocked() else message = msg
    }
    LaunchedEffect(Unit) { prompt() }

    // Back would otherwise reach the hidden screens and close them unseen.
    val activity = LocalActivity.current
    BackHandler { activity?.moveTaskToBack(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Swallow taps so nothing underneath can be pressed.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(120.dp))
        Text("Receipt Vault is locked", style = MaterialTheme.typography.headlineSmall)
        message?.let { Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::prompt) { Text("Unlock") }
    }
}
