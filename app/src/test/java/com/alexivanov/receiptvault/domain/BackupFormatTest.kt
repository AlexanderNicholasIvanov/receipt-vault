package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupFormatTest {

    @Test
    fun `round trips and ignores fields from newer versions`() {
        val m = BackupManifest(appVersion = "1.0.9", exportedAt = 42, receipts = listOf(
            BackupReceipt(merchant = "A", purchasedAt = 1, totalCents = 100, currency = "USD", items = listOf(BackupItem("x", 2, 50))),
        ))
        assertEquals(m, BackupFormat.decode(BackupFormat.encode(m)))

        val future = """{"app":"receipt-vault","format":1,"someNewThing":true,
            "receipts":[{"merchant":"B","purchasedAt":2,"totalCents":3,"currency":"EUR","tags":["work"]}]}"""
        assertEquals("B", BackupFormat.decode(future).receipts.single().merchant)
    }

    @Test
    fun `rejects other apps, newer formats and garbage`() {
        fun message(text: String) = runCatching { BackupFormat.decode(text) }.exceptionOrNull()?.message
        assertEquals("This isn't a Receipt Vault backup.", message("""{"app":"other"}"""))
        assertTrue(message("""{"app":"receipt-vault","format":99}""")!!.contains("newer version"))
        assertTrue(message("not json")!!.contains("damaged"))
    }

    @Test
    fun `only photos slash number dot jpg are photo entries`() {
        assertTrue(BackupFormat.isPhotoEntry("photos/12.jpg"))
        assertFalse(BackupFormat.isPhotoEntry("../photos/12.jpg"))
        assertFalse(BackupFormat.isPhotoEntry("photos/../../x.jpg"))
        assertFalse(BackupFormat.isPhotoEntry("photos/12.jpg/../a"))
    }

    @Test
    fun `same purchase key ignores case and spacing of the merchant`() {
        assertEquals(
            BackupFormat.sameReceiptKey(" Corner Cafe", 1, 2, "usd"),
            BackupFormat.sameReceiptKey("corner cafe ", 1, 2, "USD"),
        )
        assertEquals("ReceiptVault-backup-2026-11-06.zip", BackupFormat.fileName(LocalDate.of(2026, 11, 6)))
    }
}
