package com.alexivanov.receiptvault.capture

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat

/** "Scan receipt" in Quick Settings: one tap opens the document scanner. */
class ScanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            // An action, not a toggle: always shown as off.
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // On the lock screen, ask to unlock first; receipts are private.
        if (isLocked) unlockAndRun(::openScanner) else openScanner()
    }

    private fun openScanner() {
        // Android 14+ takes a PendingIntent, Android 13 an Intent; the compat call picks the right one.
        TileServiceCompat.startActivityAndCollapse(
            this,
            PendingIntentActivityWrapper(
                this,
                QuickAction.SCAN.ordinal,
                QuickCapture.intent(this, QuickAction.SCAN),
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            ),
        )
    }
}
