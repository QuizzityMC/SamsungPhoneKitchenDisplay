package com.kitchendisplay.app.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Nextcloud Talk OCS API v4.
 *
 * Authentication uses HTTP Basic Auth with an app password (recommended) or
 * the account password.  Create an app password in Nextcloud → Settings →
 * Security → App passwords.
 *
 * All methods are synchronous and **must** be called from a background thread.
 */
class NextcloudTalkService(
    serverUrl: String,
    private val username: String,
    password: String
) {

    private val baseUrl = serverUrl.trimEnd('/')
    private val credentials = Credentials.basic(username, password)
    private val gson = Gson()

    /** Short-lived client for normal requests */
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Long-polling client — read timeout > server poll timeout (30 s) */
    private val longPollClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // ──────────────── Room helpers ────────────────

    /**
     * Returns the Talk conversation token for a DM with [nextcloudUserId].
     * Looks for an existing one-to-one room; creates it if absent.
     * Returns null if the server cannot be reached or the user is not found.
     */
    fun getOrCreateDmToken(nextcloudUserId: String): String? {
        val rooms = listRooms() ?: return null
        val existing = rooms.firstOrNull { room ->
            room.get("type")?.asInt == ROOM_TYPE_ONE_TO_ONE &&
                    room.get("name")?.asString == nextcloudUserId
        }
        if (existing != null) return existing.get("token")?.asString
        return createDmRoom(nextcloudUserId)
    }

    private fun listRooms(): List<JsonObject>? {
        val request = ocsGet("$baseUrl/ocs/v2.php/apps/spreed/api/v4/room")
        return try {
            val body = client.newCall(request).execute().body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            val data = json.getAsJsonObject("ocs")?.getAsJsonArray("data") ?: return null
            data.map { it.asJsonObject }
        } catch (_: Exception) {
            null
        }
    }

    private fun createDmRoom(nextcloudUserId: String): String? {
        val body = "roomType=$ROOM_TYPE_ONE_TO_ONE&invite=${enc(nextcloudUserId)}"
            .toRequestBody(FORM_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/ocs/v2.php/apps/spreed/api/v4/room")
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        return try {
            val resp = client.newCall(request).execute()
            val bodyStr = resp.body?.string() ?: return null
            gson.fromJson(bodyStr, JsonObject::class.java)
                ?.getAsJsonObject("ocs")
                ?.getAsJsonObject("data")
                ?.get("token")?.asString
        } catch (_: Exception) {
            null
        }
    }

    // ──────────────── Sending ────────────────

    /**
     * Sends a text [message] to the Talk conversation identified by [roomToken].
     * Returns the server-assigned message ID on success, or -1 on failure.
     */
    fun sendTextMessage(roomToken: String, message: String): Long {
        val body = "message=${enc(message)}".toRequestBody(FORM_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/ocs/v2.php/apps/spreed/api/v4/chat/$roomToken")
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        return try {
            val resp = client.newCall(request).execute()
            val bodyStr = resp.body?.string() ?: return -1
            gson.fromJson(bodyStr, JsonObject::class.java)
                ?.getAsJsonObject("ocs")
                ?.getAsJsonObject("data")
                ?.get("id")?.asLong ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Uploads [audioFile] to the user's Nextcloud Files "Talk" folder via
     * WebDAV, then shares it into [roomToken] as a Talk file share.
     * Returns true on success.
     */
    fun sendVoiceMessage(roomToken: String, audioFile: File): Boolean {
        val davBase = "$baseUrl/remote.php/dav/files/$username/Talk"
        ensureDir(davBase)
        val remotePath = "/Talk/${audioFile.name}"

        return try {
            val uploadReq = Request.Builder()
                .url("$davBase/${audioFile.name}")
                .addHeader("Authorization", credentials)
                .put(audioFile.asRequestBody("audio/mp4".toMediaType()))
                .build()
            val uploadResp = client.newCall(uploadReq).execute()
            if (uploadResp.code !in 200..204) return false

            val shareBody =
                "shareType=10&shareWith=${enc(roomToken)}&path=${enc(remotePath)}"
                    .toRequestBody(FORM_TYPE)
            val shareReq = Request.Builder()
                .url("$baseUrl/ocs/v2.php/apps/files_sharing/api/v1/shares")
                .addHeader("Authorization", credentials)
                .addHeader("OCS-APIRequest", "true")
                .addHeader("Accept", "application/json")
                .post(shareBody)
                .build()
            client.newCall(shareReq).execute().isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    // ──────────────── Polling ────────────────

    /**
     * Long-polls [roomToken] for messages newer than [lastKnownMessageId].
     * Blocks for up to 30 seconds (server-side timeout).
     *
     * Returns a list of new message [JsonObject]s, an empty list if nothing
     * arrived within the timeout, or null on a network error.
     */
    fun pollMessages(roomToken: String, lastKnownMessageId: Long): List<JsonObject>? {
        val url = "$baseUrl/ocs/v2.php/apps/spreed/api/v4/chat/$roomToken" +
                "?lookIntoFuture=1&limit=50&timeout=30&lastKnownMessageId=$lastKnownMessageId"
        val request = ocsGet(url)
        return try {
            val resp = longPollClient.newCall(request).execute()
            if (resp.code == 304) return emptyList()
            val body = resp.body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            val data = json.getAsJsonObject("ocs")?.getAsJsonArray("data") ?: return null
            data.map { it.asJsonObject }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches the [limit] most-recent messages from [roomToken] (newest first).
     * Used on first launch to back-fill local history.
     */
    fun getRecentMessages(roomToken: String, limit: Int = 50): List<JsonObject> {
        val url = "$baseUrl/ocs/v2.php/apps/spreed/api/v4/chat/$roomToken" +
                "?lookIntoFuture=0&limit=$limit"
        val request = ocsGet(url)
        return try {
            val body = client.newCall(request).execute().body?.string() ?: return emptyList()
            val json = gson.fromJson(body, JsonObject::class.java)
            val data = json.getAsJsonObject("ocs")?.getAsJsonArray("data") ?: return emptyList()
            data.map { it.asJsonObject }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ──────────────── Helpers ────────────────

    /** Extracts the displayable text from a Talk message JSON object. */
    fun extractMessageText(msgJson: JsonObject): String {
        return when (msgJson.get("messageType")?.asString) {
            "voice-message" -> "\uD83C\uDFA4 Voice message"
            else -> msgJson.get("message")?.asString ?: ""
        }
    }

    /** Returns true if the message came from [username] (i.e. was sent by us). */
    fun isSentByUs(msgJson: JsonObject): Boolean =
        msgJson.get("actorId")?.asString.equals(username, ignoreCase = true)

    private fun ocsGet(url: String) = Request.Builder()
        .url(url)
        .addHeader("Authorization", credentials)
        .addHeader("OCS-APIRequest", "true")
        .addHeader("Accept", "application/json")
        .get()
        .build()

    private fun ensureDir(davDirUrl: String) {
        try {
            val req = Request.Builder()
                .url(davDirUrl)
                .addHeader("Authorization", credentials)
                .method("MKCOL", ByteArray(0).toRequestBody())
                .build()
            client.newCall(req).execute()
        } catch (_: Exception) {
            // Ignore — directory likely already exists, or server doesn't support MKCOL
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())

    companion object {
        private const val ROOM_TYPE_ONE_TO_ONE = 1
        private val FORM_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}
