package com.alexivanov.receiptvault.ui.update

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.alexivanov.receiptvault.update.UpdateController
import com.alexivanov.receiptvault.update.UpdateInfo
import com.alexivanov.receiptvault.update.UpdateInstaller
import com.alexivanov.receiptvault.update.UpdateState

/**
 * The download-and-install card for an available update. Renders nothing
 * unless [state] is about a specific release. Used by the home banner and by
 * Settings > Updates.
 */
@Composable
fun UpdateCard(
    controller: UpdateController,
    state: UpdateState,
    modifier: Modifier = Modifier,
    onLater: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var hint by remember { mutableStateOf<String?>(null) }

    fun install(apk: java.io.File) {
        hint = when (UpdateInstaller.install(context, apk)) {
            UpdateInstaller.Result.STARTED -> null
            UpdateInstaller.Result.NEEDS_PERMISSION ->
                "Turn on \"Allow from this source\" for Receipt Vault, then come back and tap Install."
            UpdateInstaller.Result.NO_INSTALLER -> "No app installer found on this phone."
        }
    }

    // Open the installer as soon as a download the user started is verified.
    LaunchedEffect(state) {
        if (state is UpdateState.ReadyToInstall && controller.takeAutoInstall()) install(state.apk)
    }

    val info: UpdateInfo = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.ReadyToInstall -> state.info
        is UpdateState.DownloadFailed -> state.info
        else -> return
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Update available: ${info.versionName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            val body = when (state) {
                is UpdateState.Available -> "Download ${formatSize(info.sizeBytes)} from GitHub and install it. Your receipts are kept."
                is UpdateState.Downloading -> state.progress?.let { "Downloading… ${(it * 100).toInt()}%" } ?: "Downloading…"
                is UpdateState.ReadyToInstall -> "Downloaded and verified. Tap Install and confirm on the next screen."
                is UpdateState.DownloadFailed -> state.message
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)

            if (state is UpdateState.Downloading) {
                val p = state.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    is UpdateState.Available -> {
                        if (onLater != null) TextButton(onClick = onLater) { Text("Later") }
                        Button(onClick = { hint = null; controller.download() }) { Text("Download & install") }
                    }
                    is UpdateState.Downloading -> TextButton(onClick = controller::cancelDownload) { Text("Cancel") }
                    is UpdateState.ReadyToInstall -> Button(onClick = { install(state.apk) }) { Text("Install") }
                    is UpdateState.DownloadFailed -> {
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, info.releaseUrl.ifBlank { info.apkUrl }.toUri()))
                        }) { Text("Open in browser") }
                        Button(onClick = { hint = null; controller.download() }) { Text("Retry") }
                    }
                }
            }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "the update"
    bytes < 1_000_000 -> "${(bytes / 1_000).coerceAtLeast(1)} KB"
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}
