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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kitchendisplay.app.MainActivity
import com.kitchendisplay.app.R
import com.kitchendisplay.app.data.MessageRepository
import com.kitchendisplay.app.data.SettingsRepository
import com.kitchendisplay.app.databinding.FragmentMessagesBinding
import com.kitchendisplay.app.models.Contact
import com.kitchendisplay.app.models.Message
import com.kitchendisplay.app.services.AudioRecorderHelper
import com.kitchendisplay.app.services.NextcloudPollService
import com.kitchendisplay.app.services.NextcloudTalkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Displays recent Nextcloud Talk messages and lets the user compose and send
 * text or voice DMs to a selected contact.
 *
 * Sending happens via the Nextcloud Talk OCS API (HTTP, background coroutine).
 * New incoming messages are delivered by [NextcloudPollService] via a local
 * broadcast; no notification-access permission is required.
 */
class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!

    private lateinit var messageRepo: MessageRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var adapter: MessagesAdapter
    private val audioRecorder = AudioRecorderHelper()

    private var selectedContact: Contact? = null
    private var isRecording = false

    /** Refresh the list whenever the poll service delivers a new message. */
    private val newMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshMessages()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        messageRepo = MessageRepository(requireContext())
        arguments?.getString(ARG_CONTACT_ID)?.let { contactId ->
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
        settingsRepo = SettingsRepository(requireContext())

        adapter = MessagesAdapter(emptyList()) { msg ->
            msg.voiceFilePath?.let { audioRecorder.playFile(it) }
        }
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter

        populateContactSpinner()

        selectedContact?.let { contact ->
            val pos = messageRepo.getContacts().indexOfFirst { it.id == contact.id }
            if (pos >= 0) binding.spinnerContacts.setSelection(pos + 1)
        }

        binding.btnSendText.setOnClickListener { sendText() }
        binding.btnVoice.setOnClickListener { toggleRecording() }

        refreshMessages()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(NextcloudPollService.ACTION_NEW_MESSAGE)
        ContextCompat.registerReceiver(
            requireContext(),
            newMessageReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(newMessageReceiver)
        } catch (_: Exception) { /* not registered */ }
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
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerContacts.adapter = spinnerAdapter
        binding.spinnerContacts.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long
                ) { selectedContact = if (pos == 0) null else contacts[pos - 1] }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {
                    selectedContact = null
                }
            }
    }

    // ── Send text ─────────────────────────────────────────────────────────

    private fun sendText() {
        val contact = selectedContact ?: run {
            Toast.makeText(requireContext(), R.string.select_contact_first, Toast.LENGTH_SHORT).show()
            return
        }
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), R.string.enter_message, Toast.LENGTH_SHORT).show()
            return
        }
        if (!checkNextcloudConfig()) return

        binding.btnSendText.isEnabled = false
        lifecycleScope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    val talkService = buildTalkService()
                    val token = resolveRoomToken(talkService, contact) ?: return@withContext false
                    talkService.sendTextMessage(token, text) >= 0
                }
            } catch (_: Exception) {
                false
            }
            if (_binding == null) return@launch
            binding.btnSendText.isEnabled = true
            if (success) {
                messageRepo.addMessage(
                    Message(
                        id = UUID.randomUUID().toString(),
                        contactId = contact.id,
                        contactName = contact.displayName,
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isVoice = false,
                        direction = Message.Direction.SENT
                    )
                )
                binding.etMessage.setText("")
                refreshMessages()
            } else {
                Toast.makeText(requireContext(), R.string.send_failed, Toast.LENGTH_SHORT).show()
            }
        }
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
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_AUDIO)
            return
        }
        if (selectedContact == null) {
            Toast.makeText(requireContext(), R.string.select_contact_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!checkNextcloudConfig()) return

        val file = File(requireContext().cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        audioRecorder.startRecording(file)
        isRecording = true
        binding.btnVoice.text = getString(R.string.stop_recording)
        binding.btnVoice.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.recording_red)
        )
        (activity as? MainActivity)?.suppressIdleReturn = true
    }

    private fun stopRecording(send: Boolean) {
        val path = audioRecorder.stopRecording()
        isRecording = false
        _binding?.btnVoice?.text = getString(R.string.record_voice)
        _binding?.btnVoice?.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.button_default)
        )
        (activity as? MainActivity)?.suppressIdleReturn = false
        (activity as? MainActivity)?.resetIdleTimer()

        val contact = selectedContact ?: return
        if (!send || path == null) return

        _binding?.btnVoice?.isEnabled = false
        lifecycleScope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    val talkService = buildTalkService()
                    val token = resolveRoomToken(talkService, contact) ?: return@withContext false
                    talkService.sendVoiceMessage(token, File(path))
                }
            } catch (_: Exception) {
                false
            }
            if (_binding == null) return@launch
            binding.btnVoice.isEnabled = true
            if (success) {
                messageRepo.addMessage(
                    Message(
                        id = UUID.randomUUID().toString(),
                        contactId = contact.id,
                        contactName = contact.displayName,
                        text = "",
                        timestamp = System.currentTimeMillis(),
                        isVoice = true,
                        voiceFilePath = path,
                        direction = Message.Direction.SENT
                    )
                )
                refreshMessages()
            } else {
                Toast.makeText(requireContext(), R.string.send_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == RC_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) startRecording()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun checkNextcloudConfig(): Boolean {
        if (settingsRepo.nextcloudServerUrl.isEmpty() ||
            settingsRepo.nextcloudUsername.isEmpty() ||
            settingsRepo.nextcloudPassword.isEmpty()
        ) {
            Toast.makeText(requireContext(), R.string.nextcloud_not_configured, Toast.LENGTH_LONG)
                .show()
            return false
        }
        return true
    }

    private fun buildTalkService() = NextcloudTalkService(
        settingsRepo.nextcloudServerUrl,
        settingsRepo.nextcloudUsername,
        settingsRepo.nextcloudPassword
    )

    /**
     * Returns the DM room token for [contact], using the cached value when
     * available or calling the API to resolve/create the room.
     * Persists the token into the contact record so future calls are instant.
     */
    private fun resolveRoomToken(
        talkService: NextcloudTalkService,
        contact: Contact
    ): String? {
        if (contact.cachedRoomToken.isNotEmpty()) return contact.cachedRoomToken
        val token = talkService.getOrCreateDmToken(contact.nextcloudUserId) ?: return null
        messageRepo.cacheRoomToken(contact.id, token)
        return token
    }

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

        fun newInstance(contactId: String) = MessagesFragment().apply {
            arguments = Bundle().apply { putString(ARG_CONTACT_ID, contactId) }
        }
    }
}
