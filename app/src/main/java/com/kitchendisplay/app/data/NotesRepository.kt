package com.kitchendisplay.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kitchendisplay.app.models.Note

/**
 * Stores notes in SharedPreferences (Gson-serialised list).
 * Notes are ordered newest-first.
 */
class NotesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getNotes(): List<Note> {
        val json = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Note>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(notes: List<Note>) {
        prefs.edit().putString(KEY_NOTES, gson.toJson(notes)).apply()
    }

    /** Insert or update a note (matched by id). */
    fun upsert(note: Note) {
        val list = getNotes().toMutableList()
        val idx = list.indexOfFirst { it.id == note.id }
        if (idx >= 0) list[idx] = note else list.add(0, note)
        // Keep newest-first
        list.sortByDescending { it.updatedAt }
        saveAll(list)
    }

    fun delete(id: String) {
        saveAll(getNotes().filter { it.id != id })
    }

    companion object {
        private const val PREFS_NAME = "kitchen_notes"
        private const val KEY_NOTES = "notes"
    }
}
