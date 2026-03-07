package com.kitchendisplay.app.models

import java.io.Serializable

/**
 * Represents a Nextcloud Talk contact shortcut configured in settings.
 *
 * [nextcloudUserId] is the Nextcloud account ID of the contact (e.g. "alice").
 * [cachedRoomToken] is the Talk conversation token for the DM room, lazily
 * populated on first send/poll and persisted so subsequent API calls are fast.
 */
data class Contact(
    val id: String,
    val displayName: String,
    val nextcloudUserId: String,
    val cachedRoomToken: String = ""
) : Serializable
