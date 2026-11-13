/*
 * Copyright (c) 2026, Nordic Semiconductor
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


package no.nordicsemi.kotlin.ble.android.sample.proximity

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.android.sample.theme.Nordic
import no.nordicsemi.kotlin.ble.client.ProximityChanged.Proximity
import kotlin.math.cos
import kotlin.math.sin

/** The outer edges of the zones on the radar, as a fraction of its radius. */
private const val IMMEDIATE_EDGE = 0.36f
private const val NEAR_EDGE = 0.68f
private const val FAR_EDGE = 1f

/** The distance of a device from the center of the radar, as a fraction of its radius. */
private fun Proximity.radius(): Float = when (this) {
    Proximity.IMMEDIATE -> IMMEDIATE_EDGE * 0.5f
    Proximity.NEAR -> (IMMEDIATE_EDGE + NEAR_EDGE) / 2
    Proximity.FAR -> (NEAR_EDGE + FAR_EDGE) / 2
    // The proximity is not known yet, show the device on the rim.
    Proximity.UNKNOWN -> FAR_EDGE
}

private fun Proximity.label(): String = when (this) {
    Proximity.IMMEDIATE -> "Immediate"
    Proximity.NEAR -> "Near"
    Proximity.FAR -> "Far"
    Proximity.UNKNOWN -> "Unknown"
}

private fun Proximity.color(): Color = when (this) {
    Proximity.IMMEDIATE -> Nordic.Color.Green
    Proximity.NEAR -> Nordic.Color.Fall
    Proximity.FAR -> Nordic.Color.Red
    Proximity.UNKNOWN -> Nordic.Color.MiddleGrey
}

@Composable
fun ProximityView(
    devices: List<ProximityDevice>,
    selected: Set<String>,
    isMonitoring: Boolean,
    onDeviceClicked: (String) -> Unit,
    onClearSelectionRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        val radar = @Composable { radarModifier: Modifier ->
            RadarCard(devices = devices, selected = selected, isMonitoring = isMonitoring, modifier = radarModifier)
        }
        val list = @Composable { listModifier: Modifier ->
            DevicesCard(
                devices = devices,
                selected = selected,
                isMonitoring = isMonitoring,
                onDeviceClicked = onDeviceClicked,
                onClearSelectionRequested = onClearSelectionRequested,
                modifier = listModifier,
            )
        }

        val radarMaxHeight = min(maxWidth, maxHeight * 0.55f)
        if (maxWidth > maxHeight) {
            // Landscape: the radar on the left, the list on the right.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                radar(Modifier.aspectRatio(1f).fillMaxHeight())
                list(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                radar(Modifier.fillMaxWidth().heightIn(max = radarMaxHeight).aspectRatio(1f).align(Alignment.CenterHorizontally))
                list(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun RadarCard(
    devices: List<ProximityDevice>,
    selected: Set<String>,
    isMonitoring: Boolean,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Radar(
            devices = devices,
            selected = selected,
            isMonitoring = isMonitoring,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}

/**
 * A radar showing devices in three zones around the center, where the monitoring device is.
 *
 * Devices move between the zones when their proximity changes. The direction is arbitrary.
 */
@Composable
private fun Radar(
    devices: List<ProximityDevice>,
    selected: Set<String>,
    isMonitoring: Boolean,
    modifier: Modifier = Modifier,
) {
    // Text is measured once, not on every frame: the radar is redrawn all the time (the sonar pulse),
    // and measuring text of many devices on each frame blocks the UI thread. The default cache
    // of the measurer is too small for that, too.
    val textMeasurer = rememberTextMeasurer()
    val maxNameWidth = with(LocalDensity.current) { 96.dp.roundToPx() }
    val zoneColor = MaterialTheme.colorScheme.onSurface
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val pulseColor = MaterialTheme.colorScheme.primary
    // When some devices are selected, the others are grey, like in the RSSI graph.
    val greyedOutColor = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val nameStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)

    // A sonar pulse expanding from the center while monitoring.
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseProgress by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 3000, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseProgress",
    )

    // Animated position of each device. The animations are kept per device (key), so a device
    // glides to its new zone, and fades in when it enters.
    val zoneLabels = remember(labelStyle) {
        listOf(Proximity.IMMEDIATE, Proximity.NEAR, Proximity.FAR).associateWith { textMeasurer.measure(it.label(), labelStyle) }
    }
    val animated = devices.map { device ->
        key(device.address) {
            // The state is read when drawing, not here, so an animation does not recompose the radar.
            val radius = animateFloatAsState(
                targetValue = device.proximity.radius(),
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "radius",
            )
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) { appear.animateTo(1f, tween(600)) }
            // Devices without a name are shown without a label, instead of their addresses.
            val name = remember(device.name, nameStyle) {
                device.name?.let {
                    textMeasurer.measure(
                        text = it,
                        style = nameStyle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = maxNameWidth),
                    )
                }
            }
            AnimatedDevice(device, radius, appear, name)
        }
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        // Leave some room for the names of the devices on the rim.
        val maxRadius = minOf(size.width, size.height) / 2 * 0.86f

        // Zones, from the outside.
        listOf(FAR_EDGE to 0.04f, NEAR_EDGE to 0.07f, IMMEDIATE_EDGE to 0.10f).forEach { (edge, alpha) ->
            drawCircle(color = zoneColor.copy(alpha = alpha), radius = maxRadius * edge, center = center)
            drawCircle(color = lineColor, radius = maxRadius * edge, center = center, style = Stroke(width = 1.dp.toPx()))
        }
        // Cross hair.
        drawLine(lineColor, Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y), strokeWidth = 0.5.dp.toPx())
        drawLine(lineColor, Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius), strokeWidth = 0.5.dp.toPx())

        // Zone labels, on the top of each zone.
        listOf(Proximity.IMMEDIATE to IMMEDIATE_EDGE, Proximity.NEAR to NEAR_EDGE, Proximity.FAR to FAR_EDGE).forEach { (proximity, edge) ->
            val layout = zoneLabels.getValue(proximity)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(center.x - layout.size.width / 2f, center.y - maxRadius * edge + 2.dp.toPx()),
            )
        }

        // The sonar pulse.
        if (isMonitoring) {
            listOf(0f, 0.5f).forEach { offset ->
                val progress = (pulseProgress + offset) % 1f
                drawCircle(
                    color = pulseColor.copy(alpha = 0.35f * (1f - progress)),
                    radius = maxRadius * progress,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // The monitoring device.
        drawCircle(color = pulseColor.copy(alpha = 0.25f), radius = 12.dp.toPx(), center = center)
        drawCircle(color = pulseColor, radius = 5.dp.toPx(), center = center)

        // Devices in range.
        animated.forEach { animatedDevice ->
            val device = animatedDevice.device
            val radius = animatedDevice.radius.value
            val appear = animatedDevice.appear
            val angle = Math.toRadians(device.angle.toDouble())
            val position = Offset(
                x = center.x + (maxRadius * radius * cos(angle)).toFloat(),
                y = center.y + (maxRadius * radius * sin(angle)).toFloat(),
            )
            val isGreyedOut = selected.isNotEmpty() && device.address !in selected
            val color = if (isGreyedOut) greyedOutColor else device.color
            val alpha = appear.value * if (device.proximity == Proximity.UNKNOWN) 0.4f else 1f
            drawCircle(color = color.copy(alpha = 0.25f * alpha), radius = 14.dp.toPx(), center = position)
            drawCircle(color = color.copy(alpha = alpha), radius = 7.dp.toPx(), center = position)
            drawCircle(color = Color.White.copy(alpha = if (isGreyedOut) alpha * 0.3f else alpha), radius = 7.dp.toPx(), center = position, style = Stroke(width = 1.5.dp.toPx()))

            animatedDevice.nameLayout?.let { name ->
                // Keep the name inside the card.
                val x = (position.x - name.size.width / 2f).coerceIn(0f, size.width - name.size.width)
                val y = (position.y + 10.dp.toPx()).coerceAtMost(size.height - name.size.height)
                drawText(textLayoutResult = name, topLeft = Offset(x, y), alpha = if (isGreyedOut) alpha * 0.5f else alpha)
            }
        }
    }
}

private data class AnimatedDevice(
    val device: ProximityDevice,
    val radius: State<Float>,
    val appear: Animatable<Float, *>,
    val nameLayout: TextLayoutResult?,
)

@Composable
private fun DevicesCard(
    devices: List<ProximityDevice>,
    selected: Set<String>,
    isMonitoring: Boolean,
    onDeviceClicked: (String) -> Unit,
    onClearSelectionRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val selectedCount = devices.count { it.address in selected }
            Text(
                text = if (selectedCount == 0) "Devices in range: ${devices.size}" else "Selected: $selectedCount of ${devices.size}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (selectedCount > 0) {
                TextButton(onClick = onClearSelectionRequested) {
                    Text(text = "Clear")
                }
            }
        }

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isMonitoring) "Looking for devices..." else "Press Start to monitor devices",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@ElevatedCard
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(items = devices, key = { it.address }) { device ->
                DeviceItem(
                    device = device,
                    isSelected = device.address in selected,
                    isGreyedOut = selected.isNotEmpty() && device.address !in selected,
                    onClick = { onDeviceClicked(device.address) },
                )
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: ProximityDevice,
    isSelected: Boolean,
    isGreyedOut: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    color = if (isGreyedOut) MaterialTheme.colorScheme.outlineVariant else device.color,
                    shape = CircleShape,
                ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name ?: "Unknown device",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ProximityChip(device.proximity)
    }
}

@Composable
private fun ProximityChip(proximity: Proximity) {
    val color by animateColorAsState(proximity.color(), label = "chip")
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.2f),
        contentColor = color,
    ) {
        Text(
            text = proximity.label(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProximityViewPreview() {
    AppTheme {
        ProximityView(
            devices = listOf(
                ProximityDevice("AA:BB:CC:DD:EE:01", "Nordic_Blinky", Proximity.IMMEDIATE, Nordic.Color.Blue, 20f),
                ProximityDevice("AA:BB:CC:DD:EE:02", "Nordic_Beacon", Proximity.NEAR, Nordic.Color.Fall, 157.5f),
                ProximityDevice("AA:BB:CC:DD:EE:03", "Nordic_LBS", Proximity.FAR, Nordic.Color.Green, 295f),
            ),
            selected = setOf("AA:BB:CC:DD:EE:02"),
            isMonitoring = true,
            onDeviceClicked = {},
            onClearSelectionRequested = {},
        )
    }
}
