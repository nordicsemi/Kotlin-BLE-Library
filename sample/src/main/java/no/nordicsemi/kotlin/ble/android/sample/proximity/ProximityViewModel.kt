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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.android.sample.theme.Nordic
import no.nordicsemi.kotlin.ble.client.PeripheralEnteredRange
import no.nordicsemi.kotlin.ble.client.PeripheralLeftRange
import no.nordicsemi.kotlin.ble.client.ProximityChanged
import no.nordicsemi.kotlin.ble.client.ProximityChanged.Proximity
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import timber.log.Timber
import javax.inject.Inject

/**
 * A device that is in range of the monitoring device.
 *
 * @property address The address of the device.
 * @property name The name of the device, if known.
 * @property proximity The last estimated proximity, [Proximity.UNKNOWN] until the first result.
 * @property color The color of the device on the radar.
 * @property angle The direction of the device on the radar, in degrees. It is only a way to
 * spread the devices on the radar, as the proximity does not tell the direction.
 */
@Immutable
data class ProximityDevice(
    val address: String,
    val name: String?,
    val proximity: Proximity,
    val color: Color,
    val angle: Float,
)

/**
 * Monitors devices with names matching [DEVICE_NAME_FILTER]. Ranging is started for each
 * device that enters the range, and stopped when it leaves the range.
 */
@HiltViewModel
class ProximityViewModel @Inject constructor(
    private val centralManager: CentralManager,
    private val scope: CoroutineScope,
) : ViewModel() {
    val state = centralManager.state

    val isMonitoring: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Devices in range, in the order they entered it. */
    val devices: StateFlow<List<ProximityDevice>>
        field = MutableStateFlow<List<ProximityDevice>>(emptyList())

    /** Addresses of the devices selected in the list. Devices that left the range are removed. */
    val selected: StateFlow<Set<String>>
        field = MutableStateFlow<Set<String>>(emptySet())

    private var monitoringJob: Job? = null

    /** Ranging jobs, by address. Access only when holding the lock. */
    private val rangingJobs = mutableMapOf<String, Job>()
    private var enteredCount = 0

    fun onStartRequested() {
        if (monitoringJob?.isActive == true) return
        monitoringJob = scope.launch {
            isMonitoring.update { true }
            try {
                centralManager.monitor { Name(DEVICE_NAME_FILTER) }
                    .catch { t -> Timber.e("Monitoring failed: $t") }
                    .collect { event ->
                        when (event) {
                            is PeripheralEnteredRange -> startRanging(event.peripheral)
                            is PeripheralLeftRange -> stopRanging(event.peripheral.address)
                        }
                    }
            } finally {
                // Stop ranging of all devices when monitoring stops.
                synchronized(rangingJobs) { rangingJobs.keys.toList() }.forEach { stopRanging(it) }
                isMonitoring.update { false }
            }
        }
    }

    fun onStopRequested() {
        monitoringJob?.cancel()
    }

    fun onDeviceClicked(address: String) {
        selected.update { if (address in it) it - address else it + address }
    }

    fun onClearSelectionRequested() {
        selected.update { emptySet() }
    }

    private fun startRanging(peripheral: Peripheral) {
        val address = peripheral.address
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                centralManager.range(peripheral)
                    .catch { t -> Timber.e("Ranging $address failed: $t") }
                    .collect { event ->
                        if (event is ProximityChanged) {
                            Timber.i("$address: ${event.previousProximity} -> ${event.proximity}")
                            updateDevice(address) { it.copy(proximity = event.proximity) }
                        }
                    }
                // The flow is closed when the peripheral leaves the range.
                Timber.i("$address left the range")
            } finally {
                synchronized(rangingJobs) { rangingJobs.remove(address, coroutineContext[Job]) }
                devices.update { list -> list.filterNot { it.address == address } }
                selected.update { it - address }
            }
        }
        synchronized(rangingJobs) {
            if (rangingJobs.containsKey(address)) {
                job.cancel()
                return
            }
            rangingJobs[address] = job
        }
        val index = enteredCount++
        devices.update { list ->
            list.filterNot { it.address == address } + ProximityDevice(
                address = address,
                name = peripheral.name,
                proximity = Proximity.UNKNOWN,
                color = COLORS[index % COLORS.size],
                // Spread the devices evenly, no matter how many of them there are.
                angle = (index * GOLDEN_ANGLE) % 360f,
            )
        }
        job.start()
    }

    private fun stopRanging(address: String) {
        synchronized(rangingJobs) { rangingJobs[address] }?.cancel()
    }

    private fun updateDevice(address: String, transform: (ProximityDevice) -> ProximityDevice) {
        devices.update { list -> list.map { if (it.address == address) transform(it) else it } }
    }

    private companion object {
        /** Matches names starting with "Nordic". */
        val DEVICE_NAME_FILTER = Regex("Nordic.*")

        const val GOLDEN_ANGLE = 137.5f

        val COLORS = listOf(
            Nordic.Color.Blue,
            Nordic.Color.Fall,
            Nordic.Color.Green,
            Nordic.Color.Pink,
            Nordic.Color.Lake,
            Nordic.Color.Sun,
            Nordic.Color.Red,
            Nordic.Color.Blueslate,
        )
    }
}
