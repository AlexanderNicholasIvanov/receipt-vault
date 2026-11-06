package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class ReminderKind(val leadDays: Long) {
    /** Last day to return the item to the store. */
    RETURN(leadDays = 2),

    /** Last day of the warranty. */
    WARRANTY(leadDays = 30),
}

data class Reminder(
    val receiptId: Long,
    val merchant: String,
    val kind: ReminderKind,
    val date: LocalDate,
    val daysLeft: Long,
) {
    /** Changing the date re-arms the reminder. */
    val key: String get() = "${kind.name.lowercase()}:$receiptId:$date"

    val title: String
        get() = when (kind) {
            ReminderKind.RETURN -> "Return window closing: $merchant"
            ReminderKind.WARRANTY -> "Warranty ending: $merchant"
        }

    val text: String
        get() = when (kind) {
            ReminderKind.RETURN -> "${whenText()} is the last day to return it."
            ReminderKind.WARRANTY -> "The warranty runs out ${whenText().replaceFirstChar { it.lowercase() }}."
        }

    private fun whenText(): String = when (daysLeft) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> "In $daysLeft days"
    }
}

object Reminders {

    /** Return and warranty dates within their lead time, soonest first. Past dates are not nagged about. */
    fun due(receipts: List<Receipt>, today: LocalDate): List<Reminder> =
        all(receipts, today).filter { it.daysLeft in 0..it.kind.leadDays }

    /** Every reminder that hasn't passed, soonest first. */
    fun all(receipts: List<Receipt>, today: LocalDate): List<Reminder> = receipts
        .flatMap { r ->
            listOfNotNull(
                r.returnBy?.let { reminder(r, ReminderKind.RETURN, LocalDate.ofEpochDay(it), today) },
                r.warrantyUntil?.let { reminder(r, ReminderKind.WARRANTY, LocalDate.ofEpochDay(it), today) },
            )
        }
        .filter { it.daysLeft >= 0 }
        .sortedWith(compareBy<Reminder> { it.date }.thenBy { it.kind })

    fun daysLeft(date: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, date)

    private fun reminder(r: Receipt, kind: ReminderKind, date: LocalDate, today: LocalDate) =
        Reminder(r.id, r.merchant, kind, date, daysLeft(date, today))
}
