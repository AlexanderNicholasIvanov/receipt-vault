package com.alexivanov.receiptvault.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.BuildConfig
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.data.AppSettings
import com.alexivanov.receiptvault.domain.CsvExport
import com.alexivanov.receiptvault.ui.review.SectionTitle
import com.alexivanov.receiptvault.ui.shareFile
import com.alexivanov.receiptvault.ui.update.UpdateCard
import com.alexivanov.receiptvault.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var currency by remember { mutableStateOf("") }
    val updateState by app.updates.state.collectAsStateWithLifecycle()

    LaunchedEffect(settings.defaultCurrency) { currency = settings.defaultCurrency }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Scanning") }
            ListItem(
                headlineContent = { Text("Save scan location") },
                supportingContent = { Text("Attach where you were when scanning with the camera. Gallery imports never use your current location.") },
                trailingContent = {
                    Switch(checked = settings.tagLocation, onCheckedChange = { v -> scope.launch { app.settings.setTagLocation(v) } })
                },
            )
            ListItem(
                headlineContent = { Text("Day-first dates") },
                supportingContent = { Text("Read 03/04/2026 as 3 April rather than March 4.") },
                trailingContent = {
                    Switch(checked = settings.dayFirstDates, onCheckedChange = { v -> scope.launch { app.settings.setDayFirstDates(v) } })
                },
            )
            OutlinedTextField(
                value = currency,
                onValueChange = { v ->
                    currency = v.uppercase().filter { it.isLetter() }.take(3)
                    if (currency.length == 3) scope.launch { app.settings.setDefaultCurrency(currency) }
                },
                label = { Text("Default currency") },
                supportingText = { Text("Used when a receipt doesn't show one, e.g. USD, EUR, GBP") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Your data") }
            ListItem(
                headlineContent = { Text("Export to CSV") },
                supportingContent = { Text("Every receipt with amounts, tips, tax, places and items. Opens in Excel or Google Sheets.") },
                modifier = Modifier.clickable {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            val csv = CsvExport.build(app.receipts.getAll(), app.receipts.getAllItems())
                            File(context.cacheDir, "exports").apply { mkdirs() }
                                .resolve("receipts-${LocalDate.now()}.csv")
                                .apply { writeText(csv) }
                        }
                        shareFile(context, file, "text/csv", "Export receipts")
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Privacy") },
                supportingContent = {
                    Text("Receipts, photos and text recognition stay on this phone. The app only goes online to check GitHub for updates and to look up addresses on the map.")
                },
            )
            HorizontalDivider()

            Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Updates") }
            ListItem(
                headlineContent = { Text("Check for updates") },
                supportingContent = {
                    Text(
                        when (updateState) {
                            UpdateState.Checking -> "Checking GitHub for a newer version…"
                            UpdateState.UpToDate -> "You're on the latest version (${BuildConfig.VERSION_NAME})."
                            UpdateState.CheckFailed -> "Couldn't reach GitHub. Check your connection and try again."
                            is UpdateState.Available, is UpdateState.Downloading,
                            is UpdateState.ReadyToInstall, is UpdateState.DownloadFailed -> "A newer version is available."
                            UpdateState.Idle -> "Installed: ${BuildConfig.VERSION_NAME}"
                        },
                    )
                },
                trailingContent = {
                    if (updateState == UpdateState.Checking) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                },
                modifier = Modifier.clickable(enabled = updateState != UpdateState.Checking) { app.updates.check(manual = true) },
            )
            UpdateCard(
                controller = app.updates,
                state = updateState,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ListItem(
                headlineContent = { Text("Check automatically") },
                supportingContent = { Text("Look for a new version each time the app opens.") },
                trailingContent = {
                    Switch(checked = settings.autoCheckUpdates, onCheckedChange = { v -> scope.launch { app.settings.setAutoCheckUpdates(v) } })
                },
            )
            ListItem(
                headlineContent = { Text("Releases page") },
                supportingContent = { Text("github.com/${BuildConfig.UPDATE_REPO}/releases", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/${BuildConfig.UPDATE_REPO}/releases".toUri()))
                },
            )
        }
    }
}
