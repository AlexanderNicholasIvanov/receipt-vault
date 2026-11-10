package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException

class ProblemsTest {

    @Test
    fun `system messages are replaced with plain words`() {
        assertEquals(
            "Couldn't save the image. The file is no longer there.",
            Problems.message("Couldn't save the image", FileNotFoundException("/data/user/0/x.jpg: open failed: ENOENT (No such file or directory)")),
        )
        assertEquals(
            "Couldn't make the report. The phone is out of storage space.",
            Problems.message("Couldn't make the report", IOException("write failed", IOException("write failed: ENOSPC (No space left on device)"))),
        )
        assertEquals(
            "Couldn't open that photo. Receipt Vault isn't allowed to open that file any more.",
            Problems.message("Couldn't open that photo", SecurityException("Permission Denial: opening provider com.android.providers…")),
        )
        assertEquals("Couldn't do it. Please try again.", Problems.message("Couldn't do it", IllegalStateException("lateinit property x has not been initialized")))
    }

    @Test
    fun `messages written for people are shown as they are`() {
        assertEquals("That PDF is password-protected.", Problems.message("Couldn't open that PDF", UserFacingException("That PDF is password-protected.")))
        assertEquals("That isn't a Receipt Vault backup.", Problems.message("Couldn't restore", BackupException("That isn't a Receipt Vault backup.")))
    }

    @Test
    fun `failures while online point at the connection`() {
        assertEquals("Download failed. Check your internet connection and try again.", Problems.message("Download failed", IOException("unexpected end of stream"), online = true))
        assertEquals("Check your internet connection and try again.", Problems.reason(UnknownHostException("Unable to resolve host \"api.github.com\"")))
        assertEquals("The file couldn't be read or written.", Problems.reason(IOException("unexpected end of stream")))
    }
}
