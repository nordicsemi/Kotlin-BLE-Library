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

package no.nordicsemi.kotlin.ble.client.android.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import no.nordicsemi.kotlin.ble.client.MonitoringEvent
import no.nordicsemi.kotlin.ble.client.PeripheralEnteredRange
import no.nordicsemi.kotlin.ble.client.PeripheralLeftRange
import no.nordicsemi.kotlin.ble.client.ProximityChanged
import no.nordicsemi.kotlin.ble.client.ProximityChanged.Proximity
import no.nordicsemi.kotlin.ble.client.RangeEvent
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.CentralManager.ConnectionOptions
import no.nordicsemi.kotlin.ble.client.android.ConjunctionFilterScope
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.internal.CentralManagerImpl
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Android-specific implementation of a central manager interface.
 *
 * @param scope The coroutine scope.
 * @param environment The Android-specific environment to use for the central manager.
 */
abstract class CentralManagerImpl(
    scope: CoroutineScope,
    private val environment: AndroidEnvironment,
): CentralManagerImpl<String, Peripheral, Peripheral.Executor, ConjunctionFilterScope, ScanResult>(scope, environment),
    CentralManager {

    /**
     * Checks whether the BLUETOOTH_CONNECT permission is granted.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     */
    fun checkConnectPermission() {
        check(!environment.requiresBluetoothRuntimePermissions || environment.isBluetoothConnectPermissionGranted) {
            throw SecurityException("BLUETOOTH_CONNECT permission not granted")
        }
    }

    /**
     * Checks whether the BLUETOOTH_SCAN permission is granted.
     *
     * @throws SecurityException If BLUETOOTH_SCAN permission is denied.
     */
    fun checkScanningPermission() {
        check(!environment.requiresBluetoothRuntimePermissions || environment.isBluetoothScanPermissionGranted) {
            throw SecurityException("BLUETOOTH_SCAN permission not granted")
        }
    }

    override suspend fun connect(
        peripheral: Peripheral,
        options: ConnectionOptions
    ) {
        // Ensure the central manager has not been closed.
        ensureOpen()

        // Ensure the peripheral was acquired from this Central Manager.
        checkPeripheral(peripheral)

        // Ensure Bluetooth is enabled.
        check(state.value == Manager.State.POWERED_ON) {
            throw BluetoothUnavailableException()
        }

        // Verify the BLUETOOTH_CONNECT permission is granted (Android 12+).
        checkConnectPermission()

        // Finally, connect to the peripheral.
        peripheral.connect(options)
    }

    // Monitoring and ranging.
    //
    // Both are implemented on top of scanning, so they work the same on the native and mock
    // implementation, and on all Android versions. The scan result is the only source of
    // information whether a peripheral is around.

    override fun monitor(
        timeout: Duration,
        filter: ConjunctionFilterScope.() -> Unit,
    ): Flow<MonitoringEvent<Peripheral>> = flow {
        // The number of ticks (seconds) since a packet from the peripheral was last received.
        val inRange = mutableMapOf<String, Pair<Peripheral, Int>>()
        var tick = 0
        val items = merge(
            scan(timeout, filter)
                .map { Item.Result(it.peripheral, it.rssi) }
                .endWhenComplete(),
            ticks(),
        )
        items.takeWhile { it !is Item.End }.collect { item ->
            when (item) {
                is Item.Result -> {
                    val previous = inRange.put(item.peripheral.identifier, item.peripheral to tick)
                    if (previous == null) {
                        emit(PeripheralEnteredRange(item.peripheral))
                    }
                }
                is Item.Tick -> {
                    tick++
                    val lost = inRange.values.filter { (_, lastSeen) -> tick - lastSeen >= LEFT_RANGE_TIMEOUT_TICKS }
                    lost.forEach { (peripheral, _) ->
                        inRange.remove(peripheral.identifier)
                        emit(PeripheralLeftRange(peripheral))
                    }
                }
                is Item.End -> {}
            }
        }
    }

    override fun range(
        peripheral: Peripheral,
        timeout: Duration,
    ): Flow<RangeEvent<Peripheral>> = flow {
        // Ensure the peripheral was acquired from this Central Manager.
        checkPeripheral(peripheral)

        var tick = 0
        var lastSeen = 0
        var proximity = Proximity.UNKNOWN
        val rssiSamples = ArrayDeque<Int>()
        val items = merge(
            // Address is not offloaded to the controller (see ScanFilter.toNative()), but checked
            // by the library.
            scan(timeout) { Address(peripheral.address) }
                .map { Item.Result(it.peripheral, it.rssi) }
                .endWhenComplete(),
            ticks(),
        )
        emitAll(
            items.transformWhile { item ->
                when (item) {
                    is Item.Result -> {
                        lastSeen = tick
                        rssiSamples.addLast(item.rssi)
                        if (rssiSamples.size > RSSI_SAMPLES) rssiSamples.removeFirst()
                        val newProximity = rssiSamples.average().toProximity(proximity)
                        if (newProximity != proximity) {
                            emit(ProximityChanged(peripheral, proximity, newProximity))
                            proximity = newProximity
                        }
                        true
                    }
                    is Item.Tick -> {
                        tick++
                        if (tick - lastSeen >= LEFT_RANGE_TIMEOUT_TICKS) {
                            // The peripheral left the range. The flow is closed, as documented.
                            if (proximity != Proximity.UNKNOWN) {
                                emit(ProximityChanged(peripheral, proximity, Proximity.UNKNOWN))
                            }
                            false
                        } else {
                            true
                        }
                    }
                    is Item.End -> false
                }
            }
        )
    }

    /** Items processed sequentially by [monitor] and [range]. */
    private sealed class Item {
        class Result(val peripheral: Peripheral, val rssi: Int): Item()
        data object Tick: Item()
        data object End: Item()
    }

    /** Emits [Item.End] when the scan completes, so that the merged flow can be completed. */
    private fun Flow<Item>.endWhenComplete(): Flow<Item> = onCompletion { cause ->
        if (cause == null) emit(Item.End)
    }

    /** Emits [Item.Tick] every second. Never completes. */
    private fun ticks(): Flow<Item> = flow {
        while (true) {
            delay(1.seconds)
            emit(Item.Tick)
        }
    }

    /**
     * Estimates the proximity from the average RSSI, with some hysteresis, so that the proximity
     * does not flip back and forth around the thresholds.
     */
    private fun Double.toProximity(current: Proximity): Proximity {
        val hysteresis = if (current == Proximity.UNKNOWN) 0 else PROXIMITY_HYSTERESIS_DBM
        val immediate = if (current == Proximity.IMMEDIATE) IMMEDIATE_RSSI - hysteresis else IMMEDIATE_RSSI + hysteresis
        val near = if (current == Proximity.IMMEDIATE || current == Proximity.NEAR) NEAR_RSSI - hysteresis else NEAR_RSSI + hysteresis
        return when {
            this >= immediate -> Proximity.IMMEDIATE
            this >= near -> Proximity.NEAR
            else -> Proximity.FAR
        }
    }

    private companion object {
        /**
         * The peripheral is considered to have left the range when no advertising packet was
         * received for this number of seconds. Peripherals advertise every 20 ms - 10 s, and
         * some packets may be lost.
         */
        const val LEFT_RANGE_TIMEOUT_TICKS = 10

        /** The number of recent RSSI samples averaged to estimate the proximity. */
        const val RSSI_SAMPLES = 5

        /** The RSSI, in dBm, above which the peripheral is IMMEDIATE (approx. up to 0.5 m). */
        const val IMMEDIATE_RSSI = -50

        /** The RSSI, in dBm, above which the peripheral is NEAR (up to a few meters). */
        const val NEAR_RSSI = -75

        /** The margin, in dB, needed to change the proximity back to the previous value. */
        const val PROXIMITY_HYSTERESIS_DBM = 3
    }
}
