package com.sasch.cameragps.sharednew.bluetooth.session

/**
 * Retry policy for authentication/pairing errors (ATT errors 5/15).
 * Defaults match the previous iOS-only constants (3 retries, 3s apart).
 */
data class PairingRetryPolicy(
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 3_000L,
    /**
     * Delay before the FIRST retry; later retries use [retryDelayMs]. Android
     * passes 0: an auth error on reconnect only means link encryption is still
     * being re-established, and an immediately re-issued operation queues
     * behind it in the stack (pre-restructure Android behavior).
     */
    val firstRetryDelayMs: Long = retryDelayMs,
)
