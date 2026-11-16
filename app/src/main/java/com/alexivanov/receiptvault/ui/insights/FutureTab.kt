package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexivanov.receiptvault.domain.MonthlyCosts
import com.alexivanov.receiptvault.domain.RetirementEstimate
import com.alexivanov.receiptvault.domain.RetirementPlan
import com.alexivanov.receiptvault.ui.roughMoney

/** Where today's spending leads: the retirement estimate, and what it adds up to over the years. */
@Composable
internal fun FutureTab(
    costs: MonthlyCosts,
    estimate: RetirementEstimate,
    plan: RetirementPlan,
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
        item { ProjectionCard(costs) }
    }
}

/** A way into the retirement estimate, with its answer once the user's age is known. */
@Composable
internal fun RetirementCard(e: RetirementEstimate, plan: RetirementPlan, currency: String, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Retirement", style = MaterialTheme.typography.titleMedium)
            if (e.age == null) {
                Text(
                    "Estimate how much you'd need to retire, based on what your receipts show you spend.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "About ${roughMoney(e.neededCents, currency)} by age ${plan.retireAge}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${roughMoney(e.neededTodayCents, currency)} in today's money, to last until ${plan.planToAge}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
