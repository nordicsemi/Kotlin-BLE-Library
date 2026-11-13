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

package no.nordicsemi.kotlin.ble.client.android.mock.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.CentralManager.ScanOptions
import no.nordicsemi.kotlin.ble.client.android.ConjunctionFilterScope
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.android.exception.ScanningFailedToStartException
import no.nordicsemi.kotlin.ble.client.android.internal.CentralManagerImpl
import no.nordicsemi.kotlin.ble.client.android.internal.ConjunctionFilter
import no.nordicsemi.kotlin.ble.client.android.internal.match
import no.nordicsemi.kotlin.ble.client.android.mock.MockCentralManager
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.client.mock.internal.MockBluetoothLeAdvertiser
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.log.Log
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * A mock implementation of [CentralManager] for Android.
 *
 * @param scope The coroutine scope.
 * @property environment The environment to use for the mock, defaults to the latest supported API.
 * @param clock The clock used for timestamps of scan results, defaults to the system clock.
 */
open class MockCentralManagerImpl(
    scope: CoroutineScope,
    private val environment: MockAndroidEnvironment = LatestApi(),
    clock: Clock = Clock.System,
): MockCentralManager, CentralManagerImpl(scope, environment) {
    override var logger: Log.Sink<Layer>? = Log.Sink.Null

    // Simulation methods
    private var peripheralSpecs = mutableListOf<PeripheralSpec<String>>()
    private val mockAdvertiser = MockBluetoothLeAdvertiser<String>(scope, clock, environment)

    override fun simulatePeripherals(peripherals: List<PeripheralSpec<String>>) {
        require(peripheralSpecs.isEmpty()) {
            "Peripherals have already been added to the simulation"
        }
        // Validate the MAC addresses.
        peripherals.forEach {
            require(checkBluetoothAddress(it.identifier)) {
                "${it.identifier} + is not a valid Bluetooth address"
            }
        }
        // Add known peripherals to the managed list. They will be available for connection without scanning.
        // This list includes bonded devices as well.
        peripherals
            .filter { it.isKnown }
            .forEach {
                addPeripheral(
                    id = it.identifier,
                    peripheral = Peripheral(
                        scope = scope,
                        impl = MockExecutor(
                            peripheralSpec = it,
                            name = it.name,
                            environment = environment,
                            advertisements = mockAdvertiser.events,
                            scope = scope,
                        )
                    )
                )
            }
        peripheralSpecs.addAll(peripherals)
        mockAdvertiser.simulateAdvertising(peripherals)
    }

    override fun tearDownSimulation() {
        mockAdvertiser.cancel()
        peripheralSpecs.clear()
    }

    // Implementation
    override val state: StateFlow<Manager.State>
        get() = environment.bluetoothState

    override fun getPeripheralsById(ids: List<String>): List<Peripheral> {
        // Ensure the central manager has not been closed.
        ensureOpen()

        // The identifiers are validated first, regardless of the state.
        ids.forEach { id ->
            require(checkBluetoothAddress(id)) {
                "$id is not a valid Bluetooth address"
            }
        }

        // Like on iOS, peripherals are returned only when Bluetooth is powered on.
        if (state.value != Manager.State.POWERED_ON) {
            logger?.warn(Layer.GAP) { "Peripherals can be retrieved only when Bluetooth is powered on (state: ${state.value})" }
            return emptyList()
        }
        return ids.map { id ->
            peripheral(id) {
                Peripheral(
                    scope = scope,
                    impl = MockExecutor(
                        peripheralSpec = peripheralSpecs.firstOrNull { it.identifier == id }
                            // If the peripheral is not found, simulate it as out of range.
                            // Use `PeripheralSpec.simulatePeripheral` to create a mock peripheral spec.
                            ?: PeripheralSpec.simulatePeripheral(
                                identifier = id,
                                proximity = Proximity.OUT_OF_RANGE
                            ),
                        name = null,
                        environment = environment,
                        advertisements = mockAdvertiser.events,
                        scope = scope,
                    ).also { p -> p.logger = logger }
                )
            }
        }
    }

    override fun getBondedPeripherals(): List<Peripheral> {
        // Ensure the central manager has not been closed.
        ensureOpen()

        // Verify the BLUETOOTH_CONNECT permission is granted (Android 12+).
        checkConnectPermission()

        // Like on iOS, peripherals are returned only when Bluetooth is powered on.
        if (state.value != Manager.State.POWERED_ON) {
            logger?.warn(Layer.GAP) { "Bonded peripherals can be retrieved only when Bluetooth is powered on (state: ${state.value})" }
            return emptyList()
        }

        // Here we need to concatenate two lists:
        // - known peripherals with bond information
        // - peripherals that have not been scanned yet, but are bonded (PeripheralSpec.isBonded == true)
        // Should we just iterate over peripheral specs, we would miss those that were bonded
        // in runtime and would make all specs "known" (available for retrieval).
        // Should we iterate only managed, we would miss devices that were defined as bonded,
        // but were not scanned yet.
        val managedBondedPeripherals = managedPeripherals()
            .filter { it.hasBondInformation }
//        val otherBondedPeripherals = peripheralSpecs
//            .filter { it.isBonded }
//            .filter { it.identifier !in managedPeripherals.keys }
//            .map { peripheralSpec ->
//                peripheral(peripheralSpec.identifier) {
//                    Peripheral(
//                        scope = scope,
//                        impl = MockExecutor(
//                            peripheralSpec = peripheralSpec,
//                            name = peripheralSpec.name,
//                            advertisements = mockAdvertiser.events,
//                        )
//                    )
//                }
//            }
        // TODO any order?
        // TODO NOTE all known (including bonded) peripherals were added to managed already in simulatePeripherals
        // TODO a peripheral may change MAC address, how about that?
        return managedBondedPeripherals // + otherBondedPeripherals
    }

    override fun scan(
        timeout: Duration,
        options: ScanOptions,
        filter: ConjunctionFilterScope.() -> Unit
    ): Flow<ScanResult> {
        return channelFlow {
            // Like with the native scanner, all errors are thrown when the flow is collected,
            // not when scan() is called. This way they can be handled with Flow.catch().

            // Ensure the central manager has not been closed.
            ensureOpen()

            // Ensure Bluetooth is supported.
            check(environment.isBluetoothSupported && environment.isBluetoothEnabled) {
                throw BluetoothUnavailableException()
            }

            // Verify the BLUETOOTH_SCAN permission is granted (Android 12+).
            checkScanningPermission()

            // The PHY is ignored when scanning for legacy advertisements only, as legacy
            // advertisements are always sent on LE 1M.
            val phy = options.phy.takeUnless { options.legacyOnly }

            // Scanning on LE Coded requires Android 8 and a phone that supports it.
            if (phy == PrimaryPhy.PHY_LE_CODED && !environment.isLeCodedPhySupported) {
                throw ScanningFailedToStartException(ScanningFailedToStartException.Reason.FeatureUnsupported)
            }

            // Build the filter based on the provided builder
            val filters = ConjunctionFilter().apply(filter).filters

            // The scan mode is not simulated. The mock receives every advertisement, like with
            // ScanMode.LOW_LATENCY. Passive scanning is available from Android 16 QPR2. The mock
            // doesn't model minor versions, so Android 16 counts as Android 16 QPR2. The mock also
            // doesn't separate scan responses from advertising data, so passive scan results still
            // include the scan response data.
            val passive = options.passive &&
                environment.androidSdkVersion >= AndroidEnvironment.SdkVersion.BAKLAVA

            logger?.trace(Layer.GAP) {
                "Starting scanning with $options and ${filters?.let { "filters: $it" } ?: "no filters"}"
            }

            val reportResult = environment.scanner().getOrThrow()

            // Emit all scan results until the timeout.
            withTimeoutOrNull(timeout) {
                // Keep IDs of all scanned peripherals to this scan.
                // This is used for handing passive scan.
                val cache = mutableSetOf<String>()

                mockAdvertiser.events.collect { result ->
                    // Some (most?) Android devices do not report scan error using `onScanFailed`
                    // callback, but instead don't return any results.
                    if (!reportResult) {
                        return@collect
                    }

                    // Some phones send only one Scan Request to connectable devices per scan.
                    // Such devices are only reported once. Non-connectable devices, which only
                    // send Advertising Data, are reported continuously. A passive scan doesn't
                    // send Scan Requests at all, so it reports all devices continuously.
                    if (environment.issueOnlyOneActiveScan && !passive) {
                        if (result.isConnectable) {
                            if (cache.contains(result.peripheralSpec.identifier)) {
                                return@collect
                            } else {
                                cache.add(result.peripheralSpec.identifier)
                            }
                        }
                    }

                    // Starting from Android 6 Location permission and Location service are required
                    // to scan for BLE devices. Since Android 12, apps can set a `neverForLocation`
                    // flag to claim that they won't estimate user's location from scan results.
                    if (environment.isLocationRequiredForScanning &&
                        (!environment.isLocationPermissionGranted || !environment.isLocationEnabled)) {
                        return@collect
                    }

                    // If the `neverForLocation` flag is set, check if the device is a beacon.
                    val neverForLocationSet =
                        !environment.isLocationRequiredForScanning &&
                         environment.androidSdkVersion >= AndroidEnvironment.SdkVersion.S
                    if (neverForLocationSet && result.isBeacon) {
                        return@collect
                    }

                    // If PHY LE Coded is not supported, ignore results sent with LE Coded PHY.
                    if (result.primaryPhy == PrimaryPhy.PHY_LE_CODED && !environment.isScanningOnLeCodedPhySupported) {
                        return@collect
                    }
                    if (result.secondaryPhy == Phy.PHY_LE_CODED && !environment.isLeCodedPhySupported) {
                        return@collect
                    }

                    // If PHY LE 2M is not supported, ignore results sent with LE 2M PHY as secondary PHY.
                    if (result.secondaryPhy == Phy.PHY_LE_2M && !environment.isLe2MPhySupported) {
                        return@collect
                    }

                    // Extended advertisements, which are the ones with a secondary PHY, are not
                    // reported when scanning for legacy advertisements only, or by phones without
                    // LE Extended Advertising support, which includes all phones before Android 8.
                    if ((options.legacyOnly || !environment.isLeExtendedAdvertisingSupported) &&
                        result.secondaryPhy != null) {
                        return@collect
                    }

                    // When scanning on a single PHY, ignore advertisements sent on the other one.
                    if (phy != null && result.primaryPhy != phy) {
                        return@collect
                    }

                    // The mock scanner found the device and cached its MAC address.
                    result.peripheralSpec.simulateCaching()

                    val scanResult = result.toScanResult(environment) { peripheralSpec, name ->
                        peripheral(peripheralSpec.identifier) {
                            Peripheral(
                                scope = scope,
                                impl = MockExecutor(
                                    peripheralSpec = peripheralSpec,
                                    name = name,
                                    environment = environment,
                                    advertisements = mockAdvertiser.events,
                                    scope = scope,
                                )
                            ).also { p -> p.logger = logger }
                        }
                    }

                    // Apply the filters if set.
                    if (filters?.match(scanResult) == false) return@collect

                    // Like the native scanner, results are sent without waiting for the collector.
                    // A slow collector gets the newest 64 queued results, see buffer() below.
                    trySend(scanResult)
                }
            }
            logger?.trace(Layer.GAP) { "Scanning timed out after $timeout" }
        }
            // Like the native scanner, keep the newest 64 results if the collector falls behind.
            // This must be applied right after channelFlow, so that it sets the channel capacity.
            .buffer(64, BufferOverflow.DROP_OLDEST)
            .catch { throwable ->
                (throwable as? ScanningFailedToStartException)?.let {
                    logger?.error(Layer.GAP, it)
                }
                // Pass the error to the collector, like the native scanner does.
                throw throwable
            }
            .onCompletion {
                logger?.trace(Layer.GAP) { "Scanning stopped" }
            }
    }

    override fun close() {
        // Ignore if already closed.
        if (!isOpen) return
        super.close()
        // Stop simulating the peripherals, so they stop advertising.
        tearDownSimulation()
    }

    // ---- Private implementation ----

    companion object {
        private const val ADDRESS_LENGTH = 17

        // This implementation is copied from BluetoothAdapter.checkBluetoothAddress(address)..
        private fun checkBluetoothAddress(address: String): Boolean {
            if (address.length != ADDRESS_LENGTH) {
                return false
            }
            for (i in 0 until ADDRESS_LENGTH) {
                val c = address[i]
                when (i % 3) {
                    0, 1 -> {
                        if ((c !in '0'..'9') && (c !in 'A'..'F')) {
                            return false
                        }
                    }
                    2 -> {
                        if (c != ':') {
                            return false
                        }
                    }
                }
            }
            return true
        }
    }
}