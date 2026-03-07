package com.kitchendisplay.app.ui.weather

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kitchendisplay.app.R
import com.kitchendisplay.app.data.SettingsRepository
import com.kitchendisplay.app.databinding.FragmentWeatherBinding
import com.kitchendisplay.app.databinding.ItemForecastDayBinding
import com.kitchendisplay.app.databinding.ItemForecastHourBinding
import com.kitchendisplay.app.models.DailyForecast
import com.kitchendisplay.app.models.HourlyForecast
import com.kitchendisplay.app.models.WeatherData
import com.kitchendisplay.app.services.WeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detailed weather screen.
 *
 * Left panel : current conditions (temperature, description, wind).
 * Right panel: toggle between —
 *   • Today (hourly)  — hour-by-hour forecast for the current day
 *   • 7-Day Forecast  — daily summary cards
 *
 * Default location: Canberra, ACT, Australia (pre-seeded in SettingsRepository).
 * Data source: Open-Meteo (free, no API key, accurate global model data).
 */
class WeatherFragment : Fragment() {

    private var _binding: FragmentWeatherBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsRepository
    private val weatherService = WeatherService()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWeatherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())

        // ── Daily RecyclerView ─────────────────────────────────────────────
        binding.rvForecast.layoutManager = LinearLayoutManager(requireContext())
        binding.rvForecast.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        // ── Hourly RecyclerView ────────────────────────────────────────────
        binding.rvHourly.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHourly.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        // ── Toggle ─────────────────────────────────────────────────────────
        binding.rgForecastMode.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            when (checkedId) {
                R.id.rb_hourly -> showHourly()
                R.id.rb_daily  -> showDaily()
            }
        }

        binding.btnRefreshWeather.setOnClickListener { loadWeather() }

        loadWeather()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Panel helpers ──────────────────────────────────────────────────────

    private fun showHourly() {
        binding.rvHourly.visibility = View.VISIBLE
        binding.rvForecast.visibility = View.GONE
    }

    private fun showDaily() {
        binding.rvHourly.visibility = View.GONE
        binding.rvForecast.visibility = View.VISIBLE
    }

    // ── Data loading ───────────────────────────────────────────────────────

    private fun loadWeather() {
        binding.tvCurrentTemp.text = "…"
        binding.tvCurrentDesc.text = getString(R.string.weather_loading)
        binding.tvCurrentWind.text = ""
        binding.tvCurrentIcon.text = "🌡️"
        binding.tvLocation.text = settings.weatherLocation

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                try {
                    val lat: Double
                    val lon: Double
                    if (!settings.weatherLocationResolved) {
                        val coords = weatherService.geocode(settings.weatherLocation)
                        if (coords != null) {
                            settings.weatherLatitude = coords.first
                            settings.weatherLongitude = coords.second
                            settings.weatherLocationResolved = true
                            lat = coords.first; lon = coords.second
                        } else return@withContext null
                    } else {
                        lat = settings.weatherLatitude
                        lon = settings.weatherLongitude
                    }
                    weatherService.fetchWeather(lat, lon, settings.weatherLocation)
                } catch (_: Exception) {
                    null
                }
            }
            if (_binding == null) return@launch
            updateUi(data)
        }
    }

    private fun updateUi(data: WeatherData?) {
        if (data == null) {
            binding.tvCurrentTemp.text = "--"
            binding.tvCurrentDesc.text = getString(R.string.weather_unavailable)
            return
        }
        val temp = if (settings.temperatureUnit == "F") {
            "%.0f°F".format(data.temperatureCelsius * 9 / 5 + 32)
        } else {
            "%.0f°C".format(data.temperatureCelsius)
        }
        binding.tvLocation.text = data.location
        binding.tvCurrentIcon.text = data.icon
        binding.tvCurrentTemp.text = temp
        binding.tvCurrentDesc.text = data.description
        binding.tvCurrentWind.text = "💨 %.0f km/h".format(data.windSpeedKmh)

        binding.rvForecast.adapter = ForecastAdapter(data.forecast, settings.temperatureUnit)
        binding.rvHourly.adapter = HourlyAdapter(data.hourlyForecast, settings.temperatureUnit)

        // Restore selected panel visibility in case the adapter swap collapsed it
        if (binding.rbHourly.isChecked) showHourly() else showDaily()
    }

    // ── Daily forecast adapter ─────────────────────────────────────────────

    private class ForecastAdapter(
        private val items: List<DailyForecast>,
        private val tempUnit: String
    ) : RecyclerView.Adapter<ForecastAdapter.VH>() {

        inner class VH(val b: ItemForecastDayBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemForecastDayBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvDay.text = item.dayLabel
            holder.b.tvIcon.text = item.icon
            val maxT: String
            val minT: String
            if (tempUnit == "F") {
                maxT = "%.0f°F".format(item.maxTempCelsius * 9 / 5 + 32)
                minT = "%.0f°F".format(item.minTempCelsius * 9 / 5 + 32)
            } else {
                maxT = "%.0f°C".format(item.maxTempCelsius)
                minT = "%.0f°C".format(item.minTempCelsius)
            }
            holder.b.tvTemps.text = "$maxT / $minT"
            holder.b.tvRain.text = if (item.precipitationMm > 0) "🌧 %.1fmm".format(item.precipitationMm) else ""
            holder.b.tvDesc.text = item.description
        }
    }

    // ── Hourly forecast adapter ────────────────────────────────────────────

    private class HourlyAdapter(
        private val items: List<HourlyForecast>,
        private val tempUnit: String
    ) : RecyclerView.Adapter<HourlyAdapter.VH>() {

        inner class VH(val b: ItemForecastHourBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemForecastHourBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvHour.text = item.hourLabel
            holder.b.tvHourIcon.text = item.icon
            if (tempUnit == "F") {
                holder.b.tvHourTemp.text = "%.0f°F".format(item.temperatureCelsius * 9 / 5 + 32)
                holder.b.tvHourFeels.text = "feels %.0f°F".format(item.feelsLikeCelsius * 9 / 5 + 32)
            } else {
                holder.b.tvHourTemp.text = "%.0f°C".format(item.temperatureCelsius)
                holder.b.tvHourFeels.text = "feels %.0f°C".format(item.feelsLikeCelsius)
            }
            holder.b.tvHourRain.text = if (item.precipitationMm > 0) "🌧 %.1fmm".format(item.precipitationMm) else ""
            holder.b.tvHourHumidity.text = "💧${item.humidityPercent}%"
            holder.b.tvHourWind.text = "💨%.0fkm/h".format(item.windSpeedKmh)
            holder.b.tvHourDesc.text = item.description
        }
    }
}
