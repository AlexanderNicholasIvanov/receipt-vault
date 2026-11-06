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
    @Query("SELECT * FROM receipts ORDER BY purchasedAt DESC, id DESC")
    fun observeAll(): Flow<List<Receipt>>

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun observe(id: Long): Flow<Receipt?>

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun get(id: Long): Receipt?

    @Query("SELECT * FROM receipts ORDER BY purchasedAt DESC, id DESC")
    suspend fun getAll(): List<Receipt>

    @Query("SELECT * FROM line_items WHERE receiptId = :receiptId ORDER BY position")
    fun observeItems(receiptId: Long): Flow<List<LineItem>>

    @Query("SELECT * FROM line_items WHERE receiptId = :receiptId ORDER BY position")
    suspend fun getItems(receiptId: Long): List<LineItem>

    @Query("SELECT * FROM line_items")
    suspend fun getAllItems(): List<LineItem>

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
