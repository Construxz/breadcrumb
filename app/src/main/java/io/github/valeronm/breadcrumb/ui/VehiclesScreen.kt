package io.github.valeronm.breadcrumb.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.VehicleRepository
import io.github.valeronm.breadcrumb.data.db.Vehicle
import io.github.valeronm.breadcrumb.data.db.VehicleLink
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.VehicleLinkKind
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import io.github.valeronm.breadcrumb.location.VehicleWatch
import io.github.valeronm.breadcrumb.util.isGranted
import kotlinx.coroutines.launch

/** The types a vehicle can be, in the order offered: what carries someone, not how they walk. */
private val VEHICLE_TYPES = listOf(
    ActivityType.DRIVING,
    ActivityType.CYCLING,
    ActivityType.TRANSIT,
    ActivityType.FERRY,
    ActivityType.TAXI,
    ActivityType.FLIGHT,
)

/**
 * Settings → Vehicles: the vehicles the user travels in and what the phone recognises each by. A
 * trip made with one of them connected for most of it finishes as that vehicle's type and names
 * it. The list and one vehicle's editor share this layer — the editor is a step into the list, not
 * a screen of its own — so back leaves the editor first.
 */
@Composable
internal fun VehiclesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { VehicleRepository(context) }
    val vehicles by remember { repository.observeVehicles() }.collectAsStateWithLifecycle(emptyList())
    val links by remember { repository.observeLinks() }.collectAsStateWithLifecycle(emptyList())
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = vehicles.firstOrNull { it.id == editingId }

    BackHandler(enabled = editingId != null) { editingId = null }
    if (editing != null) {
        VehicleEditor(
            vehicle = editing,
            links = links.filter { it.vehicleId == editing.id },
            repository = repository,
            onBack = { editingId = null },
        )
    } else {
        VehicleList(vehicles, links, repository, onBack, onOpen = { editingId = it })
    }
}

@Composable
private fun VehicleList(
    vehicles: List<Vehicle>,
    links: List<VehicleLink>,
    repository: VehicleRepository,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    SettingsSubScreen(stringResource(R.string.vehicles_title), onBack) {
        Text(
            stringResource(R.string.vehicles_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        if (vehicles.isNotEmpty()) {
            GroupedRows(
                *vehicles.map<Vehicle, @Composable () -> Unit> { vehicle ->
                    {
                        val type = ActivityType.ofName(vehicle.activityType)
                        val count = links.count { it.vehicleId == vehicle.id }
                        NavRow(
                            vehicle.name,
                            subtitle = if (count == 0) {
                                stringResource(R.string.vehicles_no_links)
                            } else {
                                pluralStringResource(R.plurals.vehicles_link_count, count, count)
                            },
                            icon = activityIcon(type),
                        ) { onOpen(vehicle.id) }
                    }
                }.toTypedArray(),
            )
            Spacer(Modifier.height(16.dp))
        }
        OutlinedButton(onClick = { naming = true }) {
            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.vehicles_add))
        }
    }
    if (naming) {
        TextEntryDialog(
            title = stringResource(R.string.vehicles_add),
            label = stringResource(R.string.vehicles_name),
            onDismiss = { naming = false },
        ) { name ->
            naming = false
            scope.launch { onOpen(repository.create(name, ActivityType.DRIVING)) }
        }
    }
}

@Composable
private fun VehicleEditor(
    vehicle: Vehicle,
    links: List<VehicleLink>,
    repository: VehicleRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(vehicle.id) { mutableStateOf(vehicle.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingDevice by remember { mutableStateOf(false) }
    var typingNetwork by remember { mutableStateOf(false) }
    val type = ActivityType.ofName(vehicle.activityType)

    fun addLink(kind: VehicleLinkKind, key: String, label: String) {
        scope.launch {
            if (repository.addLink(vehicle.id, kind, key, label)) {
                LocationRecordingService.instance?.refreshVehicleWatch()
            } else {
                Toast.makeText(context, R.string.vehicles_link_taken, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickingDevice = true
    }

    SettingsSubScreen(
        vehicle.name,
        onBack,
        actions = {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.vehicles_delete))
            }
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { typed ->
                name = typed.replace("\n", " ")
                if (name.isNotBlank()) scope.launch { repository.update(vehicle.id, name.trim(), type ?: ActivityType.DRIVING) }
            },
            singleLine = true,
            label = { Text(stringResource(R.string.vehicles_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.vehicles_type), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in VEHICLE_TYPES) {
                FilterChip(
                    selected = type == option,
                    onClick = { scope.launch { repository.update(vehicle.id, vehicle.name, option) } },
                    label = { Text(stringResource(option.labelRes)) },
                    leadingIcon = { Icon(activityIcon(option), contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.vehicles_links), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.vehicles_links_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (links.isNotEmpty()) {
            GroupedRows(
                *links.map<VehicleLink, @Composable () -> Unit> { link ->
                    { LinkRow(link) { scope.launch { repository.removeLink(link.id) } } }
                }.toTypedArray(),
            )
            Spacer(Modifier.height(8.dp))
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
                Text(stringResource(R.string.vehicles_add_bluetooth))
            }
            OutlinedButton(
                onClick = {
                    val ssid = currentSsid(context)
                    if (ssid == null) {
                        Toast.makeText(context, R.string.vehicles_no_wifi, Toast.LENGTH_LONG).show()
                    } else {
                        addLink(VehicleLinkKind.WIFI, ssid, ssid)
                    }
                },
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.vehicles_add_current_wifi))
            }
            TextButton(onClick = { typingNetwork = true }) {
                Text(stringResource(R.string.vehicles_add_wifi_by_name))
            }
        }
    }

    if (pickingDevice) {
        BluetoothDeviceDialog(
            onDismiss = { pickingDevice = false },
        ) { address, label ->
            pickingDevice = false
            addLink(VehicleLinkKind.BLUETOOTH, address, label)
        }
    }
    if (typingNetwork) {
        TextEntryDialog(
            title = stringResource(R.string.vehicles_add_wifi_by_name),
            label = stringResource(R.string.vehicles_wifi_name),
            onDismiss = { typingNetwork = false },
        ) { ssid ->
            typingNetwork = false
            addLink(VehicleLinkKind.WIFI, ssid, ssid)
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            icon = Icons.Filled.Delete,
            title = stringResource(R.string.vehicles_delete),
            text = stringResource(R.string.vehicles_delete_text, vehicle.name),
            confirmLabel = stringResource(R.string.vehicles_delete_confirm),
            onConfirm = {
                confirmDelete = false
                onBack()
                scope.launch {
                    repository.delete(vehicle.id)
                    LocationRecordingService.instance?.refreshVehicleWatch()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun LinkRow(link: VehicleLink, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val kind = VehicleLinkKind.fromCode(link.kind)
        Icon(
            if (kind == VehicleLinkKind.BLUETOOTH) Icons.Filled.Bluetooth else Icons.Filled.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(link.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (kind == VehicleLinkKind.BLUETOOTH) R.string.vehicles_kind_bluetooth else R.string.vehicles_kind_wifi,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.vehicles_remove_link))
        }
    }
}

/** The phone's paired devices, to pick the vehicle's from. Pairing itself is the system's job. */
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
        title = { Text(stringResource(R.string.vehicles_add_bluetooth)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.vehicles_no_paired_devices))
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
private fun TextEntryDialog(title: String, label: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
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
                Text(stringResource(R.string.vehicles_confirm_add))
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
