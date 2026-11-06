package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class TrashPolicyTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_800_000_000_000

    @Test
    fun `cutoff is thirty days back`() {
        assertEquals(now - 30 * day, TrashPolicy.cutoff(now))
    }

    @Test
    fun `days left counts down and never goes negative`() {
        assertEquals(30, TrashPolicy.daysLeft(now, now))
        assertEquals(30, TrashPolicy.daysLeft(now - day / 2, now))
        assertEquals(29, TrashPolicy.daysLeft(now - day, now))
        assertEquals(1, TrashPolicy.daysLeft(now - 29 * day, now))
        assertEquals(0, TrashPolicy.daysLeft(now - 40 * day, now))
    }
}
