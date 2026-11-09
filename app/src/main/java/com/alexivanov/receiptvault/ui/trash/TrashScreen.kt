package com.alexivanov.receiptvault.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.domain.TrashPolicy
import com.alexivanov.receiptvault.ui.formatDate
import com.alexivanov.receiptvault.ui.lock.LocalAppLocked
import com.alexivanov.receiptvault.ui.money
import kotlinx.coroutines.launch

/** Deleted receipts, kept for [TrashPolicy.RETENTION_DAYS] days so a slip of the thumb is never final. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { (context.applicationContext as ReceiptVaultApp).container }
    val trash by app.receipts.trash.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    // Receipts waiting for "Delete forever" to be confirmed; everything when emptying the trash.
    var confirm by remember { mutableStateOf<List<Receipt>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!trash.isNullOrEmpty()) TextButton(onClick = { confirm = trash }) { Text("Empty trash") }
                },
            )
        },
    ) { padding ->
        val list = trash ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    "Deleted receipts are removed for good after ${TrashPolicy.RETENTION_DAYS} days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (list.isEmpty()) {
                item {
                    Text(
                        "The trash is empty.",
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(list, key = { it.id }) { r ->
                val now = System.currentTimeMillis()
                val days = TrashPolicy.daysLeft(r.deletedAt ?: now, now)
                ListItem(
                    headlineContent = { Text(r.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${formatDate(r.purchasedAt)} · ${money(r.totalCents, r.currency)} · " +
                                if (days <= 1) "deleted tomorrow" else "$days days left",
                        )
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { confirm = listOf(r) }) {
                        Text("Delete forever")
                    }
                    TextButton(onClick = { scope.launch { app.receipts.restoreFromTrash(listOf(r.id)) } }) { Text("Restore") }
                }
                HorizontalDivider()
            }
        }
    }

    confirm?.takeUnless { LocalAppLocked.current }?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (doomed.size == 1) "Delete forever?" else "Empty trash?") },
            text = {
                Text(
                    if (doomed.size == 1) {
                        "${doomed.single().merchant} and its photo will be deleted. This can't be undone."
                    } else {
                        "${doomed.size} receipts and their photos will be deleted. This can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { app.receipts.deleteForever(doomed) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
