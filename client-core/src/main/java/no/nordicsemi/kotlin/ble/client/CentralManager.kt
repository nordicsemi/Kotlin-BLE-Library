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

package no.nordicsemi.kotlin.ble.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import no.nordicsemi.kotlin.ble.client.exception.ConnectionFailedException
import no.nordicsemi.kotlin.ble.client.exception.ScanningException
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.Peer
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import kotlin.time.Duration

/**
 * A central manager is responsible for scanning, monitoring and connecting to Bluetooth LE devices.
 *
 * @param ID The type of the peripheral identifier.
 * @param P The type of the peripheral.
 * @param F The type of the scan filter scope.
 * @param SR Scan result type.
 */
interface CentralManager<
    ID: Any,
    P: Peripheral<ID, EX>,
    EX: Peripheral.Executor<ID>,
    F: CentralManager.ScanFilterScope,
    SR: ScanResult<*, *>,
>: Manager {

    /**
     * A list of peripherals that the central manager is able to match to the provided identifiers.
     *
     * Peripherals are returned only when Bluetooth is powered on, that is when [state] is
     * [Manager.State.POWERED_ON]. In any other state an empty list is returned, and no error is
     * thrown. This is how `retrievePeripherals(withIdentifiers:)` of `CBCentralManager` works on iOS.
     * Wait for the state to change before retrieving peripherals.
     *
     * @param ids List of peripheral identifiers.
     * @return List of peripherals. The list may have a smaller size than the input list, and is
     * empty if Bluetooth is not powered on.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws IllegalArgumentException If any of the identifiers is not valid. This is checked
     * regardless of the state.
     * @see [Peer.identifier]
     */
    fun getPeripheralsById(ids: List<ID>): List<P>

    /**
     * A peripheral that the central manager is able to match to the provided identifier.
     *
     * The peripheral is returned only when Bluetooth is powered on, see [getPeripheralsById].
     *
     * @param id The peripheral identifier.
     * @return A peripheral associated with the given identifier, if found, or `null` if not found
     * or Bluetooth is not powered on.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws IllegalArgumentException If the identifier is not valid. This is checked
     * regardless of the state.
     * @see [Peer.identifier]
     */
    fun getPeripheralById(id: ID): P? = getPeripheralsById(listOf(id)).firstOrNull()

    /**
     * Scans for Bluetooth LE devices.
     *
     * The scan will be stopped after the given period of time or when the flow is canceled.
     *
     * Results are queued while the collector is busy, and a collector that falls behind loses
     * some of them. To choose which ones are kept, apply `conflate()` or a `buffer` with
     * `DROP_OLDEST` or `DROP_LATEST` to the returned flow, e.g. `buffer(64, DROP_LATEST)`.
     *
     * The scan starts when the returned flow is collected, and all errors are thrown then, to the
     * collector, not when this method is called. They can be handled using `Flow.catch()`.
     *
     * @param timeout The scan duration. By default, the scan will run until the flow is closed.
     * @param filter The filter to apply. By default, no filter is applied.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws BluetoothUnavailableException If Bluetooth is disabled or not available.
     * @throws SecurityException If the permission to scan is denied.
     * @throws ScanningException If an error occurred while starting the scan.
     */
    fun scan(timeout: Duration = Duration.INFINITE, filter: F.() -> Unit = {}): Flow<SR>

    /**
     * Starts monitoring for Bluetooth LE devices.
     *
     * This method will emit events when the monitoring device enters or leaves the range of a
     * peripheral advertising packets matching given filter.
     *
     * A [PeripheralEnteredRange] event is emitted when the first matching advertising packet from
     * a peripheral is received. A [PeripheralLeftRange] event is emitted when no packets have been
     * received from that peripheral for 10 seconds. If it returns, [PeripheralEnteredRange]
     * is emitted again. Each peripheral is tracked independently.
     *
     * Monitoring is based on scanning, so it consumes as much power as a scan with the same
     * filter, and the same limitations apply.
     *
     * The scan will be stopped after the given period of time or when the flow is canceled.
     *
     * @param timeout The scan duration. By default, the scan will run until the flow is closed.
     * @param filter The filter to apply. By default, no filter is applied.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws BluetoothUnavailableException If Bluetooth is disabled or not available.
     * @throws SecurityException If the permission to scan is denied.
     * @throws ScanningException If an error occurred while starting the scan.
     */
    fun monitor(timeout: Duration = Duration.INFINITE, filter: F.() -> Unit): Flow<MonitoringEvent<P>>

    /**
     * Starts ranging for Bluetooth LE devices.
     *
     * This method can be started when [monitor] reports [PeripheralEnteredRange] event for given
     * peripheral.
     *
     * The flow emits [ProximityChanged] event when the first advertising packet from the peripheral
     * is received, and then every time the estimated proximity changes. The proximity is estimated
     * from the average of the last 5 RSSI values, with some hysteresis to avoid flapping at
     * thresholds, and is only approximate.
     *
     * The flow is closed automatically when the peripheral leaves range of the monitoring device,
     * that is when no packets have been received for 10 seconds. If the proximity was known,
     * the last event reports the change to [UNKNOWN][ProximityChanged.Proximity.UNKNOWN].
     * @param peripheral The peripheral to range.
     * @param timeout The scan duration. By default, the scan will run until the flow is closed.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws BluetoothUnavailableException If Bluetooth is disabled or not available.
     * @throws SecurityException If the permission to scan is denied.
     * @throws ScanningException If an error occurred while starting the scan.
     */
    fun range(peripheral: P, timeout: Duration = Duration.INFINITE): Flow<RangeEvent<P>>

    /**
     * Establishes Bluetooth LE connection to the peripheral using default connection options.
     *
     * @param peripheral The peripheral to connect to.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws BluetoothUnavailableException If Bluetooth is disabled or not available.
     * @throws SecurityException If the permission to connect to a peripheral is denied.
     * @throws IllegalArgumentException If the Peripheral wasn't acquired from this manager
     * by scanning, ranging, or using [getPeripheralsById] method.
     * @throws ConnectionFailedException If connection failed. See [ConnectionFailedException.reason]
     * for a reason.
     * @throws CancellationException If the coroutine was canceled.
     * @throws TimeoutCancellationException If the connection attempt timed out.
     */
    suspend fun connect(peripheral: P)

    /**
     * Closes the manager and releases its resources.
     *
     * This method must be called when the manager is no longer needed.
     *
     * Calling the method on a closed manager has no effect.
     */
    override fun close()

    /**
     * A base interface for scan filter scope.
     *
     * Different platforms may allow to filter devices based on different criteria.
     */
    interface ScanFilterScope
}