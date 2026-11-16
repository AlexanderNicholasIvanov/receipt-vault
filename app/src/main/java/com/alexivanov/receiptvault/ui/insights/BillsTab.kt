package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alexivanov.receiptvault.domain.Bills
import com.alexivanov.receiptvault.domain.CostEstimate
import com.alexivanov.receiptvault.domain.DueBill
import com.alexivanov.receiptvault.domain.MonthlyCosts
import com.alexivanov.receiptvault.domain.RecurringCost
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.review.SectionTitle
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** What's due soon, what's paid this month, and every regular payment found in the receipts. */
@Composable
internal fun BillsTab(costs: MonthlyCosts, today: LocalDate, modifier: Modifier = Modifier) {
    val upcoming = remember(costs.recurring, today) { Bills.upcoming(costs.recurring, today) }
    val month = remember(costs.recurring, today) { Bills.thisMonth(costs.recurring, today) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (costs.recurring.isNotEmpty()) {
            item { DueSoonCard(upcoming, costs.currency, today) }
            if (month.due > 0) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Bills this month", style = MaterialTheme.typography.titleMedium)
                            Text("${month.paid} of ${month.due} paid", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(
                                progress = { month.paid.toFloat() / month.due },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                strokeCap = StrokeCap.Round,
                                drawStopIndicator = {},
                                gapSize = 2.dp,
                            )
                            if (month.leftCents > 0) {
                                Text(
                                    "About ${money(month.leftCents, costs.currency)} still to come",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("Recurring costs") }
        item { RecurringCosts(costs) }
    }
}

/** "Today", "Tomorrow", a weekday this week, or the date. */
internal fun relativeDay(date: LocalDate, today: LocalDate, locale: Locale): String = when {
    date == today -> "Today"
    date == today.plusDays(1) -> "Tomorrow"
    date.isAfter(today) && date.isBefore(today.plusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    else -> formatDate(date)
}

@Composable
private fun DueSoonCard(due: List<DueBill>, currency: String, today: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Due in the next 7 days", style = MaterialTheme.typography.titleMedium)
            Text(money(due.sumOf { it.bill.typicalCents }, currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            WeekStrip(due, currency, today, locale)
            if (due.isEmpty()) {
                Text(
                    "Nothing due this week.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            due.forEach { d ->
                Row(Modifier.semantics(mergeDescendants = true) {}) {
                    Column(Modifier.weight(1f)) {
                        Text("${d.bill.category.emoji} ${d.bill.merchant}", fontWeight = FontWeight.Medium)
                        Text(
                            relativeDay(d.date, today, locale),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PriceChangeTag(d.bill, currency)
                    }
                    Text("usually ${money(d.bill.typicalCents, currency)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Seven days from today; a dot under each day with a payment. One TalkBack stop per day. */
@Composable
private fun WeekStrip(due: List<DueBill>, currency: String, today: LocalDate, locale: Locale) {
    val byDay = due.groupBy { it.date }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0L until 7L).map { today.plusDays(it) }.forEach { day ->
            val bills = byDay[day].orEmpty()
            val spoken = "${relativeDay(day, today, locale)}, ${formatDate(day)}: " +
                if (bills.isEmpty()) "nothing due" else bills.joinToString { "${it.bill.merchant} ${money(it.bill.typicalCents, currency)}" }
            Column(
                Modifier.weight(1f)
                    .background(
                        if (day == today) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(vertical = 8.dp)
                    .clearAndSetSemantics { contentDescription = spoken },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                if (bills.isEmpty()) Spacer(Modifier.size(6.dp)) else Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    }
}

/** "▲ Price up from $15.99 on Sep 19, 2026" while a bill's new price is still news. */
internal fun priceChangeText(bill: RecurringCost, currency: String): String? {
    val change = bill.priceChange?.takeIf { it.recent } ?: return null
    val what = if (change.up) "▲ Price up" else "▼ Price down"
    return "$what from ${money(change.fromCents, currency)} on ${formatDate(change.since)}"
}

/** An arrow and words, so the change never depends on color alone. */
@Composable
private fun PriceChangeTag(bill: RecurringCost, currency: String) {
    val text = priceChangeText(bill, currency) ?: return
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (bill.priceChange?.up == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

/** Merchants paid at a steady pace, with their usual amount and when the next one is due. */
@Composable
private fun RecurringCosts(c: MonthlyCosts) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (c.recurring.isEmpty()) {
                Text(
                    "Nothing repeats regularly yet. A place you pay at a steady pace, " +
                        "${CostEstimate.MIN_PAYMENTS} or more times, shows up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Recurring: ${money(c.recurringMonthlyCents, c.currency)} a month from ${c.recurring.size} regular " +
                        if (c.recurring.size == 1) "payment" else "payments",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            c.recurring.forEach { r ->
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    LabelValue("${r.category.emoji} ${r.merchant}", "${money(r.monthlyCents, c.currency)} a month", labelWeight = FontWeight.Medium)
                    Text(
                        "${CostEstimate.everyLabel(r.everyDays)} · usually ${money(r.typicalCents, c.currency)} · next around ${formatDate(r.nextDate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PriceChangeTag(r, c.currency)
                }
            }
        }
    }
}
