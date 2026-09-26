package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.valeronm.breadcrumb.domain.VehicleLinkKind
import io.github.valeronm.breadcrumb.util.isGranted
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches the two kinds of link a vehicle can be recognised by while the recorder is armed: a
 * Bluetooth device connecting or disconnecting (by address), and the Wi-Fi network the phone is on
 * (by name). It reports every change of either to [onChange] and decides nothing — which of them
 * belong to a vehicle is the recorder's filter, so nothing about another device or network is kept.
 *
 * Both are event-driven and cost nothing between events: a broadcast for Bluetooth and a network
 * callback for Wi-Fi, neither of which scans. On [start] each also reports what is connected right
 * then — the network callback does so by itself, and Bluetooth is asked through the audio profiles
 * a car kit uses — so a recorder armed inside a running car knows it is in one.
 *
 * Reading a network's name needs the location grant the recorder has anyway; Bluetooth needs its
 * own (`BLUETOOTH_CONNECT` from Android 12), asked for where a device is added. Without it the
 * Bluetooth half stays silent and the Wi-Fi half carries on.
 */
class VehicleWatch(
    private val context: Context,
    private val onChange: (kind: VehicleLinkKind, key: String, connected: Boolean) -> Unit,
) {

    private var receiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** The name each Wi-Fi network was reported under, so its loss can be reported by name. Written
     *  on the connectivity thread, cleared on the main one. */
    private val ssids = ConcurrentHashMap<Network, String>()

    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }

    val running: Boolean get() = receiver != null || networkCallback != null

    fun start() {
        startBluetooth()
        startWifi()
    }

    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
        networkCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        networkCallback = null
        ssids.clear()
    }

    // --- Bluetooth ------------------------------------------------------------------------------

    private fun bluetoothGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.isGranted(Manifest.permission.BLUETOOTH_CONNECT)

    @SuppressLint("MissingPermission")
    private fun startBluetooth() {
        if (receiver != null || !bluetoothGranted()) return
        val registered = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                ) ?: return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> onChange(VehicleLinkKind.BLUETOOTH, device.address, true)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onChange(VehicleLinkKind.BLUETOOTH, device.address, false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, registered, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = registered
        reportConnectedBluetooth()
    }

    /** What is connected already: a connection made before the recorder was watching sends no event. */
    @SuppressLint("MissingPermission")
    private fun reportConnectedBluetooth() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            adapter.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices }.getOrNull()?.forEach { device ->
                            onChange(VehicleLinkKind.BLUETOOTH, device.address, true)
                        }
                        adapter.closeProfileProxy(profile, proxy)
                    }

                    override fun onServiceDisconnected(profile: Int) = Unit
                },
                profile,
            )
        }
    }

    // --- Wi-Fi ----------------------------------------------------------------------------------

    private fun startWifi() {
        if (networkCallback != null) return
        val cm = connectivity ?: return
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // From Android 12 a network's name reaches a callback only when it asks for it.
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifi(network, (caps.transportInfo as? WifiInfo)?.ssid)

                override fun onLost(network: Network) = onWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifi(network, legacySsid())

                override fun onLost(network: Network) = onWifiLost(network)
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(request, callback) }.onSuccess { networkCallback = callback }
    }

    @Suppress("DEPRECATION")
    private fun legacySsid(): String? =
        context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid

    private fun onWifi(network: Network, rawSsid: String?) {
        val ssid = ssidOf(rawSsid) ?: return
        val previous = ssids.put(network, ssid)
        if (previous == ssid) return
        previous?.let { onChange(VehicleLinkKind.WIFI, it, false) }
        onChange(VehicleLinkKind.WIFI, ssid, true)
    }

    private fun onWifiLost(network: Network) {
        ssids.remove(network)?.let { onChange(VehicleLinkKind.WIFI, it, false) }
    }

    companion object {
        private const val UNKNOWN_SSID = "<unknown ssid>"

        /**
         * A network's name as the user knows it: the platform wraps a readable one in quotes and
         * reports `<unknown ssid>` when it withholds it (no location grant, location switched off).
         */
        fun ssidOf(raw: String?): String? {
            if (raw == null || raw == UNKNOWN_SSID) return null
            val name = raw.removeSurrounding("\"")
            return name.ifBlank { null }
        }
    }
}
