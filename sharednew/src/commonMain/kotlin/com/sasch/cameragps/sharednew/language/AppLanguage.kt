package com.sasch.cameragps.sharednew.language

/**
 * A selectable app language. The list of instances (SupportedLanguages) is
 * generated at build time from the composeResources locale folders — see the
 * :sharednew:generateSupportedLanguages task.
 */
data class AppLanguage(
    val tag: String,
    val displayName: String,
)
