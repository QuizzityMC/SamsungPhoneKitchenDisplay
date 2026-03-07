package com.kitchendisplay.app.models

import java.io.Serializable

/**
 * Represents a Signal contact shortcut configured in settings.
 */
data class Contact(
    val id: String,
    val displayName: String,
    val phoneNumber: String
) : Serializable
