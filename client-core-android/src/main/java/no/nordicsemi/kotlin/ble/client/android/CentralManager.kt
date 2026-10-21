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

@file:Suppress("unused")

package no.nordicsemi.kotlin.ble.client.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import no.nordicsemi.kotlin.ble.client.CentralManager
import no.nordicsemi.kotlin.ble.client.android.exception.ScanningFailedToStartException
import no.nordicsemi.kotlin.ble.client.exception.ConnectionFailedException
import no.nordicsemi.kotlin.ble.client.exception.ScanningException
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Android-specific interface of the Central Manager.
 *
 * This interface extends [CentralManager] and adds support for bonded devices,
 * connecting and scanning using Android-specific options, and getting peripherals by their
 * MAC addresses.
 */
interface CentralManager:
    CentralManager<String, Peripheral, Peripheral.Executor, ConjunctionFilterScope, ScanResult> {

    /**
     * Returns a list of peripherals for which the system has bond information.
     *
     * Like [getPeripheralsById], peripherals are returned only when Bluetooth is powered on.
     * Otherwise, an empty list is returned.
     *
     * @return A list of bonded peripherals, or an empty list if Bluetooth is not powered on.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     */
    fun getBondedPeripherals(): List<Peripheral>

    /**
     * Establishes Bluetooth LE connection to the peripheral using default connection options.
     *
     * @param peripheral The peripheral to connect to.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws BluetoothUnavailableException If Bluetooth is disabled or not available.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @throws IllegalArgumentException If the Peripheral wasn't acquired from this manager
     * by scanning, ranging, or using methods [getPeripheralsById] or [getBondedPeripherals].
     * @throws ConnectionFailedException If connection failed. See [ConnectionFailedException.reason]
     * for a reason.
     * @throws CancellationException If the coroutine was canceled.
     * @throws TimeoutCancellationException If the connection attempt timed out.
     * @see [ConnectionOptions.Default]
     */
    override suspend fun connect(peripheral: Peripheral) {
        connect(peripheral, ConnectionOptions.Default)
    }

    /**
     * Establishes Bluetooth LE connection to the peripheral.
     *
     * This method does nothing if the device is already connected.
     *
     * @param peripheral The peripheral to connect to.
     * @param options Connection options.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws BluetoothUnavailableException If Bluetooth is disabled or not available.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @throws IllegalArgumentException If the Peripheral wasn't acquired from this manager
     * by scanning, [getPeripheralsById] or [getBondedPeripherals].
     * @throws ConnectionFailedException If connection failed. See [ConnectionFailedException.reason]
     * for a reason.
     * @throws CancellationException If the coroutine was canceled.
     * @throws TimeoutCancellationException If the connection attempt timed out.
     */
    suspend fun connect(peripheral: Peripheral, options: ConnectionOptions)

    /**
     * Scans for Bluetooth LE devices using default scan options.
     *
     * The scan will be stopped after the given period of time or when the flow is canceled.
     *
     * @param timeout The scan duration. By default, the scan will run until the flow is closed.
     * @param filter The filter to apply. By default, no filter is applied.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws BluetoothUnavailableException If Bluetooth is disabled or not available.
     * @throws SecurityException If the permission to scan is denied.
     * @throws ScanningException If an error occurred while starting the scan.
     * @see [ScanOptions.Default]
     */
    override fun scan(
        timeout: Duration,
        filter: ConjunctionFilterScope.() -> Unit,
    ): Flow<ScanResult> = scan(timeout, ScanOptions.Default, filter)

    /**
     * Scans for Bluetooth LE devices.
     *
     * The scan will be stopped after the given period of time or when the flow is canceled.
     *
     * @param timeout The scan duration. By default, the scan will run until the flow is closed.
     * @param options Scan options, e.g. the scan mode.
     * @param filter The filter to apply. By default, no filter is applied.
     * @throws ManagerClosedException If the central manager has been closed.
     * @throws BluetoothUnavailableException If Bluetooth is disabled or not available.
     * @throws SecurityException If the permission to scan is denied.
     * @throws ScanningException If an error occurred while starting the scan.
     */
    fun scan(
        timeout: Duration = Duration.INFINITE,
        options: ScanOptions,
        filter: ConjunctionFilterScope.() -> Unit = {},
    ): Flow<ScanResult>

    // TODO Add scan with Pending Intent (Android 8+, or for compat for legacy?)

    companion object Factory
    
    /**
     * Android-specific connection options.
     *
     * @property automaticallyRequestHighestValueLength If true, the manager will automatically request
     * the highest MTU supported by the remote device immediately after establishing the connection.
     * @property opportunistic Opportunistic connection is available from Android 17 onwards.
     * An opportunistic GATT client does not hold a GATT connection. It automatically disconnects
     * when no other GATT connections are active for the remote device.
     */
    sealed class ConnectionOptions(
        open val automaticallyRequestHighestValueLength: Boolean,
        open val opportunistic: Boolean,
    ) {
        companion object {
            /**
             * Default connection options.
             */
            val Default: ConnectionOptions
                get() = Direct()
        }

        /**
         * Connection using Auto Connect feature.
         *
         * The option to passively scan and finalize the connection when the remote device is in
         * range and available. It does not allow to set a connection timeout and will not retry
         * on failure.
         *
         * A peripheral that is **not bonded** is first connected directly, for up to 10 seconds.
         * Such a device may use a Resolvable Private Address (RPA), which the system cannot
         * use with auto connect: Android 13+ returns an error
         * ([UnsupportedAddress][ConnectionState.Disconnected.Reason.UnsupportedAddress]),
         * and older versions accept the request, but never connect.
         * If the direct connection fails, the peripheral uses auto connect.
         * After the link of such a connection is lost, the peripheral uses auto connect as well,
         * which fails for a device with an RPA that is still not bonded.
         *
         * A bonded peripheral uses auto connect only.
         *
         * In general, the first ever connection to a device should be direct and subsequent
         * connections to known devices should be invoked with this option.
         *
         * @param automaticallyRequestHighestValueLength If true, the manager will automatically request
         * the highest MTU supported by the remote device immediately after establishing the connection.
         * @param opportunistic Opportunistic connection is available from Android 17 onwards.
         * An opportunistic GATT client does not hold a GATT connection. It automatically disconnects
         * when no other GATT connections are active for the remote device.
         */
        data class AutoConnect(
            override val automaticallyRequestHighestValueLength: Boolean = true,
            override val opportunistic: Boolean = false,
        ): ConnectionOptions(automaticallyRequestHighestValueLength, opportunistic)

        /**
         * Connection options for direct connection.
         *
         * Direct connection has a maximum timeout which depends on the device manufacturer and is
         * usually 30 seconds or less. It may be shortened using the [timeout] parameter.
         *
         * @property timeout The connection timeout, defaults to 10 seconds.
         * If set to a longer time than the system timeout (around 30 seconds)
         * the connection will still timeout after the default timer. Use [AutoConnect] instead.
         * @property retry The number of connection retries. Value *N* indicates *N+1* connection attempts.
         * @property retryDelay The delay between connection retries, defaults to 300 ms.
         * @property automaticallyRequestHighestValueLength If true, the manager will automatically request
         * the highest MTU supported by the remote device immediately after establishing the connection.
         * @property opportunistic Opportunistic connection is available from Android 17 onwards.
         * An opportunistic GATT client does not hold a GATT connection. It automatically disconnects
         * when no other GATT connections are active for the remote device.
         */
        data class Direct(
            val timeout: Duration = 10.seconds,
            val retry: Int = 2,
            val retryDelay: Duration = 300.milliseconds,
            override val automaticallyRequestHighestValueLength: Boolean = true,
            override val opportunistic: Boolean = false
        ): ConnectionOptions(automaticallyRequestHighestValueLength, opportunistic)
    }

    /**
     * Android-specific scan options.
     *
     * @property mode The scan mode, which trades scanning latency for power consumption.
     * Ignored before Android 5.
     * @property passive Whether to scan passively. A passive scan doesn't send scan requests,
     * so it doesn't get scan responses, which often contain the device name. In return, it uses
     * less power and radio traffic. Available from Android 16 QPR2, ignored on older versions.
     * @property legacyOnly Whether to report only legacy advertisements. By default, extended
     * advertisements are reported as well, if the phone supports them (Android 8+).
     * @property phy The primary PHY to scan on, or `null` to scan on all PHYs supported by the phone.
     * Scanning on LE Coded requires Android 8 and a phone that supports it, otherwise the scan fails
     * to start with [ScanningFailedToStartException]. Ignored when [legacyOnly] is set, as legacy
     * advertisements are always sent on LE 1M.
     */
    data class ScanOptions(
        val mode: ScanMode = ScanMode.LOW_LATENCY,
        val passive: Boolean = false,
        val legacyOnly: Boolean = false,
        val phy: PrimaryPhy? = null,
    ) {
        companion object {
            /**
             * Default scan options: active scanning in low latency mode on all supported PHYs,
             * reporting both legacy and extended advertisements.
             */
            val Default = ScanOptions()
        }
    }
}