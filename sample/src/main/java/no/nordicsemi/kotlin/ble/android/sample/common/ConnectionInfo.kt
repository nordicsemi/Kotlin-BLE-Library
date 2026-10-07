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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.kotlin.ble.android.sample.graph.RssiGraph
import no.nordicsemi.kotlin.ble.android.sample.graph.RssiGraphState
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewPeripheral
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds

/**
 * Holds the RSSI of a connected peripheral, which has to be polled.
 *
 * @property graph The state of the graph with RSSI samples.
 * @property rssi The last RSSI value in dBm, or `null` if not known.
 */
@Stable
class RssiMonitor(
    val clock: () -> Long = System::currentTimeMillis,
) {
    val graph = RssiGraphState(
        timeout = 2.1.seconds,
        clock = clock,
    )

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    fun add(rssi: Int, time: Long = clock()) {
        graph.add(KEY, rssi, time)
        _rssi.value = rssi
    }

    fun clear() {
        _rssi.value = null
    }

    private companion object {
        const val KEY = "rssi"
    }
}

/**
 * Shows the RSSI graph, MTU, PHY and connection parameters of a connected peripheral.
 */
@Composable
fun ConnectionInfo(
    peripheral: Peripheral,
    rssiMonitor: RssiMonitor,
    modifier: Modifier = Modifier,
) {
    val mtu by peripheral.mtu.collectAsStateWithLifecycle()
    val phy by peripheral.phy.collectAsStateWithLifecycle()
    val parameters by peripheral.connectionParameters.collectAsStateWithLifecycle()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RssiPane(
            rssiMonitor = rssiMonitor,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val specified = parameters as? ConnectionParameters.Specified
            InfoChip("MTU", mtu?.let { "$it bytes" })
            InfoChip("PHY", phy?.format())
            InfoChip("Interval", specified?.let { "${it.connectionIntervalMillis} ms" })
            InfoChip("Latency", specified?.latency?.toString())
            InfoChip("Timeout", specified?.let { "${it.supervisionTimeoutMillis} ms" })
        }
    }
}

private fun PhyInUse.format() =
    if (txPhy == rxPhy) "$txPhy" else "TX $txPhy · RX $rxPhy"

@Composable
private fun RssiPane(
    rssiMonitor: RssiMonitor,
    modifier: Modifier = Modifier,
) {
    val rssi by rssiMonitor.rssi.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "RSSI",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = rssi?.let { "$it dBm" } ?: "–",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            RssiGraph(
                state = rssiMonitor.graph,
                gridStep = 20,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )
        }
    }
}

@Composable
private fun InfoChip(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value ?: "–",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Preview
@Composable
private fun InfoChipPreview() {
    AppTheme {
        InfoChip(
            label = "MTU",
            value = "512 bytes",
        )
    }
}

@Preview
@Composable
private fun ConnectionInfoPreview() {
    val scope = rememberCoroutineScope()
    AppTheme {
        ConnectionInfo(
            peripheral = PreviewPeripheral(
                scope = scope,
                state = ConnectionState.Connected,
                phy = PhyInUse(txPhy = Phy.PHY_LE_1M, rxPhy = Phy.PHY_LE_2M),
            ),
            rssiMonitor = RssiMonitor(clock = { 0 })
                .apply {
                    for (time in -30000L until  1L step 314 * 3) {
                        add((sin(time.toFloat()) * 20f).toInt() - 60, time)
                    }
                },
        )
    }
}