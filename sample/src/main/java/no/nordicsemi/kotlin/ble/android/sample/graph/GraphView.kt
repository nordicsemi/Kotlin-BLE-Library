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

package no.nordicsemi.kotlin.ble.android.sample.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme

@Composable
fun GraphView(
    graphState: RssiGraphState,
    devices: List<GraphDevice>,
    selected: Set<String>,
    isScanning: Boolean,
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
        val graph = @Composable { graphModifier: Modifier ->
            GraphCard(
                graphState = graphState,
                selected = selected,
                isScanning = isScanning,
                modifier = graphModifier,
            )
        }
        val legend = @Composable { legendModifier: Modifier ->
            LegendCard(
                devices = devices,
                selected = selected,
                isScanning = isScanning,
                onDeviceClicked = onDeviceClicked,
                onClearSelectionRequested = onClearSelectionRequested,
                modifier = legendModifier,
            )
        }

        val graphHeight = min(maxHeight * 0.45f, 320.dp)
        if (maxWidth > maxHeight) {
            // Landscape: the graph on the left, the legend on the right.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                graph(Modifier.weight(3f).fillMaxHeight())
                legend(Modifier.weight(2f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                graph(Modifier.fillMaxWidth().height(graphHeight))
                legend(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun GraphCard(
    graphState: RssiGraphState,
    selected: Set<String>,
    isScanning: Boolean,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        RssiGraph(
            state = graphState,
            selected = selected,
            paused = !isScanning,
            showFps = true,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun LegendCard(
    devices: List<GraphDevice>,
    selected: Set<String>,
    isScanning: Boolean,
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
            Text(
                text = if (selected.isEmpty()) "Devices: ${devices.size}" else "Selected: ${selected.size} of ${devices.size}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (selected.isNotEmpty()) {
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
                    text = if (isScanning) "Scanning..." else "Start scanning to find devices",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@ElevatedCard
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(items = devices, key = { it.address }) { device ->
                LegendItem(
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
private fun LegendItem(
    device: GraphDevice,
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
        Text(
            text = device.rssi?.let { "$it dBm" } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun GraphViewPreview() {
    AppTheme {
        val graphState = RssiGraphState(clock = { 30_000L }).apply {
            repeat(30) { i ->
                add(key = "AA:BB:CC:DD:EE:01", rssi = -60 - (i % 7), time = i * 1_000L)
                if (i !in 10..16) add(key = "AA:BB:CC:DD:EE:02", rssi = -80 + (i % 5), time = i * 1_000L)
            }
        }
        GraphView(
            graphState = graphState,
            devices = listOf(
                GraphDevice("AA:BB:CC:DD:EE:01", "Nordic_LBS", graphState.colorOf("AA:BB:CC:DD:EE:01"), -62),
                GraphDevice("AA:BB:CC:DD:EE:02", null, graphState.colorOf("AA:BB:CC:DD:EE:02"), null),
            ),
            selected = emptySet(),
            isScanning = true,
            onDeviceClicked = {},
            onClearSelectionRequested = {},
        )
    }
}
