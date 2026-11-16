package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.domain.MonthlyCosts
import com.alexivanov.receiptvault.domain.PersonalInflation
import com.alexivanov.receiptvault.domain.PriceHistory
import com.alexivanov.receiptvault.domain.Retirement
import com.alexivanov.receiptvault.domain.RetirementEstimate
import com.alexivanov.receiptvault.domain.RetirementPlan
import com.alexivanov.receiptvault.ui.heroMuted
import com.alexivanov.receiptvault.ui.heroCardColors
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.roughMoney
import kotlinx.coroutines.launch

/** Where today's spending leads: the retirement estimate, and what it adds up to over the years. */
@Composable
internal fun FutureTab(
    costs: MonthlyCosts,
    estimate: RetirementEstimate,
    plan: RetirementPlan,
    inflation: Result<PersonalInflation?>?,
    onRetirement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (costs.receipts == 0) {
            item {
                Text(
                    "Add receipts, and what they show you spend is projected forward here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }
        item { RetirementCard(estimate, plan, costs.currency, onRetirement) }
        inflation?.let { item { InflationCard(plan, it.getOrNull()) } }
        item { ProjectionCard(costs) }
    }
}

/**
 * How fast the prices of what this user buys again and again are rising, from their own line
 * items, with a way to use that rate in the retirement estimate instead of the 3 % default.
 */
@Composable
private fun InflationCard(plan: RetirementPlan, inflation: PersonalInflation?) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your inflation", style = MaterialTheme.typography.titleMedium)
            if (inflation == null) {
                Text(
                    "Keep line items on your receipts. Once ${PriceHistory.MIN_ITEMS} things you buy again and again " +
                        "have prices ${PriceHistory.MIN_SPAN_DAYS / 30} months or more apart, this shows how fast your prices are rising.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                "${Retirement.percentText(inflation.roundedBp)}% a year",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "From the prices of ${inflation.items} things you buy again and again, since ${formatDate(inflation.since)}. " +
                    "Each counts by how much you spend on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val usable = inflation.usableBp ?: return@Column
            if (usable == plan.inflationBp) {
                Text("Used in your retirement estimate.", style = MaterialTheme.typography.bodySmall)
            } else {
                FilledTonalButton(
                    onClick = { scope.launch { app.settings.setRetirementPlan(plan.copy(inflationBp = usable)) } },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Use ${Retirement.percentText(usable)}% in the retirement estimate") }
            }
        }
    }
}

/** A way into the retirement estimate, with its answer once the user's age is known. */
@Composable
internal fun RetirementCard(e: RetirementEstimate, plan: RetirementPlan, currency: String, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), colors = heroCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OpensLabel("Retirement", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (e.age == null) {
                Text(
                    "Estimate how much you'd need to retire, based on what your receipts show you spend.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = heroMuted(),
                )
            } else {
                Text(
                    when {
                        e.shortfallCents == 0L -> "Your savings cover it"
                        e.monthlySavingCents > 0 -> "Save about ${roughMoney(e.monthlySavingCents, currency)} a month"
                        else -> "About ${roughMoney(e.neededCents, currency)} by age ${plan.retireAge}"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "You'd need about ${roughMoney(e.neededCents, currency)} by ${plan.retireAge} " +
                        "(${roughMoney(e.neededTodayCents, currency)} in today's money), to last until ${plan.planToAge}",
                    style = MaterialTheme.typography.bodySmall,
                    color = heroMuted(),
                )
            }
        }
    }
}

/** The average month, added up over 1 to 25 years. */
@Composable
private fun ProjectionCard(c: MonthlyCosts) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("At this pace", style = MaterialTheme.typography.titleMedium)
            Text(
                "What ${roughMoney(c.monthlyCents, c.currency)} a month adds up to, in today's money. " +
                    "Prices usually rise, so the amounts you actually pay will be higher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(1, 5, 10, 25).forEachIndexed { i, years ->
                if (i > 0) HorizontalDivider()
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    LabelValue(if (years == 1) "Next year" else "Next $years years", roughMoney(c.yearlyCents * years, c.currency))
                }
            }
        }
    }
}
