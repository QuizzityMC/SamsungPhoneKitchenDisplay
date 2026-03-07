package com.kitchendisplay.app.services

import com.google.gson.Gson
import com.google.gson.JsonObject
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
     * Fetches current weather for the given coordinates.
     * Returns null if the request fails.
     */
    fun fetchWeather(lat: Double, lon: Double, locationName: String): WeatherData? {
        val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current_weather=true" +
                "&timezone=auto"
        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            val cw = json.getAsJsonObject("current_weather") ?: return null
            WeatherData(
                temperatureCelsius = cw.get("temperature").asDouble,
                weatherCode = cw.get("weathercode").asInt,
                windSpeedKmh = cw.get("windspeed").asDouble,
                location = locationName
            )
        } catch (e: IOException) {
            null
        }
    }
}
