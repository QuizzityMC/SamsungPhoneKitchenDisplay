package com.kitchendisplay.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.kitchendisplay.app.databinding.ActivityMainBinding
import com.kitchendisplay.app.data.SettingsRepository
import com.kitchendisplay.app.ui.main.MainFragment
import com.kitchendisplay.app.ui.messages.MessagesFragment
import com.kitchendisplay.app.ui.settings.SettingsFragment
import com.kitchendisplay.app.ui.youtube.YoutubeFragment

/**
 * Single activity host.
 *
 * Responsibilities:
 *  - Keep the screen always on.
 *  - Force landscape orientation.
 *  - Enable immersive (full-screen) mode.
 *  - Engage lock-task (kiosk) mode when device owner is configured.
 *  - Reset an idle timer on every user interaction; after [idleTimeout] seconds
 *    of inactivity the app returns to [MainFragment].
 *  - Intercept the back button to prevent leaving the app.
 */
class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository

    private val idleHandler = Handler(Looper.getMainLooper())
    private val returnToMainRunnable = Runnable { showMain() }

    // Whether a video is playing or audio is being recorded — suppresses idle return
    var suppressIdleReturn: Boolean = false

    private val idleTimeout: Long
        get() = settings.idleTimeoutSeconds * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)

        // ── Keep screen on ─────────────────────────────────────────────────
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ── Force landscape ────────────────────────────────────────────────
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── Immersive sticky mode ──────────────────────────────────────────
        enableImmersiveMode()

        // ── Kiosk / lock-task mode ─────────────────────────────────────────
        engageLockTask()

        // ── Bottom navigation ──────────────────────────────────────────────
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showFragment(MainFragment(), "home")
                R.id.nav_messages -> showFragment(MessagesFragment(), "messages")
                R.id.nav_youtube -> showFragment(YoutubeFragment(), "youtube")
                R.id.nav_settings -> showFragment(SettingsFragment(), "settings")
            }
            true
        }

        if (savedInstanceState == null) {
            showMain()
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        resetIdleTimer()
    }

    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(returnToMainRunnable)
    }

    // ── Back-button intercept ──────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        resetIdleTimer()
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current is MainFragment) {
            // Already on main screen — swallow the back press (kiosk behaviour)
        } else {
            showMain()
        }
    }

    // ── Touch intercept for idle reset ────────────────────────────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    fun showMain() {
        showFragment(MainFragment(), "home")
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commitAllowingStateLoss()
    }

    fun resetIdleTimer() {
        idleHandler.removeCallbacks(returnToMainRunnable)
        if (!suppressIdleReturn) {
            idleHandler.postDelayed(returnToMainRunnable, idleTimeout)
        }
    }

    private fun enableImmersiveMode() {
        val decorView = window.decorView
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    private fun engageLockTask() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            // Allow lock-task for our own package
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
            startLockTask()
        }
        // If not device owner, the app still uses immersive mode as a soft kiosk.
    }
}
