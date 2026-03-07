package com.kitchendisplay.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kitchendisplay.app.models.Contact
import com.kitchendisplay.app.models.Message

/**
 * Lightweight repository backed by SharedPreferences + Gson.
 * Stores contacts (shortcuts) and the most recent messages.
 */
class MessageRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // ──────────────── Contacts ────────────────

    fun getContacts(): List<Contact> {
        val json = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        val type = object : TypeToken<List<Contact>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun saveContacts(contacts: List<Contact>) {
        prefs.edit().putString(KEY_CONTACTS, gson.toJson(contacts)).apply()
    }

    fun addContact(contact: Contact) {
        val list = getContacts().toMutableList()
        list.removeAll { it.id == contact.id }
        list.add(contact)
        saveContacts(list)
    }

    fun removeContact(id: String) {
        saveContacts(getContacts().filter { it.id != id })
    }

    // ──────────────── Messages ────────────────

    fun getMessages(): List<Message> {
        val json = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        val type = object : TypeToken<List<Message>>() {}.type
        return gson.fromJson<List<Message>>(json, type) ?: emptyList()
    }

    fun addMessage(message: Message) {
        val list = getMessages().toMutableList()
        list.add(0, message)
        // Keep only the most recent MAX_MESSAGES
        val trimmed = if (list.size > MAX_MESSAGES) list.subList(0, MAX_MESSAGES) else list
        prefs.edit().putString(KEY_MESSAGES, gson.toJson(trimmed)).apply()
    }

    fun clearMessages() {
        prefs.edit().remove(KEY_MESSAGES).apply()
    }

    companion object {
        private const val PREFS_NAME = "kitchen_messages"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_MESSAGES = "messages"
        private const val MAX_MESSAGES = 200
    }
}
