package com.kitchendisplay.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin receiver required for full lock-task (kiosk) mode.
 *
 * To enable full kiosk mode, set this app as the Device Owner via ADB once:
 *   adb shell dpm set-device-owner com.kitchendisplay.app/.KioskDeviceAdminReceiver
 *
 * After that, the app can call Activity.startLockTask() to pin itself and
 * prevent the user from navigating away.
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = Unit
    override fun onDisabled(context: Context, intent: Intent) = Unit
}
