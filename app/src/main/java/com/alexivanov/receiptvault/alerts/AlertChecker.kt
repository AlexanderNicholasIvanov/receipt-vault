package com.alexivanov.receiptvault.alerts

import com.alexivanov.receiptvault.data.ReceiptRepository
import com.alexivanov.receiptvault.data.SettingsRepository
import com.alexivanov.receiptvault.domain.BudgetLevel
import com.alexivanov.receiptvault.domain.BudgetMath
import com.alexivanov.receiptvault.domain.Conversion
import com.alexivanov.receiptvault.domain.BudgetUse
import com.alexivanov.receiptvault.domain.Reminder
import com.alexivanov.receiptvault.domain.Reminders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Shows notifications; the Android implementation is [AndroidNotifier]. */
interface Notifier {
    /** False when the user hasn't allowed notifications; nothing is marked as sent then. */
    fun enabled(): Boolean

    /** [hideDetails]: app lock is on, so no merchant or amount appears even on an unlocked phone. */
    fun reminder(reminder: Reminder, hideDetails: Boolean)
    fun budget(use: BudgetUse, currency: String, hideDetails: Boolean)
}

/**
 * Sends each due return/warranty reminder and each budget warning once.
 * Runs daily from [AlertWorker], at app start, and after a receipt is saved.
 */
class AlertChecker(
    private val receipts: ReceiptRepository,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
) {
    private val mutex = Mutex()

    /** Returns the keys of the notifications it sent. */
    suspend fun run(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<String> = mutex.withLock {
        if (!notifier.enabled()) return emptyList()
        val all = receipts.getAll()
        val s = settings.current()
        val sent = settings.sentAlerts()
        val posted = mutableListOf<String>()

        for (r in Reminders.due(all, today)) {
            if (r.key in sent) continue
            notifier.reminder(r, hideDetails = s.appLock)
            posted += r.key
        }

        val month = YearMonth.from(today)
        for (use in BudgetMath.usage(s.budgets, Conversion.toBase(all, s.activeRates), s.defaultCurrency, month, zone)) {
            val key = BudgetMath.alertKey(month, use) ?: continue
            if (key in sent) continue
            notifier.budget(use, s.defaultCurrency, hideDetails = s.appLock)
            posted += key
            // Going straight past 80 % to over budget sends one warning, not two.
            if (use.level == BudgetLevel.OVER) {
                posted += BudgetMath.alertKey(month, use.copy(spentCents = use.budgetCents))!!
            }
        }

        if (posted.isNotEmpty() || sent.any { !isRecent(it, today) }) {
            settings.setSentAlerts((sent + posted).filterTo(HashSet()) { isRecent(it, today) })
        }
        posted
    }

    /** Old keys are dropped so the list doesn't grow forever; their dates are long past anyway. */
    internal fun isRecent(key: String, today: LocalDate): Boolean {
        val parts = key.split(':')
        val date = runCatching {
            when (parts.firstOrNull()) {
                "budget" -> YearMonth.parse(parts[1]).atEndOfMonth()
                else -> LocalDate.parse(parts.last())
            }
        }.getOrNull() ?: return false
        return !date.isBefore(today.minusDays(KEEP_DAYS))
    }

    private companion object {
        const val KEEP_DAYS = 120L
    }
}
