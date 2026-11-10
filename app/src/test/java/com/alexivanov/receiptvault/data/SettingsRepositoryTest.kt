package com.alexivanov.receiptvault.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.alexivanov.receiptvault.domain.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Locale

class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } })
    }
    private val locale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(locale)
        scope.cancel()
    }

    @Test
    fun `the first run's currency stays the default when the phone's region changes`() = runBlocking {
        Locale.setDefault(Locale.GERMANY)
        settings.pinDefaultCurrency()
        assertEquals("EUR", settings.current().defaultCurrency)

        Locale.setDefault(Locale.JAPAN)
        settings.pinDefaultCurrency()
        assertEquals("EUR", settings.current().defaultCurrency)
        assertEquals("JPY", Money.defaultCurrency())
    }

    @Test
    fun `a chosen currency is never overwritten`() = runBlocking {
        settings.setDefaultCurrency("CAD")
        settings.pinDefaultCurrency()
        assertEquals("CAD", settings.current().defaultCurrency)
    }

    @Test
    fun `a settings file that can't be read gives defaults instead of an error`() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("I/O error") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("I/O error")
        }
        assertEquals(false, SettingsRepository(broken).current().appLock)
    }
}
