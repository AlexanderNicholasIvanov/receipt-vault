package com.alexivanov.receiptvault.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexivanov.receiptvault.AppContainer
import com.alexivanov.receiptvault.data.AppSettings
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.CsvExport
import com.alexivanov.receiptvault.domain.ImageNames
import com.alexivanov.receiptvault.domain.Insights
import com.alexivanov.receiptvault.domain.Period
import com.alexivanov.receiptvault.domain.Summary
import com.alexivanov.receiptvault.update.UpdateController
import com.alexivanov.receiptvault.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class HomeUi(
    val loading: Boolean = true,
    val period: Period = Period.THIS_MONTH,
    val query: String = "",
    val receipts: List<Receipt> = emptyList(),
    val summary: Summary? = null,
    val hasAny: Boolean = false,
    /** Update to show in the home banner, or null. */
    val update: UpdateState? = null,
    val settings: AppSettings = AppSettings(),
)

class HomeViewModel(private val app: AppContainer) : ViewModel() {
    private val period = MutableStateFlow(Period.THIS_MONTH)
    private val query = MutableStateFlow("")

    val updates: UpdateController = app.updates

    // "Later" hides an offered release until a newer one appears; once a download
    // has started the banner stays so progress and the Install button remain visible.
    private val visibleUpdate = combine(app.updates.state, app.settings.settings) { state, s ->
        when (state) {
            is UpdateState.Available -> state.takeIf { it.info.versionCode > s.dismissedUpdateVersionCode }
            is UpdateState.Downloading, is UpdateState.ReadyToInstall, is UpdateState.DownloadFailed -> state
            else -> null
        }
    }

    /** Receipts just moved to trash, from here or a receipt's screen, for the Undo snackbar. */
    val pendingUndo: StateFlow<List<Long>?> = app.receipts.pendingUndo

    fun undoOffered(ids: List<Long>) = app.receipts.undoOffered(ids)

    val ui: StateFlow<HomeUi> = combine(app.receipts.receipts, period, query, visibleUpdate, app.settings.settings) { all, p, q, update, settings ->
        val inPeriod = Insights.inPeriod(all, p, LocalDate.now())
        val needle = q.trim().lowercase()
        val filtered = if (needle.isEmpty()) inPeriod else inPeriod.filter { r -> matches(r, needle) }
        HomeUi(
            loading = false,
            period = p,
            query = q,
            receipts = filtered,
            summary = Insights.summarize(filtered, settings.defaultCurrency),
            hasAny = all.isNotEmpty(),
            update = update,
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    init {
        app.checkForUpdatesOnLaunch()
    }

    // ------------------------------------------------------------------ selection

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())

    /** Receipts picked with a long-press; non-empty means the list is in selection mode. */
    val selected: StateFlow<Set<Long>> = _selected

    fun toggleSelected(id: Long) = _selected.update { if (id in it) it - id else it + id }
    fun selectAll() = _selected.update { ui.value.receipts.mapTo(HashSet()) { it.id } }
    fun clearSelection() { _selected.value = emptySet() }

    fun trashSelected() {
        val ids = _selected.value.toList()
        clearSelection()
        viewModelScope.launch { app.receipts.moveToTrash(ids) }
    }

    fun undoTrash(ids: List<Long>) {
        viewModelScope.launch { app.receipts.restoreFromTrash(ids) }
    }

    /** CSV of just the selected receipts. */
    suspend fun exportSelected(dir: File): File = withContext(Dispatchers.IO) {
        val ids = _selected.value.toList()
        val receipts = app.receipts.getByIds(ids).sortedByDescending { it.purchasedAt }
        val csv = CsvExport.build(receipts, app.receipts.getItemsFor(ids))
        File(dir, "exports").apply { mkdirs() }.resolve("receipts-selected-${LocalDate.now()}.csv").apply { writeText(csv) }
    }

    /** Saves the photos of the selected receipts to Pictures; returns how many were saved. */
    suspend fun downloadSelectedPhotos(): Int {
        var saved = 0
        for (r in app.receipts.getByIds(_selected.value.toList())) {
            val path = r.imagePath ?: continue
            if (runCatching { app.images.saveToPictures(path, ImageNames.forReceipt(r.merchant, r.purchasedAt)) }.isSuccess) saved++
        }
        return saved
    }

    fun setPeriod(p: Period) { period.value = p }
    fun setQuery(q: String) { query.value = q }

    fun dismissUpdate(versionCode: Int) {
        viewModelScope.launch { app.settings.setDismissedUpdate(versionCode) }
    }

    private fun matches(r: Receipt, needle: String): Boolean =
        r.merchant.lowercase().contains(needle) ||
            Category.fromName(r.category).label.lowercase().contains(needle) ||
            r.notes.lowercase().contains(needle) ||
            r.address.orEmpty().lowercase().contains(needle) ||
            r.ocrText.lowercase().contains(needle)
}
