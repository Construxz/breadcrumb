package io.github.valeronm.breadcrumb.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    val type = ActivityType.ofName(vehicle.activityType)

    fun addLink(kind: VehicleLinkKind, key: String, label: String) {
        scope.launch {
            if (repository.addLink(vehicle.id, kind, key, label)) {
                LocationRecordingService.instance?.refreshVehicleWatch()
            } else {
                Toast.makeText(context, R.string.connections_link_taken, Toast.LENGTH_LONG).show()
            }
        }
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
                    {
                        val kind = VehicleLinkKind.fromCode(link.kind)
                        ConnectionRow(kind, link.label, kindLabel(kind)) {
                            scope.launch { repository.removeLink(link.id) }
                        }
                    }
                }.toTypedArray(),
            )
            Spacer(Modifier.height(8.dp))
        }
        ConnectionAdder(::addLink)
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
