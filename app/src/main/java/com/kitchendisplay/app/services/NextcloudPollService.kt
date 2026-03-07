package com.kitchendisplay.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import com.kitchendisplay.app.data.MessageRepository
import com.kitchendisplay.app.data.SettingsRepository
import com.kitchendisplay.app.models.Message
import java.util.Locale
import java.util.UUID

/**
 * Foreground service that continuously long-polls each configured Nextcloud
 * Talk DM room for new incoming messages.
 *
 * One polling thread is spawned per contact. Each thread:
 *  1. Resolves / creates the DM room token for the contact (cached after first
 *     successful lookup so no extra round-trips on restart).
 *  2. Long-polls the Talk chat API (`lookIntoFuture=1&timeout=30`). The server
 *     holds the connection for up to 30 s and responds immediately when a new
 *     message arrives.
 *  3. Stores new messages, broadcasts [ACTION_NEW_MESSAGE] so any open
 *     [com.kitchendisplay.app.ui.messages.MessagesFragment] can refresh.
 *  4. Optionally reads the message aloud via TTS.
 *
 * Start / restart by calling [start]; stop with [stop].
 * The service is declared START_STICKY so Android restarts it if killed.
 */
class NextcloudPollService : Service() {

    private lateinit var messageRepo: MessageRepository
    private lateinit var settingsRepo: SettingsRepository
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile private var running = false
    private val pollingThreads = mutableListOf<Thread>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        messageRepo = MessageRepository(this)
        settingsRepo = SettingsRepository(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        // Restart polling threads (handles both fresh start and RESTART_STICKY re-delivery)
        launchPollingThreads()
        return START_STICKY
    }

    override fun onDestroy() {
        stopPollingThreads()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ── Polling ───────────────────────────────────────────────────────────

    private fun stopPollingThreads() {
        running = false
        pollingThreads.forEach { it.interrupt() }
        pollingThreads.clear()
    }

    private fun launchPollingThreads() {
        stopPollingThreads()

        val serverUrl = settingsRepo.nextcloudServerUrl
        val ncUsername = settingsRepo.nextcloudUsername
        val ncPassword = settingsRepo.nextcloudPassword

        if (serverUrl.isEmpty() || ncUsername.isEmpty() || ncPassword.isEmpty()) return

        val talkService = NextcloudTalkService(serverUrl, ncUsername, ncPassword)
        val contacts = messageRepo.getContacts()
        if (contacts.isEmpty()) return

        running = true
        contacts.forEach { contact ->
            if (contact.nextcloudUserId.isBlank()) return@forEach
            val thread = Thread { pollContactLoop(talkService, contact.id, ncUsername) }
            thread.isDaemon = true
            thread.name = "talk-poll-${contact.displayName}"
            pollingThreads.add(thread)
            thread.start()
        }
    }

    private fun pollContactLoop(
        talkService: NextcloudTalkService,
        contactId: String,
        ownUsername: String
    ) {
        // Resolve fresh contact state (token may have been cached since service last ran)
        val contact = messageRepo.getContacts().find { it.id == contactId } ?: return

        // Resolve the room token (use cached value or fetch from server)
        val token = resolveToken(talkService, contact) ?: return

        // Initialise cursor — use 0 so the server sends any historical messages on
        // first poll; we skip messages sent by ourselves in the handler.
        var lastKnownId = messageRepo.getLastMessageId(contactId)

        // If this is the very first poll, seed lastKnownId from recent history
        // so we don't replay all old messages on every app restart.
        if (lastKnownId == 0L) {
            val recent = talkService.getRecentMessages(token, limit = 1)
            lastKnownId = recent.maxOfOrNull { it.get("id")?.asLong ?: 0L } ?: 0L
            if (lastKnownId > 0) messageRepo.setLastMessageId(contactId, lastKnownId)
        }

        var backoffMs = BACKOFF_MIN_MS

        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val newMsgs = talkService.pollMessages(token, lastKnownId)
                if (newMsgs == null) {
                    // Network error — back off exponentially
                    Thread.sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                    continue
                }
                // Successful poll — reset backoff
                backoffMs = BACKOFF_MIN_MS

                for (msgJson in newMsgs) {
                    val msgId = msgJson.get("id")?.asLong ?: continue
                    if (msgId <= lastKnownId) continue
                    lastKnownId = msgId
                    messageRepo.setLastMessageId(contactId, msgId)

                    // Skip messages we sent ourselves
                    if (talkService.isSentByUs(msgJson)) continue

                    val isVoice = msgJson.get("messageType")?.asString == "voice-message"
                    val text = talkService.extractMessageText(msgJson)

                    // Re-read contact for display name (might have changed in settings)
                    val currentContact =
                        messageRepo.getContacts().find { it.id == contactId }
                    val displayName = currentContact?.displayName ?: contact.displayName

                    val message = Message(
                        id = UUID.randomUUID().toString(),
                        contactId = contactId,
                        contactName = displayName,
                        text = if (isVoice) "" else text,
                        timestamp = (msgJson.get("timestamp")?.asLong ?: 0L) * 1000L,
                        isVoice = isVoice,
                        direction = Message.Direction.RECEIVED
                    )
                    messageRepo.addMessage(message)

                    // Broadcast so any open MessagesFragment can refresh
                    sendBroadcast(Intent(ACTION_NEW_MESSAGE).apply { setPackage(packageName) })

                    // TTS readout of text messages
                    if (!isVoice && settingsRepo.readMessagesAloud && ttsReady) {
                        tts?.speak(
                            "Message from $displayName: $text",
                            TextToSpeech.QUEUE_ADD,
                            null,
                            UUID.randomUUID().toString()
                        )
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // Unexpected error — back off exponentially
                try {
                    Thread.sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun resolveToken(
        talkService: NextcloudTalkService,
        contact: com.kitchendisplay.app.models.Contact
    ): String? {
        if (contact.cachedRoomToken.isNotEmpty()) return contact.cachedRoomToken
        val token = talkService.getOrCreateDmToken(contact.nextcloudUserId) ?: return null
        messageRepo.cacheRoomToken(contact.id, token)
        return token
    }

    // ── Foreground notification ───────────────────────────────────────────

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nextcloud Talk polling",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps Talk messages in sync" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Kitchen Display")
                .setContentText("Listening for Nextcloud Talk messages…")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Kitchen Display")
                .setContentText("Listening for Nextcloud Talk messages…")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }
    }

    companion object {
        const val ACTION_NEW_MESSAGE = "com.kitchendisplay.app.NEW_MESSAGE"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nextcloud_poll"

        /** Minimum back-off on network error: 5 seconds */
        private const val BACKOFF_MIN_MS = 5_000L
        /** Maximum back-off cap: 2 minutes */
        private const val BACKOFF_MAX_MS = 120_000L

        fun start(context: Context) {
            val intent = Intent(context, NextcloudPollService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NextcloudPollService::class.java))
        }
    }
}
