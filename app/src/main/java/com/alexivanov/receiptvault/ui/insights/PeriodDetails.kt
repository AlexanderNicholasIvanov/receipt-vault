package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.Summary
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.review.SectionTitle

/**
 * Totals for one stretch of time: what was spent, tips, tax and discounts, then where it went by
 * category and by merchant. Home shows it for the period and filters chosen there.
 */
@Composable
internal fun PeriodDetails(summary: Summary, onCategory: (Category) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatsGrid(summary)
        if (summary.byCategory.isNotEmpty()) {
            SectionTitle("By category")
            Breakdown(summary.byCategory.map { (c, v) -> "${c.emoji} ${c.label}" to v }, summary, onClick = { i -> onCategory(summary.byCategory[i].first) })
        }
        if (summary.topMerchants.isNotEmpty()) {
            SectionTitle("Top merchants")
            Breakdown(summary.topMerchants, summary)
        }
    }
}

@Composable
private fun StatsGrid(s: Summary) {
    // Tips, tax, discounts and subtotals only when the receipts have them: a row of $0.00 says nothing.
    val tiles = listOfNotNull(
        "Total spent" to money(s.totalCents, s.currency),
        "Receipts" to s.count.toString(),
        "Average receipt" to money(s.averageReceiptCents, s.currency),
        ("Tips" to money(s.tipCents, s.currency)).takeIf { s.tipCents != 0L },
        s.averageTipPercent?.let { "Average tip" to "%.1f%%".format(it) },
        ("Tax" to money(s.taxCents, s.currency)).takeIf { s.taxCents != 0L },
        ("Discounts" to money(s.discountCents, s.currency)).takeIf { s.discountCents != 0L },
        ("Pre-tax subtotal" to money(s.subtotalCents, s.currency)).takeIf { s.subtotalCents != 0L },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(if (isLargeFont()) 1 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                // An odd tile out keeps its half width instead of stretching across.
                if (row.size == 1 && !isLargeFont()) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Single-hue horizontal magnitude bars; the label and value text carry identity, not color. */
@Composable
private fun Breakdown(rows: List<Pair<String, Long>>, s: Summary, onClick: ((Int) -> Unit)? = null) {
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEachIndexed { i, (label, value) ->
                Column(if (onClick != null) Modifier.clickable(onClickLabel = "See the breakdown") { onClick(i) } else Modifier) {
                    val share = if (s.totalCents > 0) " · %.0f%%".format(100.0 * value / s.totalCents) else ""
                    LabelValue(label, money(value, s.currency) + share)
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
