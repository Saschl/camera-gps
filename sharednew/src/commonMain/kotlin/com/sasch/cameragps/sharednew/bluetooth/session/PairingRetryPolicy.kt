package com.sasch.cameragps.sharednew.bluetooth.session

/**
 * Retry policy for authentication/pairing errors (ATT errors 5/15).
 * Defaults match the previous iOS-only constants (3 retries, 3s apart).
 */
data class PairingRetryPolicy(
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 3_000L,
)
