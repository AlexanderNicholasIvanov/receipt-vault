package com.alexivanov.receiptvault.ui.detail

import androidx.compose.foundation.layout.Box
import com.alexivanov.receiptvault.domain.Problems
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.lock.LocalAppLocked
import com.alexivanov.receiptvault.ui.startOutside
import java.time.LocalDate
import com.alexivanov.receiptvault.domain.Reminders
import com.alexivanov.receiptvault.domain.Tags
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.alexivanov.receiptvault.R
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.ImageNames
import com.alexivanov.receiptvault.scan.ImageStore
import com.alexivanov.receiptvault.ui.formatDateTime
import com.alexivanov.receiptvault.ui.money
import com.alexivanov.receiptvault.ui.review.SectionTitle
import com.alexivanov.receiptvault.ui.review.ZoomableImageDialog
import com.alexivanov.receiptvault.ui.shareFile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

private data class Loaded(val receipt: Receipt?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    // Wrapped so that "still loading" (null) differs from "doesn't exist any more" (Loaded(null)).
    val loaded by remember(id) { app.receipts.receipt(id).map { Loaded(it) } }.collectAsStateWithLifecycle(initialValue = null)
    val receipt = loaded?.receipt
    val items by remember(id) { app.receipts.items(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var showImage by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val r = receipt
                    if (r != null) {
                        IconButton(onClick = { share(context, r, items) }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                        if (r.deletedAt == null) {
                            IconButton(onClick = { onEdit(r.id) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                            IconButton(onClick = {
                                // Undo is offered on the home screen; the trash keeps it until the user deletes it for good.
                                // App scope, so leaving this screen can't cancel the write.
                                app.appScope.launch { app.receipts.moveToTrash(listOf(r.id)) }
                                onBack()
                            }) { Icon(Icons.Default.Delete, contentDescription = "Move to trash") }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (loaded != null && receipt == null) {
            // A reminder can outlive its receipt once it is deleted for good from the trash.
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("This receipt no longer exists.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val r = receipt ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            r.deletedAt?.let { deletedAt ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "In the trash since ${formatDate(deletedAt)}",
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { app.appScope.launch { app.receipts.restoreFromTrash(listOf(r.id)) } }) { Text("Restore") }
                    }
                }
            }
            r.imagePath?.let { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = "Receipt photo, tap to zoom",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).clickable { showImage = true },
                )
                OutlinedButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val saved = runCatching {
                                app.images.saveToPictures(path, ImageNames.forReceipt(r.merchant, r.purchasedAt))
                            }
                            saving = false
                            saved.onSuccess { uri ->
                                val result = snackbar.showSnackbar(
                                    "Saved to Pictures/${ImageStore.PICTURES_FOLDER}",
                                    actionLabel = "View",
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) viewImage(context, uri)
                            }.onFailure { e ->
                                snackbar.showSnackbar(Problems.message("Couldn't save the image", e))
                            }
                        }
                    },
                ) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (saving) "Saving…" else "Download image")
                }
            }
            val category = Category.fromName(r.category)
            Text(r.merchant, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${category.emoji} ${category.label} · ${formatDateTime(r.purchasedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(r.totalCents, r.currency), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.subtotalCents?.let { AmountRow("Subtotal", money(it, r.currency)) }
                    r.discountCents?.takeIf { it > 0 }?.let { AmountRow("Discount", "−" + money(it, r.currency)) }
                    r.taxCents?.let { AmountRow("Tax", money(it, r.currency)) }
                    r.tipCents?.let { tip ->
                        val pct = r.subtotalCents?.takeIf { it > 0 }?.let { " (%.0f%%)".format(100.0 * tip / it) }.orEmpty()
                        AmountRow("Tip$pct", money(tip, r.currency))
                    }
                    HorizontalDivider()
                    AmountRow("Total", money(r.totalCents, r.currency), bold = true)
                }
            }

            if (r.address != null || r.phone != null) {
                SectionTitle("Store")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.address?.let { LabeledText("Store address", it) }
                        r.phone?.let { LabeledText("Phone", it) }
                        if (r.address != null) {
                            OutlinedButton(onClick = { openMap(context, r.merchant, r.address) }) {
                                Icon(Icons.Default.LocationOn, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Open in Maps")
                            }
                        }
                    }
                }
            }

            if (items.isNotEmpty()) {
                SectionTitle("Items")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items.forEach { item ->
                            AmountRow(if (item.quantity > 1) "${item.quantity} × ${item.name}" else item.name, money(item.priceCents, r.currency))
                        }
                    }
                }
            }

            val today = LocalDate.now()
            val reminders = listOfNotNull(
                r.returnBy?.let { "Return by" to LocalDate.ofEpochDay(it) },
                r.warrantyUntil?.let { "Warranty until" to LocalDate.ofEpochDay(it) },
            )
            if (reminders.isNotEmpty()) {
                SectionTitle("Reminders")
                reminders.forEach { (label, date) ->
                    val days = Reminders.daysLeft(date, today)
                    val left = when {
                        days < 0 -> "ended"
                        days == 0L -> "today"
                        days == 1L -> "tomorrow"
                        else -> "in $days days"
                    }
                    AmountRow(label, "${formatDate(date)} ($left)")
                }
            }

            if (r.notes.isNotBlank()) {
                SectionTitle("Notes")
                Text(r.notes)
            }
            val tags = Tags.decode(r.tags)
            if (tags.isNotEmpty()) {
                SectionTitle("Tags")
                Text(tags.joinToString("   ") { "#$it" }, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showImage && r.imagePath != null && !LocalAppLocked.current) ZoomableImageDialog(File(r.imagePath)) { showImage = false }

    }
}

@Composable
private fun AmountRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.SemiBold else null)
        Text(value, fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

/** Looks up the store address printed on the receipt. */
private fun openMap(context: android.content.Context, merchant: String, address: String) {
    val query = Uri.encode("$merchant, $address")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, "geo:0,0?q=$query".toUri()))
    } catch (_: ActivityNotFoundException) {
        context.startOutside(Intent(Intent.ACTION_VIEW, "https://maps.google.com/?q=$query".toUri()))
    }
}

private fun viewImage(context: android.content.Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
        // No viewer: the file is still saved where the snackbar said.
    }
}

private fun share(context: android.content.Context, r: Receipt, items: List<LineItem>) {
    val text = buildString {
        appendLine(r.merchant)
        appendLine(formatDateTime(r.purchasedAt))
        r.subtotalCents?.let { appendLine("Subtotal: ${money(it, r.currency)}") }
        r.taxCents?.let { appendLine("Tax: ${money(it, r.currency)}") }
        r.tipCents?.let { appendLine("Tip: ${money(it, r.currency)}") }
        appendLine("Total: ${money(r.totalCents, r.currency)}")
        if (items.isNotEmpty()) appendLine("${items.size} item(s)")
        r.address?.let { appendLine(it) }
    }.trim()
    val image = r.imagePath?.let(::File)?.takeIf { it.exists() }
    if (image != null) {
        shareFile(context, image, "image/jpeg", "Share receipt", text)
    } else {
        context.startOutside(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share receipt"))
    }
}
