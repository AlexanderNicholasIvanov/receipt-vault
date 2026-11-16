package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.data.AppSettings
import com.alexivanov.receiptvault.domain.ItemHistory
import com.alexivanov.receiptvault.domain.PriceHistory
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.temporal.ChronoUnit

/** One item's price every time it was bought: is it getting dearer, and where is it cheapest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemHistoryScreen(itemKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val receipts by app.receipts.receipts.collectAsStateWithLifecycle(initialValue = null)
    val items by app.receipts.allItems.collectAsStateWithLifecycle(initialValue = null)
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val rates = settings.activeRates
    // Loading, then the history, or null inside the result when the item is gone (its receipts deleted).
    val history by produceState<Result<ItemHistory?>?>(null, receipts, items, settings.defaultCurrency, rates) {
        val r = receipts ?: return@produceState
        val i = items ?: return@produceState
        value = Result.success(withContext(Dispatchers.Default) { PriceHistory.of(itemKey, r, i, settings.defaultCurrency, rates) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(history?.getOrNull()?.name ?: "Price history") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val loaded = history
        if (loaded == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val h = loaded.getOrNull()
        if (h == null) {
            Text(
                "No receipts list this item any more.",
                Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Summary(h) }
            if (h.points.size >= 2) item { PriceChart(h) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Every purchase", style = MaterialTheme.typography.titleMedium)
                        h.points.asReversed().forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider()
                            Row(Modifier.semantics(mergeDescendants = true) {}) {
                                Column(Modifier.weight(1f)) {
                                    Text(formatDate(p.date))
                                    Text(p.merchant, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(money(p.unitCents, h.currency), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(h: ItemHistory) {
    val first = h.points.first()
    val last = h.points.last()
    val cheapest = h.points.minBy { it.unitCents }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last paid", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(last.unitCents, h.currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            val change = h.changePercent
            Text(
                when {
                    change == null -> "Bought once, on ${formatDate(first.date)}."
                    change > 0 -> "▲ $change% since ${formatDate(first.date)}, when it was ${money(first.unitCents, h.currency)}."
                    change < 0 -> "▼ ${-change}% since ${formatDate(first.date)}, when it was ${money(first.unitCents, h.currency)}."
                    else -> "The same as on ${formatDate(first.date)}."
                },
            )
            if (h.points.size > 1) {
                Text(
                    "Bought ${h.points.size} times · cheapest ${money(cheapest.unitCents, h.currency)} at ${cheapest.merchant}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One series over time: a 2 dp line with a dot on each purchase, spaced by date so a long gap
 * looks long. The range is labelled, and the list below is the same data as text.
 */
@Composable
private fun PriceChart(h: ItemHistory) {
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val low = h.points.minOf { it.unitCents }
    val high = h.points.maxOf { it.unitCents }
    val first = h.points.first().date
    val days = ChronoUnit.DAYS.between(first, h.points.last().date).coerceAtLeast(1)
    val spoken = "Price from ${money(h.points.first().unitCents, h.currency)} on ${formatDate(first)} " +
        "to ${money(h.points.last().unitCents, h.currency)} on ${formatDate(h.points.last().date)}, " +
        "between ${money(low, h.currency)} and ${money(high, h.currency)}."
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).clearAndSetSemantics { contentDescription = spoken }) {
            Text(money(high, h.currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 6.dp)) {
                val dot = 4.dp.toPx()
                val w = size.width - dot * 2
                val hgt = size.height
                val span = (high - low).coerceAtLeast(1)
                fun at(i: Int): Offset {
                    val p = h.points[i]
                    val x = dot + w * ChronoUnit.DAYS.between(first, p.date) / days
                    // Flat when every price is the same.
                    val y = if (high == low) hgt / 2 else hgt - hgt * (p.unitCents - low) / span
                    return Offset(x, y)
                }
                drawLine(axisColor, Offset(0f, hgt), Offset(size.width, hgt), strokeWidth = 1.dp.toPx())
                val path = Path().apply {
                    h.points.indices.forEach { i -> at(i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                }
                drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                h.points.indices.forEach { i -> drawCircle(lineColor, radius = dot, center = at(i)) }
            }
            Text(money(low, h.currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(formatDate(first), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDate(h.points.last().date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
