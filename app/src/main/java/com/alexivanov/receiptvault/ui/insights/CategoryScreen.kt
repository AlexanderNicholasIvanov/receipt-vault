package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.data.AppSettings
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.CategoryBreakdown
import com.alexivanov.receiptvault.domain.CostEstimate
import com.alexivanov.receiptvault.domain.SpendingBreakdown
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.review.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Rows shown before "Show all". */
private const val SHORT_LIST = 15

/**
 * What one category costs and what it is made of: the usual amount per receipt, the things
 * usually bought and their usual price, and where the money goes. [category] null is everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(category: Category?, onBack: () -> Unit, onItem: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val receipts by app.receipts.receipts.collectAsStateWithLifecycle(initialValue = null)
    val items by app.receipts.allItems.collectAsStateWithLifecycle(initialValue = null)
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val rates = settings.activeRates
    val breakdown by produceState<CategoryBreakdown?>(null, receipts, items, settings.defaultCurrency, rates) {
        val r = receipts ?: return@produceState
        val i = items ?: return@produceState
        value = withContext(Dispatchers.Default) {
            SpendingBreakdown.of(category, r, i, settings.defaultCurrency, LocalDate.now(), rates)
        }
    }
    var allItems by rememberSaveable { mutableStateOf(false) }
    var allPlaces by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category?.let { "${it.emoji} ${it.label}" } ?: "Everything you buy") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val b = breakdown ?: return@Scaffold
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (b.receipts == 0) {
                item {
                    Text(
                        "No receipts here yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }
            item { Overview(b) }

            item { SectionTitle(if (category == null) "What you usually buy" else "What you usually buy here") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (b.items.isEmpty()) {
                            Text(
                                "These receipts have no line items. Items read from a scan, or typed in when you add a receipt, show up here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (b.itemizedReceipts < b.receipts) {
                                Text(
                                    "From the ${b.itemizedReceipts} of ${b.receipts} receipts that list their items.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val shown = if (allItems) b.items else b.items.take(SHORT_LIST)
                            shown.forEach { item ->
                                Column(Modifier.clickable(onClickLabel = "See its price over time") { onItem(SpendingBreakdown.itemKey(item.name)) }) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text(item.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text("usually ${money(item.typicalUnitCents, b.currency)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val times = if (item.timesBought == 1) "Bought once" else "Bought ${item.timesBought} times"
                                    val range = if (item.lowestUnitCents != item.highestUnitCents) {
                                        " · ${money(item.lowestUnitCents, b.currency)}–${money(item.highestUnitCents, b.currency)}"
                                    } else ""
                                    val last = if (item.timesBought > 1 && item.lastUnitCents != item.typicalUnitCents) {
                                        " · last ${money(item.lastUnitCents, b.currency)}"
                                    } else ""
                                    Text(
                                        "$times$range$last · ${money(item.totalCents, b.currency)} in all",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (b.items.size > SHORT_LIST) {
                                TextButton(onClick = { allItems = !allItems }, modifier = Modifier.align(Alignment.End)) {
                                    Text(if (allItems) "Show fewer" else "Show all ${b.items.size} items")
                                }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Where it goes") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val shown = if (allPlaces) b.places else b.places.take(SHORT_LIST)
                        shown.forEach { place ->
                            Column {
                                Row {
                                    Text(place.merchant, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text(money(place.totalCents, b.currency), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    (if (place.visits == 1) "1 visit" else "${place.visits} visits") +
                                        " · usually ${money(place.typicalCents, b.currency)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (b.places.size > SHORT_LIST) {
                            TextButton(onClick = { allPlaces = !allPlaces }, modifier = Modifier.align(Alignment.End)) {
                                Text(if (allPlaces) "Show fewer" else "Show all ${b.places.size} places")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Overview(b: CategoryBreakdown) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${money(b.monthlyCents, b.currency)} a month", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                CostEstimate.howOften(b.timesPerMonth) + " · " +
                    (if (b.receipts == 1) "1 receipt" else "${b.receipts} receipts") +
                    (b.since?.let { " since ${formatDate(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (b.otherCurrencyCount > 0) {
                Text(
                    "${b.otherCurrencyCount} ${if (b.otherCurrencyCount == 1) "receipt" else "receipts"} in other currencies not included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val tiles = listOf(
            "You usually pay" to money(b.typicalCents, b.currency),
            "Average" to money(b.averageCents, b.currency),
            "Lowest" to money(b.lowestCents, b.currency),
            "Highest" to money(b.highestCents, b.currency),
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Text(
                "Per receipt · ${money(b.totalCents, b.currency)} in all",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
