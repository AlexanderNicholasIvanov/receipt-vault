package com.alexivanov.receiptvault.alerts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.alexivanov.receiptvault.data.AppDatabase
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.data.ReceiptRepository
import com.alexivanov.receiptvault.data.SettingsRepository
import com.alexivanov.receiptvault.domain.BudgetUse
import com.alexivanov.receiptvault.domain.Budgets
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.Reminder
import com.alexivanov.receiptvault.scan.ImageStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AlertCheckerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val repo = ReceiptRepository(db.receiptDao(), ImageStore(context))

    // Same DataStore file as the app; reset around each test.
    private val settings = SettingsRepository(context)
    private val notifier = FakeNotifier()
    private val checker = AlertChecker(repo, settings, notifier)
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-11-06")

    @Before
    @After
    fun reset() {
        runBlocking {
            settings.setBudgets(Budgets())
            settings.setSentAlerts(emptySet())
            settings.setDefaultCurrency("USD")
        }
    }

    @After
    fun close() = db.close()

    private suspend fun add(cents: Long, category: Category = Category.DINING, returnBy: LocalDate? = null) = repo.save(
        Receipt(
            merchant = "Shop", category = category.name, totalCents = cents, currency = "USD",
            purchasedAt = today.atTime(10, 0).toInstant(zone).toEpochMilli(), createdAt = 0, updatedAt = 0,
            returnBy = returnBy?.toEpochDay(),
        ),
        emptyList(),
    )

    @Test
    fun `a due reminder is sent once, and again only if its date changes`() = runBlocking {
        val id = add(1000, returnBy = today.plusDays(1))

        assertEquals(listOf("return:$id:2026-11-07"), checker.run(today, zone))
        assertEquals(listOf("return:$id:2026-11-07"), notifier.reminders.map { it.key })
        assertTrue(checker.run(today, zone).isEmpty())
        assertTrue("next day, same date", checker.run(today.plusDays(1), zone).isEmpty())

        repo.save(repo.get(id)!!.copy(returnBy = today.plusDays(2).toEpochDay()), emptyList())
        assertEquals(listOf("return:$id:2026-11-08"), checker.run(today, zone))
    }

    @Test
    fun `trashed receipts don't remind`() = runBlocking {
        val id = add(1000, returnBy = today)
        repo.moveToTrash(listOf(id))
        assertTrue(checker.run(today, zone).isEmpty())
    }

    @Test
    fun `budget warns at 80 percent, then when over, once each`() = runBlocking {
        settings.setBudgets(Budgets(monthlyCents = 10_000))
        add(5_000)
        assertTrue(checker.run(today, zone).isEmpty())

        add(3_500)
        assertEquals(listOf("budget:2026-11:ALL:NEAR"), checker.run(today, zone))
        assertTrue(checker.run(today, zone).isEmpty())

        add(2_000)
        assertEquals(listOf("budget:2026-11:ALL:OVER"), checker.run(today, zone).take(1))
        assertTrue(checker.run(today, zone).isEmpty())
        assertEquals(2, notifier.budgets.size)
    }

    @Test
    fun `jumping straight over budget sends one warning`() = runBlocking {
        settings.setBudgets(Budgets(byCategory = mapOf(Category.FUEL to 1_000)))
        add(5_000, Category.FUEL)
        checker.run(today, zone)
        assertEquals(1, notifier.budgets.size)
        assertEquals(Category.FUEL, notifier.budgets.single().category)
        assertTrue(checker.run(today, zone).isEmpty())
    }

    @Test
    fun `nothing is sent or marked while notifications are off`() = runBlocking {
        add(1000, returnBy = today)
        notifier.on = false
        assertTrue(checker.run(today, zone).isEmpty())
        notifier.on = true
        assertEquals(1, checker.run(today, zone).size)
    }

    @Test
    fun `old keys are pruned`() {
        assertTrue(checker.isRecent("return:1:2026-11-01", today))
        assertTrue(checker.isRecent("budget:2026-08:ALL:NEAR", today))
        assertFalse(checker.isRecent("return:1:2026-01-01", today))
        assertFalse(checker.isRecent("budget:2026-01:ALL:OVER", today))
        assertFalse(checker.isRecent("garbage", today))
    }

    @Test
    fun `the daily worker runs the checker`() {
        val worker = TestListenableWorkerBuilder<AlertWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }

    private class FakeNotifier : Notifier {
        var on = true
        val reminders = mutableListOf<Reminder>()
        val budgets = mutableListOf<BudgetUse>()
        override fun enabled() = on
        override fun reminder(reminder: Reminder, hideDetails: Boolean) { reminders += reminder }
        override fun budget(use: BudgetUse, currency: String, hideDetails: Boolean) { budgets += use }
    }
}
