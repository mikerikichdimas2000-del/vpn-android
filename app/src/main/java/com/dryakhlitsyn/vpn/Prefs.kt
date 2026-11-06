package com.dryakhlitsyn.vpn

import android.content.Context

/** User-facing preferences that must survive restarts. */
object Prefs {
    const val THEME_AUTO = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private const val NAME = "vpn-prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_AUTOCONNECT = "autoconnect"
    private const val KEY_TILE_HINT_SHOWN = "tile-hint-shown"
    private const val KEY_RU_DIRECT = "ru-direct"
    private const val KEY_FAST_ROUTES = "fast-routes"
    private const val KEY_LAST_UPDATE_CHECK = "last-update-check"

    private fun read(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun themeMode(context: Context): Int = read(context).getInt(KEY_THEME, THEME_AUTO)

    fun setThemeMode(context: Context, mode: Int) {
        read(context).edit().putInt(KEY_THEME, mode).apply()
    }

    fun autoconnect(context: Context): Boolean = read(context).getBoolean(KEY_AUTOCONNECT, false)

    fun setAutoconnect(context: Context, enabled: Boolean) {
        read(context).edit().putBoolean(KEY_AUTOCONNECT, enabled).apply()
    }

    fun tileHintShown(context: Context): Boolean = read(context).getBoolean(KEY_TILE_HINT_SHOWN, false)

    fun markTileHintShown(context: Context) {
        read(context).edit().putBoolean(KEY_TILE_HINT_SHOWN, true).apply()
    }

    fun fastRoutes(context: Context): Boolean = read(context).getBoolean(KEY_FAST_ROUTES, false)

    fun setFastRoutes(context: Context, enabled: Boolean) {
        read(context).edit().putBoolean(KEY_FAST_ROUTES, enabled).apply()
    }

    fun ruDirect(context: Context): Boolean = read(context).getBoolean(KEY_RU_DIRECT, true)

    fun setRuDirect(context: Context, enabled: Boolean) {
        read(context).edit().putBoolean(KEY_RU_DIRECT, enabled).apply()
    }

    /** Когда последний раз успешно проверяли обновления; 0 — ещё ни разу. */
    fun lastUpdateCheckAt(context: Context): Long = read(context).getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastUpdateCheckAt(context: Context, timestamp: Long) {
        read(context).edit().putLong(KEY_LAST_UPDATE_CHECK, timestamp).apply()
    }

    fun themeLabel(context: Context): String = when (themeMode(context)) {
        THEME_LIGHT -> "светлая"
        THEME_DARK -> "тёмная"
        else -> "авто"
    }
}
