package com.alexivanov.receiptvault.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.R
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.Period
import com.alexivanov.receiptvault.domain.Summary
import com.alexivanov.receiptvault.ui.appViewModel
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.review.CaptureSource
import com.alexivanov.receiptvault.update.UpdateInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (Long) -> Unit,
    onCaptured: (Uri, CaptureSource) -> Unit,
    onManual: () -> Unit,
    onInsights: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm = appViewModel { app, _ -> HomeViewModel(app) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = (context.applicationContext as com.alexivanov.receiptvault.ReceiptVaultApp).container
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }

    val capture = rememberCaptureActions(
        askForLocation = ui.settings.tagLocation && !app.location.hasPermission(),
        onCaptured = onCaptured,
        onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipts", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onInsights) {
                        Icon(painterResource(R.drawable.ic_insights), contentDescription = "Insights")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSheet = true },
                icon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
                text = { Text("Add receipt") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            ui.update?.let { info ->
                item { UpdateBanner(info, onDismiss = { vm.dismissUpdate(info.versionCode) }) }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(Period.entries) { p ->
                        FilterChip(selected = ui.period == p, onClick = { vm.setPeriod(p) }, label = { Text(p.label) })
                    }
                }
            }
            ui.summary?.let { s -> item { SummaryCard(s) } }
            if (ui.hasAny) {
                item {
                    OutlinedTextField(
                        value = ui.query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text("Search merchant, item, note, place") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (ui.query.isNotEmpty()) {
                                IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            when {
                ui.loading -> Unit
                !ui.hasAny -> item { EmptyState(onScan = { showSheet = true }) }
                ui.receipts.isEmpty() -> item {
                    Text(
                        if (ui.query.isBlank()) "No receipts in this period." else "No receipts match \"${ui.query}\".",
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(ui.receipts, key = { it.id }) { r ->
                    ReceiptRow(r, onClick = { onOpen(r.id) })
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text("Add a receipt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SheetOption(painterResource(R.drawable.ic_camera), "Scan receipt", "Auto-crops and flattens the paper. Best results.") {
                    showSheet = false; capture.scan()
                }
                SheetOption(painterResource(R.drawable.ic_camera), "Take a photo", "Use the regular camera app.") {
                    showSheet = false; capture.camera()
                }
                SheetOption(painterResource(R.drawable.ic_photo), "Choose from gallery", "Import a receipt photo or screenshot.") {
                    showSheet = false; capture.gallery()
                }
                ListItem(
                    headlineContent = { Text("Enter manually") },
                    supportingContent = { Text("No photo, just the numbers.") },
                    leadingContent = { Icon(Icons.Default.Create, contentDescription = null) },
                    modifier = Modifier.clickable { showSheet = false; onManual() },
                )
            }
        }
    }
}

@Composable
private fun SheetOption(icon: androidx.compose.ui.graphics.painter.Painter, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SummaryCard(s: Summary) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Spent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                money(s.totalCents, s.currency),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("Receipts", s.count.toString())
                Stat("Tips", money(s.tipCents, s.currency))
                Stat("Tax", money(s.taxCents, s.currency))
            }
            if (s.otherCurrencyCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${s.otherCurrencyCount} receipt(s) in other currencies not included",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ReceiptRow(r: Receipt, onClick: () -> Unit) {
    val category = Category.fromName(r.category)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(category.emoji) }
        },
        headlineContent = { Text(r.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val extras = listOfNotNull(
                formatDate(r.purchasedAt),
                r.tipCents?.takeIf { it > 0 }?.let { "tip ${money(it, r.currency)}" },
                r.paymentMethod,
            )
            Text(extras.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Text(money(r.totalCents, r.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
    )
}

@Composable
private fun EmptyState(onScan: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🧾", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text("No receipts yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Snap a photo of a receipt and the app reads the store, date, subtotal, tax, tip and total for you.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onScan) { Text("Add your first receipt") }
    }
}

@Composable
private fun UpdateBanner(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Update ${info.versionName} available", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Later") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, info.apkUrl.toUri()))
            }) { Text("Download") }
        }
    }
}
