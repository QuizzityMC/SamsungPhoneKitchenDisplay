package com.kitchendisplay.app.models

/**
 * Represents a message sent to or received from a Signal contact.
 */
data class Message(
    val id: String,
    val contactId: String,
    val contactName: String,
    /** Text content; empty string for voice-only messages */
    val text: String,
    val timestamp: Long,
    val isVoice: Boolean,
    /** Local file path for a recorded / received voice message */
    val voiceFilePath: String? = null,
    val direction: Direction
) {
    enum class Direction { SENT, RECEIVED }
}
