package com.alexivanov.receiptvault.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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

    fun spending(alert: SpendingAlert, currency: String, hideDetails: Boolean): AlertText {
        fun m(cents: Long) = Money.format(cents, currency)
        return when (alert) {
            is SpendingAlert.PriceUp -> if (hideDetails) {
                AlertText("A regular payment went up", OPEN_TO_SEE)
            } else {
                val b = alert.bill
                val before = b.previousCents ?: b.typicalCents
                val percent = Math.floorDiv((b.latestCents - before) * 200 + before, before * 2)
                AlertText("Price up: ${b.merchant}", "${m(before)} → ${m(b.latestCents)} (+$percent%) on ${formatDay(b.lastDate)}.")
            }
            is SpendingAlert.DueTomorrow -> if (hideDetails) {
                AlertText("A bill is due tomorrow", OPEN_TO_SEE)
            } else {
                AlertText("Due tomorrow: ${alert.bill.merchant}", "Usually ${m(alert.bill.typicalCents)}.")
            }
            is SpendingAlert.NewRegular -> if (hideDetails) {
                AlertText("A new regular payment", OPEN_TO_SEE)
            } else {
                val b = alert.bill
                AlertText(
                    "New regular payment: ${b.merchant}",
                    "${m(b.typicalCents)} ${CostEstimate.everyLabel(b.everyDays).lowercase()}, about ${m(b.monthlyCents * 12)} a year.",
                )
            }
            is SpendingAlert.AboveUsual -> if (hideDetails) {
                AlertText("Spending is above usual", OPEN_TO_SEE)
            } else {
                AlertText(
                    "${alert.category.label} is above usual",
                    "${m(alert.spentCents)} so far this month; a usual month is about ${m(alert.usualCents)}.",
                )
            }
        }
    }

    private fun formatDay(d: LocalDate) = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    private const val OPEN_TO_SEE = "Open Receipt Vault to see the details."
}
