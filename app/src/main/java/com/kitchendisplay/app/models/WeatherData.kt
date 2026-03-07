package com.kitchendisplay.app.models

/**
 * Current weather conditions fetched from Open-Meteo.
 */
data class WeatherData(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val windSpeedKmh: Double,
    val location: String,
    /** 7-day daily forecast (today first). Empty if not fetched. */
    val forecast: List<DailyForecast> = emptyList(),
    /** Today's hour-by-hour forecast (00:00–23:00). Empty if not fetched. */
    val hourlyForecast: List<HourlyForecast> = emptyList()
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

/**
 * One day's forecast from the Open-Meteo daily API.
 */
data class DailyForecast(
    /** ISO date string e.g. "2024-01-15" */
    val date: String,
    val weatherCode: Int,
    val maxTempCelsius: Double,
    val minTempCelsius: Double,
    val precipitationMm: Double,
    val maxWindSpeedKmh: Double
) {
    val icon: String get() = WeatherData.weatherCodeToIcon(weatherCode)
    val description: String get() = WeatherData.weatherCodeToDescription(weatherCode)

    /** Short day-of-week label, e.g. "Mon" */
    val dayLabel: String
        get() = try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val dateFmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
            val parsed = sdf.parse(date) ?: return date
            dateFmt.format(parsed)
        } catch (_: Exception) {
            date
        }
}

/**
 * One hour's forecast from the Open-Meteo hourly API.
 */
data class HourlyForecast(
    /** ISO datetime string e.g. "2024-01-15T14:00" */
    val time: String,
    val weatherCode: Int,
    val temperatureCelsius: Double,
    val feelsLikeCelsius: Double,
    val precipitationMm: Double,
    val windSpeedKmh: Double,
    val humidityPercent: Int
) {
    val icon: String get() = WeatherData.weatherCodeToIcon(weatherCode)
    val description: String get() = WeatherData.weatherCodeToDescription(weatherCode)

    /** "HH:mm" extracted from the ISO datetime */
    val hourLabel: String
        get() = try { time.substring(11, 16) } catch (_: Exception) { time }
}
