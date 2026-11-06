package com.alexivanov.receiptvault.data

import com.alexivanov.receiptvault.scan.ImageStore
import kotlinx.coroutines.flow.Flow

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

    suspend fun delete(receipt: Receipt) {
        dao.delete(receipt)
        images.delete(receipt.imagePath)
    }

    /** Drops a photo that was captured but never saved as a receipt. */
    fun discardImage(path: String?) = images.delete(path)
}
