package com.alexivanov.receiptvault.domain

import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A failure whose message was written for the person using the app, as a complete sentence. */
open class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a failure into words for the person using the app. System messages ("ENOENT (No such
 * file or directory)", "Unable to resolve host …") are for developers and are never shown.
 */
object Problems {

    /**
     * "[whatFailed]. [reason]", or the failure's own message when it was written for people.
     * [online] says the work used the internet, so an unexplained I/O failure is a connection problem.
     */
    fun message(whatFailed: String, e: Throwable, online: Boolean = false): String =
        (e as? UserFacingException)?.message ?: "$whatFailed. ${reason(e, online)}"

    fun reason(e: Throwable, online: Boolean = false): String = when {
        e is OutOfMemoryError -> "It's too large for this phone's memory."
        e.isOutOfSpace() -> "The phone is out of storage space."
        e is UnknownHostException || e is SocketException || e is SocketTimeoutException || e is SSLException ->
            "Check your internet connection and try again."
        e is FileNotFoundException -> "The file is no longer there."
        e is SecurityException -> "Receipt Vault isn't allowed to open that file any more."
        e is IOException && online -> "Check your internet connection and try again."
        e is IOException -> "The file couldn't be read or written."
        else -> "Please try again."
    }

    private fun Throwable.isOutOfSpace(): Boolean = generateSequence(this) { it.cause }.take(8).any { t ->
        val m = t.message.orEmpty()
        "ENOSPC" in m || m.contains("No space left", ignoreCase = true)
    }
}
