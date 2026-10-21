package com.freeturn.app.data.config

object AppTheme {
    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"
    val VALUES = listOf(LIGHT, DARK, SYSTEM)
    fun normalize(value: String?): String = value?.takeIf { it in VALUES } ?: SYSTEM
    fun isDark(value: String, systemDark: Boolean): Boolean = when (normalize(value)) {
        DARK -> true
        LIGHT -> false
        else -> systemDark
    }
}
