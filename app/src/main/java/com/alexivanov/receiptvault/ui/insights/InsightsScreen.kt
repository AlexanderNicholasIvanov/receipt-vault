package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.data.AppSettings
import com.alexivanov.receiptvault.domain.Insights
import com.alexivanov.receiptvault.domain.Period
import com.alexivanov.receiptvault.domain.Summary
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.review.SectionTitle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val all by app.receipts.receipts.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var period by remember { mutableStateOf(Period.THIS_YEAR) }

    val today = LocalDate.now()
    val summary = remember(all, period, settings.defaultCurrency) {
        Insights.summarize(Insights.inPeriod(all, period, today), settings.defaultCurrency)
    }
    val monthly = remember(all, summary.currency) { Insights.monthly(all, summary.currency, today) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Monthly spending", style = MaterialTheme.typography.titleMedium)
                        Text("Last 6 months · ${summary.currency}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        MonthlyBars(monthly, summary.currency)
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Period.entries) { p ->
                        FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
                    }
                }
            }
            item { StatsGrid(summary) }
            if (summary.byCategory.isNotEmpty()) {
                item { SectionTitle("By category") }
                item { Breakdown(summary.byCategory.map { (c, v) -> "${c.emoji} ${c.label}" to v }, summary) }
            }
            if (summary.topMerchants.isNotEmpty()) {
                item { SectionTitle("Top merchants") }
                item { Breakdown(summary.topMerchants, summary) }
            }
        }
    }
}

@Composable
private fun StatsGrid(s: Summary) {
    val tiles = listOf(
        "Total spent" to money(s.totalCents, s.currency),
        "Receipts" to s.count.toString(),
        "Average receipt" to money(s.averageReceiptCents, s.currency),
        "Tips" to money(s.tipCents, s.currency),
        "Average tip" to (s.averageTipPercent?.let { "%.1f%%".format(it) } ?: "—"),
        "Tax" to money(s.taxCents, s.currency),
        "Discounts" to money(s.discountCents, s.currency),
        "Pre-tax subtotal" to money(s.subtotalCents, s.currency),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** Single-hue horizontal magnitude bars; the label and value text carry identity, not color. */
@Composable
private fun Breakdown(rows: List<Pair<String, Long>>, s: Summary) {
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { (label, value) ->
                Column {
                    Row {
                        Text(label, Modifier.weight(1f))
                        val share = if (s.totalCents > 0) " · %.0f%%".format(100.0 * value / s.totalCents) else ""
                        Text(money(value, s.currency) + share, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { value.toFloat() / max },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                        gapSize = 2.dp,
                    )
                }
            }
        }
    }
}

/**
 * One series, one hue: bars rounded only at the data end and anchored to the
 * baseline, a recessive baseline, and tap-to-read (touch has no hover). The
 * selected month's value is spelled out in text above the plot.
 */
@Composable
private fun MonthlyBars(data: List<Pair<YearMonth, Long>>, currency: String) {
    var selected by remember(data) { mutableIntStateOf(data.lastIndex) }
    val barColor = MaterialTheme.colorScheme.primary
    val mutedBar = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val max = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val locale = LocalConfiguration.current.locales[0]
    val description = data.joinToString("; ") { (m, v) ->
        "${m.month.getDisplayName(TextStyle.FULL, locale)} ${m.year}: ${money(v, currency)}"
    }

    Column(Modifier.semantics { contentDescription = "Monthly spending. $description" }) {
        data.getOrNull(selected)?.let { (m, v) ->
            Text(
                "${m.month.getDisplayName(TextStyle.FULL, locale)} ${m.year}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(money(v, currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(data) {
                    detectTapGestures { pos ->
                        val slot = size.width / data.size.toFloat()
                        selected = (pos.x / slot).toInt().coerceIn(0, data.lastIndex)
                    }
                },
            ) {
                val slot = size.width / data.size
                val barWidth = (slot * 0.5f).coerceAtMost(28.dp.toPx())
                val radius = 4.dp.toPx()
                val baseline = size.height - 1.dp.toPx()
                data.forEachIndexed { i, (_, value) ->
                    val h = (value.toFloat() / max) * (baseline - 4.dp.toPx())
                    if (h <= 0f) return@forEachIndexed
                    val left = i * slot + (slot - barWidth) / 2
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left, top = baseline - h, right = left + barWidth, bottom = baseline,
                                topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                            ),
                        )
                    }
                    drawPath(path, if (i == selected) barColor else mutedBar)
                }
                drawLine(axisColor, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            data.forEachIndexed { i, (m, _) ->
                Text(
                    m.month.getDisplayName(TextStyle.SHORT, locale),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == selected) FontWeight.SemiBold else null,
                    color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

