/*
 * Copyright (c) 2024, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.kotlin.ble.android.sample.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.android.sample.view.LabeledSwitch
import no.nordicsemi.kotlin.ble.client.AnyRemoteService
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewRemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewRemoteDescriptor
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewRemoteService
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.util.toShortString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

@Composable
fun DeviceServices(
    services: RemoteServices,
    values: Map<Any, AttributeValue>,
    onAction: (AttributeAction) -> Unit,
) {
    when (services) {
        is RemoteServices.Unknown -> {
            Text(text = "Unknown")
        }
        is RemoteServices.Discovering -> {
            Text(text = "Discovering...")
        }
        is RemoteServices.Discovered -> {
            Column {
                services.services.forEach { service ->
                    Service(service, values, onAction)
                }
            }
        }
        is RemoteServices.Failed -> {
            Text(text = "$services")
        }
    }
}

@Composable
private fun Service(
    service: AnyRemoteService,
    values: Map<Any, AttributeValue>,
    onAction: (AttributeAction) -> Unit,
) {
    Column(
        modifier = Modifier.indent(12.dp, MaterialTheme.colorScheme.primary)
    ) {
        Text(
            text = service.uuid.toShortString(),
            style = MaterialTheme.typography.bodySmall
        )
        if (service.characteristics.isNotEmpty()) {
            service.characteristics.forEach { characteristic ->
                Characteristic(characteristic, values, onAction)

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        if (service.includedServices.isNotEmpty()) {
            service.includedServices.forEach { service ->
                Service(service, values, onAction)

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun Characteristic(
    characteristic: RemoteCharacteristic,
    values: Map<Any, AttributeValue>,
    onAction: (AttributeAction) -> Unit,
) {
    val isNotifying by characteristic.isNotifying.collectAsStateWithLifecycle()
    var showWriteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.indent(12.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = characteristic.uuid.toShortString(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )

            if (characteristic.isReadable()) {
                ActionButton(
                    text = "R",
                    onClick = { onAction(AttributeAction.Read(characteristic)) }
                )
            }

            if (characteristic.isWritable()) {
                ActionButton(
                    text = "W",
                    onClick = {
                        showWriteDialog = true
                    }
                )
            }

            // Mind, that if both are supported, the library enables notifications.
            if (CharacteristicProperty.NOTIFY in characteristic.properties) {
                ActionButton(
                    text = "N",
                    isSelected = isNotifying,
                    onClick = { onAction(AttributeAction.ToggleNotifications(characteristic)) }
                )
            }

            if (CharacteristicProperty.INDICATE in characteristic.properties) {
                ActionButton(
                    text = "I",
                    isSelected = isNotifying,
                    onClick = { onAction(AttributeAction.ToggleNotifications(characteristic)) }
                )
            }
        }

        AttributeValueView(values[characteristic])

        if (showWriteDialog) {
            WriteDialog(
                characteristic = characteristic,
                initialValue = lastUsedWriteValue,
                onDismissRequest = { showWriteDialog = false },
                onWrite = { bytes, writeType, text ->
                    lastUsedWriteValue = text
                    onAction(AttributeAction.Write(characteristic, bytes, writeType))
                }
            )
        }

        if (characteristic.descriptors.isNotEmpty()) {
            characteristic.descriptors.forEach { descriptor ->
                Descriptor(descriptor, values[descriptor], onAction)

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                shape = CircleShape
            )
            .background(
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun Descriptor(
    descriptor: RemoteDescriptor,
    value: AttributeValue?,
    onAction: (AttributeAction) -> Unit,
) {
    Column(
        modifier = Modifier.indent(12.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = descriptor.uuid.toShortString(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )

            ActionButton(
                text = "R",
                onClick = { onAction(AttributeAction.ReadDescriptor(descriptor)) }
            )
        }

        AttributeValueView(value)
    }
}

/**
 * Shows the last received value, the last sent value and the last error of an attribute.
 */
@Composable
private fun AttributeValueView(value: AttributeValue?) {
    value ?: return
    value.received?.let { received ->
        ValueRow(
            symbol = "↙",
            text = received.value.toDisplayString(),
            timestamp = received.timestamp,
            count = value.notificationCount,
        )
    }
    value.sent?.let { sent ->
        ValueRow(
            symbol = "↗",
            text = sent.value.toDisplayString(),
            timestamp = sent.timestamp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    value.error?.let { error ->
        ValueRow(
            symbol = "✕",
            text = error.value,
            timestamp = error.timestamp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ValueRow(
    symbol: String,
    text: String,
    timestamp: Long,
    count: Int = 0,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = symbol, style = style, color = color)
        Text(
            text = text,
            style = style,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = timeFormat.format(Date(timestamp)) + if (count > 0) " (x$count)" else "",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun ByteArray.toDisplayString(): String =
    if (isEmpty()) "(empty)" else toHexString(HexFormat.UpperCase)

/**
 * A modifier that draws a vertical line on the left side of the content.
 */
@Composable
private fun Modifier.indent(strokeWidth: Dp = 12.dp, color: Color): Modifier {
    val density = LocalDensity.current
    val strokeWidthPx = density.run { strokeWidth.toPx() }

    return this then Modifier
        .drawBehind {
            val margin = strokeWidthPx / 8
            val offset = strokeWidthPx / 2

            drawLine(
                color = color,
                start = Offset(x = margin + offset, y = offset),
                end = Offset(x = margin + offset , y = size.height - offset),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )
        }
        .padding(start = strokeWidth * 1.25f)
}

@Preview(showBackground = true)
@Composable
private fun PreviewDeviceServices_discovery() {
    AppTheme {
        DeviceServices(
            services = RemoteServices.Discovering,
            values = emptyMap(),
            onAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewDeviceServices() {
    AppTheme {
        DeviceServices(
            RemoteServices.Discovered(
                services = listOf(
                    PreviewRemoteService(0x1800) {
                        Characteristic(0x2A00, CharacteristicProperty.NOTIFY) {
                            CharacteristicUserDescriptionDescriptor("Example")
                        }
                        Characteristic(0x2A01)
                    },
                    PreviewRemoteService(0x1801),
                    // LED Button Service
                    PreviewRemoteService(
                        uuid = Uuid.parse("00001523-1212-efde-1523-785feabcd123"),
                    ) {
                        // Button Characteristic
                        Characteristic(
                            Uuid.parse("00001524-1212-efde-1523-785feabcd123"),
                            CharacteristicProperty.NOTIFY
                        )
                        // LED Characteristic
                        Characteristic(
                            Uuid.parse("00001525-1212-efde-1523-785feabcd123"),
                            CharacteristicProperty.WRITE_WITHOUT_RESPONSE
                        )
                        // Another LED Button Service inside! What a surprise!
                        IncludedService(
                            uuid = Uuid.parse("00001523-1212-efde-1523-785feabcd123")
                        ) {
                            Characteristic(
                                Uuid.parse("00001524-1212-efde-1523-785feabcd123"),
                                CharacteristicProperty.NOTIFY
                            )
                            Characteristic(
                                Uuid.parse("00001525-1212-efde-1523-785feabcd123"),
                                CharacteristicProperty.WRITE_WITHOUT_RESPONSE
                            )
                        }
                    }
                )
            ),
            values = emptyMap(),
            onAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewCharacteristics() {
    AppTheme {
        val characteristic = PreviewRemoteCharacteristic(
            shortUuid = 0x2A00,
            properties = setOf(
                CharacteristicProperty.READ,
                CharacteristicProperty.WRITE,
                CharacteristicProperty.NOTIFY,
                CharacteristicProperty.INDICATE
            )
        ) {
            CharacteristicUserDescriptionDescriptor("Description")
            ClientCharacteristicConfigurationDescriptor()
            Descriptor(Uuid.random())
        }
        Characteristic(
            characteristic = characteristic,
            values = mapOf(
                characteristic to AttributeValue(
                    received = Timestamped(byteArrayOf(0x01, 0x02, 0x03)),
                    notificationCount = 23,
                    sent = Timestamped(byteArrayOf(0x01)),
                ),
                characteristic.descriptors[0] to AttributeValue(
                    received = Timestamped("Description".encodeToByteArray()),
                ),
                characteristic.descriptors[1] to AttributeValue(
                    sent = Timestamped(byteArrayOf(0x01, 0x00)),
                ),
                characteristic.descriptors[2] to AttributeValue(
                    error = Timestamped("Read not permitted"),
                ),
            ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewDescriptor() {
    AppTheme {
        Descriptor(
            descriptor = PreviewRemoteDescriptor(Uuid.random()),
            value = null,
            onAction = {},
        )
    }
}

private var lastUsedWriteValue = "01"

private data class PresetValue(val label: String, val hex: String)

private val genericPresetValues = listOf(
    PresetValue("0x00", "00"),
    PresetValue("0x01", "01"),
    PresetValue("0xFF", "FF"),
    PresetValue("ASCII \"Hello\"", "48656C6C6F"),
)

private val smpPresetValues = listOf(
    PresetValue("Echo (Hello)",  "0A00000A00000000A161646648656C6C6F21"),
    PresetValue("McuMgr Params", "0800000100000006A0"),
    PresetValue("Memory Pools",  "0800000100000003A0"),
    PresetValue("Reset",         "0A00000100000005A0"),
    PresetValue("Image List",    "0800000100010000A0"),
)

private val smpUuid = Uuid.parse("da2e7828-fbce-4e01-ae9e-261174997c48")
private val RemoteCharacteristic.isSmp: Boolean
    get() = uuid == smpUuid

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WriteDialog(
    characteristic: RemoteCharacteristic,
    initialValue: String,
    onDismissRequest: () -> Unit,
    onWrite: (ByteArray, WriteType, String) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    val defaultResponseRequired = CharacteristicProperty.WRITE in characteristic.properties
    var responseRequired by remember { mutableStateOf(defaultResponseRequired) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = "Write Characteristic")
        },
        text = {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodySmall,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = characteristic.uuid.toShortString(),
                    )

                    OutlinedTextField(
                        value = text,
                        onValueChange = { newValue ->
                            text = newValue.filter { c ->
                                c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
                            }
                        },
                        label = { Text("Value (Hex)") },
                        placeholder = { Text("e.g. 0102 or FF") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Presets",
                        )
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (characteristic.isSmp) {
                                    smpPresetValues.forEach { preset ->
                                        SuggestionChip(
                                            onClick = { text = preset.hex },
                                            label = { Text(preset.label) }
                                        )
                                    }
                                } else {
                                    genericPresetValues.forEach { preset ->
                                        SuggestionChip(
                                            onClick = { text = preset.hex },
                                            label = { Text(preset.label) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    LabeledSwitch(
                        title = "Response required",
                        enabled = true,
                        checked = responseRequired,
                        onCheckedChange = { responseRequired = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val bytes = text.toHexByteArray()
                    val writeType = if (responseRequired) {
                        WriteType.WITH_RESPONSE
                    } else {
                        WriteType.WITHOUT_RESPONSE
                    }
                    onWrite(bytes, writeType, text)
                    onDismissRequest()
                }
            ) {
                Text("Write")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}

private fun String.toHexByteArray(): ByteArray {
    val cleanHex = filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    if (cleanHex.isEmpty()) return byteArrayOf()
    val formatted = if (cleanHex.length % 2 != 0) "0$cleanHex" else cleanHex
    return formatted.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@Preview(showBackground = true)
@Composable
private fun PreviewWriteDialog() {
    AppTheme {
        WriteDialog(
            characteristic = PreviewRemoteCharacteristic(
                shortUuid = 0x2A00,
                properties = setOf(CharacteristicProperty.WRITE)
            ),
            initialValue = "01",
            onDismissRequest = {},
            onWrite = { _, _, _ -> }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWriteDialog_smp() {
    AppTheme {
        WriteDialog(
            characteristic = PreviewRemoteCharacteristic(
                uuid = Uuid.parse("8d53dc1d-e380-4e46-a10a-d157b28b0000"),
                properties = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE)
            ),
            initialValue = "01",
            onDismissRequest = {},
            onWrite = { _, _, _ -> }
        )
    }
}