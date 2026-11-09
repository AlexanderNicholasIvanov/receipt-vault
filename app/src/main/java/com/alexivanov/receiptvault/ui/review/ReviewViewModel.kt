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
import com.alexivanov.receiptvault.domain.Duplicates
import com.alexivanov.receiptvault.domain.LineAssembler
import com.alexivanov.receiptvault.domain.Money
import com.alexivanov.receiptvault.domain.PageUris
import com.alexivanov.receiptvault.domain.ParsedReceipt
import com.alexivanov.receiptvault.domain.ReceiptParser
import com.alexivanov.receiptvault.domain.Tags
import com.alexivanov.receiptvault.scan.PdfPages
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** How the photo was obtained. */
enum class CaptureSource { SCAN, CAMERA, GALLERY, SHARED, PDF, MANUAL }

@Serializable
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
    /** As typed, comma-separated; cleaned up by [Tags.parse] on save. */
    val tags: String = "",
    val returnBy: LocalDate? = null,
    val warrantyUntil: LocalDate? = null,
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
        /** A saved receipt this one looks like; the screen asks before saving a second copy. */
        val duplicateOf: Receipt? = null,
    ) : ReviewState
    data class Failed(val message: String) : ReviewState
}

class ReviewViewModel(
    private val app: AppContainer,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val receiptId: Long = handle.get<String>("id")?.toLongOrNull() ?: 0L
    /** One photo, several scanned pages, or a PDF. */
    private val imageUris: List<String> = PageUris.decode(handle.get<String>("uri"))

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
            val resumed = handle.get<String>(KEY_DRAFT)?.let(SavedDraft::decode)
            when {
                receiptId != 0L -> loadExisting(resumed)
                resumed != null -> resume(resumed)
                imageUris.isNotEmpty() -> processPhoto(imageUris.map { it.toUri() })
                else -> startManual()
            }
        }
        // Kept in the saved state so that Android closing the app in the background loses nothing.
        viewModelScope.launch {
            _state.collect { s ->
                if (s is ReviewState.Editing) handle[KEY_DRAFT] = SavedDraft.of(s.draft, s.imagePath, ocrText, s.notice).encode()
            }
        }
    }

    /** A new receipt that was being filled in when Android closed the app. */
    private fun resume(saved: SavedDraft) {
        ocrText = saved.ocrText
        imagePath = saved.imagePath?.takeIf { File(it).isFile }
        nextKey = (saved.items.maxOfOrNull { it.key } ?: 0) + 1
        _state.value = ReviewState.Editing(saved.draft, isNew = true, imagePath = imagePath, notice = saved.notice)
    }

    private suspend fun loadExisting(resumed: SavedDraft?) {
        val r = app.receipts.get(receiptId) ?: run {
            _state.value = ReviewState.Failed("That receipt no longer exists.")
            return
        }
        existing = r
        ocrText = r.ocrText
        imagePath = r.imagePath
        val at = Instant.ofEpochMilli(r.purchasedAt).atZone(ZoneId.systemDefault())
        val items = app.receipts.getItems(r.id).map { ItemDraft(nextKey++, it.name, it.quantity.toString(), Money.toPlain(it.priceCents)) }
        nextKey = maxOf(nextKey, (resumed?.items?.maxOfOrNull { it.key } ?: 0) + 1)
        _state.value = ReviewState.Editing(
            draft = resumed?.draft ?: ReceiptDraft(
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
                tags = Tags.display(r.tags),
                returnBy = r.returnBy?.let(LocalDate::ofEpochDay),
                warrantyUntil = r.warrantyUntil?.let(LocalDate::ofEpochDay),
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

    private suspend fun processPhoto(uris: List<Uri>) {
        val settings = app.settings.current()
        _state.value = ReviewState.Working("Saving photo…")
        // Every page is imported (upright, downscaled) and read on its own, then the pages are stacked into one photo.
        val pages = mutableListOf<File>()
        var fromPdf = false
        try {
            for (uri in uris) {
                if (pages.size >= PdfPages.MAX_PAGES) break
                if (PdfPages.isPdf(app.context, uri)) {
                    fromPdf = true
                    _state.value = ReviewState.Working("Opening PDF…")
                    val rendered = app.pdfPages.render(uri, PdfPages.MAX_PAGES - pages.size)
                    try {
                        rendered.forEach { pages += app.images.import(Uri.fromFile(it)) }
                    } finally {
                        rendered.forEach { it.delete() }
                    }
                } else {
                    pages += app.images.import(uri)
                }
            }
            require(pages.isNotEmpty()) { "Nothing to import." }
        } catch (e: Exception) {
            pages.forEach { app.images.delete(it.absolutePath) }
            val what = if (fromPdf) "PDF" else "photo"
            _state.value = ReviewState.Failed("Could not open that $what: ${e.message ?: "unknown error"}")
            return
        }

        var notice: String? = null
        val parsed = try {
            val rows = pages.flatMapIndexed { i, page ->
                _state.value = ReviewState.Working(if (pages.size == 1) "Reading receipt…" else "Reading page ${i + 1} of ${pages.size}…")
                LineAssembler.assemble(app.textReader.read(page))
            }
            ocrText = rows.joinToString("\n") { it.text }
            if (rows.isEmpty()) notice = "No text found. Fill in the details by hand, or retake the photo in better light."
            ReceiptParser.parse(rows, LocalDate.now(), settings.dayFirstDates)
        } catch (e: Exception) {
            notice = "Text recognition failed (${e.message ?: "unknown error"}). Fill in the details by hand."
            ParsedReceipt()
        }

        if (notice == null && parsed.totalCents == null) notice = "Couldn't find the total. Please check the amounts."

        imagePath = if (pages.size == 1) {
            pages.single().absolutePath
        } else {
            _state.value = ReviewState.Working("Joining ${pages.size} pages…")
            joinPages(pages) { notice = notice ?: it }
        }

        _state.value = ReviewState.Editing(
            draft = parsed.toDraft(settings.defaultCurrency),
            isNew = true,
            imagePath = imagePath,
            notice = notice,
        )
    }

    /** Stacks the pages into one photo; if that fails (very long scans on a small phone), keeps the first page. */
    private suspend fun joinPages(pages: List<File>, warn: (String) -> Unit): String {
        val joined = try {
            app.images.stitch(pages)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (joined != null) {
            pages.forEach { app.images.delete(it.absolutePath) }
            return joined.absolutePath
        }
        warn("Couldn't join the pages, so only the first one is kept as the photo. The text of every page was read.")
        pages.drop(1).forEach { app.images.delete(it.absolutePath) }
        return pages.first().absolutePath
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

    /** Tags on other receipts, most used first, offered as one-tap suggestions. */
    val tagsInUse: StateFlow<List<String>> = app.receipts.receipts
        .map { all -> Tags.inUse(all.map { it.tags }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTag(tag: String) = edit { d -> d.copy(tags = Tags.parse(d.tags + "," + tag).joinToString(", ")) }

    fun edit(transform: (ReceiptDraft) -> ReceiptDraft) {
        _state.update { s -> if (s is ReviewState.Editing) s.copy(draft = transform(s.draft), error = null) else s }
    }

    fun addItem() = edit { it.copy(items = it.items + ItemDraft(nextKey++, "", "1", "")) }

    fun dismissNotice() = _state.update { s -> if (s is ReviewState.Editing) s.copy(notice = null) else s }

    fun dismissDuplicate() = _state.update { s -> if (s is ReviewState.Editing) s.copy(duplicateOf = null) else s }

    /** @param allowDuplicate the user has seen the "already saved?" question and wants a second copy. */
    fun save(allowDuplicate: Boolean = false) {
        val s = _state.value as? ReviewState.Editing ?: return
        if (saving) return
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
            merchant = d.merchant.trim().ifBlank { Duplicates.UNKNOWN_MERCHANT },
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
            tags = Tags.encode(Tags.parse(d.tags)),
            returnBy = d.returnBy?.toEpochDay(),
            warrantyUntil = d.warrantyUntil?.toEpochDay(),
            imagePath = imagePath,
            ocrText = ocrText,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            deletedAt = existing?.deletedAt,
        )
        val items = d.items.mapNotNull { item ->
            val price = Money.parseUserInput(item.price) ?: return@mapNotNull null
            if (item.name.isBlank()) return@mapNotNull null
            LineItem(receiptId = 0, position = 0, name = item.name.trim(), quantity = item.quantity.toIntOrNull()?.coerceIn(1, 999) ?: 1, priceCents = price)
        }
        saving = true
        viewModelScope.launch {
            try {
                if (existing == null && !allowDuplicate) {
                    val twin = Duplicates.find(receipt, app.receipts.getAll())
                    if (twin != null) {
                        _state.update { if (it is ReviewState.Editing) it.copy(error = null, duplicateOf = twin) else it }
                        return@launch
                    }
                }
                _state.update { if (it is ReviewState.Editing) it.copy(error = null, duplicateOf = null) else it }
                _saved.value = app.receipts.save(receipt, items)
                handle.remove<String>(KEY_DRAFT)
                app.checkAlertsSoon()
            } finally {
                saving = false
            }
        }
    }

    private var saving = false

    override fun onCleared() {
        // A new capture the user backed out of: do not leave an orphaned photo behind.
        if (existing == null && _saved.value == null) app.receipts.discardImage(imagePath)
    }

    companion object {
        internal const val KEY_DRAFT = "review_draft"
    }
}
