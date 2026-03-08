# Kitchen Display — Samsung Galaxy Android App

A full-screen landscape kiosk app for a Samsung Galaxy phone (optimised for the A3, works on any Android 6.0+ device) permanently mounted on a kitchen wall. Shows a large clock, live weather, and integrates with **Nextcloud Talk** for messaging — no Signal, no SIM card needed.

---

## Features

| Feature | Details |
|---|---|
| **Landscape lock** | Always forced to landscape; cannot rotate |
| **Screen always on** | `FLAG_KEEP_SCREEN_ON` keeps the display lit while plugged in |
| **Boot on startup** | Automatically launches after the device boots |
| **Kiosk mode** | Immersive full-screen + back-button intercept; full lock-task with optional device-owner ADB setup |
| **Large clock** | Full-screen digital clock (`HH:mm:ss`) with date |
| **Live weather** | Powered by [Open-Meteo](https://open-meteo.com/) — no API key required |
| **Nextcloud Talk shortcuts** | One-tap buttons to send a text or voice DM to a Nextcloud Talk contact |
| **Receive messages** | Long-polls the Nextcloud Talk API; stores messages in-app and reads text aloud via TTS |
| **Voice messages** | Records audio → uploads to Nextcloud Files → shares into Talk conversation |
| **In-app YouTube** | Built-in WebView search + playback; never leaves the app |
| **20 s idle timeout** | Returns to the main screen after 20 seconds of inactivity (configurable) |
| **Settings screen** | PIN-protected settings for Nextcloud credentials, contacts, weather, TTS, idle timeout, and kiosk exit |

---

## Requirements

- Android Studio **Hedgehog (2023.1.1)** or later  
- **JDK 17**  
- Android Gradle Plugin **8.1.x**  
- `compileSdk 34`, `minSdk 23` (Android 6.0 Marshmallow)  
- A **Nextcloud** instance (self-hosted or managed) with the **Talk** app enabled

---

## Quick Build

```bash
# Clone the repository
git clone https://github.com/QuizzityMC/SamsungPhoneKitchenDisplay.git
cd SamsungPhoneKitchenDisplay

# Build debug APK
./gradlew assembleDebug

# APK location:
# app/build/outputs/apk/debug/app-debug.apk

# Install directly via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open in **Android Studio → File → Open** and use **Build → Build APK**.

---

## First-Time Device Setup

### 1. Install the APK

```bash
adb install app-debug.apk
```

### 2. Configure Nextcloud credentials

Open the app → tap **Settings** (bottom nav) → **Nextcloud Talk Account**:

| Field | Value |
|---|---|
| Server URL | `https://your-nextcloud-instance.example.com` |
| Username | Your Nextcloud login name |
| App password | Generate one in Nextcloud → **Settings → Security → App passwords** |

Tap **Save & Connect**. The app immediately starts polling your DM rooms.

> **Why an app password?** App passwords can be revoked individually from your Nextcloud account without changing your main password. They also work with two-factor authentication.

### 3. Add contacts

In Settings → **Contacts & Shortcuts**:
- Tap **Add Contact**
- Enter a display name (shown on the shortcut button, e.g. "Elisabeth")
- Enter the contact's **Nextcloud username** (e.g. `elisabeth`)

The app automatically creates a DM Talk room the first time you send a message.

### 4. (Optional) Enable Full Kiosk Mode — Lock-Task

Full kiosk mode (hardware home button + recents truly disabled) requires setting the app as the [Device Owner](https://developer.android.com/work/dpc/build-dpc) once:

```bash
# Factory-reset the device first (no accounts signed in), then:
adb shell dpm set-device-owner com.kitchendisplay.app/.KioskDeviceAdminReceiver
```

Without device-owner mode the app still uses immersive (full-screen) mode and intercepts the software back button.

### 5. Set a Settings PIN *(recommended)*

Inside the app → **Settings → Change Settings PIN** → set a numeric PIN.  
This PIN is required to access Settings or exit kiosk mode.

---

## Configuration (in-app Settings)

| Setting | Description |
|---|---|
| **Nextcloud Server URL** | Base URL of your Nextcloud instance |
| **Username** | Your Nextcloud login name |
| **App Password** | An app-specific password generated in Nextcloud Security settings |
| **Contacts** | Each contact needs a display name + their Nextcloud username. DM rooms are created / looked up automatically. |
| **Weather location** | Enter a city name; the app geocodes it via Open-Meteo |
| **Temperature unit** | °C or °F |
| **Read messages aloud** | Toggle TTS readout of incoming text messages |
| **Idle timeout** | Seconds of inactivity before returning to the main screen (default: 20) |
| **Change Settings PIN** | Set or clear the PIN protecting the Settings screen |
| **Exit Kiosk Mode** | Stops lock-task and returns to the Android home screen (PIN required if set) |

---

## How Nextcloud Talk Integration Works

### Sending text messages

1. Tap a shortcut button on the main screen (or go to **Messages** and select a contact).
2. Type a message and tap **Send**.
3. The app calls `POST /ocs/v2.php/apps/spreed/api/v4/chat/{token}` directly — no other app is launched and the screen never leaves the kitchen display.

### Sending voice messages

1. Select a contact, tap **🎤 Record**, speak, tap **⏹ Stop**.
2. The `.m4a` file is uploaded to `{NextcloudFiles}/Talk/` via WebDAV.
3. The file is shared into the Talk conversation (`shareType=10`).

### Receiving messages

`NextcloudPollService` runs as a persistent foreground service with one thread per contact. Each thread long-polls:

```
GET /ocs/v2.php/apps/spreed/api/v4/chat/{token}?lookIntoFuture=1&timeout=30&lastKnownMessageId=X
```

The server holds the connection for up to 30 seconds and responds immediately when a new message arrives. New messages are:
- Stored locally (SharedPreferences + Gson).
- Broadcast as `com.kitchendisplay.app.NEW_MESSAGE` so the open Messages screen refreshes.
- Optionally read aloud via Android TTS.

No notification-access permission is required — the app communicates directly with the Nextcloud Talk REST API.

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/kitchendisplay/app/
│   ├── MainActivity.kt                   # Host activity; kiosk, idle timer, screen-on, starts poll service
│   ├── BootReceiver.kt                   # Launches app on device boot
│   ├── KioskDeviceAdminReceiver.kt       # Device-owner for full lock-task
│   ├── data/
│   │   ├── MessageRepository.kt         # SharedPrefs + Gson; contacts, messages, poll state
│   │   └── SettingsRepository.kt        # App settings including Nextcloud credentials
│   ├── models/
│   │   ├── Contact.kt                   # Display name + nextcloudUserId + cachedRoomToken
│   │   ├── Message.kt                   # Sent/received message
│   │   └── WeatherData.kt               # Open-Meteo response + WMO helpers
│   ├── services/
│   │   ├── NextcloudTalkService.kt      # OCS API v4 HTTP client (sync, background thread)
│   │   ├── NextcloudPollService.kt      # Foreground service; long-poll DM rooms
│   │   ├── AudioRecorderHelper.kt       # MediaRecorder wrapper
│   │   └── WeatherService.kt            # Open-Meteo HTTP client
│   └── ui/
│       ├── main/MainFragment.kt         # Clock + weather + shortcut buttons
│       ├── messages/
│       │   ├── MessagesFragment.kt      # Compose + message list; sends via Nextcloud API
│       │   └── MessagesAdapter.kt       # RecyclerView adapter
│       ├── youtube/YoutubeFragment.kt   # In-app YouTube WebView
│       └── settings/SettingsFragment.kt # Nextcloud credentials + all settings
└── res/
    ├── layout/  (activity_main, fragment_*, item_*, dialog_*)
    ├── values/  (strings, colors, themes)
    ├── menu/    (bottom_nav_menu)
    └── xml/     (device_admin, file_provider_paths)
```

---

## Permissions Used

| Permission | Purpose |
|---|---|
| `INTERNET` | Nextcloud API + weather + YouTube |
| `WAKE_LOCK` | Keep screen on |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on boot |
| `RECORD_AUDIO` | Voice message recording |
| `FOREGROUND_SERVICE` | Nextcloud poll service stays alive |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14 foreground service type |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Messages not arriving | Check server URL, username, and app password in Settings; verify the Talk app is enabled on your Nextcloud instance |
| "Failed to send" | Same credentials issue — also check that the contact's Nextcloud username is correct |
| Voice upload fails | Ensure the Nextcloud account has write access to the Files app |
| App does not start on boot | Launch the app manually at least once after install |
| Lock-task not working | Run `adb shell dpm set-device-owner …` (see setup above) |


---

## Features

| Feature | Details |
|---|---|
| **Landscape lock** | Always forced to landscape; cannot rotate or tilt |
| **Screen always on** | `FLAG_KEEP_SCREEN_ON` keeps the display lit while plugged in |
| **Boot on startup** | Automatically launches after the device boots |
| **Kiosk mode** | Immersive full-screen + back-button intercept; full lock-task available with device-owner ADB setup |
| **Large clock** | Full-screen digital clock (`HH:mm:ss`) with date |
| **Live weather** | Powered by [Open-Meteo](https://open-meteo.com/) – no API key required |
| **Signal shortcuts** | Configurable one-tap buttons to send text or voice messages to Signal contacts |
| **Receive messages** | Intercepts Signal notifications; stores them and reads text aloud via TTS |
| **In-app YouTube** | Built-in WebView search + playback; never leaves the app |
| **20 s idle timeout** | Returns to the main screen after 20 seconds of inactivity (configurable) |
| **Settings screen** | PIN-protected settings for contacts, weather location, TTS, idle timeout, and kiosk exit |

---

## Requirements

- Android Studio **Hedgehog (2023.1.1)** or later  
- **JDK 17**  
- Android Gradle Plugin **8.1.x**  
- `compileSdk 34`, `minSdk 23` (Android 6.0 Marshmallow)  
- Signal app installed on the device (as a linked device)

---

## Quick Build

```bash
# Clone the repository
git clone https://github.com/QuizzityMC/SamsungPhoneKitchenDisplay.git
cd SamsungPhoneKitchenDisplay

# Build debug APK
./gradlew assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk

# Install directly via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in **Android Studio → File → Open** and use **Build → Build APK**.

---

## First-Time Device Setup

### 1. Install the APK

```bash
adb install app-debug.apk
```

### 2. Grant Notification Access (required for receiving Signal messages)

Go to **Settings → Apps → Special app access → Notification access** and enable **Kitchen Display**.

You can also tap the **Grant Notification Access** button inside the app's Settings screen.

### 3. (Optional) Enable Full Kiosk Mode — Lock-Task

Full kiosk mode (hardware home button + recents truly disabled) requires setting the app as the [Device Owner](https://developer.android.com/work/dpc/build-dpc) once:

```bash
# Factory-reset the device first (no accounts signed in), then:
adb shell dpm set-device-owner com.kitchendisplay.app/.KioskDeviceAdminReceiver
```

After this, the app will call `startLockTask()` automatically on every launch and the hardware buttons will be disabled.

Without device-owner mode the app still uses immersive (full-screen) mode and intercepts the software back button, which is sufficient for casual use.

### 4. Set a Settings PIN *(recommended)*

Inside the app, go to **Settings → Change Settings PIN** to set a numeric PIN.  
This PIN is required to reach the Settings screen or exit kiosk mode.

---

## Configuration (in-app Settings)

| Setting | Description |
|---|---|
| **Contacts** | Add contacts with display name + phone number (with country code). Each contact gets a shortcut button on the main screen. |
| **Weather location** | Enter a city name; the app geocodes it automatically using Open-Meteo. |
| **Temperature unit** | Choose °C or °F. |
| **Read messages aloud** | Toggle TTS readout of incoming Signal text messages. |
| **Idle timeout** | Seconds of inactivity before returning to the main screen (default: 20). |
| **Grant Notification Access** | Opens the system notification-access settings page. |
| **Change Settings PIN** | Set or clear the PIN protecting the Settings screen. |
| **Exit Kiosk Mode** | Stops lock-task and returns to the Android home screen (PIN required if set). |

---

## How Signal Integration Works

### Sending messages

1. Tap a shortcut button on the main screen (or open **Messages** and select a contact).
2. Type a text message and tap **Send** — the app targets the Signal package directly via `Intent.ACTION_SEND`.
3. To send a voice message, tap **🎤 Record**, speak, then tap **⏹ Stop** — the app saves a `.m4a` audio file and shares it to Signal.

> **Note:** Sending briefly hands off to the Signal app. This is by design — Signal does not expose a programmatic API that would allow another app to send messages entirely in the background.

### Receiving messages

The `SignalNotificationListener` service intercepts Signal notifications while they arrive, extracts the sender name and message text, and:
- Stores the message in local storage (SharedPreferences + Gson).
- Triggers Text-to-Speech readout if enabled.
- Broadcasts `com.kitchendisplay.app.NEW_MESSAGE` so any open Messages screen refreshes instantly.
- Voice-message notifications are flagged and shown with a **tap-to-play** prompt (playback requires the file to be saved locally by Signal).

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/kitchendisplay/app/
│   ├── MainActivity.kt            # Host activity; kiosk, idle timer, screen-on
│   ├── BootReceiver.kt            # Launches app on device boot
│   ├── KioskDeviceAdminReceiver.kt# Device-owner receiver for full lock-task
│   ├── data/
│   │   ├── MessageRepository.kt  # SharedPrefs + Gson message/contact storage
│   │   └── SettingsRepository.kt # App settings
│   ├── models/
│   │   ├── Contact.kt            # Shortcut contact data class
│   │   ├── Message.kt            # Sent/received message data class
│   │   └── WeatherData.kt        # Weather response model + WMO code helpers
│   ├── services/
│   │   ├── AudioRecorderHelper.kt         # MediaRecorder wrapper
│   │   ├── SignalNotificationListener.kt  # NotificationListenerService
│   │   └── WeatherService.kt             # Open-Meteo HTTP client
│   └── ui/
│       ├── main/MainFragment.kt          # Clock + weather + shortcuts
│       ├── messages/
│       │   ├── MessagesFragment.kt       # Compose + message list
│       │   └── MessagesAdapter.kt        # RecyclerView adapter
│       ├── youtube/YoutubeFragment.kt    # In-app YouTube WebView
│       └── settings/SettingsFragment.kt  # All settings
└── res/
    ├── layout/   (activity_main, fragment_*, item_*, dialog_*)
    ├── values/   (strings, colors, themes)
    ├── menu/     (bottom_nav_menu)
    └── xml/      (device_admin, file_provider_paths)
```

---

## Permissions Used

| Permission | Purpose |
|---|---|
| `INTERNET` | Weather API + YouTube WebView |
| `WAKE_LOCK` | Keep screen on |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on boot |
| `RECORD_AUDIO` | Voice message recording |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Intercept Signal notifications |
| `FOREGROUND_SERVICE` | Background service stability |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Weather not loading | Check city name spelling in Settings; requires internet access |
| Messages not appearing | Grant Notification Access in system settings |
| App does not start on boot | Ensure the app has been launched at least once manually after install |
| Lock-task not working | Run the `dpm set-device-owner` ADB command (see setup above) |
| Signal not found | Ensure Signal (package `org.thoughtcrime.securesms`) is installed |
