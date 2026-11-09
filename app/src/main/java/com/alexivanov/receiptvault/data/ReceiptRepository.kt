package com.alexivanov.receiptvault.data

import com.alexivanov.receiptvault.scan.ImageStore
import com.alexivanov.receiptvault.domain.TrashPolicy
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ReceiptRepository(
    private val dao: ReceiptDao,
    private val images: ImageStore,
) {
    val receipts: Flow<List<Receipt>> = dao.observeAll()

    fun receipt(id: Long): Flow<Receipt?> = dao.observe(id)
    fun items(id: Long): Flow<List<LineItem>> = dao.observeItems(id)

    suspend fun get(id: Long): Receipt? = dao.get(id)
    suspend fun getItems(id: Long): List<LineItem> = dao.getItems(id)
    suspend fun getAll(): List<Receipt> = dao.getAll()
    suspend fun getAllItems(): List<LineItem> = dao.getAllItems()

    suspend fun save(receipt: Receipt, items: List<LineItem>): Long = dao.save(receipt, items)

    val trash: Flow<List<Receipt>> = dao.observeTrash()

    /**
     * The last batch moved to the trash, until the home screen has offered Undo
     * for it. A state rather than an event: a delete on a receipt's own screen
     * happens before Home is back on screen to hear about it.
     */
    private val _pendingUndo = MutableStateFlow<List<Long>?>(null)
    val pendingUndo: StateFlow<List<Long>?> = _pendingUndo

    /** Called once Home has shown the Undo prompt for [ids]. */
    fun undoOffered(ids: List<Long>) {
        _pendingUndo.compareAndSet(ids, null)
    }

    suspend fun getEverything(): List<Receipt> = dao.getEverything()
    suspend fun getByIds(ids: List<Long>): List<Receipt> = dao.getByIds(ids)
    suspend fun getItemsFor(ids: List<Long>): List<LineItem> = dao.getItemsFor(ids)

    suspend fun moveToTrash(ids: List<Long>, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        dao.setDeletedAt(ids, now)
        _pendingUndo.value = ids
    }

    suspend fun restoreFromTrash(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.setDeletedAt(ids, null)
    }

    /** Deletes receipts and their photos for good. */
    suspend fun deleteForever(receipts: List<Receipt>) {
        for (r in receipts) {
            dao.delete(r)
            images.delete(r.imagePath)
        }
    }

    suspend fun emptyTrash() = deleteForever(dao.getTrash())

    /** Run at startup: the trash keeps receipts for [TrashPolicy.RETENTION_DAYS] days. */
    suspend fun purgeExpiredTrash(now: Long = System.currentTimeMillis()): Int {
        val expired = dao.getTrashedBefore(TrashPolicy.cutoff(now))
        deleteForever(expired)
        return expired.size
    }

    /** Drops a photo that was captured but never saved as a receipt. */
    fun discardImage(path: String?) = images.delete(path)

    /** Removes photos left behind by drafts that were never saved. @return how many. */
    suspend fun sweepOrphanImages(): Int =
        images.sweepOrphans(dao.getEverything().mapNotNullTo(HashSet()) { r -> r.imagePath?.let { File(it).name } })
}
