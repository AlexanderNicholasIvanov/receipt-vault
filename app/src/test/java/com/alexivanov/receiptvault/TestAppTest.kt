package com.alexivanov.receiptvault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestAppTest {
    @Test
    fun `tests run in the app that never checks for updates over the network`() {
        assertTrue(ApplicationProvider.getApplicationContext<Application>() is TestReceiptVaultApp)
    }
}
