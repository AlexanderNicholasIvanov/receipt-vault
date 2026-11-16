package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.data.AppSettings
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.CostEstimate
import com.alexivanov.receiptvault.domain.Money
import com.alexivanov.receiptvault.domain.Retirement
import com.alexivanov.receiptvault.domain.RetirementEstimate
import com.alexivanov.receiptvault.domain.RetirementPlan
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.roughMoney
import com.alexivanov.receiptvault.ui.review.SectionTitle
import java.time.LocalDate

/**
 * How much would be needed to retire on the spending the receipts show. Receipts give the
 * spending; the user adds their age, savings, costs that never come with a receipt (rent,
 * utilities, internet) and any pension. Every change is saved and the estimate follows at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetirementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val all by app.receipts.receipts.collectAsStateWithLifecycle(initialValue = emptyList())
    val loadedSettings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = loadedSettings ?: AppSettings()
    val today = LocalDate.now()

    // Typed text, saveable so turning the phone keeps it; filled from the saved plan once.
    var age by rememberSaveable { mutableStateOf("") }
    var retireAge by rememberSaveable { mutableStateOf("") }
    var planTo by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf("") }
    var other by rememberSaveable { mutableStateOf("") }
    var income by rememberSaveable { mutableStateOf("") }
    var inflation by rememberSaveable { mutableStateOf("") }
    var growth by rememberSaveable { mutableStateOf("") }
    var excluded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loadedSettings) {
        val p = loadedSettings?.retirement ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        age = p.birthYear?.let { (today.year - it).toString() }.orEmpty()
        retireAge = p.retireAge.toString()
        planTo = p.planToAge.toString()
        saved = if (p.savedCents > 0) Money.toPlain(p.savedCents) else ""
        other = if (p.otherMonthlyCents > 0) Money.toPlain(p.otherMonthlyCents) else ""
        income = if (p.incomeMonthlyCents > 0) Money.toPlain(p.incomeMonthlyCents) else ""
        inflation = Retirement.percentText(p.inflationBp)
        growth = Retirement.percentText(p.returnBp)
        excluded = p.excluded.map { it.name }
        loaded = true
    }

    // What the fields say right now; anything unreadable keeps the saved value.
    val saved0 = settings.retirement
    val plan = RetirementPlan(
        birthYear = age.trim().toIntOrNull()?.takeIf { it in 15..100 }?.let { today.year - it },
        retireAge = retireAge.trim().toIntOrNull()?.takeIf { it in 30..100 } ?: saved0.retireAge,
        planToAge = planTo.trim().toIntOrNull()?.takeIf { it in 31..120 } ?: saved0.planToAge,
        inflationBp = Retirement.parsePercent(inflation) ?: saved0.inflationBp,
        returnBp = Retirement.parsePercent(growth) ?: saved0.returnBp,
        savedCents = Money.parseUserInput(saved)?.coerceAtLeast(0) ?: 0,
        otherMonthlyCents = Money.parseUserInput(other)?.coerceAtLeast(0) ?: 0,
        incomeMonthlyCents = Money.parseUserInput(income)?.coerceAtLeast(0) ?: 0,
        excluded = excluded.mapNotNull { n -> Category.entries.firstOrNull { it.name == n } }.toSet(),
    )
    LaunchedEffect(plan, loaded) {
        if (loaded && plan != saved0) app.settings.setRetirementPlan(plan)
    }

    val costs = remember(all, settings.defaultCurrency, settings.activeRates) {
        CostEstimate.monthly(all, settings.defaultCurrency, today, settings.activeRates)
    }
    val estimate = remember(plan, costs) { Retirement.estimate(plan, costs.byCategory, today.year) }
    val currency = costs.currency

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Retirement") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Result(estimate, plan, currency) }

            item { SectionTitle("Spending in retirement") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            "From your receipts. Untick what you won't pay for once retired, such as fuel for the commute.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (costs.byCategory.isEmpty()) {
                            Text(
                                "No receipts yet. Add receipts, and what they show you spend is counted here.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        costs.byCategory.forEach { line ->
                            val on = line.category.name !in excluded
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(role = Role.Checkbox) {
                                        excluded = if (on) excluded + line.category.name else excluded - line.category.name
                                    }
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                                Text("${line.category.emoji} ${line.category.label}", Modifier.weight(1f))
                                Text(
                                    "${money(line.monthlyCents, currency)} a month",
                                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Other costs a month ($currency)", other, KeyboardType.Decimal, "Rent, utilities, internet, insurance") { other = it }
                            Field("Pension or Social Security a month ($currency)", income, KeyboardType.Decimal, "Leave empty if none") { income = it }
                            Text(
                                "Retirement has to pay ${money(estimate.monthlyTodayCents, currency)} a month in today's money " +
                                    "(${money(estimate.receiptsMonthlyCents, currency)} from receipts).",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            item { SectionTitle("About you") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Your age", age, KeyboardType.Number) { age = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Retire at age", retireAge, KeyboardType.Number, modifier = Modifier.weight(1f)) { retireAge = it }
                        Field("Money lasts until age", planTo, KeyboardType.Number, modifier = Modifier.weight(1f)) { planTo = it }
                    }
                    Field("Retirement savings so far ($currency)", saved, KeyboardType.Decimal) { saved = it }
                }
            }

            item { SectionTitle("Assumptions") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Inflation %", inflation, KeyboardType.Decimal, modifier = Modifier.weight(1f)) { inflation = it }
                        Field("Investment return %", growth, KeyboardType.Decimal, modifier = Modifier.weight(1f)) { growth = it }
                    }
                    Text(
                        "Both are yearly rates. A rough estimate, not financial advice. It assumes you keep spending what your receipts show, " +
                            "rising with inflation, and that savings earn the same return before and during retirement. " +
                            "Taxes are not included.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Result(e: RetirementEstimate, plan: RetirementPlan, currency: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            if (e.age == null) {
                Text("You'd need about", style = MaterialTheme.typography.labelLarge, color = muted)
                Text("${roughMoney(e.neededTodayCents, currency)} in today's money", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "to spend ${money(e.monthlyTodayCents, currency)} a month from ${plan.retireAge} to ${plan.planToAge}. " +
                        "Add your age below to see it in the money of the day you retire, and what to save each month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            } else {
                val retiring = if (e.yearsToRetire == 0) "now" else "at ${plan.retireAge}, in ${e.yearsToRetire} ${if (e.yearsToRetire == 1) "year" else "years"}"
                Text("You'd need about", style = MaterialTheme.typography.labelLarge, color = muted)
                Text(roughMoney(e.neededCents, currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "when you retire $retiring. That's ${roughMoney(e.neededTodayCents, currency)} in today's money, " +
                        "for ${e.yearsInRetirement} years of ${money(e.monthlyTodayCents, currency)} a month in today's money " +
                        "(${roughMoney(e.firstYearCents, currency)} in your first year of retirement).",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                when {
                    e.shortfallCents == 0L -> Text(
                        "Your savings, at ${roughMoney(e.savingsAtRetirementCents, currency)} by then, already cover it.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    e.monthlySavingCents > 0 -> {
                        Text("Save about ${roughMoney(e.monthlySavingCents, currency)} a month", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (e.savingsAtRetirementCents > 0) {
                                "until you retire. Your savings so far grow to about ${roughMoney(e.savingsAtRetirementCents, currency)} by then."
                            } else {
                                "until you retire, starting now."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                    else -> Text(
                        "You'd be about ${roughMoney(e.shortfallCents, currency)} short today.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Text(
                "Rule of thumb (25 times a year's spending): ${roughMoney(e.ruleOfThumbCents, currency)} in today's money.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType,
    placeholder: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}
