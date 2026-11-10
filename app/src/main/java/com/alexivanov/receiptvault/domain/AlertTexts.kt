package com.alexivanov.receiptvault.domain

data class AlertText(val title: String, val text: String)

/**
 * What reminder and budget notifications say. Merchants and amounts never reach the phone's
 * lock screen, and with app lock on they stay out of the notification shade as well.
 */
object AlertTexts {

    /** Shown on a secure lock screen instead of the notification itself. */
    val lockScreen = AlertText("Receipt Vault", "Unlock your phone to see this alert.")

    fun reminder(r: Reminder, hideDetails: Boolean): AlertText = when {
        !hideDetails -> AlertText(r.title, r.text)
        r.kind == ReminderKind.RETURN -> AlertText("A return window is closing", OPEN_TO_SEE)
        else -> AlertText("A warranty is ending", OPEN_TO_SEE)
    }

    fun budget(use: BudgetUse, currency: String, hideDetails: Boolean): AlertText {
        val over = use.level == BudgetLevel.OVER
        if (hideDetails) return AlertText(if (over) "Over budget" else "A budget is almost used", OPEN_TO_SEE)
        val title = when {
            over && use.category == null -> "Over your monthly budget"
            over -> "Over budget: ${use.label}"
            use.category == null -> "Monthly budget almost used"
            else -> "Budget almost used: ${use.label}"
        }
        val spent = Money.format(use.spentCents, currency)
        val limit = Money.format(use.budgetCents, currency)
        return AlertText(title, "$spent of $limit spent this month.")
    }

    private const val OPEN_TO_SEE = "Open Receipt Vault to see the details."
}
