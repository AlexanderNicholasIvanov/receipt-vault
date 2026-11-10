package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoDownloadTest {

    @Test
    fun `the message says what actually happened`() {
        assertEquals("None of these receipts have a photo.", PhotoDownload(saved = 0, failed = 0).message)
        assertEquals("Saved 1 photo to Pictures/Receipt Vault", PhotoDownload(saved = 1, failed = 0).message)
        // Used to claim none of them had a photo.
        assertEquals("Couldn't save the photos. Check that the phone has free space.", PhotoDownload(saved = 0, failed = 3).message)
        assertEquals("Saved 2 photos to Pictures/Receipt Vault. 1 couldn't be saved.", PhotoDownload(saved = 2, failed = 1).message)
    }
}
