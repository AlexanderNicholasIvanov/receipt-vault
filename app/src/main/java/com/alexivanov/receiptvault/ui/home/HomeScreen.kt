package com.alexivanov.receiptvault.ui.home

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.alexivanov.receiptvault.ui.lock.LocalAppLocked
import com.alexivanov.receiptvault.ui.shareFile
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
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
import com.alexivanov.receiptvault.domain.BudgetLevel
import com.alexivanov.receiptvault.domain.BudgetUse
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.Period
import com.alexivanov.receiptvault.domain.SortOrder
import com.alexivanov.receiptvault.domain.Tags
import com.alexivanov.receiptvault.domain.Summary
import com.alexivanov.receiptvault.ui.appViewModel
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.review.CaptureSource
import com.alexivanov.receiptvault.ui.update.UpdateCard
import com.alexivanov.receiptvault.update.UpdateState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (Long) -> Unit,
    onCaptured: (List<Uri>, CaptureSource) -> Unit,
    onManual: () -> Unit,
    onInsights: () -> Unit,
    onSettings: () -> Unit,
    scanRequests: Flow<Unit> = emptyFlow(),
) {
    val vm = appViewModel { app, _ -> HomeViewModel(app) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    fun makeReport() {
        if (working) return
        working = true
        scope.launch {
            try {
                shareFile(context, vm.expenseReport(context.cacheDir), "application/pdf", "Share expense report")
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't make the report: ${e.message ?: e::class.java.simpleName}")
            } finally {
                working = false
            }
        }
    }

    val selected by vm.selected.collectAsStateWithLifecycle()
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }

    // "Moved to trash" with Undo, for deletes from here or from a receipt's screen.
    LaunchedEffect(Unit) {
        vm.pendingUndo.filterNotNull().collectLatest { ids ->
            vm.undoOffered(ids)
            val n = ids.size
            val result = snackbar.showSnackbar(
                if (n == 1) "Moved to trash" else "Moved $n receipts to trash",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoTrash(ids)
        }
    }

    val capture = rememberCaptureActions(
        onCaptured = onCaptured,
        onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
    )
    // "Scan receipt" from an app shortcut, the Quick Settings tile or the widget.
    LaunchedEffect(scanRequests) { scanRequests.collect { capture.scan() } }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
                    },
                    actions = {
                        TextButton(onClick = vm::selectAll) { Text("All") }
                        IconButton(onClick = vm::trashSelected) {
                            Icon(Icons.Default.Delete, contentDescription = "Move selected to trash")
                        }
                        Box {
                            IconButton(onClick = { showMore = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More actions") }
                            DropdownMenu(expanded = showMore && !LocalAppLocked.current, onDismissRequest = { showMore = false }) {
                                DropdownMenuItem(
                                    text = { Text("Expense report (PDF)") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_report), contentDescription = null) },
                                    onClick = { showMore = false; makeReport() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export to CSV") },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        showMore = false
                                        scope.launch { shareFile(context, vm.exportSelected(context.cacheDir), "text/csv", "Export receipts") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Download photos") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                                    onClick = {
                                        showMore = false
                                        scope.launch {
                                            val n = vm.downloadSelectedPhotos()
                                            snackbar.showSnackbar(
                                                when (n) {
                                                    0 -> "None of these receipts have a photo."
                                                    1 -> "Saved 1 photo to Pictures/Receipt Vault"
                                                    else -> "Saved $n photos to Pictures/Receipt Vault"
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Receipts", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        if (ui.receipts.isNotEmpty()) {
                            IconButton(onClick = ::makeReport, enabled = !working) {
                                Icon(painterResource(R.drawable.ic_report), contentDescription = "Expense report")
                            }
                        }
                        IconButton(onClick = onInsights) {
                            Icon(painterResource(R.drawable.ic_insights), contentDescription = "Insights")
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    },
                )
            }
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
            ui.update?.let { state ->
                item {
                    UpdateCard(
                        controller = vm.updates,
                        state = state,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        onLater = (state as? UpdateState.Available)?.let { a -> { vm.dismissUpdate(a.info.versionCode) } },
                    )
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        val n = ui.filter.activeCount
                        FilterChip(
                            selected = n > 0 || ui.filter.sort != SortOrder.NEWEST,
                            onClick = { showFilters = true },
                            label = { Text(if (n > 0) "Filters ($n)" else "Filters") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_filter), contentDescription = null, Modifier.size(18.dp)) },
                        )
                    }
                    val from = ui.filter.from
                    val to = ui.filter.to
                    if (from != null && to != null) {
                        item {
                            FilterChip(
                                selected = true,
                                onClick = { vm.setFilter(ui.filter.copy(from = null, to = null)) },
                                label = { Text(if (from == to) formatDate(from) else "${formatDate(from)} – ${formatDate(to)}") },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Clear dates", Modifier.size(18.dp)) },
                            )
                        }
                    }
                    items(Period.entries) { p ->
                        FilterChip(selected = !ui.filter.hasDates && ui.period == p, onClick = { vm.setPeriod(p) }, label = { Text(p.label) })
                    }
                }
            }
            ui.summary?.let { s -> item { SummaryCard(s, ui.budget) } }
            if (ui.hasAny) {
                item {
                    OutlinedTextField(
                        value = ui.query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text("Search merchant, item, note, tag") },
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
                        when {
                            ui.query.isNotBlank() -> "No receipts match \"${ui.query}\"."
                            ui.filter.activeCount > 0 -> "No receipts match these filters."
                            else -> "No receipts in this period."
                        },
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(ui.receipts, key = { it.id }) { r ->
                    ReceiptRow(
                        r,
                        selected = r.id in selected,
                        onClick = { if (selecting) vm.toggleSelected(r.id) else onOpen(r.id) },
                        onLongClick = { vm.toggleSelected(r.id) },
                    )
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            current = ui.filter,
            tagsInUse = ui.tagsInUse,
            onApply = { vm.setFilter(it); showFilters = false },
            onDismiss = { showFilters = false },
        )
    }

    if (showSheet && !LocalAppLocked.current) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text("Add a receipt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SheetOption(painterResource(R.drawable.ic_camera), "Scan receipt", "Auto-crops and flattens the paper. Long receipts can be scanned in several pages.") {
                    showSheet = false; capture.scan()
                }
                SheetOption(painterResource(R.drawable.ic_camera), "Take a photo", "Use the regular camera app.") {
                    showSheet = false; capture.camera()
                }
                SheetOption(painterResource(R.drawable.ic_photo), "Choose from gallery", "Import a receipt photo or screenshot.") {
                    showSheet = false; capture.gallery()
                }
                SheetOption(painterResource(R.drawable.ic_report), "Import a PDF", "An emailed invoice or a saved order confirmation.") {
                    showSheet = false; capture.pdf()
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
private fun SummaryCard(s: Summary, budget: BudgetUse?) {
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
            if (budget != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { budget.fraction },
                    color = if (budget.level == BudgetLevel.OVER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                val left = budget.budgetCents - budget.spentCents
                Text(
                    if (left >= 0) {
                        "${money(left, s.currency)} left of ${money(budget.budgetCents, s.currency)} budget"
                    } else {
                        "${money(-left, s.currency)} over the ${money(budget.budgetCents, s.currency)} budget"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (s.convertedCount > 0 && s.ratesDate != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Includes ${s.convertedCount} receipt(s) in other currencies, converted at ${shortDate(s.ratesDate)} rates",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
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
private fun ReceiptRow(r: Receipt, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val category = Category.fromName(r.category)
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .semantics { this.selected = selected },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(category.emoji)
                }
            }
        },
        headlineContent = { Text(r.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val extras = listOfNotNull(
                formatDate(r.purchasedAt),
                r.tipCents?.takeIf { it > 0 }?.let { "tip ${money(it, r.currency)}" },
                Tags.decode(r.tags).takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
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

private fun shortDate(date: java.time.LocalDate): String =
    date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
