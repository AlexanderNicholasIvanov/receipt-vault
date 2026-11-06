package com.alexivanov.receiptvault.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds an old-version database exactly as Room exported it (app/schemas),
 * fills it like a real install would have, then opens it with the current
 * [AppDatabase] so the shipped migrations run the same way they do on a phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before
    @After
    fun clean() {
        context.deleteDatabase(name)
    }

    @Test
    fun `v1 install keeps receipts and line items, payment and scan location are gone`() {
        createAtVersion(1) { db ->
            db.execSQL(
                """INSERT INTO receipts (id, merchant, address, phone, category, purchasedAt, subtotalCents, taxCents,
                   tipCents, discountCents, totalCents, currency, paymentMethod, cardLast4, latitude, longitude,
                   locationLabel, notes, imagePath, ocrText, createdAt, updatedAt)
                   VALUES (7, 'Corner Cafe', '1 Main St', NULL, 'DINING', 1000, 2000, 160, 400, NULL, 2560, 'USD',
                   'Visa', '4821', 40.1, -73.2, 'Main St', 'lunch', NULL, 'CORNER CAFE', 1, 2)""",
            )
            db.execSQL(LINE_ITEM)
        }
        assertMigrated()
    }

    @Test
    fun `v2 install keeps receipts and line items, scan location is gone`() {
        createAtVersion(2) { db ->
            db.execSQL(
                """INSERT INTO receipts (id, merchant, address, phone, category, purchasedAt, subtotalCents, taxCents,
                   tipCents, discountCents, totalCents, currency, latitude, longitude,
                   locationLabel, notes, imagePath, ocrText, createdAt, updatedAt)
                   VALUES (7, 'Corner Cafe', '1 Main St', NULL, 'DINING', 1000, 2000, 160, 400, NULL, 2560, 'USD',
                   40.1, -73.2, 'Main St', 'lunch', NULL, 'CORNER CAFE', 1, 2)""",
            )
            db.execSQL(LINE_ITEM)
        }
        assertMigrated()
    }

    @Test
    fun `v3 install keeps receipts and line items, none of them in the trash`() {
        createAtVersion(3) { db ->
            db.execSQL(
                """INSERT INTO receipts (id, merchant, address, phone, category, purchasedAt, subtotalCents, taxCents,
                   tipCents, discountCents, totalCents, currency, notes, imagePath, ocrText, createdAt, updatedAt)
                   VALUES (7, 'Corner Cafe', '1 Main St', NULL, 'DINING', 1000, 2000, 160, 400, NULL, 2560, 'USD',
                   'lunch', NULL, 'CORNER CAFE', 1, 2)""",
            )
            db.execSQL(LINE_ITEM)
        }
        assertMigrated()
    }

    @Test
    fun `v4 install keeps receipts, trash and items, with no tags yet`() {
        createAtVersion(4) { db ->
            db.execSQL(
                """INSERT INTO receipts (id, merchant, address, phone, category, purchasedAt, subtotalCents, taxCents,
                   tipCents, discountCents, totalCents, currency, notes, imagePath, ocrText, createdAt, updatedAt, deletedAt)
                   VALUES (7, 'Corner Cafe', '1 Main St', NULL, 'DINING', 1000, 2000, 160, 400, NULL, 2560, 'USD',
                   'lunch', NULL, 'CORNER CAFE', 1, 2, NULL),
                   (8, 'Old Diner', NULL, NULL, 'DINING', 900, NULL, NULL, NULL, NULL, 500, 'USD',
                   '', NULL, '', 1, 2, 12345)""",
            )
            db.execSQL(LINE_ITEM)
        }
        assertMigrated { dao ->
            // A receipt that was in the trash stays there.
            assertEquals(listOf(8L), runBlocking { dao.getTrash() }.map { it.id })
            assertEquals(12345L, runBlocking { dao.get(8) }!!.deletedAt)
        }
    }

    @Test
    fun `v5 install keeps receipts and tags, with no reminders yet`() {
        createAtVersion(5) { db ->
            db.execSQL(
                """INSERT INTO receipts (id, merchant, address, phone, category, purchasedAt, subtotalCents, taxCents,
                   tipCents, discountCents, totalCents, currency, notes, imagePath, ocrText, createdAt, updatedAt, deletedAt, tags)
                   VALUES (7, 'Corner Cafe', '1 Main St', NULL, 'DINING', 1000, 2000, 160, 400, NULL, 2560, 'USD',
                   'lunch', NULL, 'CORNER CAFE', 1, 2, NULL, '')""",
            )
            db.execSQL(LINE_ITEM)
        }
        assertMigrated { dao ->
            val r = runBlocking { dao.get(7) }!!
            assertNull(r.returnBy)
            assertNull(r.warrantyUntil)
        }
    }

    /** Opens the database with the current [AppDatabase], which runs the shipped migrations. */
    private fun assertMigrated(extra: (ReceiptDao) -> Unit = {}) {
        val room = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val dao = room.receiptDao()
            val r = runBlocking { dao.get(7) }!!
            assertEquals("Corner Cafe", r.merchant)
            assertEquals(2560L, r.totalCents)
            assertEquals(400L, r.tipCents)
            assertEquals("1 Main St", r.address)
            assertEquals("lunch", r.notes)
            assertNull("an upgraded receipt must not land in the trash", r.deletedAt)
            assertEquals(listOf(7L), runBlocking { dao.getAll() }.map { it.id })
            assertEquals("", r.tags)
            extra(dao)
            // The receipts table is rebuilt; its line items must not be cascade-deleted along the way.
            assertEquals(listOf("Latte"), runBlocking { dao.getItems(7) }.map { it.name })

            val columns = room.openHelper.readableDatabase.query("PRAGMA table_info(receipts)").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            for (gone in listOf("paymentMethod", "cardLast4", "latitude", "longitude", "locationLabel")) {
                assertFalse("$gone should be dropped", gone in columns)
            }
        } finally {
            room.close()
        }
    }

    private fun createAtVersion(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File("schemas/${AppDatabase::class.java.name}/$version.json").readText())
            .getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            fill(db)
            db.version = version
        }
    }

    private companion object {
        const val LINE_ITEM = "INSERT INTO line_items (id, receiptId, position, name, quantity, priceCents) VALUES (1, 7, 0, 'Latte', 2, 900)"
    }
}
