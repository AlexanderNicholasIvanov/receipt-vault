package com.alexivanov.receiptvault.domain

/** How saving the photos of selected receipts to Pictures went. */
data class PhotoDownload(val saved: Int, val failed: Int) {
    val message: String
        get() = when {
            saved == 0 && failed == 0 -> "None of these receipts have a photo."
            failed == 0 -> "Saved ${photos(saved)} to Pictures/Receipt Vault"
            saved == 0 -> "Couldn't save the ${if (failed == 1) "photo" else "photos"}. Check that the phone has free space."
            else -> "Saved ${photos(saved)} to Pictures/Receipt Vault. $failed couldn't be saved."
        }

    private fun photos(n: Int) = if (n == 1) "1 photo" else "$n photos"
}
