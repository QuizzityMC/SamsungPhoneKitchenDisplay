package com.kitchendisplay.app.ui.messages

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.kitchendisplay.app.MainActivity
import com.kitchendisplay.app.R
import com.kitchendisplay.app.data.MessageRepository
import com.kitchendisplay.app.databinding.FragmentMessagesBinding
import com.kitchendisplay.app.models.Contact
import com.kitchendisplay.app.models.Message
import com.kitchendisplay.app.services.AudioRecorderHelper
import com.kitchendisplay.app.services.SignalNotificationListener
import java.io.File
import java.util.UUID

/**
 * Displays recent messages and provides UI to send text or voice messages
 * to a selected Signal contact.
 *
 * Signal messages are sent via Android's share intent (ACTION_SEND) targeting
 * the Signal package, which is the standard inter-app sharing mechanism.
 */
class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!

    private lateinit var messageRepo: MessageRepository
    private lateinit var adapter: MessagesAdapter
    private val audioRecorder = AudioRecorderHelper()

    private var selectedContact: Contact? = null
    private var isRecording = false
    private var currentAudioFile: File? = null

    // Refresh on new incoming Signal notifications
    private val newMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshMessages()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString(ARG_CONTACT_ID)?.let { contactId ->
            messageRepo = MessageRepository(requireContext())
            selectedContact = messageRepo.getContacts().find { it.id == contactId }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        messageRepo = MessageRepository(requireContext())

        // RecyclerView
        adapter = MessagesAdapter(emptyList()) { msg ->
            msg.voiceFilePath?.let { audioRecorder.playFile(it) }
        }
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter

        // Contact spinner
        populateContactSpinner()

        // Pre-select contact if launched from shortcut
        selectedContact?.let { contact ->
            val pos = messageRepo.getContacts().indexOfFirst { it.id == contact.id }
            if (pos >= 0) binding.spinnerContacts.setSelection(pos)
        }

        // Send text
        binding.btnSendText.setOnClickListener { sendText() }

        // Voice record toggle
        binding.btnVoice.setOnClickListener { toggleRecording() }

        refreshMessages()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SignalNotificationListener.ACTION_NEW_MESSAGE)
        ContextCompat.registerReceiver(
            requireContext(),
            newMessageReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(newMessageReceiver)
        if (isRecording) stopRecording(send = false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        audioRecorder.release()
        _binding = null
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    private fun populateContactSpinner() {
        val contacts = messageRepo.getContacts()
        val names = contacts.map { it.displayName }.toMutableList()
        names.add(0, getString(R.string.select_contact))
        val spinnerAdapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            names
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerContacts.adapter = spinnerAdapter
        binding.spinnerContacts.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long
                ) {
                    selectedContact = if (pos == 0) null else contacts[pos - 1]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {
                    selectedContact = null
                }
            }
    }

    // ── Send text ─────────────────────────────────────────────────────────

    private fun sendText() {
        val contact = selectedContact ?: run {
            Toast.makeText(requireContext(), R.string.select_contact_first, Toast.LENGTH_SHORT)
                .show()
            return
        }
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), R.string.enter_message, Toast.LENGTH_SHORT).show()
            return
        }

        // Send to Signal via ACTION_SEND intent (briefly switches to Signal)
        val signalPkg = "org.thoughtcrime.securesms"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(signalPkg)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.send_via)))

        // Save locally
        val message = Message(
            id = UUID.randomUUID().toString(),
            contactId = contact.id,
            contactName = contact.displayName,
            text = text,
            timestamp = System.currentTimeMillis(),
            isVoice = false,
            direction = Message.Direction.SENT
        )
        messageRepo.addMessage(message)
        binding.etMessage.setText("")
        refreshMessages()
    }

    // ── Voice recording ───────────────────────────────────────────────────

    private fun toggleRecording() {
        if (!isRecording) startRecording() else stopRecording(send = true)
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_AUDIO)
            return
        }
        val contact = selectedContact ?: run {
            Toast.makeText(requireContext(), R.string.select_contact_first, Toast.LENGTH_SHORT)
                .show()
            return
        }
        currentAudioFile = File(
            requireContext().cacheDir,
            "voice_${System.currentTimeMillis()}.m4a"
        )
        audioRecorder.startRecording(currentAudioFile!!)
        isRecording = true
        binding.btnVoice.text = getString(R.string.stop_recording)
        binding.btnVoice.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.recording_red)
        )
        // Prevent idle timeout while recording
        (activity as? MainActivity)?.suppressIdleReturn = true
    }

    private fun stopRecording(send: Boolean) {
        val path = audioRecorder.stopRecording()
        isRecording = false
        binding.btnVoice.text = getString(R.string.record_voice)
        binding.btnVoice.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.button_default)
        )
        (activity as? MainActivity)?.suppressIdleReturn = false
        (activity as? MainActivity)?.resetIdleTimer()

        if (send && path != null && selectedContact != null) {
            val contact = selectedContact!!
            val file = File(path)
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("org.thoughtcrime.securesms")
            }
            startActivity(intent)

            // Save locally
            val message = Message(
                id = UUID.randomUUID().toString(),
                contactId = contact.id,
                contactName = contact.displayName,
                text = "",
                timestamp = System.currentTimeMillis(),
                isVoice = true,
                voiceFilePath = path,
                direction = Message.Direction.SENT
            )
            messageRepo.addMessage(message)
            refreshMessages()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == RC_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun refreshMessages() {
        val messages = messageRepo.getMessages()
        adapter = MessagesAdapter(messages) { msg ->
            msg.voiceFilePath?.let { audioRecorder.playFile(it) }
        }
        _binding?.rvMessages?.adapter = adapter
    }

    companion object {
        private const val ARG_CONTACT_ID = "contact_id"
        private const val RC_AUDIO = 1001

        fun newInstance(contactId: String): MessagesFragment {
            return MessagesFragment().apply {
                arguments = Bundle().apply { putString(ARG_CONTACT_ID, contactId) }
            }
        }
    }
}
