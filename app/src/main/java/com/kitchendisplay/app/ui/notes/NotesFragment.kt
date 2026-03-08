package com.kitchendisplay.app.ui.notes

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kitchendisplay.app.R
import com.kitchendisplay.app.data.NotesRepository
import com.kitchendisplay.app.databinding.FragmentNotesBinding
import com.kitchendisplay.app.databinding.ItemNoteBinding
import com.kitchendisplay.app.models.Note
import java.util.UUID

/**
 * Notes screen — landscape split-pane layout:
 *  - Left panel (30%): scrollable list of notes with a "New Note" button.
 *  - Right panel (70%): live editor for the selected note with auto-save.
 */
class NotesFragment : Fragment() {

    private var _binding: FragmentNotesBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: NotesRepository
    private lateinit var adapter: NoteListAdapter

    private var currentNote: Note? = null
    private var isSaving = false  // guard to prevent TextWatcher re-entrancy

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = NotesRepository(requireContext())

        adapter = NoteListAdapter(emptyList()) { note -> openNote(note) }
        binding.rvNotes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotes.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        binding.rvNotes.adapter = adapter

        binding.btnNewNote.setOnClickListener {
            val note = Note(id = UUID.randomUUID().toString(), content = "")
            repo.upsert(note)
            refreshList()
            openNote(note)
        }

        binding.btnDeleteNote.setOnClickListener {
            currentNote?.let { note ->
                repo.delete(note.id)
                currentNote = null
                refreshList()
                showEditor(false)
            }
        }

        setupTextWatcher()
        refreshList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Note editing ─────────────────────────────────────────────────────

    private fun openNote(note: Note) {
        currentNote = note
        showEditor(true)
        isSaving = true
        binding.etNoteContent.setText(note.content)
        binding.etNoteContent.setSelection(note.content.length)
        isSaving = false
        binding.tvNoteSaved.visibility = View.INVISIBLE
    }

    private fun showEditor(show: Boolean) {
        binding.layoutEditor.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvNoNoteHint.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun setupTextWatcher() {
        binding.etNoteContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isSaving) return
                val note = currentNote ?: return
                val updated = note.copy(
                    content = s.toString(),
                    updatedAt = System.currentTimeMillis()
                )
                currentNote = updated
                repo.upsert(updated)
                refreshList()
                // Show "saved" indicator briefly
                _binding?.tvNoteSaved?.visibility = View.VISIBLE
                _binding?.tvNoteSaved?.postDelayed({
                    _binding?.tvNoteSaved?.visibility = View.INVISIBLE
                }, 1500)
            }
        })
    }

    private fun refreshList() {
        val notes = repo.getNotes()
        adapter.update(notes)
        // Keep the selected note highlighted
        val selectedId = currentNote?.id
        if (selectedId != null) {
            val pos = notes.indexOfFirst { it.id == selectedId }
            if (pos >= 0) adapter.setSelectedPosition(pos)
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    private inner class NoteListAdapter(
        private var items: List<Note>,
        private val onClick: (Note) -> Unit
    ) : RecyclerView.Adapter<NoteListAdapter.VH>() {

        private var selectedPos = -1

        inner class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val note = items[position]
            holder.b.tvNoteTitle.text = note.title
            holder.b.tvNoteDate.text = note.updatedAtLabel
            val bgColor = if (position == selectedPos)
                ContextCompat.getColor(requireContext(), R.color.input_background)
            else
                ContextCompat.getColor(requireContext(), R.color.surface)
            holder.b.root.setBackgroundColor(bgColor)
            holder.b.root.setOnClickListener { onClick(note) }
        }

        fun update(newItems: List<Note>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun setSelectedPosition(pos: Int) {
            val prev = selectedPos
            selectedPos = pos
            if (prev >= 0 && prev < items.size) notifyItemChanged(prev)
            if (pos >= 0 && pos < items.size) notifyItemChanged(pos)
        }
    }
}
