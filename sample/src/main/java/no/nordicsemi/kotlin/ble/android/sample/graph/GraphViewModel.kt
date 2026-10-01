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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * A device shown in the legend of the RSSI graph.
 *
 * @property address The address of the device, used as the key in the graph.
 * @property name The name of the device, if known.
 * @property color The color of the device on the graph.
 * @property rssi The last RSSI, or `null` if the device is not advertising.
 */
@Immutable
data class GraphDevice(
    val address: String,
    val name: String?,
    val color: Color,
    val rssi: Int?,
)

@HiltViewModel
class GraphViewModel @Inject constructor(
    private val centralManager: CentralManager,
    private val scope: CoroutineScope,
) : ViewModel() {
    val state = centralManager.state

    /** The samples shown on the graph. */
    val graphState = RssiGraphState()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _devices = MutableStateFlow<List<GraphDevice>>(emptyList())
    val devices: StateFlow<List<GraphDevice>> = _devices.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** Names of found devices, by address, in order of discovery. */
    private val names = LinkedHashMap<String, String?>()

    private var scanningJob: Job? = null

    fun onScanRequested() {
        if (scanningJob?.isActive == true) return
        scanningJob = scope.launch {
            // Refresh the legend periodically, not on every packet.
            val legendJob = launch {
                while (isActive) {
                    refreshDevices()
                    delay(LEGEND_REFRESH_INTERVAL)
                }
            }
            centralManager
                .scan()
                .onStart { _isScanning.update { true } }
                .catch { t -> Timber.e("Scan failed: $t") }
                .onCompletion { _isScanning.update { false } }
                .collect { result ->
                    // Android reports 127 if RSSI is not available.
                    if (result.rssi > MAX_RSSI) return@collect

                    val address = result.peripheral.address
                    // The lock makes sure the legend and the graph are cleared together.
                    synchronized(names) {
                        graphState.add(
                            key = address,
                            rssi = result.rssi,
                            time = result.timestamp.toEpochMilliseconds(),
                        )
                        names[address] = result.peripheral.name
                    }
                }
            legendJob.cancel()
            refreshDevices()
        }
    }

    fun onStopScanRequested() {
        scanningJob?.cancel()
    }

    fun onClearRequested() {
        synchronized(names) {
            names.clear()
            graphState.clear()
        }
        _selected.update { emptySet() }
        refreshDevices()
    }

    fun onDeviceClicked(address: String) {
        _selected.update { if (address in it) it - address else it + address }
    }

    fun onClearSelectionRequested() {
        _selected.update { emptySet() }
    }

    private fun refreshDevices() {
        val now = graphState.clock()
        val snapshot = synchronized(names) { names.toList() }
        _devices.update {
            snapshot.map { (address, name) ->
                GraphDevice(
                    address = address,
                    name = name,
                    color = graphState.colorOf(address),
                    rssi = graphState.lastRssi(address, now),
                )
            }
        }
    }

    private companion object {
        val LEGEND_REFRESH_INTERVAL = 500L.milliseconds
        const val MAX_RSSI = 20
    }
}
