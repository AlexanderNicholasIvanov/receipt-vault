package com.alexivanov.receiptvault

import com.alexivanov.receiptvault.update.ReleaseSource
import com.alexivanov.receiptvault.update.UpdateChecker

/**
 * The app as tests run it (set in robolectric.properties). Opening the app checks GitHub for a
 * newer release; on CI that call sometimes got through, and an "Update available" card appeared
 * on Home in the middle of whatever test was running, failing a different test each time.
 */
class TestReceiptVaultApp : ReceiptVaultApp() {
    override fun releaseSource() = ReleaseSource { UpdateChecker.Outcome.UpToDate }
}
