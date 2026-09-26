package io.github.valeronm.breadcrumb.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.VehicleLinkKind
import io.github.valeronm.breadcrumb.location.VehicleWatch
import io.github.valeronm.breadcrumb.util.isGranted

/**
 * The ways to add a device or network — a paired Bluetooth device, the Wi-Fi the phone is on, or
 * one typed by name — shared by the vehicle editor, the place editor and Settings → Connections,
 * so the three add alike. What becomes of the pick is [onAdd]'s.
 */
@Composable
internal fun ConnectionAdder(onAdd: (kind: VehicleLinkKind, key: String, label: String) -> Unit) {
    val context = LocalContext.current
    var pickingDevice by remember { mutableStateOf(false) }
    var typingNetwork by remember { mutableStateOf(false) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickingDevice = true
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                if (bluetoothGranted(context)) {
                    pickingDevice = true
                } else {
                    askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
        ) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.connections_add_bluetooth))
        }
        OutlinedButton(
            onClick = {
                val ssid = currentSsid(context)
                if (ssid == null) {
                    Toast.makeText(context, R.string.connections_no_wifi, Toast.LENGTH_LONG).show()
                } else {
                    onAdd(VehicleLinkKind.WIFI, ssid, ssid)
                }
            },
        ) {
            Icon(Icons.Filled.Wifi, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.connections_add_current_wifi))
        }
        TextButton(onClick = { typingNetwork = true }) {
            Text(stringResource(R.string.connections_add_wifi_by_name))
        }
    }

    if (pickingDevice) {
        BluetoothDeviceDialog(onDismiss = { pickingDevice = false }) { address, label ->
            pickingDevice = false
            onAdd(VehicleLinkKind.BLUETOOTH, address, label)
        }
    }
    if (typingNetwork) {
        TextEntryDialog(
            title = stringResource(R.string.connections_add_wifi_by_name),
            label = stringResource(R.string.connections_wifi_name),
            onDismiss = { typingNetwork = false },
        ) { ssid ->
            typingNetwork = false
            onAdd(VehicleLinkKind.WIFI, ssid, ssid)
        }
    }
}

/**
 * One device or network: its kind's glyph, its name, and under it [subtitle] — the kind, or on
 * Settings → Connections what it stands for. [onClick] opens it where there is somewhere to go.
 */
@Composable
internal fun ConnectionRow(
    kind: VehicleLinkKind?,
    label: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    onRemove: () -> Unit,
) {
    Row(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            kindIcon(kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.connections_remove_link))
        }
    }
}

internal fun kindIcon(kind: VehicleLinkKind?): ImageVector =
    if (kind == VehicleLinkKind.BLUETOOTH) Icons.Filled.Bluetooth else Icons.Filled.Wifi

@Composable
internal fun kindLabel(kind: VehicleLinkKind?): String = stringResource(
    if (kind == VehicleLinkKind.BLUETOOTH) R.string.connections_kind_bluetooth else R.string.connections_kind_wifi,
)

/** The phone's paired devices, to pick one from. Pairing itself is the system's job. */
@SuppressLint("MissingPermission")
@Composable
private fun BluetoothDeviceDialog(onDismiss: () -> Unit, onPick: (address: String, label: String) -> Unit) {
    val context = LocalContext.current
    val devices = remember {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !bluetoothGranted(context)) {
            emptyList()
        } else {
            runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
                .map { it.address to (it.name?.takeIf(String::isNotBlank) ?: it.address) }
                .sortedBy { it.second.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
        title = { Text(stringResource(R.string.connections_add_bluetooth)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.connections_no_paired_devices))
                }
                for ((address, label) in devices) {
                    OptionRow(
                        icon = Icons.Filled.Bluetooth,
                        label = label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { onPick(address, label) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun TextEntryDialog(title: String, label: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\n", " ") },
                singleLine = true,
                label = { Text(label) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.connections_confirm_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private fun bluetoothGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.isGranted(Manifest.permission.BLUETOOTH_CONNECT)

/** The Wi-Fi the phone is on, by name, or null when there is none or its name is withheld. */
@Suppress("DEPRECATION")
private fun currentSsid(context: Context): String? =
    VehicleWatch.ssidOf(context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid)
