package com.alexivanov.receiptvault.ui.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.domain.BudgetLevel
import com.alexivanov.receiptvault.domain.BudgetMath
import com.alexivanov.receiptvault.domain.BudgetUse
import com.alexivanov.receiptvault.domain.Budgets
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.Conversion
import com.alexivanov.receiptvault.domain.Money
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.rememberNotificationPermissionRequest
import com.alexivanov.receiptvault.ui.review.SectionTitle
import kotlinx.coroutines.launch
import java.time.YearMonth

/** Monthly budgets, overall and per category, with this month's progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val receipts by app.receipts.receipts.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val askNotifications = rememberNotificationPermissionRequest()

    var monthly by remember { mutableStateOf("") }
    val perCategory = remember { mutableStateMapOf<Category, String>() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        monthly = s.budgets.monthlyCents?.let(Money::toPlain).orEmpty()
        s.budgets.byCategory.forEach { (c, cents) -> perCategory[c] = Money.toPlain(cents) }
        loaded = true
    }

    val s = settings
    val currency = s?.defaultCurrency ?: "USD"
    val uses = if (s == null) emptyList() else BudgetMath.usage(s.budgets, Conversion.toBase(receipts, s.activeRates), currency, YearMonth.now())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budgets") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uses.isNotEmpty()) {
                item { SectionTitle("This month") }
                items(uses, key = { it.category?.name ?: "ALL" }) { use -> UsageRow(use, currency) }
            }
            item { SectionTitle("Monthly limits ($currency)") }
            item {
                Text(
                    if (s?.activeRates != null) {
                        "You'll get a notification at 80 % and when a budget is used up. Receipts in other currencies count at today's exchange rates."
                    } else {
                        "You'll get a notification at 80 % and when a budget is used up. Only receipts in $currency count, unless you turn on currency conversion in Settings."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { AmountField("Everything", monthly) { monthly = it } }
            items(Category.entries, key = { it.name }) { c ->
                AmountField("${c.emoji} ${c.label}", perCategory[c].orEmpty()) { perCategory[c] = it }
            }
            item {
                Button(
                    onClick = {
                        val budgets = Budgets(
                            monthlyCents = Money.parseUserInput(monthly)?.takeIf { it > 0 },
                            byCategory = perCategory.mapNotNull { (c, text) -> Money.parseUserInput(text)?.takeIf { it > 0 }?.let { c to it } }.toMap(),
                        )
                        scope.launch {
                            app.settings.setBudgets(budgets)
                            if (!budgets.isEmpty) askNotifications()
                            app.checkAlertsSoon()
                            snackbar.showSnackbar(if (budgets.isEmpty) "Budgets cleared." else "Budgets saved.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save budgets") }
            }
        }
    }
}

@Composable
private fun UsageRow(use: BudgetUse, currency: String) {
    val color = when (use.level) {
        BudgetLevel.OK -> MaterialTheme.colorScheme.primary
        BudgetLevel.NEAR -> MaterialTheme.colorScheme.tertiary
        BudgetLevel.OVER -> MaterialTheme.colorScheme.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(use.label, Modifier.weight(1f))
            Text("${money(use.spentCents, currency)} of ${money(use.budgetCents, currency)}")
        }
        LinearProgressIndicator(progress = { use.fraction }, color = color, modifier = Modifier.fillMaxWidth())
        if (use.level == BudgetLevel.OVER) {
            Text(
                "Over by ${money(use.spentCents - use.budgetCents, currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AmountField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("No limit") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
