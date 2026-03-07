package com.kitchendisplay.app.models

/**
 * Current weather conditions fetched from Open-Meteo.
 */
data class WeatherData(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val windSpeedKmh: Double,
    val location: String
) {
    /** Human-readable description derived from WMO weather code */
    val description: String get() = weatherCodeToDescription(weatherCode)

    /** Emoji icon derived from WMO weather code */
    val icon: String get() = weatherCodeToIcon(weatherCode)

    companion object {
        fun weatherCodeToDescription(code: Int): String = when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            in 45..48 -> "Fog"
            in 51..55 -> "Drizzle"
            in 61..65 -> "Rain"
            in 71..75 -> "Snow"
            77 -> "Snow grains"
            in 80..82 -> "Rain showers"
            in 85..86 -> "Snow showers"
            95 -> "Thunderstorm"
            in 96..99 -> "Thunderstorm with hail"
            else -> "Unknown"
        }

        fun weatherCodeToIcon(code: Int): String = when (code) {
            0 -> "☀️"
            1, 2 -> "⛅"
            3 -> "☁️"
            in 45..48 -> "🌫️"
            in 51..67 -> "🌧️"
            in 71..77 -> "❄️"
            in 80..82 -> "🌦️"
            in 85..86 -> "🌨️"
            in 95..99 -> "⛈️"
            else -> "🌡️"
        }
    }
}
