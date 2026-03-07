package com.kitchendisplay.app.ui.clock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.kitchendisplay.app.R
import com.kitchendisplay.app.databinding.FragmentClockBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clock, Countdown Timer, and Stopwatch screen.
 *
 * The left panel always shows the current time and date.
 * The right panel switches between Timer and Stopwatch modes.
 *
 * Timer: user sets H/M/S, counts down; plays a LOUD alarm at the end.
 * Stopwatch: elapsed time with lap recording.
 *
 * Timer and stopwatch state is held in companion-object singletons so it
 * survives navigation to another fragment and back.
 */
class ClockFragment : Fragment() {

    private var _binding: FragmentClockBinding? = null
    private val binding get() = _binding!!

    private val clockHandler = Handler(Looper.getMainLooper())
    private val timerHandler = Handler(Looper.getMainLooper())
    private val swHandler = Handler(Looper.getMainLooper())

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())

    private var alarmPlayer: MediaPlayer? = null

    // ── Clock tick ────────────────────────────────────────────────────────

    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            _binding?.tvClockLarge?.text = timeFormat.format(now)
            _binding?.tvDateLarge?.text = dateFormat.format(now)
            clockHandler.postDelayed(this, 1000)
        }
    }

    // ── Timer tick (every 100 ms for smooth display) ─────────────────────

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!TimerState.isRunning) return
            val remaining = TimerState.endTimeMs - System.currentTimeMillis()
            if (remaining <= 0) {
                TimerState.isRunning = false
                updateTimerDisplay(0)
                if (!TimerState.alarmTriggered) {
                    TimerState.alarmTriggered = true
                    fireAlarm()
                }
            } else {
                updateTimerDisplay(remaining)
                timerHandler.postDelayed(this, 100)
            }
        }
    }

    // ── Stopwatch tick (every 100 ms) ─────────────────────────────────────

    private val swRunnable = object : Runnable {
        override fun run() {
            if (!StopwatchState.isRunning) return
            val elapsed = StopwatchState.elapsedAtPauseMs +
                    (System.currentTimeMillis() - StopwatchState.startTimeMs)
            updateSwDisplay(elapsed)
            swHandler.postDelayed(this, 100)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTimerButtons()
        setupStopwatchButtons()

        binding.rgClockMode.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            when (checkedId) {
                R.id.rb_timer -> showPanel(timer = true)
                R.id.rb_stopwatch -> showPanel(timer = false)
            }
        }

        // Restore panel state
        restoreUiState()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
        // Resume running timer / stopwatch ticks if they were active
        if (TimerState.isRunning) timerHandler.post(timerRunnable)
        if (StopwatchState.isRunning) swHandler.post(swRunnable)
        // If alarm was triggered while we were away, show the alert UI
        if (TimerState.alarmTriggered && alarmPlayer == null) {
            showAlarmUi()
        }
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        timerHandler.removeCallbacks(timerRunnable)
        swHandler.removeCallbacks(swRunnable)
        // Do NOT stop the alarm player here — it should keep ringing until dismissed
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
    }

    // ── Panel switching ───────────────────────────────────────────────────

    private fun showPanel(timer: Boolean) {
        binding.panelTimer.visibility = if (timer) View.VISIBLE else View.GONE
        binding.panelStopwatch.visibility = if (timer) View.GONE else View.VISIBLE
    }

    private fun restoreUiState() {
        // Timer
        val timerActive = TimerState.isRunning || TimerState.isPaused
        binding.llTimerInput.visibility = if (timerActive) View.GONE else View.VISIBLE
        binding.tvTimerDisplay.visibility = if (timerActive) View.VISIBLE else View.GONE
        binding.btnTimerStart.text = if (TimerState.isRunning)
            getString(R.string.pause) else getString(R.string.start)

        if (TimerState.isRunning) {
            val remaining = TimerState.endTimeMs - System.currentTimeMillis()
            updateTimerDisplay(remaining.coerceAtLeast(0))
        } else if (TimerState.isPaused) {
            updateTimerDisplay(TimerState.remainingAtPauseMs)
        }
        if (TimerState.alarmTriggered && alarmPlayer == null) showAlarmUi()

        // Stopwatch
        if (StopwatchState.isRunning || StopwatchState.isPaused || StopwatchState.elapsedAtPauseMs > 0) {
            val elapsed = if (StopwatchState.isRunning)
                StopwatchState.elapsedAtPauseMs + (System.currentTimeMillis() - StopwatchState.startTimeMs)
            else StopwatchState.elapsedAtPauseMs
            updateSwDisplay(elapsed)
        }
        binding.btnSwStartStop.text = if (StopwatchState.isRunning)
            getString(R.string.stop) else getString(R.string.start)

        rebuildLapList()
    }

    // ── Timer ─────────────────────────────────────────────────────────────

    private fun setupTimerButtons() {
        binding.btnTimerStart.setOnClickListener { onTimerStartPause() }
        binding.btnTimerReset.setOnClickListener { onTimerReset() }
        binding.btnTimerStopAlarm.setOnClickListener { stopAlarm() }
    }

    private fun onTimerStartPause() {
        when {
            TimerState.alarmTriggered -> stopAlarm()

            !TimerState.isRunning && !TimerState.isPaused -> {
                // Start fresh from input
                val h = binding.etTimerHours.text.toString().toIntOrNull() ?: 0
                val m = binding.etTimerMinutes.text.toString().toIntOrNull() ?: 0
                val s = binding.etTimerSeconds.text.toString().toIntOrNull() ?: 0
                val totalMs = (h * 3600L + m * 60L + s) * 1000L
                if (totalMs <= 0) return

                TimerState.totalDurationMs = totalMs
                TimerState.endTimeMs = System.currentTimeMillis() + totalMs
                TimerState.isRunning = true
                TimerState.isPaused = false
                TimerState.alarmTriggered = false

                binding.llTimerInput.visibility = View.GONE
                binding.tvTimerDisplay.visibility = View.VISIBLE
                binding.btnTimerStart.text = getString(R.string.pause)
                timerHandler.post(timerRunnable)
            }

            TimerState.isRunning -> {
                // Pause
                TimerState.remainingAtPauseMs =
                    (TimerState.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0)
                TimerState.isRunning = false
                TimerState.isPaused = true
                timerHandler.removeCallbacks(timerRunnable)
                binding.btnTimerStart.text = getString(R.string.resume)
            }

            TimerState.isPaused -> {
                // Resume
                TimerState.endTimeMs =
                    System.currentTimeMillis() + TimerState.remainingAtPauseMs
                TimerState.isRunning = true
                TimerState.isPaused = false
                binding.btnTimerStart.text = getString(R.string.pause)
                timerHandler.post(timerRunnable)
            }
        }
    }

    private fun onTimerReset() {
        timerHandler.removeCallbacks(timerRunnable)
        stopAlarm()
        TimerState.isRunning = false
        TimerState.isPaused = false
        TimerState.endTimeMs = 0L
        TimerState.remainingAtPauseMs = 0L
        TimerState.alarmTriggered = false

        binding.llTimerInput.visibility = View.VISIBLE
        binding.tvTimerDisplay.visibility = View.GONE
        binding.tvTimerDisplay.text = "00:00:00"
        binding.tvTimerStatus.text = ""
        binding.btnTimerStart.text = getString(R.string.start)
        binding.btnTimerStopAlarm.visibility = View.GONE
    }

    private fun updateTimerDisplay(remainingMs: Long) {
        val totalSec = remainingMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        _binding?.tvTimerDisplay?.text = "%02d:%02d:%02d".format(h, m, s)
    }

    // ── Alarm ─────────────────────────────────────────────────────────────

    private var savedAlarmVolume = -1

    private fun fireAlarm() {
        showAlarmUi()
        try {
            val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Save current volume and raise to max so the alarm is always audible
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return

            alarmPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(requireContext(), alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            // Alarm sound unavailable — visual indicator is still shown
        }
    }

    private fun showAlarmUi() {
        _binding?.tvTimerStatus?.text = getString(R.string.timer_done)
        _binding?.tvTimerDisplay?.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.recording_red)
        )
        _binding?.btnTimerStopAlarm?.visibility = View.VISIBLE
        _binding?.btnTimerStart?.text = getString(R.string.stop_alarm)
    }

    private fun stopAlarm() {
        alarmPlayer?.stop()
        alarmPlayer?.release()
        alarmPlayer = null
        // Restore alarm volume to what it was before we raised it
        if (savedAlarmVolume >= 0) {
            try {
                val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            } catch (_: Exception) { /* ignore */ }
            savedAlarmVolume = -1
        }
        TimerState.alarmTriggered = false
        _binding?.tvTimerStatus?.text = ""
        _binding?.tvTimerDisplay?.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.clock_color)
        )
        _binding?.btnTimerStopAlarm?.visibility = View.GONE
        _binding?.btnTimerStart?.text = getString(R.string.start)
    }

    // ── Stopwatch ─────────────────────────────────────────────────────────

    private fun setupStopwatchButtons() {
        binding.btnSwStartStop.setOnClickListener { onSwStartStop() }
        binding.btnSwLap.setOnClickListener { onSwLap() }
        binding.btnSwReset.setOnClickListener { onSwReset() }
    }

    private fun onSwStartStop() {
        if (StopwatchState.isRunning) {
            // Stop / pause
            StopwatchState.elapsedAtPauseMs += System.currentTimeMillis() - StopwatchState.startTimeMs
            StopwatchState.isRunning = false
            StopwatchState.isPaused = true
            swHandler.removeCallbacks(swRunnable)
            binding.btnSwStartStop.text = getString(R.string.start)
        } else {
            // Start / resume
            StopwatchState.startTimeMs = System.currentTimeMillis()
            StopwatchState.isRunning = true
            StopwatchState.isPaused = false
            swHandler.post(swRunnable)
            binding.btnSwStartStop.text = getString(R.string.stop)
        }
    }

    private fun onSwLap() {
        if (!StopwatchState.isRunning) return
        val elapsed = StopwatchState.elapsedAtPauseMs +
                (System.currentTimeMillis() - StopwatchState.startTimeMs)
        StopwatchState.laps.add(elapsed)
        rebuildLapList()
    }

    private fun onSwReset() {
        swHandler.removeCallbacks(swRunnable)
        StopwatchState.isRunning = false
        StopwatchState.isPaused = false
        StopwatchState.startTimeMs = 0L
        StopwatchState.elapsedAtPauseMs = 0L
        StopwatchState.laps.clear()
        binding.tvStopwatchDisplay.text = "00:00.0"
        binding.btnSwStartStop.text = getString(R.string.start)
        binding.llLaps.removeAllViews()
    }

    private fun updateSwDisplay(elapsedMs: Long) {
        val centis = (elapsedMs / 100) % 10
        val totalSec = elapsedMs / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        _binding?.tvStopwatchDisplay?.text = "%02d:%02d.%d".format(m, s, centis)
    }

    private fun rebuildLapList() {
        _binding?.llLaps?.removeAllViews() ?: return
        StopwatchState.laps.reversed().forEachIndexed { revIdx, lapMs ->
            val lapNum = StopwatchState.laps.size - revIdx
            val tv = TextView(requireContext()).apply {
                text = "Lap $lapNum  ${formatLap(lapMs)}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                setPadding(4, 4, 4, 4)
            }
            _binding?.llLaps?.addView(tv)
        }
    }

    private fun formatLap(ms: Long): String {
        val centis = (ms / 100) % 10
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d.%d".format(m, s, centis)
    }

    // ── Singleton state (survives navigation) ─────────────────────────────

    companion object {
        object TimerState {
            var isRunning = false
            var isPaused = false
            var endTimeMs = 0L
            var totalDurationMs = 0L
            var remainingAtPauseMs = 0L
            var alarmTriggered = false
        }

        object StopwatchState {
            var isRunning = false
            var isPaused = false
            var startTimeMs = 0L
            var elapsedAtPauseMs = 0L
            val laps = mutableListOf<Long>()
        }
    }
}
