package com.kitchendisplay.app.ui.main

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kitchendisplay.app.data.MessageRepository
import com.kitchendisplay.app.data.SettingsRepository
import com.kitchendisplay.app.databinding.FragmentMainBinding
import com.kitchendisplay.app.models.WeatherData
import com.kitchendisplay.app.services.WeatherService
import com.kitchendisplay.app.ui.messages.MessagesFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main display: large clock, weather summary and quick-send shortcut buttons.
 */
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsRepository
    private lateinit var messageRepo: MessageRepository
    private val weatherService = WeatherService()

    private val clockHandler = Handler(Looper.getMainLooper())
    private val weatherHandler = Handler(Looper.getMainLooper())

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())

    // ── Clock tick runnable ───────────────────────────────────────────────

    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            _binding?.tvClock?.text = timeFormat.format(now)
            _binding?.tvDate?.text = dateFormat.format(now)
            clockHandler.postDelayed(this, 1000)
        }
    }

    // ── Weather refresh (every 10 minutes) ───────────────────────────────

    private val weatherRunnable = object : Runnable {
        override fun run() {
            fetchWeather()
            weatherHandler.postDelayed(this, WEATHER_INTERVAL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        messageRepo = MessageRepository(requireContext())
        buildShortcutButtons()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
        weatherHandler.post(weatherRunnable)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        weatherHandler.removeCallbacks(weatherRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Shortcut buttons ─────────────────────────────────────────────────

    private fun buildShortcutButtons() {
        binding.shortcutsContainer.removeAllViews()
        val contacts = messageRepo.getContacts()
        if (contacts.isEmpty()) {
            binding.tvNoShortcuts.visibility = View.VISIBLE
            return
        }
        binding.tvNoShortcuts.visibility = View.GONE
        contacts.forEach { contact ->
            val btn = Button(requireContext()).apply {
                text = "📨 ${contact.displayName}"
                textSize = 16f
                setPadding(32, 24, 32, 24)
                setOnClickListener {
                    // Navigate to messages, opening a compose window for this contact
                    val frag = MessagesFragment.newInstance(contact.id)
                    parentFragmentManager.beginTransaction()
                        .replace(
                            com.kitchendisplay.app.R.id.fragment_container,
                            frag,
                            "messages"
                        )
                        .addToBackStack(null)
                        .commit()
                    (activity as? com.kitchendisplay.app.MainActivity)
                    ?.binding?.navRail?.selectedItemId =
                        com.kitchendisplay.app.R.id.nav_messages
                }
            }
            binding.shortcutsContainer.addView(btn)
        }
    }

    // ── Weather fetch ─────────────────────────────────────────────────────

    private fun fetchWeather() {
        lifecycleScope.launch {
            val weather = withContext(Dispatchers.IO) {
                try {
                    val lat: Double
                    val lon: Double
                    if (!settings.weatherLocationResolved) {
                        val coords = weatherService.geocode(settings.weatherLocation)
                        if (coords != null) {
                            settings.weatherLatitude = coords.first
                            settings.weatherLongitude = coords.second
                            settings.weatherLocationResolved = true
                            lat = coords.first
                            lon = coords.second
                        } else {
                            return@withContext null
                        }
                    } else {
                        lat = settings.weatherLatitude
                        lon = settings.weatherLongitude
                    }
                    weatherService.fetchWeather(lat, lon, settings.weatherLocation)
                } catch (e: Exception) {
                    null
                }
            }
            updateWeatherUi(weather)
        }
    }

    private fun updateWeatherUi(data: WeatherData?) {
        if (_binding == null) return
        if (data == null) {
            binding.tvWeather.text = getString(com.kitchendisplay.app.R.string.weather_unavailable)
            return
        }
        val temp = if (settings.temperatureUnit == "F") {
            val f = data.temperatureCelsius * 9 / 5 + 32
            "%.0f°F".format(f)
        } else {
            "%.0f°C".format(data.temperatureCelsius)
        }
        binding.tvWeather.text = "${data.icon} $temp  ${data.description}  💨 %.0f km/h".format(data.windSpeedKmh)
        binding.tvWeatherLocation.text = data.location
    }

    companion object {
        private const val WEATHER_INTERVAL_MS = 10 * 60 * 1000L
    }
}
