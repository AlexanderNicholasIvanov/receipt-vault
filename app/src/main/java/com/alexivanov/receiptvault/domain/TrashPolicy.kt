package com.alexivanov.receiptvault.domain

import java.util.concurrent.TimeUnit

/** Deleted receipts wait in the trash this long before they're removed for good. */
object TrashPolicy {
    const val RETENTION_DAYS = 30L

    /** Receipts trashed before this moment are due for permanent deletion. */
    fun cutoff(now: Long): Long = now - TimeUnit.DAYS.toMillis(RETENTION_DAYS)

    fun daysLeft(deletedAt: Long, now: Long): Long =
        (RETENTION_DAYS - TimeUnit.MILLISECONDS.toDays(now - deletedAt)).coerceAtLeast(0)
}
