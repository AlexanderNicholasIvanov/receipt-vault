package com.alexivanov.receiptvault.ui.detail

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
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val receipt by remember(id) { app.receipts.receipt(id) }.collectAsStateWithLifecycle(initialValue = null)
    val items by remember(id) { app.receipts.items(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
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
                        IconButton(onClick = { onEdit(r.id) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val r = receipt ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                                snackbar.showSnackbar("Couldn't save the image: ${e.message ?: "unknown error"}")
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
                    val payment = listOfNotNull(r.paymentMethod, r.cardLast4?.let { "•••• $it" }).joinToString(" ")
                    if (payment.isNotBlank()) AmountRow("Paid with", payment)
                }
            }

            if (r.address != null || r.locationLabel != null || r.latitude != null || r.phone != null) {
                SectionTitle("Location")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.address?.let { LabeledText("Store address", it) }
                        r.phone?.let { LabeledText("Phone", it) }
                        (r.locationLabel ?: r.latitude?.let { "%.5f, %.5f".format(it, r.longitude) })?.let { LabeledText("Scanned at", it) }
                        OutlinedButton(onClick = { openMap(context, r) }) {
                            Icon(Icons.Default.LocationOn, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Open in Maps")
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

            if (r.notes.isNotBlank()) {
                SectionTitle("Notes")
                Text(r.notes)
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showImage && r.imagePath != null) ZoomableImageDialog(File(r.imagePath)) { showImage = false }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete receipt?") },
                text = { Text("This removes the receipt and its photo from this phone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            app.receipts.delete(r)
                            onBack()
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
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

private fun openMap(context: android.content.Context, r: Receipt) {
    val query = r.address ?: r.locationLabel
    val uri = when {
        r.latitude != null && r.longitude != null ->
            ("geo:${r.latitude},${r.longitude}?q=" + Uri.encode(query ?: "${r.latitude},${r.longitude}(${r.merchant})")).toUri()
        query != null -> ("geo:0,0?q=" + Uri.encode("${r.merchant}, $query")).toUri()
        else -> return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, ("https://maps.google.com/?q=" + Uri.encode(query ?: "${r.latitude},${r.longitude}")).toUri()))
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
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share receipt"))
    }
}
