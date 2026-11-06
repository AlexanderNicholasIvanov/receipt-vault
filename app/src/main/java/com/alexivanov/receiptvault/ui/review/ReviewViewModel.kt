package com.alexivanov.receiptvault.ui.review

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexivanov.receiptvault.AppContainer
import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.LineAssembler
import com.alexivanov.receiptvault.domain.Money
import com.alexivanov.receiptvault.domain.ParsedReceipt
import com.alexivanov.receiptvault.domain.ReceiptParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** How the photo was obtained. */
enum class CaptureSource { SCAN, CAMERA, GALLERY, SHARED, MANUAL }

data class ItemDraft(val key: Long, val name: String, val quantity: String, val price: String)

data class ReceiptDraft(
    val merchant: String = "",
    val category: Category = Category.OTHER,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime? = null,
    val subtotal: String = "",
    val tax: String = "",
    val tip: String = "",
    val discount: String = "",
    val total: String = "",
    val currency: String = "USD",
    val address: String = "",
    val phone: String = "",
    val notes: String = "",
    val items: List<ItemDraft> = emptyList(),
) {
    /** subtotal - discount + tax + tip, when there is a subtotal to start from. */
    val computedTotal: Long?
        get() {
            val sub = Money.parseUserInput(subtotal) ?: return null
            return sub - (Money.parseUserInput(discount)?.let { kotlin.math.abs(it) } ?: 0) +
                (Money.parseUserInput(tax) ?: 0) + (Money.parseUserInput(tip) ?: 0)
        }

    /** Set when the parts visibly disagree with the total, so the user can fix a misread. */
    val mismatch: Long?
        get() {
            val computed = computedTotal ?: return null
            val entered = Money.parseUserInput(total) ?: return null
            // Discounts are often already inside the subtotal; accept either reading.
            val withoutDiscount = computed + (Money.parseUserInput(discount)?.let { kotlin.math.abs(it) } ?: 0)
            return if (kotlin.math.abs(entered - computed) <= 2 || kotlin.math.abs(entered - withoutDiscount) <= 2) null else computed
        }
}

sealed interface ReviewState {
    data class Working(val step: String) : ReviewState
    data class Editing(
        val draft: ReceiptDraft,
        val isNew: Boolean,
        val imagePath: String?,
        val notice: String? = null,
        val error: String? = null,
    ) : ReviewState
    data class Failed(val message: String) : ReviewState
}

class ReviewViewModel(
    private val app: AppContainer,
    handle: SavedStateHandle,
) : ViewModel() {

    private val receiptId: Long = handle.get<String>("id")?.toLongOrNull() ?: 0L
    private val imageUri: String? = handle.get<String>("uri")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow<ReviewState>(ReviewState.Working("Starting…"))
    val state: StateFlow<ReviewState> = _state

    private val _saved = MutableStateFlow<Long?>(null)

    /** Id of the receipt once saved; the screen navigates on it. */
    val saved: StateFlow<Long?> = _saved

    private var existing: Receipt? = null
    private var ocrText: String = ""
    private var imagePath: String? = null
    private var nextKey = 1L

    init {
        viewModelScope.launch {
            when {
                receiptId != 0L -> loadExisting()
                imageUri != null -> processPhoto(imageUri.toUri())
                else -> startManual()
            }
        }
    }

    private suspend fun loadExisting() {
        val r = app.receipts.get(receiptId) ?: run {
            _state.value = ReviewState.Failed("That receipt no longer exists.")
            return
        }
        existing = r
        ocrText = r.ocrText
        imagePath = r.imagePath
        val at = Instant.ofEpochMilli(r.purchasedAt).atZone(ZoneId.systemDefault())
        val items = app.receipts.getItems(r.id).map { ItemDraft(nextKey++, it.name, it.quantity.toString(), Money.toPlain(it.priceCents)) }
        _state.value = ReviewState.Editing(
            draft = ReceiptDraft(
                merchant = r.merchant,
                category = Category.fromName(r.category),
                date = at.toLocalDate(),
                time = at.toLocalTime().withSecond(0).withNano(0),
                subtotal = Money.toPlain(r.subtotalCents),
                tax = Money.toPlain(r.taxCents),
                tip = Money.toPlain(r.tipCents),
                discount = Money.toPlain(r.discountCents),
                total = Money.toPlain(r.totalCents),
                currency = r.currency,
                address = r.address.orEmpty(),
                phone = r.phone.orEmpty(),
                notes = r.notes,
                items = items,
            ),
            isNew = false,
            imagePath = r.imagePath,
        )
    }

    private suspend fun startManual() {
        val settings = app.settings.current()
        _state.value = ReviewState.Editing(ReceiptDraft(currency = settings.defaultCurrency), isNew = true, imagePath = null)
    }

    private suspend fun processPhoto(uri: Uri) {
        val settings = app.settings.current()
        _state.value = ReviewState.Working("Saving photo…")
        val photo = try {
            app.images.import(uri)
        } catch (e: Exception) {
            _state.value = ReviewState.Failed("Could not open that photo: ${e.message ?: "unknown error"}")
            return
        }
        imagePath = photo.absolutePath

        _state.value = ReviewState.Working("Reading receipt…")
        var notice: String? = null
        val parsed = try {
            val rows = LineAssembler.assemble(app.textReader.read(photo))
            ocrText = rows.joinToString("\n") { it.text }
            if (rows.isEmpty()) notice = "No text found. Fill in the details by hand, or retake the photo in better light."
            ReceiptParser.parse(rows, LocalDate.now(), settings.dayFirstDates)
        } catch (e: Exception) {
            notice = "Text recognition failed (${e.message ?: "unknown error"}). Fill in the details by hand."
            ParsedReceipt()
        }

        if (notice == null && parsed.totalCents == null) notice = "Couldn't find the total. Please check the amounts."

        _state.value = ReviewState.Editing(
            draft = parsed.toDraft(settings.defaultCurrency),
            isNew = true,
            imagePath = imagePath,
            notice = notice,
        )
    }

    private fun ParsedReceipt.toDraft(defaultCurrency: String) = ReceiptDraft(
        merchant = merchant.orEmpty(),
        category = category,
        date = date ?: LocalDate.now(),
        time = time,
        subtotal = Money.toPlain(subtotalCents),
        tax = Money.toPlain(taxCents),
        tip = Money.toPlain(tipCents),
        discount = Money.toPlain(discountCents),
        total = Money.toPlain(totalCents),
        currency = currencyCode ?: defaultCurrency,
        address = address.orEmpty(),
        phone = phone.orEmpty(),
        items = items.map { ItemDraft(nextKey++, it.name, it.quantity.toString(), Money.toPlain(it.priceCents)) },
    )

    // ------------------------------------------------------------------ edits

    fun edit(transform: (ReceiptDraft) -> ReceiptDraft) {
        _state.update { s -> if (s is ReviewState.Editing) s.copy(draft = transform(s.draft), error = null) else s }
    }

    fun addItem() = edit { it.copy(items = it.items + ItemDraft(nextKey++, "", "1", "")) }

    fun dismissNotice() = _state.update { s -> if (s is ReviewState.Editing) s.copy(notice = null) else s }

    fun save() {
        val s = _state.value as? ReviewState.Editing ?: return
        val d = s.draft
        val total = Money.parseUserInput(d.total) ?: d.computedTotal
        if (total == null) {
            _state.value = s.copy(error = "Enter the total amount.")
            return
        }
        val currency = d.currency.trim().uppercase().takeIf { it.matches(Regex("[A-Z]{3}")) }
        if (currency == null) {
            _state.value = s.copy(error = "Currency must be a 3-letter code like USD.")
            return
        }
        val now = System.currentTimeMillis()
        val purchasedAt = d.date.atTime(d.time ?: LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val receipt = Receipt(
            id = existing?.id ?: 0,
            merchant = d.merchant.trim().ifBlank { "Unknown merchant" },
            address = d.address.trim().ifBlank { null },
            phone = d.phone.trim().ifBlank { null },
            category = d.category.name,
            purchasedAt = purchasedAt,
            subtotalCents = Money.parseUserInput(d.subtotal),
            taxCents = Money.parseUserInput(d.tax),
            tipCents = Money.parseUserInput(d.tip),
            discountCents = Money.parseUserInput(d.discount)?.let { kotlin.math.abs(it) },
            totalCents = total,
            currency = currency,
            notes = d.notes.trim(),
            imagePath = imagePath,
            ocrText = ocrText,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        val items = d.items.mapNotNull { item ->
            val price = Money.parseUserInput(item.price) ?: return@mapNotNull null
            if (item.name.isBlank()) return@mapNotNull null
            LineItem(receiptId = 0, position = 0, name = item.name.trim(), quantity = item.quantity.toIntOrNull()?.coerceIn(1, 999) ?: 1, priceCents = price)
        }
        viewModelScope.launch {
            _saved.value = app.receipts.save(receipt, items)
        }
    }

    override fun onCleared() {
        // A new capture the user backed out of: do not leave an orphaned photo behind.
        if (existing == null && _saved.value == null) app.receipts.discardImage(imagePath)
    }
}
