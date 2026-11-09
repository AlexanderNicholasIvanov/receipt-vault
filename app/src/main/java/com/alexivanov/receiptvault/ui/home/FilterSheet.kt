package com.alexivanov.receiptvault.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.Money
import com.alexivanov.receiptvault.domain.ReceiptFilter
import com.alexivanov.receiptvault.domain.SortOrder
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.lock.LocalAppLocked
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Sort, categories, amount and a custom date range for the home list. Changes apply on "Show results". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(current: ReceiptFilter, tagsInUse: List<String>, onApply: (ReceiptFilter) -> Unit, onDismiss: () -> Unit) {
    var sort by remember { mutableStateOf(current.sort) }
    var categories by remember { mutableStateOf(current.categories) }
    var tags by remember { mutableStateOf(current.tags) }
    var min by remember { mutableStateOf(current.minCents?.let(::plain).orEmpty()) }
    var max by remember { mutableStateOf(current.maxCents?.let(::plain).orEmpty()) }
    var from by remember { mutableStateOf(current.from) }
    var to by remember { mutableStateOf(current.to) }
    var pickDates by remember { mutableStateOf(false) }

    // The choices above are kept, so the sheet comes back as it was after unlocking.
    if (LocalAppLocked.current) return

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sort by", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortOrder.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
            }

            Text("Categories", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { c ->
                    FilterChip(
                        selected = c in categories,
                        onClick = { categories = if (c in categories) categories - c else categories + c },
                        label = { Text("${c.emoji} ${c.label}") },
                    )
                }
            }

            if (tagsInUse.isNotEmpty()) {
                Text("Tags", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tagsInUse.forEach { t ->
                        FilterChip(
                            selected = t in tags,
                            onClick = { tags = if (t in tags) tags - t else tags + t },
                            label = { Text("#$t") },
                        )
                    }
                }
            }

            Text("Total", style = MaterialTheme.typography.titleSmall)
            Row {
                OutlinedTextField(
                    value = min,
                    onValueChange = { min = it },
                    label = { Text("At least") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = max,
                    onValueChange = { max = it },
                    label = { Text("At most") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            Text("Dates", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDates = true }) {
                    val f = from
                    val t = to
                    Text(if (f != null && t != null) "${formatDate(f)} – ${formatDate(t)}" else "Pick a date range")
                }
                if (from != null) TextButton(onClick = { from = null; to = null }) { Text("Clear dates") }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onApply(ReceiptFilter()) }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    val (lo, hi) = ReceiptFilter.amountRange(Money.parseUserInput(min), Money.parseUserInput(max))
                    onApply(
                        ReceiptFilter(
                            categories = categories,
                            // A tag that no receipt uses any more would hide everything with no chip to untick.
                            tags = tags.filterTo(HashSet()) { it in tagsInUse },
                            minCents = lo,
                            maxCents = hi,
                            from = from.takeIf { to != null },
                            to = to.takeIf { from != null },
                            sort = sort,
                        ),
                    )
                }) { Text("Show results") }
            }
        }
    }

    if (pickDates) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = from?.let(::utcMillis),
            initialSelectedEndDateMillis = to?.let(::utcMillis),
        )
        DatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis?.let(::utcDate)
                        from = start
                        // A single tapped day is a one-day range.
                        to = state.selectedEndDateMillis?.let(::utcDate) ?: start
                        pickDates = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

/** 1250 -> "12.50", for showing a stored amount back in a text field. */
private fun plain(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

// Material date pickers work in UTC midnight millis.
private fun utcMillis(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
