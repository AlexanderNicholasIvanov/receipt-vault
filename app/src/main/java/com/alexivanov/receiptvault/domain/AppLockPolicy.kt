package com.alexivanov.receiptvault.domain

/** When the app lock asks for the fingerprint/face/PIN again. */
object AppLockPolicy {
    /**
     * Leaving briefly (taking a photo with the camera app, picking a file,
     * answering a message) doesn't relock; being away longer than this does.
     */
    const val GRACE_MILLIS = 60_000L

    /**
     * @param backgroundedAt elapsed-realtime when the app last left the screen,
     *   or null if it hasn't since the lock was last opened.
     */
    fun shouldLockOnReturn(enabled: Boolean, backgroundedAt: Long?, now: Long): Boolean =
        enabled && backgroundedAt != null && now - backgroundedAt >= GRACE_MILLIS
}
