package com.kitchendisplay.app.services

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import com.kitchendisplay.app.data.MessageRepository
import com.kitchendisplay.app.data.SettingsRepository
import com.kitchendisplay.app.models.Message
import java.util.Locale
import java.util.UUID

/**
 * Listens to incoming Signal notifications and stores them in [MessageRepository].
 *
 * Text messages are optionally read aloud via TTS.
 * Voice/audio attachment notifications are flagged as voice messages.
 *
 * Setup: the user must grant "Notification access" to this app in
 * Settings → Apps → Special app access → Notification access.
 */
class SignalNotificationListener : NotificationListenerService() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var messageRepo: MessageRepository
    private lateinit var settingsRepo: SettingsRepository

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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        // Only process Signal notifications
        if (pkg != SIGNAL_PACKAGE && pkg != SIGNAL_BETA_PACKAGE) return

        val notification = sbn.notification ?: return
        val extras: Bundle = notification.extras ?: return

        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: "Unknown"
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Detect voice / audio attachment notes
        val isVoice = text.contains("voice message", ignoreCase = true) ||
                text.contains("audio message", ignoreCase = true) ||
                text.contains("\uD83C\uDFA4", ignoreCase = false) // 🎤 emoji

        // Find the matching stored contact by name (best-effort)
        val contacts = messageRepo.getContacts()
        val contact = contacts.firstOrNull {
            it.displayName.equals(sender, ignoreCase = true)
        }

        val message = Message(
            id = UUID.randomUUID().toString(),
            contactId = contact?.id ?: sender,
            contactName = sender,
            text = if (isVoice) "" else text,
            timestamp = System.currentTimeMillis(),
            isVoice = isVoice,
            direction = Message.Direction.RECEIVED
        )
        messageRepo.addMessage(message)

        // Broadcast so any open MessagesFragment can refresh
        val intent = android.content.Intent(ACTION_NEW_MESSAGE).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)

        // Read text messages aloud if enabled
        if (!isVoice && settingsRepo.readMessagesAloud && ttsReady) {
            val speech = "Message from $sender: $text"
            tts?.speak(speech, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    companion object {
        const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"
        const val SIGNAL_BETA_PACKAGE = "org.thoughtcrime.securesms.beta"
        const val ACTION_NEW_MESSAGE = "com.kitchendisplay.app.NEW_MESSAGE"
    }
}
