package com.alexivanov.receiptvault.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alexivanov.receiptvault.AppContainer
import com.alexivanov.receiptvault.ReceiptVaultApp
import com.alexivanov.receiptvault.domain.Money
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A ViewModel built from the app container, with the nav back stack entry's arguments. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer, SavedStateHandle) -> VM): VM =
    viewModel(
        factory = viewModelFactory {
            initializer { create((this[APPLICATION_KEY] as ReceiptVaultApp).container, createSavedStateHandle()) }
        },
    )

fun money(cents: Long, currency: String): String = Money.format(cents, currency)

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormat)

fun formatDate(date: LocalDate): String = date.format(dateFormat)

fun formatDateTime(epochMillis: Long): String {
    val at = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return "${at.format(dateFormat)} · ${at.format(timeFormat)}"
}

/** Opens another app. With nothing installed to handle it, says so instead of crashing. */
fun Context.startOutside(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app on this phone can open that.", Toast.LENGTH_SHORT).show()
    }
}

/** Shares a file from app storage through the FileProvider declared in the manifest. */
fun shareFile(context: Context, file: File, mime: String, title: String, text: String? = null) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startOutside(Intent.createChooser(send, title))
}
