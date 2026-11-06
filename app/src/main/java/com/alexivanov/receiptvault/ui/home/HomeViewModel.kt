package com.alexivanov.receiptvault.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexivanov.receiptvault.AppContainer
import com.alexivanov.receiptvault.data.AppSettings
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.Insights
import com.alexivanov.receiptvault.domain.Period
import com.alexivanov.receiptvault.domain.Summary
import com.alexivanov.receiptvault.update.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeUi(
    val loading: Boolean = true,
    val period: Period = Period.THIS_MONTH,
    val query: String = "",
    val receipts: List<Receipt> = emptyList(),
    val summary: Summary? = null,
    val hasAny: Boolean = false,
    val update: UpdateInfo? = null,
    val settings: AppSettings = AppSettings(),
)

class HomeViewModel(private val app: AppContainer) : ViewModel() {
    private val period = MutableStateFlow(Period.THIS_MONTH)
    private val query = MutableStateFlow("")

    private val visibleUpdate = combine(app.update, app.settings.settings) { info, s ->
        info?.takeIf { it.versionCode > s.dismissedUpdateVersionCode }
    }

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
        app.checkForUpdateOnce()
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
            r.locationLabel.orEmpty().lowercase().contains(needle) ||
            r.paymentMethod.orEmpty().lowercase().contains(needle) ||
            r.ocrText.lowercase().contains(needle)
}
