package com.alexivanov.receiptvault.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.alexivanov.receiptvault.ReceiptVaultApp

/**
 * Returns a function that asks for the notification permission if it hasn't
 * been granted. Called when the user sets up a reminder or budget, so the
 * request comes with an obvious reason.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) (context.applicationContext as ReceiptVaultApp).container.checkAlertsSoon()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
