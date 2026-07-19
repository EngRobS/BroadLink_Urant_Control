package com.example.broadlinkctrl

import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.broadlinkctrl.broadlink.BroadlinkDevice
import com.example.broadlinkctrl.broadlink.BroadlinkManager
import kotlinx.coroutines.launch
import java.net.InetAddress

class ControlFragment : Fragment() {

    private val devices = mutableListOf<BroadlinkDevice>()
    private lateinit var adapter: DeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_control, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnScan = view.findViewById<Button>(R.id.btnScan)
        val status = view.findViewById<TextView>(R.id.textScanStatus)
        val listView = view.findViewById<ListView>(R.id.listDevices)

        adapter = DeviceAdapter()
        listView.adapter = adapter

        btnScan.setOnClickListener {
            status.text = "Scanning… make sure your phone is on the same WiFi as the plugs."
            btnScan.isEnabled = false
            devices.clear()
            adapter.notifyDataSetChanged()

            viewLifecycleOwner.lifecycleScope.launch {
                val localIp = getLocalIpAddress()
                if (localIp == null) {
                    status.text = "Couldn't determine local WiFi IP. Make sure WiFi is on."
                    btnScan.isEnabled = true
                    return@launch
                }
                val found = BroadlinkManager.discover(localIp)
                devices.addAll(found)
                adapter.notifyDataSetChanged()
                status.text = if (found.isEmpty()) {
                    "No devices found. Confirm the plug already joined this WiFi network."
                } else {
                    "Found ${found.size} device(s)."
                }
                btnScan.isEnabled = true
            }
        }
    }

    private fun getLocalIpAddress(): InetAddress? {
        val wifiManager = requireContext().applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ipInt == 0) return null
        val bytes = byteArrayOf(
            (ipInt and 0xff).toByte(),
            (ipInt shr 8 and 0xff).toByte(),
            (ipInt shr 16 and 0xff).toByte(),
            (ipInt shr 24 and 0xff).toByte()
        )
        return InetAddress.getByAddress(bytes)
    }

    private inner class DeviceAdapter : BaseAdapter() {
        override fun getCount() = devices.size
        override fun getItem(position: Int) = devices[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(parent?.context)
                .inflate(R.layout.item_device, parent, false)

            val device = devices[position]
            val title = v.findViewById<TextView>(R.id.textDeviceTitle)
            val subtitle = v.findViewById<TextView>(R.id.textDeviceSubtitle)
            val switchPower = v.findViewById<Switch>(R.id.switchPower)

            title.text = BroadlinkDevice.describeType(device.devType)
            subtitle.text = "${device.ip.hostAddress}  •  ${device.macString}"

            // Avoid firing the listener while we set initial state / during network calls.
            switchPower.setOnCheckedChangeListener(null)
            switchPower.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                val authed = device.auth()
                if (!authed) {
                    subtitle.text = "${subtitle.text}  (auth failed)"
                    return@launch
                }
                val state = device.checkPower()
                switchPower.isChecked = state == true
                switchPower.isEnabled = true

                switchPower.setOnCheckedChangeListener { _, isChecked ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        switchPower.isEnabled = false
                        device.setPower(isChecked)
                        switchPower.isEnabled = true
                    }
                }
            }

            return v
        }
    }
}
