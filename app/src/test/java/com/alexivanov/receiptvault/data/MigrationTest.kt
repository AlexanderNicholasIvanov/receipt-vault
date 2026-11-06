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
    fun `v1 receipts and line items survive and payment columns are gone`() {
        createAtVersion(1) { db ->
            db.execSQL(
                """INSERT INTO receipts (id, merchant, address, phone, category, purchasedAt, subtotalCents, taxCents,
                   tipCents, discountCents, totalCents, currency, paymentMethod, cardLast4, latitude, longitude,
                   locationLabel, notes, imagePath, ocrText, createdAt, updatedAt)
                   VALUES (7, 'Corner Cafe', '1 Main St', NULL, 'DINING', 1000, 2000, 160, 400, NULL, 2560, 'USD',
                   'Visa', '4821', 40.1, -73.2, 'Main St', 'lunch', NULL, 'CORNER CAFE', 1, 2)""",
            )
            db.execSQL("INSERT INTO line_items (id, receiptId, position, name, quantity, priceCents) VALUES (1, 7, 0, 'Latte', 2, 900)")
        }

        val room = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val dao = room.receiptDao()
            val r = runBlocking { dao.get(7) }!!
            assertEquals("Corner Cafe", r.merchant)
            assertEquals(2560L, r.totalCents)
            assertEquals(400L, r.tipCents)
            assertEquals("1 Main St", r.address)
            assertEquals("lunch", r.notes)
            // The receipts table is rebuilt; its line items must not be cascade-deleted along the way.
            assertEquals(listOf("Latte"), runBlocking { dao.getItems(7) }.map { it.name })

            val columns = room.openHelper.readableDatabase.query("PRAGMA table_info(receipts)").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            assertFalse("paymentMethod" in columns)
            assertFalse("cardLast4" in columns)
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
}
