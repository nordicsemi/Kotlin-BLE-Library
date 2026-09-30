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

package no.nordicsemi.kotlin.ble.android.sample.scanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import no.nordicsemi.kotlin.ble.android.sample.common.AttributeAction
import no.nordicsemi.kotlin.ble.android.sample.common.AttributeValue
import no.nordicsemi.kotlin.ble.android.sample.common.DeviceList
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewPeripheral
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewScanResult
import no.nordicsemi.kotlin.ble.core.ConnectionState

@Composable
fun ScannerView(
    results: List<ScanResult>,
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onPeripheralClicked: (Peripheral) -> Unit,
    onBondRequested: (Peripheral) -> Unit,
    onRemoveBondRequested: (Peripheral) -> Unit,
    onClearCacheRequested: (Peripheral) -> Unit,
    onRssiRead: (Peripheral) -> Unit,
    onReadPhy: (Peripheral) -> Unit,
    attributeValues: Map<Any, AttributeValue>,
    onAttributeAction: (AttributeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onStartScan,
                enabled = true,//!isScanning,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = if (isScanning) "Stop scan" else "Start scan")
            }

            AnimatedVisibility(visible = isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }

        if (results.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Tap on a device to connect.")
        }

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider()

        DeviceList(
            modifier = Modifier.fillMaxSize(),
            results = results,
            onItemClick = onPeripheralClicked,
            onBondRequested = onBondRequested,
            onRemoveBondRequested = onRemoveBondRequested,
            onClearCacheRequested = onClearCacheRequested,
            onReadRssi = onRssiRead,
            onReadPhy = onReadPhy,
            attributeValues = attributeValues,
            onAttributeAction = onAttributeAction,
            contentPadding = PaddingValues(bottom = 56.dp, top = 16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ScannerScreenPreview() {
    var isScanning by remember { mutableStateOf(false) }
    AppTheme {
        val scope = rememberCoroutineScope()
        ScannerView(
            results = listOf(
                PreviewScanResult(
                    peripheral = PreviewPeripheral(
                        scope = scope,
                        address = "00:11:22:33:44:55",
                        name = "Device 1",
                        state = ConnectionState.Connected,
                    ),
                    rssi = -30,
                    isConnectable = true,
                ),
                PreviewScanResult(
                    peripheral = PreviewPeripheral(
                        scope = scope,
                        address = "11:22:33:44:55:66",
                        name = "Device 2",
                        state = ConnectionState.Connecting,
                    ),
                    rssi = -50,
                    isConnectable = true,
                ),
                PreviewScanResult(
                    peripheral = PreviewPeripheral(
                        scope = scope,
                        address = "22:33:44:55:66:77",
                        name = "Device 3",
                        state = ConnectionState.Disconnected(),
                        hasBondInformation = true,
                    ),
                    rssi = -70,
                    isConnectable = true,
                ),
                PreviewScanResult(
                    peripheral = PreviewPeripheral(
                        scope = scope,
                        address = "33:44:55:66:77:88",
                        name = "Device 4",
                        state = ConnectionState.Disconnected(),
                    ),
                    rssi = -90,
                    isConnectable = false,
                )
            ),
            isScanning = isScanning,
            onStartScan = { isScanning = !isScanning },
            onPeripheralClicked = {},
            onBondRequested = {},
            onRemoveBondRequested = {},
            onClearCacheRequested = {},
            onRssiRead = {},
            onReadPhy = {},
            attributeValues = emptyMap(),
            onAttributeAction = {},
        )
    }
}