package com.kitchendisplay.app.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kitchendisplay.app.models.DailyForecast
import com.kitchendisplay.app.models.WeatherData
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Fetches weather data from the free Open-Meteo API (no API key required).
 * Run on a background thread.
 */
class WeatherService {

    private val client = OkHttpClient()
    private val gson = Gson()

    /**
     * Resolves a city name to lat/lon using the Open-Meteo Geocoding API.
     * Returns null if the city cannot be found.
     */
    fun geocode(cityName: String): Pair<Double, Double>? {
        val encodedCity = cityName.trim().replace(" ", "+")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=en&format=json"
        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            val results = json.getAsJsonArray("results") ?: return null
            if (results.size() == 0) return null
            val first = results[0].asJsonObject
            val lat = first.get("latitude").asDouble
            val lon = first.get("longitude").asDouble
            Pair(lat, lon)
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Fetches current weather **and** a 7-day daily forecast for the given
     * coordinates.  Returns null if the request fails.
     */
    fun fetchWeather(lat: Double, lon: Double, locationName: String): WeatherData? {
        val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current_weather=true" +
                "&daily=weathercode,temperature_2m_max,temperature_2m_min" +
                ",precipitation_sum,windspeed_10m_max" +
                "&timezone=auto" +
                "&forecast_days=7"
        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)

            // Current conditions
            val cw = json.getAsJsonObject("current_weather") ?: return null
            val currentTemp = cw.get("temperature").asDouble
            val weatherCode = cw.get("weathercode").asInt
            val windSpeed = cw.get("windspeed").asDouble

            // Daily forecast
            val daily = json.getAsJsonObject("daily")
            val forecast = parseDailyForecast(daily)

            WeatherData(
                temperatureCelsius = currentTemp,
                weatherCode = weatherCode,
                windSpeedKmh = windSpeed,
                location = locationName,
                forecast = forecast
            )
        } catch (e: IOException) {
            null
        }
    }

    private fun parseDailyForecast(daily: JsonObject?): List<DailyForecast> {
        if (daily == null) return emptyList()
        return try {
            val dates = daily.getAsJsonArray("time") ?: return emptyList()
            val codes = daily.getAsJsonArray("weathercode")
            val maxTemps = daily.getAsJsonArray("temperature_2m_max")
            val minTemps = daily.getAsJsonArray("temperature_2m_min")
            val precip = daily.getAsJsonArray("precipitation_sum")
            val wind = daily.getAsJsonArray("windspeed_10m_max")

            dates.indices.map { i ->
                DailyForecast(
                    date = dates[i].asString,
                    weatherCode = codes?.get(i)?.asInt ?: 0,
                    maxTempCelsius = maxTemps?.get(i)?.asDouble ?: 0.0,
                    minTempCelsius = minTemps?.get(i)?.asDouble ?: 0.0,
                    precipitationMm = precip?.get(i)?.asDouble ?: 0.0,
                    maxWindSpeedKmh = wind?.get(i)?.asDouble ?: 0.0
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
