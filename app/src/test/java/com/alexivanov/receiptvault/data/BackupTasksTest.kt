package com.alexivanov.receiptvault.data

import com.alexivanov.receiptvault.domain.BackupException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BackupTasksTest {

    @Test
    fun `a task runs to the end with nobody watching and its outcome still reaches the user`() = runTest {
        val unseen = mutableListOf<String>()
        val tasks = BackupTasks(backgroundScope) { unseen += it }
        val stop = tasks.watch()
        val finish = CompletableDeferred<Unit>()

        assertTrue(tasks.run { finish.await(); "Restored 3 receipts." })
        runCurrent()
        assertTrue(tasks.busy.value)
        assertFalse("one at a time", tasks.run { "second" })

        stop() // Settings closes while it runs.
        finish.complete(Unit)
        runCurrent()

        assertFalse(tasks.busy.value)
        assertEquals(listOf("Restored 3 receipts."), unseen)
        assertNull(tasks.result.value)
    }

    @Test
    fun `an open Settings screen gets the outcome to show`() = runTest {
        val unseen = mutableListOf<String>()
        val tasks = BackupTasks(backgroundScope) { unseen += it }
        tasks.watch()

        tasks.run { throw BackupException("That isn't a Receipt Vault backup.") }
        runCurrent()
        assertEquals("That isn't a Receipt Vault backup.", tasks.result.value)
        tasks.resultShown()
        assertNull(tasks.result.value)

        tasks.run { throw IOException("write failed: ENOSPC (No space left on device)") }
        runCurrent()
        assertEquals("Something went wrong. The phone is out of storage space.", tasks.result.value)
        assertTrue(unseen.isEmpty())
        assertFalse(tasks.busy.value)
    }
}
