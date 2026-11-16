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
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.data.AppSettings
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.CategoryCost
import com.alexivanov.receiptvault.domain.CostEstimate
import com.alexivanov.receiptvault.domain.Money
import com.alexivanov.receiptvault.domain.Retirement
import com.alexivanov.receiptvault.domain.RetirementEstimate
import com.alexivanov.receiptvault.domain.RetirementPlan
import com.alexivanov.receiptvault.ui.heroMuted
import com.alexivanov.receiptvault.ui.heroCardColors
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
    var retiredGrowth by rememberSaveable { mutableStateOf("") }
    var health by rememberSaveable { mutableStateOf("") }
    var falls by rememberSaveable { mutableStateOf(false) }
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
        retiredGrowth = p.retiredReturnBp?.let(Retirement::percentText).orEmpty()
        health = if (p.healthcareMonthlyCents > 0) Money.toPlain(p.healthcareMonthlyCents) else ""
        falls = p.spendingFalls
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
        // Empty is "the same as before retiring".
        retiredReturnBp = if (retiredGrowth.isBlank()) null else Retirement.parsePercent(retiredGrowth) ?: saved0.retiredReturnBp,
        spendingFalls = falls,
        healthcareMonthlyCents = Money.parseUserInput(health)?.coerceAtLeast(0) ?: 0,
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
    val range = remember(plan, costs) { Retirement.range(plan, costs.byCategory, today.year) }
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
            item { RangeCard(range.first, estimate, range.second, currency) }
            if (estimate.age != null) {
                item { WhatIfCard(plan, estimate, costs.byCategory, today.year, currency) { retireAge = it.toString() } }
            }

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
                            Field("Healthcare a month ($currency)", health, KeyboardType.Decimal, "Premiums and out-of-pocket costs") { health = it }
                            if (currency == "USD" && health.isBlank()) {
                                val suggested = Retirement.usHealthcareMonthly(estimate.yearsInRetirement)
                                Text(
                                    "Fidelity estimates one person retiring at 65 in the US spends about " +
                                        "${roughMoney(Retirement.US_HEALTHCARE_CENTS, currency)} on healthcare over retirement.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { health = Money.toPlain(suggested) }) {
                                    Text("Use about ${roughMoney(suggested, currency)} a month")
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Switch) { falls = !falls },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("Spend a little less each year")
                                    Text(
                                        "Studies of retirees find spending usually falls about 1% a year after inflation. Off, it stays flat.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = falls, onCheckedChange = null)
                            }
                            Text(
                                "Retirement has to pay ${roughMoney(estimate.monthlyTodayCents, currency)} a month in today's money " +
                                    "(${roughMoney(estimate.receiptsMonthlyCents, currency)} from receipts).",
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
                    Field("Inflation %", inflation, KeyboardType.Decimal) { inflation = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Return until retiring %", growth, KeyboardType.Decimal, modifier = Modifier.weight(1f)) { growth = it }
                        Field("Return once retired %", retiredGrowth, KeyboardType.Decimal, "Same", modifier = Modifier.weight(1f)) { retiredGrowth = it }
                    }
                    Text(
                        "All are yearly rates. Many people move savings to safer, lower-return investments once retired. " +
                            "A rough estimate, not financial advice. It assumes you keep spending what your receipts show, " +
                            "rising with inflation. Taxes are not included.",
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
    Card(Modifier.fillMaxWidth(), colors = heroCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val muted = heroMuted()
            if (e.age == null) {
                Text("You'd need about", style = MaterialTheme.typography.labelLarge, color = muted)
                Text("${roughMoney(e.neededTodayCents, currency)} in today's money", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "to spend ${roughMoney(e.monthlyTodayCents, currency)} a month from ${plan.retireAge} to ${plan.planToAge}. " +
                        "Add your age below to see it in the money of the day you retire, and what to save each month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            } else {
                val retiring = if (e.yearsToRetire == 0) "now" else "at ${plan.retireAge}, in ${e.yearsToRetire} ${if (e.yearsToRetire == 1) "year" else "years"}"
                // The answer first: what to do each month. The total behind it comes second.
                when {
                    e.shortfallCents == 0L -> {
                        Text("You're on track", style = MaterialTheme.typography.labelLarge, color = muted)
                        Text("Your savings cover it", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "They grow to about ${roughMoney(e.savingsAtRetirementCents, currency)} by the time you retire.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                    e.monthlySavingCents > 0 -> {
                        Text("To retire $retiring, save about", style = MaterialTheme.typography.labelLarge, color = muted)
                        Text("${roughMoney(e.monthlySavingCents, currency)} a month", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (e.savingsAtRetirementCents > 0) {
                                "Your savings so far grow to about ${roughMoney(e.savingsAtRetirementCents, currency)} by then."
                            } else {
                                "Starting now."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                    else -> {
                        Text("You'd need about", style = MaterialTheme.typography.labelLarge, color = muted)
                        Text(roughMoney(e.neededCents, currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "You'd be about ${roughMoney(e.shortfallCents, currency)} short today.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = muted.copy(alpha = 0.3f))
                Text(
                    "You'd need about ${roughMoney(e.neededCents, currency)} when you retire $retiring " +
                        "(${roughMoney(e.neededTodayCents, currency)} in today's money): " +
                        "${e.yearsInRetirement} years of ${roughMoney(e.monthlyTodayCents, currency)} a month in today's money, " +
                        "${roughMoney(e.firstYearCents, currency)} in your first year.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
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

/** The estimate if returns once retired are a point better or worse: how sure the answer is. */
@Composable
private fun RangeCard(tight: RetirementEstimate, likely: RetirementEstimate, comfortable: RetirementEstimate, currency: String) {
    // Before the age is known, only today's money is.
    fun amount(e: RetirementEstimate) = roughMoney(if (likely.age == null) e.neededTodayCents else e.neededCents, currency)
    val point = Retirement.RANGE_BP / 100
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A range, not one number", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                listOf("Tight" to tight, "Likely" to likely, "Comfortable" to comfortable).forEach { (label, e) ->
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(amount(e), fontWeight = if (e === likely) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Text(
                "Tight if returns once retired are $point point higher than you set, comfortable if they are " +
                    "$point point lower. Returns vary, so aim for the comfortable end if you can.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The same plan, retiring a few years earlier or later. Tapping one uses that age. */
@Composable
private fun WhatIfCard(
    plan: RetirementPlan,
    current: RetirementEstimate,
    byCategory: List<CategoryCost>,
    year: Int,
    currency: String,
    onPick: (Int) -> Unit,
) {
    val age = current.age ?: return
    val ages = listOf(-5, -2, 2, 5).map { plan.retireAge + it }.filter { it > age && it < plan.planToAge && it in 30..100 }
    if (ages.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "What if you retire at another age?",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ages.forEach { a ->
                val e = remember(plan, byCategory, a) { Retirement.estimate(plan.copy(retireAge = a), byCategory, year) }
                val save = if (e.shortfallCents == 0L) "Nothing more to save" else "Save ${roughMoney(e.monthlySavingCents, currency)} a month"
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(onClickLabel = "Use this age") { onPick(a) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("At $a", Modifier.padding(end = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.weight(1f)) {
                        Text("About ${roughMoney(e.neededCents, currency)}")
                        Text(save, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
