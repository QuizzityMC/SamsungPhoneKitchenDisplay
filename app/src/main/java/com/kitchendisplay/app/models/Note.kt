package com.kitchendisplay.app.models

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A user-created note stored in the kitchen display.
 */
data class Note(
    val id: String,
    /** Full text content of the note */
    val content: String,
    val updatedAt: Long = System.currentTimeMillis()
) : Serializable {

    /** First non-blank line, capped at 60 chars — used as the list title. */
    val title: String
        get() = content.lines().firstOrNull { it.isNotBlank() }
            ?.take(60)
            ?: "(empty)"

    /** Human-readable last-updated timestamp. */
    val updatedAtLabel: String
        get() = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(updatedAt))
}
