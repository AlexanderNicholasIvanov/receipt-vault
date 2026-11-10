package com.alexivanov.receiptvault.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM receipts WHERE deletedAt IS NULL ORDER BY purchasedAt DESC, id DESC")
    fun observeAll(): Flow<List<Receipt>>

    @Query("SELECT * FROM receipts WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, id DESC")
    fun observeTrash(): Flow<List<Receipt>>

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun observe(id: Long): Flow<Receipt?>

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun get(id: Long): Receipt?

    @Query("SELECT * FROM receipts WHERE deletedAt IS NULL ORDER BY purchasedAt DESC, id DESC")
    suspend fun getAll(): List<Receipt>

    /** Including the trash. */
    @Query("SELECT * FROM receipts")
    suspend fun getEverything(): List<Receipt>

    @Query("SELECT * FROM receipts WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Receipt>

    @Query("SELECT * FROM receipts WHERE deletedAt IS NOT NULL")
    suspend fun getTrash(): List<Receipt>

    @Query("UPDATE receipts SET deletedAt = :deletedAt WHERE id IN (:ids)")
    suspend fun setDeletedAt(ids: List<Long>, deletedAt: Long?)

    @Query("SELECT * FROM line_items WHERE receiptId = :receiptId ORDER BY position")
    fun observeItems(receiptId: Long): Flow<List<LineItem>>

    @Query("SELECT * FROM line_items WHERE receiptId = :receiptId ORDER BY position")
    suspend fun getItems(receiptId: Long): List<LineItem>

    /** Items of receipts that aren't in the trash. */
    @Query("SELECT line_items.* FROM line_items JOIN receipts ON receipts.id = line_items.receiptId WHERE receipts.deletedAt IS NULL")
    suspend fun getAllItems(): List<LineItem>

    /** Item names of receipts not in the trash, for search. */
    @Query("SELECT line_items.receiptId AS receiptId, line_items.name AS name FROM line_items JOIN receipts ON receipts.id = line_items.receiptId WHERE receipts.deletedAt IS NULL")
    fun observeItemNames(): Flow<List<ItemName>>

    @Query("SELECT * FROM line_items WHERE receiptId IN (:ids)")
    suspend fun getItemsFor(ids: List<Long>): List<LineItem>

    @Insert
    suspend fun insert(receipt: Receipt): Long

    @Update
    suspend fun update(receipt: Receipt)

    @Delete
    suspend fun delete(receipt: Receipt)

    @Insert
    suspend fun insertItems(items: List<LineItem>)

    @Query("DELETE FROM line_items WHERE receiptId = :receiptId")
    suspend fun deleteItems(receiptId: Long)

    /** Inserts or updates a receipt and replaces its line items atomically. Returns the receipt id. */
    @Transaction
    suspend fun save(receipt: Receipt, items: List<LineItem>): Long {
        val id = if (receipt.id == 0L) insert(receipt) else receipt.id.also { update(receipt) }
        deleteItems(id)
        if (items.isNotEmpty()) {
            insertItems(items.mapIndexed { i, item -> item.copy(id = 0, receiptId = id, position = i) })
        }
        return id
    }
}

data class ItemName(val receiptId: Long, val name: String)
