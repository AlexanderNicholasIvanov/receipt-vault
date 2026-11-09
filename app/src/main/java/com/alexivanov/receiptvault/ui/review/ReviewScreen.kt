package com.alexivanov.receiptvault.ui.review

import com.alexivanov.receiptvault.ui.lock.LocalAppLocked
import java.time.LocalDate
import com.alexivanov.receiptvault.ui.rememberNotificationPermissionRequest
import com.alexivanov.receiptvault.domain.Tags
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.Money
import com.alexivanov.receiptvault.ui.appViewModel
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.money
import java.io.File
import java.time.ZoneId
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val vm = appViewModel { app, handle -> ReviewViewModel(app, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()

    LaunchedEffect(saved) { saved?.let(onSaved) }

    val editing = state as? ReviewState.Editing
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing?.isNew == false) "Edit receipt" else "New receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (editing != null) {
                        TextButton(onClick = vm::save) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is ReviewState.Working -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(s.step, style = MaterialTheme.typography.titleMedium)
                }
                is ReviewState.Failed -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text("Go back") }
                }
                is ReviewState.Editing -> EditForm(s, vm)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EditForm(s: ReviewState.Editing, vm: ReviewViewModel) {
    val d = s.draft
    val tagsInUse by vm.tagsInUse.collectAsStateWithLifecycle()
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showImage by remember { mutableStateOf(false) }
    val askNotifications = rememberNotificationPermissionRequest()

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.imagePath != null) {
            item {
                AsyncImage(
                    model = File(s.imagePath),
                    contentDescription = "Receipt photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).clickable { showImage = true },
                )
            }
        }
        s.notice?.let { notice ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = vm::dismissNotice) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
                    }
                }
            }
        }
        s.error?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }

        item { SectionTitle("Store") }
        item {
            OutlinedTextField(
                value = d.merchant, onValueChange = { v -> vm.edit { it.copy(merchant = v) } },
                label = { Text("Merchant") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { c ->
                    FilterChip(
                        selected = d.category == c,
                        onClick = { vm.edit { it.copy(category = c) } },
                        label = { Text("${c.emoji} ${c.label}") },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { showDate = true },
                    label = { Text(formatDate(d.date)) },
                    leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { showTime = true },
                    label = { Text(d.time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: "Add time") },
                )
            }
        }

        item { SectionTitle("Amounts") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField("Subtotal", d.subtotal, Modifier.weight(1f)) { v -> vm.edit { it.copy(subtotal = v) } }
                MoneyField("Discount", d.discount, Modifier.weight(1f)) { v -> vm.edit { it.copy(discount = v) } }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField("Tax", d.tax, Modifier.weight(1f)) { v -> vm.edit { it.copy(tax = v) } }
                MoneyField("Tip", d.tip, Modifier.weight(1f)) { v -> vm.edit { it.copy(tip = v) } }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MoneyField("Total", d.total, Modifier.weight(2f), emphasized = true) { v -> vm.edit { it.copy(total = v) } }
                OutlinedTextField(
                    value = d.currency,
                    onValueChange = { v -> vm.edit { it.copy(currency = v.uppercase().take(3)) } },
                    label = { Text("Currency") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }
        }
        val tipPct = Money.parseUserInput(d.tip)?.let { tip ->
            Money.parseUserInput(d.subtotal)?.takeIf { it > 0 }?.let { sub -> 100.0 * tip / sub }
        }
        if (tipPct != null) {
            item { Text("Tip is %.1f%% of the subtotal".format(tipPct), style = MaterialTheme.typography.bodySmall) }
        }
        d.mismatch?.let { computed ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Subtotal − discount + tax + tip = ${money(computed, d.currency)}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { vm.edit { it.copy(total = Money.toPlain(computed)) } }) { Text("Use") }
                    }
                }
            }
        }

        item { SectionTitle("Address") }
        item {
            OutlinedTextField(
                value = d.address, onValueChange = { v -> vm.edit { it.copy(address = v) } },
                label = { Text("Store address (from receipt)") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = d.phone, onValueChange = { v -> vm.edit { it.copy(phone = v) } },
                label = { Text("Store phone") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Items (${d.items.size})", Modifier.weight(1f))
                TextButton(onClick = vm::addItem) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add item")
                }
            }
        }
        items(d.items, key = { it.key }) { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                fun update(f: (ItemDraft) -> ItemDraft) = vm.edit { dr -> dr.copy(items = dr.items.map { if (it.key == item.key) f(it) else it }) }
                OutlinedTextField(
                    value = item.name, onValueChange = { v -> update { it.copy(name = v) } },
                    label = { Text("Item") }, singleLine = true, modifier = Modifier.weight(3f),
                )
                OutlinedTextField(
                    value = item.quantity, onValueChange = { v -> update { it.copy(quantity = v.filter(Char::isDigit).take(3)) } },
                    label = { Text("Qty") }, singleLine = true, modifier = Modifier.weight(1.1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                MoneyField("Price", item.price, Modifier.weight(2f)) { v -> update { it.copy(price = v) } }
                IconButton(onClick = { vm.edit { dr -> dr.copy(items = dr.items.filterNot { it.key == item.key }) } }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove item")
                }
            }
        }

        item { SectionTitle("Reminders") }
        item {
            ReminderPicker(
                label = "Return by",
                value = d.returnBy,
                presets = listOf("14 days" to d.date.plusDays(14), "30 days" to d.date.plusDays(30)),
                onChange = { v -> vm.edit { it.copy(returnBy = v) }; if (v != null) askNotifications() },
            )
        }
        item {
            ReminderPicker(
                label = "Warranty until",
                value = d.warrantyUntil,
                presets = listOf("1 year" to d.date.plusYears(1), "2 years" to d.date.plusYears(2)),
                onChange = { v -> vm.edit { it.copy(warrantyUntil = v) }; if (v != null) askNotifications() },
            )
        }

        item { SectionTitle("Notes") }
        item {
            OutlinedTextField(
                value = d.notes, onValueChange = { v -> vm.edit { it.copy(notes = v) } },
                label = { Text("Notes (business expense, warranty…)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = d.tags, onValueChange = { v -> vm.edit { it.copy(tags = v) } },
                label = { Text("Tags") },
                placeholder = { Text("work, Berlin trip") },
                supportingText = { Text("Separate tags with commas.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        val current = Tags.parse(d.tags).map { it.lowercase() }.toSet()
        val suggestions = tagsInUse.filter { it.lowercase() !in current }.take(8)
        if (suggestions.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { t ->
                        AssistChip(onClick = { vm.addTag(t) }, label = { Text("+ $t") })
                    }
                }
            }
        }
        item {
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
                Text("Save receipt")
            }
        }
    }

    val locked = LocalAppLocked.current
    if (showDate && !locked) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = d.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.edit { it.copy(date = picked) }
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }

    if (showTime && !locked) {
        val initial = d.time ?: LocalTime.now()
        val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.edit { it.copy(time = LocalTime.of(timeState.hour, timeState.minute)) }
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.edit { it.copy(time = null) }
                    showTime = false
                }) { Text("No time") }
            },
            text = { TimePicker(state = timeState) },
        )
    }

    if (showImage && s.imagePath != null && !locked) {
        ZoomableImageDialog(File(s.imagePath)) { showImage = false }
    }

    s.duplicateOf?.takeUnless { locked }?.let { twin ->
        val day = Instant.ofEpochMilli(twin.purchasedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        AlertDialog(
            onDismissRequest = vm::dismissDuplicate,
            title = { Text("Already saved?") },
            text = {
                Text(
                    "You already have a receipt from ${twin.merchant} for ${money(twin.totalCents, twin.currency)} on " +
                        "${day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}. Save this one as well?",
                )
            },
            confirmButton = { TextButton(onClick = { vm.save(allowDuplicate = true) }) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = vm::dismissDuplicate) { Text("Keep editing") } },
        )
    }
}

/** An optional date with one-tap presets, for return and warranty reminders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPicker(label: String, value: LocalDate?, presets: List<Pair<String, LocalDate>>, onChange: (LocalDate?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column {
        Text(
            if (value == null) "$label: not set" else "$label: ${formatDate(value)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (text, date) ->
                FilterChip(selected = value == date, onClick = { onChange(date) }, label = { Text(text) })
            }
            AssistChip(onClick = { picking = true }, label = { Text("Pick date") })
            if (value != null) AssistChip(onClick = { onChange(null) }, label = { Text("Clear") })
        }
    }
    if (picking && !LocalAppLocked.current) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MoneyField(label: String, value: String, modifier: Modifier = Modifier, emphasized: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.take(12)) },
        label = { Text(label) },
        singleLine = true,
        textStyle = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}
