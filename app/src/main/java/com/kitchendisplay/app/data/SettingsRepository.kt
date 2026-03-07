package com.kitchendisplay.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores app-wide settings in SharedPreferences.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ──────────────── Nextcloud account ────────────────

    /** Base URL of the Nextcloud instance, e.g. "https://cloud.example.com" */
    var nextcloudServerUrl: String
        get() = prefs.getString(KEY_NC_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NC_URL, value.trimEnd('/')).apply()

    /** Nextcloud login username */
    var nextcloudUsername: String
        get() = prefs.getString(KEY_NC_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NC_USER, value).apply()

    /**
     * Nextcloud app password (recommended) or account password.
     * Stored in SharedPreferences — use an app password so you can revoke
     * access without changing your main password.
     */
    var nextcloudPassword: String
        get() = prefs.getString(KEY_NC_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NC_PASS, value).apply()

    // ──────────────── Weather ────────────────

    var weatherLocation: String
        get() = prefs.getString(KEY_WEATHER_LOCATION, "London") ?: "London"
        set(value) = prefs.edit().putString(KEY_WEATHER_LOCATION, value).apply()

    var weatherLatitude: Double
        get() = prefs.getFloat(KEY_WEATHER_LAT, 51.5f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_WEATHER_LAT, value.toFloat()).apply()

    var weatherLongitude: Double
        get() = prefs.getFloat(KEY_WEATHER_LON, -0.12f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_WEATHER_LON, value.toFloat()).apply()

    var weatherLocationResolved: Boolean
        get() = prefs.getBoolean(KEY_WEATHER_RESOLVED, false)
        set(value) = prefs.edit().putBoolean(KEY_WEATHER_RESOLVED, value).apply()

    // ──────────────── Kiosk / security ────────────────

    /** PIN required to access settings and exit kiosk mode. Empty string = no PIN. */
    var settingsPin: String
        get() = prefs.getString(KEY_SETTINGS_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SETTINGS_PIN, value).apply()

    // ──────────────── Display ────────────────

    /** Temperature unit: "C" or "F" */
    var temperatureUnit: String
        get() = prefs.getString(KEY_TEMP_UNIT, "C") ?: "C"
        set(value) = prefs.edit().putString(KEY_TEMP_UNIT, value).apply()

    /** Whether received text messages should be read aloud via TTS */
    var readMessagesAloud: Boolean
        get() = prefs.getBoolean(KEY_READ_MESSAGES, true)
        set(value) = prefs.edit().putBoolean(KEY_READ_MESSAGES, value).apply()

    // ──────────────── Idle timeout ────────────────

    /** Seconds of inactivity before returning to the main screen (default: 20) */
    var idleTimeoutSeconds: Int
        get() = prefs.getInt(KEY_IDLE_TIMEOUT, 20)
        set(value) = prefs.edit().putInt(KEY_IDLE_TIMEOUT, value).apply()

    companion object {
        private const val PREFS_NAME = "kitchen_settings"
        private const val KEY_NC_URL = "nc_server_url"
        private const val KEY_NC_USER = "nc_username"
        private const val KEY_NC_PASS = "nc_password"
        private const val KEY_WEATHER_LOCATION = "weather_location"
        private const val KEY_WEATHER_LAT = "weather_lat"
        private const val KEY_WEATHER_LON = "weather_lon"
        private const val KEY_WEATHER_RESOLVED = "weather_resolved"
        private const val KEY_SETTINGS_PIN = "settings_pin"
        private const val KEY_TEMP_UNIT = "temp_unit"
        private const val KEY_READ_MESSAGES = "read_messages"
        private const val KEY_IDLE_TIMEOUT = "idle_timeout"
    }
}
