package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun `relocks only when enabled and away for at least the grace period`() {
        val left = 1_000_000L
        assertFalse(AppLockPolicy.shouldLockOnReturn(enabled = true, backgroundedAt = left, now = left + 59_999))
        assertTrue(AppLockPolicy.shouldLockOnReturn(enabled = true, backgroundedAt = left, now = left + 60_000))
        assertFalse(AppLockPolicy.shouldLockOnReturn(enabled = false, backgroundedAt = left, now = left + 3_600_000))
        assertFalse(AppLockPolicy.shouldLockOnReturn(enabled = true, backgroundedAt = null, now = left))
    }
}
