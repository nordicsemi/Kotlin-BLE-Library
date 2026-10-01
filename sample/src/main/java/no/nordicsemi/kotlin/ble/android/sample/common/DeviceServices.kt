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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.android.sample.view.LabeledSwitch
import no.nordicsemi.kotlin.ble.client.AnyRemoteService
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.RemoteIncludedService
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewRemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewRemoteDescriptor
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewRemoteService
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
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
            Column(
                verticalArrangement = Arrangement.spacedBy(CARD_SPACING),
            ) {
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
    var expanded by rememberSaveable(service.uuid.toString(), service.instanceId) {
        mutableStateOf(service.uuid !in collapsedByDefault)
    }
    val type = if (service is RemoteIncludedService) "Included service" else "Primary service"

    AttributeCard(level = AttributeLevel.SERVICE) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded },
        ) {
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 0f else -90f,
                label = "chevron rotation",
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotation),
            )
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = service.uuid.toShortString(),
                    style = uuidTextStyle,
                )
                Text(
                    text = listOfNotNull(service.uuid.name, type).joinToString(" · "),
                    style = labelTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(CARD_SPACING),
                modifier = Modifier.padding(top = CARD_SPACING),
            ) {
                if (service.characteristics.isEmpty() && service.includedServices.isEmpty()) {
                    Text(
                        text = "Empty service",
                        style = labelTextStyle,
                        color = mutedColor,
                        modifier = Modifier.padding(start = 24.dp, bottom = 2.dp),
                    )
                }
                service.characteristics.forEach { characteristic ->
                    Characteristic(characteristic, values, onAction)
                }
                service.includedServices.forEach { includedService ->
                    Service(includedService, values, onAction)
                }
            }
        }
    }
}

@Composable
private fun Characteristic(
    characteristic: RemoteCharacteristic,
    values: Map<Any, AttributeValue>,
    onAction: (AttributeAction) -> Unit,
) {
    val isNotifying by characteristic.isNotifying.collectAsStateWithLifecycle()
    var showWriteDialog by remember { mutableStateOf(false) }

    AttributeCard(level = AttributeLevel.CHARACTERISTIC) {
        AttributeHeader(
            uuid = characteristic.uuid,
            type = "Characteristic",
        ) {
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

        AttributeValueView(
            value = values[characteristic],
            showReceived = characteristic.isReadable() || characteristic.isSubscribable(),
            showSent = characteristic.isWritable(),
        )

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

        characteristic.descriptors.forEach { descriptor ->
            Spacer(modifier = Modifier.height(CARD_SPACING))
            Descriptor(descriptor, values[descriptor], onAction)
        }
    }
}

/**
 * The level of an attribute in the GATT hierarchy, defining how its card looks.
 */
private enum class AttributeLevel {
    SERVICE,
    CHARACTERISTIC,
    DESCRIPTOR,
}

/**
 * A card with a vertical bar on the left, used to show a service, a characteristic
 * or a descriptor.
 */
@Composable
private fun AttributeCard(
    level: AttributeLevel,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val barColor = when (level) {
        AttributeLevel.SERVICE -> colorScheme.primary
        AttributeLevel.CHARACTERISTIC -> colorScheme.primary.copy(alpha = 0.6f)
        AttributeLevel.DESCRIPTOR -> colorScheme.primary.copy(alpha = 0.35f)
    }
    // Surface tones are used instead of tints of the primary color, as with dynamic colors
    // the primary color may be close to gray, making the levels hard to distinguish.
    // The value well uses surfaceContainerHighest.
    val containerColor = when (level) {
        AttributeLevel.SERVICE -> colorScheme.surfaceContainerHigh
        AttributeLevel.CHARACTERISTIC -> colorScheme.surface
        AttributeLevel.DESCRIPTOR -> colorScheme.surfaceContainer
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .drawBehind {
                drawRect(
                    color = barColor,
                    size = Size(BAR_WIDTH.toPx(), size.height),
                )
            }
            .padding(start = BAR_WIDTH + 6.dp, top = 4.dp, end = 4.dp, bottom = 6.dp),
        content = content,
    )
}

/**
 * The UUID of a characteristic or descriptor, with its name or type and actions below.
 */
@Composable
private fun AttributeHeader(
    uuid: Uuid,
    type: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Text(
        text = uuid.toShortString(),
        style = uuidTextStyle,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.heightIn(min = 28.dp),
    ) {
        Text(
            text = uuid.name ?: type,
            style = labelTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** Services, which are collapsed by default, as they are rarely interesting when testing. */
private val collapsedByDefault = listOf(
    Uuid.fromShortUuid(0x1800), // Generic Access
    Uuid.fromShortUuid(0x1801), // Generic Attribute
)

private val BAR_WIDTH = 5.dp
private val CARD_SPACING = 6.dp

private val uuidTextStyle: TextStyle
    @Composable
    get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

private val labelTextStyle: TextStyle
    @Composable
    get() = MaterialTheme.typography.labelSmall

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
    AttributeCard(level = AttributeLevel.DESCRIPTOR) {
        AttributeHeader(
            uuid = descriptor.uuid,
            type = "Descriptor",
        ) {
            ActionButton(
                text = "R",
                onClick = { onAction(AttributeAction.ReadDescriptor(descriptor)) }
            )
        }

        AttributeValueView(
            value = value,
            showReceived = descriptor.isReadable(),
            // Descriptors can't be written from the UI, but the CCCD is written when
            // notifications or indications are enabled or disabled.
            showSent = descriptor.isWritable(),
        )
    }
}

/**
 * Shows the last received value, the last sent value and the last error of an attribute.
 */
@Composable
private fun AttributeValueView(
    value: AttributeValue?,
    showReceived: Boolean,
    showSent: Boolean,
) {
    if (!showReceived && !showSent && value?.error == null) return

    Column(
        modifier = Modifier
            .padding(top = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        AttributeValueRows(value, showReceived, showSent)
    }
}

@Composable
private fun AttributeValueRows(
    value: AttributeValue?,
    showReceived: Boolean,
    showSent: Boolean,
) {
    if (showReceived) {
        ValueRow(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Received",
            value = value?.received,
            count = value?.notificationCount ?: 0,
        )
    }
    if (showSent) {
        ValueRow(
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Sent",
            value = value?.sent,
        )
    }
    value?.error?.let { error ->
        ValueRow(
            icon = Icons.Default.Close,
            contentDescription = "Error",
            timestamp = error.timestamp,
            color = MaterialTheme.colorScheme.error,
        ) {
            Text(
                text = error.value,
                style = valueTextStyle,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ValueRow(
    icon: ImageVector,
    contentDescription: String,
    value: Timestamped<ByteArray>?,
    count: Int = 0,
) {
    ValueRow(
        icon = icon,
        contentDescription = contentDescription,
        timestamp = value?.timestamp,
        count = count,
    ) {
        val bytes = value?.value
        when {
            bytes == null -> Text(
                text = "No value",
                style = valueTextStyle,
                color = mutedColor,
            )
            bytes.isEmpty() -> Text(
                text = "Empty",
                style = valueTextStyle,
                color = mutedColor,
            )
            else -> Text(
                text = bytes.toHexDump(),
                style = valueTextStyle,
            )
        }
    }
}

/**
 * A row with an icon, the content and, if they fit, the notification count and timestamp.
 */
@Composable
private fun ValueRow(
    icon: ImageVector,
    contentDescription: String,
    timestamp: Long?,
    count: Int = 0,
    color: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    val style = valueTextStyle
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The width of a full line of a value. The timestamp is shown only if it fits next to it,
    // so that it is shown, or not, in all rows.
    val minContentWidth = remember(style, density) {
        textMeasurer.measure(FULL_LINE_SAMPLE, style).size.width +
                with(density) { (ICON_SIZE + ICON_GAP).roundToPx() }
    }
    ValueLayout(
        minStartWidth = minContentWidth,
        start = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ICON_GAP),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = color,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(ICON_SIZE),
                )
                content()
            }
        },
        end = {
            if (count > 0) {
                Text(text = "(x$count)", style = style, color = mutedColor)
            }
        },
        optionalEnd = {
            if (timestamp != null) {
                Text(text = timeFormat.format(Date(timestamp)), style = style, color = mutedColor)
            }
        },
    )
}

/**
 * Places [start] on the left and [end] followed by [optionalEnd] on the right.
 *
 * The [optionalEnd] is omitted if it doesn't fit next to [start], assuming it's at least
 * [minStartWidth] wide.
 */
@Composable
private fun ValueLayout(
    minStartWidth: Int,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    optionalEnd: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Layout(
        contents = listOf(start, end, optionalEnd),
        modifier = modifier,
    ) { (startMeasurables, endMeasurables, optionalEndMeasurables), constraints ->
        val maxWidth = constraints.maxWidth
        val loose = Constraints(maxWidth = maxWidth)

        val endPlaceable = endMeasurables.firstOrNull()?.measure(loose)
        val optionalEndPlaceable = optionalEndMeasurables.firstOrNull()?.measure(loose)
        val endWidth = endPlaceable?.let { it.width + gap } ?: 0
        val optionalEndWidth = optionalEndPlaceable?.let { it.width + gap } ?: 0

        val showOptionalEnd = optionalEndPlaceable != null &&
                minStartWidth + endWidth + optionalEndWidth <= maxWidth
        val startMaxWidth = maxWidth - endWidth - if (showOptionalEnd) optionalEndWidth else 0
        val startPlaceable = startMeasurables.first()
            .measure(loose.copy(maxWidth = startMaxWidth.coerceAtLeast(0)))

        val height = maxOf(
            startPlaceable.height,
            endPlaceable?.height ?: 0,
            if (showOptionalEnd) optionalEndPlaceable.height else 0,
        )
        layout(maxWidth, height) {
            startPlaceable.place(0, 0)
            var x = maxWidth
            if (showOptionalEnd) {
                x -= optionalEndPlaceable.width
                optionalEndPlaceable.place(x, 0)
                x -= gap
            }
            endPlaceable?.place(x - endPlaceable.width, 0)
        }
    }
}

private val ICON_SIZE = 12.dp
private val ICON_GAP = 4.dp

/** Number of bytes shown in a single line. */
private const val BYTES_PER_LINE = 8

/** A sample of a full line, used to measure its width. */
private const val FULL_LINE_SAMPLE = "0000 0000 0000 0000 | 00000000"

private val valueTextStyle: TextStyle
    @Composable
    get() = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

private val mutedColor: Color
    @Composable
    get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/**
 * Formats the bytes as lines of [BYTES_PER_LINE] bytes, with bytes in pairs,
 * followed by the text decoded using UTF-8, for example: `4865 6C6C 6F   | Hello`.
 *
 * Bytes that are not printable, or are not valid UTF-8, are shown as dots.
 */
private fun ByteArray.toHexDump(): String {
    val cells = toPrintableCells()
    val hexWidth = BYTES_PER_LINE * 2 + (BYTES_PER_LINE / 2 - 1)
    return indices.chunked(BYTES_PER_LINE).joinToString("\n") { line ->
        val hex = line.chunked(2).joinToString(" ") { pair ->
            pair.joinToString("") { "%02X".format(this[it]) }
        }
        val text = line.joinToString("") { cells[it] }
        "${hex.padEnd(hexWidth)} | $text"
    }
}

/**
 * Decodes the bytes using UTF-8.
 *
 * Returns an array of the same size, where each item is the character starting at that byte,
 * an empty string for the following bytes of a multi-byte character, or a dot for bytes
 * that are not printable, or not valid UTF-8.
 */
private fun ByteArray.toPrintableCells(): Array<String> {
    val cells = Array(size) { "." }
    var i = 0
    while (i < size) {
        val lead = this[i].toInt() and 0xFF
        val length = when (lead) {
            in 0x00..0x7F -> 1
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> 0
        }
        val isValid = length > 0 && i + length <= size &&
                (1 until length).all { (this[i + it].toInt() and 0xC0) == 0x80 }
        if (!isValid) {
            i++
            continue
        }
        val char = String(this, i, length, Charsets.UTF_8)
        val codePoint = char.codePointAt(0)
        // Overlong sequences and surrogates are decoded as replacement characters.
        if (char.codePointCount(0, char.length) == 1 && codePoint != 0xFFFD &&
            !Character.isISOControl(codePoint)) {
            cells[i] = char
            for (k in 1 until length) cells[i + k] = ""
        }
        i += length
    }
    return cells
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
                    received = Timestamped("Hello, Zażółć gęślą jaźń!".encodeToByteArray()),
                    notificationCount = 23,
                    sent = Timestamped(byteArrayOf(0x01, 0x02, 0x03, 0x00, 0x7F)),
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