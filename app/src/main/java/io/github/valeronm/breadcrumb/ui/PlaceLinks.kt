package io.github.valeronm.breadcrumb.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.ConnectionRepository
import io.github.valeronm.breadcrumb.data.LinkOwner
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The place editor's row for the devices and networks that stand for this place — its Wi-Fi,
 * typically — opening a dialog to add and remove them. One row rather than the list itself: the
 * editor's column is a full-height map, and each line of controls here is a line taken from it.
 *
 * Unlike everything else on that screen this writes at once rather than on Done: a link is its own
 * row in its own table and moves no seed, so there is no re-derivation to batch, and the device
 * picked is the thing the user just chose. A device another vehicle or place already has is refused
 * with a pointer to Settings → Connections, the one screen that moves it.
 */
@Composable
internal fun PlaceLinksCard(placeId: Long) {
    val context = LocalContext.current
    val repository = remember { ConnectionRepository(context) }
    val owner = LinkOwner.OfPlace(placeId)
    val links by remember(placeId) {
        repository.observeConnections().map { all -> all.filter { it.owner == owner } }
    }.collectAsStateWithLifecycle(emptyList())
    var open by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.connections_place_links), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (links.isEmpty()) {
                        stringResource(R.string.connections_place_links_none)
                    } else {
                        pluralStringResource(R.plurals.connections_place_link_count, links.size, links.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }

    if (open) {
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(Icons.Filled.Link, contentDescription = null) },
            title = { Text(stringResource(R.string.connections_place_links)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.connections_place_links_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    for (link in links) {
                        ConnectionRow(link.kind, link.label, kindLabel(link.kind)) {
                            scope.launch {
                                repository.remove(link.kind, link.key)
                                LocationRecordingService.instance?.refreshVehicleWatch()
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ConnectionAdder { kind, key, label ->
                        scope.launch {
                            if (repository.add(kind, key, label, owner)) {
                                LocationRecordingService.instance?.refreshVehicleWatch()
                            } else {
                                Toast.makeText(context, R.string.connections_link_taken, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.connections_done)) }
            },
        )
    }
}
