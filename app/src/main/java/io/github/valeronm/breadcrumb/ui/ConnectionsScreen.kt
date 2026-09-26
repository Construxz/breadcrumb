package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.Connection
import io.github.valeronm.breadcrumb.data.ConnectionRepository
import io.github.valeronm.breadcrumb.data.LinkOwner
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.VehicleRepository
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Vehicle
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.VehicleLinkKind
import io.github.valeronm.breadcrumb.domain.placeCategory
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import kotlinx.coroutines.launch

/** A device or network picked on this page, waiting for the user to say what it stands for. */
private data class Pick(val kind: VehicleLinkKind, val key: String, val label: String)

/**
 * Settings → Connections: every Bluetooth device and Wi-Fi network the user tied to a vehicle or a
 * place, in one list, each saying what it stands for. Tapping one moves it to another vehicle or
 * place; adding one here asks where it belongs. The vehicle and place editors add to the same
 * list — this page is where a device is seen across all of them, and the only one that can move it.
 */
@Composable
internal fun ConnectionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connections = remember { ConnectionRepository(context) }
    val all by remember { connections.observeConnections() }.collectAsStateWithLifecycle(emptyList())
    val vehicles by remember { VehicleRepository(context).observeVehicles() }.collectAsStateWithLifecycle(emptyList())
    val places by remember { PlaceRepository(context).observePlaces() }.collectAsStateWithLifecycle(emptyList())
    var assigning by remember { mutableStateOf<Pick?>(null) }
    var current by remember { mutableStateOf<LinkOwner?>(null) }

    fun ownerName(owner: LinkOwner): String? = when (owner) {
        is LinkOwner.OfVehicle -> vehicles.firstOrNull { it.id == owner.vehicleId }?.name
        is LinkOwner.OfPlace -> places.firstOrNull { it.id == owner.placeId }?.label
    }

    fun changed() {
        LocationRecordingService.instance?.refreshVehicleWatch()
    }

    SettingsSubScreen(stringResource(R.string.connections_title), onBack) {
        Text(
            stringResource(R.string.connections_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        if (all.isEmpty()) {
            Text(
                stringResource(R.string.connections_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            GroupedRows(
                *all.sortedBy { it.label.lowercase() }.map<Connection, @Composable () -> Unit> { connection ->
                    {
                        ConnectionRow(
                            connection.kind,
                            connection.label,
                            subtitle = ownerName(connection.owner) ?: kindLabel(connection.kind),
                            onClick = {
                                current = connection.owner
                                assigning = Pick(connection.kind, connection.key, connection.label)
                            },
                        ) {
                            scope.launch {
                                connections.remove(connection.kind, connection.key)
                                changed()
                            }
                        }
                    }
                }.toTypedArray(),
            )
        }
        Spacer(Modifier.height(16.dp))
        ConnectionAdder { kind, key, label ->
            current = all.firstOrNull { it.kind == kind && it.key == key }?.owner
            assigning = Pick(kind, key, label)
        }
    }

    assigning?.let { pick ->
        OwnerDialog(
            title = pick.label,
            vehicles = vehicles,
            places = places,
            current = current,
            onDismiss = { assigning = null },
        ) { owner ->
            assigning = null
            scope.launch {
                connections.assign(pick.kind, pick.key, pick.label, owner)
                changed()
            }
        }
    }
}

/** Which vehicle or place a device or network stands for — vehicles first, then places. */
@Composable
private fun OwnerDialog(
    title: String,
    vehicles: List<Vehicle>,
    places: List<Place>,
    current: LinkOwner?,
    onDismiss: () -> Unit,
    onPick: (LinkOwner) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Link, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.connections_assign), style = MaterialTheme.typography.bodyMedium)
                if (vehicles.isEmpty() && places.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.connections_no_owners))
                }
                if (vehicles.isNotEmpty()) {
                    OwnerHeader(stringResource(R.string.connections_vehicles))
                    for (vehicle in vehicles) {
                        val owner = LinkOwner.OfVehicle(vehicle.id)
                        OptionRow(
                            icon = activityIcon(ActivityType.ofName(vehicle.activityType)),
                            label = vehicle.name,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            selected = current == owner,
                        ) { onPick(owner) }
                    }
                }
                if (places.isNotEmpty()) {
                    OwnerHeader(stringResource(R.string.connections_places))
                    for (place in places.sortedBy { it.label.lowercase() }) {
                        val owner = LinkOwner.OfPlace(place.id)
                        OptionRow(
                            icon = place.placeCategory?.icon ?: Icons.Filled.Place,
                            label = place.label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            selected = current == owner,
                        ) { onPick(owner) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun OwnerHeader(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
