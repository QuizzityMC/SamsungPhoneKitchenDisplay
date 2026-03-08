package com.kitchendisplay.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.kitchendisplay.app.R
import com.kitchendisplay.app.data.MessageRepository
import com.kitchendisplay.app.data.SettingsRepository
import com.kitchendisplay.app.databinding.FragmentSettingsBinding
import com.kitchendisplay.app.models.Contact
import com.kitchendisplay.app.services.NextcloudPollService
import java.util.UUID

/**
 * Settings screen.
 *
 * Features:
 *  - Nextcloud account credentials (server URL, username, app password).
 *  - Add / remove Nextcloud Talk contact shortcuts shown on the main screen.
 *  - Weather location (city name) and temperature unit.
 *  - Toggle TTS readout of incoming messages.
 *  - Idle timeout configuration.
 *  - Change / clear the settings PIN.
 *  - Exit kiosk mode (requires correct PIN if one is set).
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsRepository
    private lateinit var messageRepo: MessageRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        messageRepo = MessageRepository(requireContext())

        loadCurrentSettings()
        setupListeners()
        refreshContactList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Load current values ───────────────────────────────────────────────

    private fun loadCurrentSettings() {
        // Nextcloud account
        binding.etNcServerUrl.setText(settings.nextcloudServerUrl)
        binding.etNcUsername.setText(settings.nextcloudUsername)
        // Password field intentionally left blank (do not pre-fill for security)

        // Weather
        binding.etWeatherLocation.setText(settings.weatherLocation)
        binding.switchReadAloud.isChecked = settings.readMessagesAloud
        binding.rgTempUnit.check(
            if (settings.temperatureUnit == "C") R.id.rb_celsius else R.id.rb_fahrenheit
        )
        binding.etIdleTimeout.setText(settings.idleTimeoutSeconds.toString())
    }

    // ── Listeners ─────────────────────────────────────────────────────────

    private fun setupListeners() {
        // Save Nextcloud credentials & restart poll service
        binding.btnSaveNcCredentials.setOnClickListener {
            val url = binding.etNcServerUrl.text.toString().trim()
            val user = binding.etNcUsername.text.toString().trim()
            val pass = binding.etNcPassword.text.toString()
            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(requireContext(), R.string.nc_credentials_incomplete, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settings.nextcloudServerUrl = url
            settings.nextcloudUsername = user
            settings.nextcloudPassword = pass
            // Restart the poll service so it picks up the new credentials
            NextcloudPollService.stop(requireContext())
            NextcloudPollService.start(requireContext())
            // Clear cached room tokens so they are re-resolved against the new server
            messageRepo.saveContacts(messageRepo.getContacts().map { it.copy(cachedRoomToken = "") })
            Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
        }

        // Save weather location
        binding.btnSaveLocation.setOnClickListener {
            val loc = binding.etWeatherLocation.text.toString().trim()
            if (loc.isEmpty()) return@setOnClickListener
            settings.weatherLocation = loc
            settings.weatherLocationResolved = false
            Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
        }

        // TTS toggle
        binding.switchReadAloud.setOnCheckedChangeListener { _, checked ->
            settings.readMessagesAloud = checked
        }

        // Temperature unit
        binding.rgTempUnit.setOnCheckedChangeListener { _, checkedId ->
            settings.temperatureUnit = if (checkedId == R.id.rb_celsius) "C" else "F"
        }

        // Idle timeout
        binding.btnSaveIdleTimeout.setOnClickListener {
            val secs = binding.etIdleTimeout.text.toString().toIntOrNull() ?: 20
            settings.idleTimeoutSeconds = secs.coerceIn(5, 3600)
            Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
        }

        // Change PIN
        binding.btnChangePin.setOnClickListener { showChangePinDialog() }

        // Exit kiosk
        binding.btnExitKiosk.setOnClickListener { exitKiosk() }

        // Add contact
        binding.btnAddContact.setOnClickListener { showAddContactDialog() }
    }

    // ── Contact management ────────────────────────────────────────────────

    private fun refreshContactList() {
        val contacts = messageRepo.getContacts()
        binding.llContacts.removeAllViews()
        contacts.forEach { contact ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_contact_row, binding.llContacts, false)
            row.findViewById<android.widget.TextView>(R.id.tv_contact_name).text =
                "${contact.displayName}  (@${contact.nextcloudUserId})"
            row.findViewById<android.widget.ImageButton>(R.id.btn_delete_contact)
                .setOnClickListener {
                    messageRepo.removeContact(contact.id)
                    refreshContactList()
                }
            binding.llContacts.addView(row)
        }
    }

    private fun showAddContactDialog() {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_contact, null)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_contact)
            .setView(view)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = view.findViewById<android.widget.EditText>(R.id.et_contact_name)
                    .text.toString().trim()
                val userId = view.findViewById<android.widget.EditText>(R.id.et_contact_nc_user)
                    .text.toString().trim()
                if (name.isNotEmpty() && userId.isNotEmpty()) {
                    messageRepo.addContact(
                        Contact(
                            id = UUID.randomUUID().toString(),
                            displayName = name,
                            nextcloudUserId = userId
                        )
                    )
                    refreshContactList()
                    // Restart poll service so it picks up the new contact
                    NextcloudPollService.stop(requireContext())
                    NextcloudPollService.start(requireContext())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── PIN ───────────────────────────────────────────────────────────────

    private fun showChangePinDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.new_pin_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.change_pin)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                settings.settingsPin = input.text.toString().trim()
                Toast.makeText(requireContext(), R.string.pin_saved, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.clear_pin) { _, _ ->
                settings.settingsPin = ""
                Toast.makeText(requireContext(), R.string.pin_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Exit kiosk ────────────────────────────────────────────────────────

    private fun exitKiosk() {
        val pin = settings.settingsPin
        if (pin.isEmpty()) {
            performExitKiosk()
            return
        }
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.enter_pin)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.exit_kiosk)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (input.text.toString() == pin) {
                    performExitKiosk()
                } else {
                    Toast.makeText(requireContext(), R.string.wrong_pin, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performExitKiosk() {
        NextcloudPollService.stop(requireContext())
        try {
            activity?.stopLockTask()
        } catch (_: Exception) {
        }
        startActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }
}
