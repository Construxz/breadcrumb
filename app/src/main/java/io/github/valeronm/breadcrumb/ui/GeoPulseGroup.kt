package io.github.valeronm.breadcrumb.ui

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.geopulse.GeoPulseSettings
import io.github.valeronm.breadcrumb.data.geopulse.GeoPulseUploader
import io.github.valeronm.breadcrumb.data.geopulse.OwnTracksHttp.Failure

/**
 * The GeoPulse connection on the Privacy page, below the online services it joins: a switch, one
 * switch per thing a point may carry beyond its position, the credentials GeoPulse issues an
 * OwnTracks source, and where the upload stands.
 *
 * Every field is written as it is typed, like every other setting here — there is no save step to
 * forget — and the uploader reads them afresh at each pass.
 */
@Composable
internal fun GeoPulseGroup() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(GeoPulseSettings.isEnabled(context)) }
    var shareActivity by remember { mutableStateOf(GeoPulseSettings.shareActivity(context)) }
    var sharePlaces by remember { mutableStateOf(GeoPulseSettings.sharePlaces(context)) }
    val state by remember { GeoPulseUploader.state(context) }.collectAsStateWithLifecycle()
    SettingsGroup(
        stringResource(R.string.geopulse_title),
        stringResource(R.string.geopulse_description),
        emptyList(),
    ) {
        GroupedRows(
            {
                SwitchSettingRow(
                    title = stringResource(R.string.geopulse_enable),
                    subtitle = stringResource(R.string.geopulse_enable_sub),
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        GeoPulseSettings.setEnabled(context, it)
                        GeoPulseUploader.sync(context)
                    },
                )
            },
            {
                SwitchSettingRow(
                    title = stringResource(R.string.geopulse_share_activity),
                    subtitle = stringResource(R.string.geopulse_share_activity_sub),
                    checked = shareActivity,
                    onCheckedChange = {
                        shareActivity = it
                        GeoPulseSettings.setShareActivity(context, it)
                    },
                )
            },
            {
                SwitchSettingRow(
                    title = stringResource(R.string.geopulse_share_places),
                    subtitle = stringResource(R.string.geopulse_share_places_sub),
                    checked = sharePlaces,
                    onCheckedChange = {
                        sharePlaces = it
                        GeoPulseSettings.setSharePlaces(context, it)
                    },
                )
            },
            {
                Column {
                    PrefField(
                        R.string.geopulse_server,
                        GeoPulseSettings.server(context),
                        KeyboardType.Uri,
                    ) { GeoPulseSettings.setServer(context, it) }
                    PrefField(
                        R.string.geopulse_username,
                        GeoPulseSettings.username(context),
                        KeyboardType.Text,
                    ) { GeoPulseSettings.setUsername(context, it) }
                    PrefField(
                        R.string.geopulse_password,
                        GeoPulseSettings.password(context),
                        KeyboardType.Password,
                        secret = true,
                    ) { GeoPulseSettings.setPassword(context, it) }
                    PrefField(
                        R.string.geopulse_device,
                        GeoPulseSettings.deviceId(context),
                        KeyboardType.Text,
                        placeholder = GeoPulseSettings.DEFAULT_DEVICE_ID,
                    ) { GeoPulseSettings.setDeviceId(context, it) }
                }
            },
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        statusText(enabled, state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled && state.failure != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { GeoPulseUploader.sync(context) },
                        enabled = enabled && !state.sending,
                    ) {
                        Text(stringResource(R.string.geopulse_send_now))
                    }
                }
            },
        )
    }
}

@Composable
private fun PrefField(
    @StringRes label: Int,
    initial: String,
    keyboard: KeyboardType,
    secret: Boolean = false,
    placeholder: String? = null,
    save: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            // One line whatever is pasted: a break in a URL or a credential is never meant.
            value = it.replace("\n", "").replace("\r", "")
            save(value)
        },
        singleLine = true,
        label = { Text(stringResource(label)) },
        placeholder = if (placeholder != null) {
            { Text(placeholder) }
        } else {
            null
        },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun statusText(enabled: Boolean, state: GeoPulseUploader.State): String {
    val context = LocalContext.current
    return when {
        !enabled -> stringResource(R.string.geopulse_status_off)
        state.sending -> stringResource(R.string.geopulse_status_sending)
        else -> when (val failure = state.failure) {
            Failure.NotConfigured -> stringResource(R.string.geopulse_failure_not_configured)
            Failure.Unauthorized -> stringResource(R.string.geopulse_failure_unauthorized)
            Failure.Unreachable -> stringResource(R.string.geopulse_failure_unreachable)
            is Failure.Http -> stringResource(R.string.geopulse_failure_http, failure.status)
            null -> stringResource(
                R.string.geopulse_status_sent_through,
                DateUtils.formatDateTime(
                    context,
                    state.sentThroughMs,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                ),
            )
        }
    }
}
