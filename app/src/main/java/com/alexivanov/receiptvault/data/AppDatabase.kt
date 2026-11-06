package com.alexivanov.receiptvault.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

/**
 * Receipts live only on the phone, so every schema change ships a migration
 * that keeps them. Never use a destructive fallback.
 */
@Database(
    entities = [Receipt::class, LineItem::class],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.DropPaymentMethod::class),
        AutoMigration(from = 2, to = 3, spec = AppDatabase.DropScanLocation::class),
        // v4: receipts.deletedAt (trash). Adding a nullable column needs no spec.
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao

    /** v2: the app no longer records how a receipt was paid. */
    @DeleteColumn.Entries(
        DeleteColumn(tableName = "receipts", columnName = "paymentMethod"),
        DeleteColumn(tableName = "receipts", columnName = "cardLast4"),
    )
    class DropPaymentMethod : AutoMigrationSpec

    /** v3: the app no longer records where the phone was when a receipt was scanned. */
    @DeleteColumn.Entries(
        DeleteColumn(tableName = "receipts", columnName = "latitude"),
        DeleteColumn(tableName = "receipts", columnName = "longitude"),
        DeleteColumn(tableName = "receipts", columnName = "locationLabel"),
    )
    class DropScanLocation : AutoMigrationSpec

    companion object {
        const val NAME = "receipts.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
