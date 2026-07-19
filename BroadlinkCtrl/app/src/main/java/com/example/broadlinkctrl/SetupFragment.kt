package com.example.broadlinkctrl

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.broadlinkctrl.broadlink.BroadlinkManager
import kotlinx.coroutines.launch

class SetupFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnOpenWifi = view.findViewById<Button>(R.id.btnOpenWifiSettings)
        val btnProvision = view.findViewById<Button>(R.id.btnProvision)
        val editSsid = view.findViewById<EditText>(R.id.editSsid)
        val editPassword = view.findViewById<EditText>(R.id.editPassword)
        val spinnerSecurity = view.findViewById<Spinner>(R.id.spinnerSecurity)
        val status = view.findViewById<TextView>(R.id.textStatus)

        val securityOptions = listOf("None (open)", "WEP", "WPA1", "WPA2", "WPA1/WPA2")
        spinnerSecurity.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, securityOptions
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerSecurity.setSelection(3) // default WPA2, the common case

        btnOpenWifi.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        btnProvision.setOnClickListener {
            val ssid = editSsid.text.toString()
            val password = editPassword.text.toString()
            val securityMode = spinnerSecurity.selectedItemPosition

            if (ssid.isBlank()) {
                status.text = "Enter your home WiFi network name first."
                return@setOnClickListener
            }

            status.text = "Sending credentials… make sure you're still connected to the plug's own WiFi network."
            btnProvision.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                val ok = BroadlinkManager.provision(ssid, password, securityMode)
                status.text = if (ok) {
                    "Sent. The plug's LED should turn solid within ~10–20 seconds once it joins your WiFi. " +
                        "Then reconnect your phone to your normal home WiFi and use the Control tab to find it."
                } else {
                    "Failed to send — check you're connected to the plug's network and try again."
                }
                btnProvision.isEnabled = true
            }
        }
    }
}
