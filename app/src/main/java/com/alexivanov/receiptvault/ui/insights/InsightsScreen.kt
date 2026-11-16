package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.data.AppSettings
import com.alexivanov.receiptvault.domain.Bills
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.CostEstimate
import com.alexivanov.receiptvault.domain.Insights
import com.alexivanov.receiptvault.domain.InsightsOverview
import com.alexivanov.receiptvault.domain.MonthlyCosts
import com.alexivanov.receiptvault.domain.Period
import com.alexivanov.receiptvault.domain.Retirement
import com.alexivanov.receiptvault.domain.RetirementEstimate
import com.alexivanov.receiptvault.domain.RetirementPlan
import com.alexivanov.receiptvault.domain.Summary
import com.alexivanov.receiptvault.domain.Trend
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.roughMoney
import com.alexivanov.receiptvault.ui.review.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(onBack: () -> Unit, onCategory: (Category?) -> Unit = {}, onRetirement: () -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val all by app.receipts.receipts.collectAsStateWithLifecycle(initialValue = null)
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var period by remember { mutableStateOf(Period.THIS_YEAR) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val today = LocalDate.now()
    val rates = settings.activeRates
    // Several milliseconds with thousands of receipts: off the main thread, so scrolling never waits.
    val overview by produceState<InsightsOverview?>(null, all, period, settings.defaultCurrency, rates) {
        val receipts = all ?: return@produceState
        value = withContext(Dispatchers.Default) {
            Insights.overview(receipts, period, settings.defaultCurrency, LocalDate.now(), rates)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Insights") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                )
                InsightsTabs(tab, onTab = { tab = it })
            }
        },
    ) { padding ->
        val data = overview
        if (data == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val costs = data.costs
        val estimate = remember(costs, settings.retirement, today.year) { Retirement.estimate(settings.retirement, costs.byCategory, today.year) }
        val content = Modifier.padding(padding)
        when (tab) {
            1 -> BillsTab(costs, today, content)
            2 -> FutureTab(costs, estimate, settings.retirement, onRetirement, content)
            else -> SpendingTab(
                data = data,
                converted = rates != null,
                today = today,
                estimate = estimate,
                plan = settings.retirement,
                period = period,
                onPeriod = { period = it },
                onBills = { tab = 1 },
                onCategory = onCategory,
                onRetirement = onRetirement,
                modifier = content,
            )
        }
    }
}

/**
 * Spending · Bills · Future. At large text sizes the three no longer fit across a phone, so the
 * row scrolls instead of breaking a word over two lines.
 */
@Composable
private fun InsightsTabs(selected: Int, onTab: (Int) -> Unit) {
    val tabs = @Composable {
        listOf("Spending", "Bills", "Future").forEachIndexed { i, title ->
            Tab(selected = selected == i, onClick = { onTab(i) }, text = { Text(title) })
        }
    }
    if (isLargeFont()) {
        PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp, tabs = tabs)
    } else {
        PrimaryTabRow(selectedTabIndex = selected, tabs = tabs)
    }
}

/** At 150% text size and up, side-by-side layouts stack so nothing is squeezed or cut off. */
@Composable
internal fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= 1.5f

/** A label and its value on one line, or stacked when the text is large. */
@Composable
internal fun LabelValue(label: String, value: String, labelWeight: FontWeight? = null) {
    if (isLargeFont()) {
        Column {
            Text(label, fontWeight = labelWeight)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Row {
            Text(label, Modifier.weight(1f), fontWeight = labelWeight)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpendingTab(
    data: InsightsOverview,
    converted: Boolean,
    today: LocalDate,
    estimate: RetirementEstimate,
    plan: RetirementPlan,
    period: Period,
    onPeriod: (Period) -> Unit,
    onBills: () -> Unit,
    onCategory: (Category?) -> Unit,
    onRetirement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (summary, monthly, costs, trend) = data
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HeroCard(costs, trend) }
        item { MonthlyChartCard(monthly, summary.currency, converted) }
        if (costs.receipts > 0) {
            item { Tiles(costs, today, estimate, plan, onBills, onCategory, onRetirement) }
            item { CategoryMonthlyCard(costs, onCategory) }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Period.entries) { p ->
                    FilterChip(selected = period == p, onClick = { onPeriod(p) }, label = { Text(p.label) })
                }
            }
        }
        item { StatsGrid(summary) }
        if (summary.byCategory.isNotEmpty()) {
            item { SectionTitle("By category") }
            item { Breakdown(summary.byCategory.map { (c, v) -> "${c.emoji} ${c.label}" to v }, summary, onClick = { i -> onCategory(summary.byCategory[i].first) }) }
        }
        if (summary.topMerchants.isNotEmpty()) {
            item { SectionTitle("Top merchants") }
            item { Breakdown(summary.topMerchants, summary) }
        }
    }
}

/**
 * The answer to "what do I spend in a month": everything since the first receipt spread evenly
 * over the months, rounded, and which way it's heading.
 */
@Composable
private fun HeroCard(c: MonthlyCosts, trend: Trend?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (c.since == null) {
                Text("What you spend a month", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add receipts to see what your expenses come to in an average month.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text("You spend about", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${roughMoney(c.monthlyCents, c.currency)} a month", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            trend?.let { TrendTag(it) }
            Text(
                "About ${roughMoney(c.yearlyCents, c.currency)} a year · " +
                    "${c.receipts} ${if (c.receipts == 1) "receipt" else "receipts"} since ${formatDate(c.since)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (c.otherCurrencyCount > 0) {
                Text(
                    "${c.otherCurrencyCount} ${if (c.otherCurrencyCount == 1) "receipt" else "receipts"} in other currencies not included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Up or down against the 3 months before, with an arrow and words, never color alone. */
@Composable
private fun TrendTag(t: Trend) {
    val (shown, spoken) = when {
        t.percent > 0 -> "▲ ${t.percent}% vs. the 3 months before" to "Up ${t.percent}% over the last 3 months, compared with the 3 before"
        t.percent < 0 -> "▼ ${-t.percent}% vs. the 3 months before" to "Down ${-t.percent}% over the last 3 months, compared with the 3 before"
        else -> "About the same as the 3 months before" to "About the same over the last 3 months as the 3 before"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(shown, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private class TileData(val label: String, val value: String, val detail: String, val onClick: () -> Unit)

/** Four ways in: the bills, the next payment, retirement and the biggest category. Stacked at large text sizes. */
@Composable
private fun Tiles(
    c: MonthlyCosts,
    today: LocalDate,
    estimate: RetirementEstimate,
    plan: RetirementPlan,
    onBills: () -> Unit,
    onCategory: (Category?) -> Unit,
    onRetirement: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val next = remember(c.recurring, today) { Bills.next(c.recurring, today) }
    val top = c.byCategory.firstOrNull()
    val tiles = listOf(
        TileData(
            "Recurring",
            "${roughMoney(c.recurringMonthlyCents, c.currency)} a month",
            when (c.recurring.size) {
                0 -> "None found yet"
                1 -> "1 regular payment"
                else -> "${c.recurring.size} regular payments"
            },
            onBills,
        ),
        if (next == null) {
            TileData("Next bill", "None yet", "Appears after ${CostEstimate.MIN_PAYMENTS} steady payments", onBills)
        } else {
            TileData("Next bill", next.bill.merchant, "${relativeDay(next.date, today, locale)} · ${money(next.bill.typicalCents, c.currency)}", onBills)
        },
        if (estimate.age == null) {
            TileData("Retirement", "Plan it", "How much you'd need", onRetirement)
        } else {
            TileData("Retirement", "About ${roughMoney(estimate.neededCents, c.currency)}", "by age ${plan.retireAge}", onRetirement)
        },
        if (top == null) {
            TileData("What you buy", "Everything", "Items and places", { onCategory(null) })
        } else {
            TileData("Top category", "${top.category.emoji} ${top.category.label}", "${roughMoney(top.monthlyCents, c.currency)} a month") { onCategory(top.category) }
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(if (isLargeFont()) 1 else 2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    Card(onClick = t.onClick, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(t.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(t.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** What each kind of spending costs in an average month, and how often it comes up. */
@Composable
private fun CategoryMonthlyCard(c: MonthlyCosts, onCategory: (Category?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("A month, by category", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val max = c.byCategory.maxOfOrNull { it.monthlyCents }?.coerceAtLeast(1) ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                c.byCategory.forEach { line ->
                    Column(Modifier.clickable(onClickLabel = "See the breakdown") { onCategory(line.category) }) {
                        LabelValue("${line.category.emoji} ${line.category.label}", "${money(line.monthlyCents, c.currency)} a month")
                        Text(
                            CostEstimate.howOften(line.timesPerMonth),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { line.monthlyCents.toFloat() / max },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            strokeCap = StrokeCap.Round,
                            drawStopIndicator = {},
                            gapSize = 2.dp,
                        )
                    }
                }
            }
            TextButton(onClick = { onCategory(null) }, modifier = Modifier.align(Alignment.End)) { Text("See everything you buy") }
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
