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

package no.nordicsemi.kotlin.ble.client.mock.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.core.Bluetooth5AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.Environment
import no.nordicsemi.kotlin.ble.core.mock.MockEnvironment
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * A mock advertiser emitting advertising events of simulated peripherals.
 *
 * @param scope The scope of the mock central manager.
 * @param clock The clock used for timestamps of the advertising events.
 * @param environment The environment of the mock central manager. The simulated peripherals use it
 * to limit the MTU and PHYs of their connections. A peripheral is simulated in one environment.
 */
class MockBluetoothLeAdvertiser<ID: Any>(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val environment: MockEnvironment? = null,
) {
    // A real advertiser never waits for scanners, so emitting must not suspend. The buffer holds
    // events for subscribers that are momentarily behind, e.g. when many devices advertise at
    // the same time. Only a subscriber lagging by more than 64 events loses the oldest ones.
    // There is no replay: late subscribers never receive past advertisements.
    val events: SharedFlow<MockScanResult<ID>>
        field = MutableSharedFlow<MockScanResult<ID>>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** The scopes in which the peripherals are simulated, see [PeripheralSpec.attach]. */
    private val simulations = mutableMapOf<PeripheralSpec<ID>, CoroutineScope>()

    /**
     * Simulates advertising for given peripheral specs.
     *
     * This implementation emits advertising events just as specified in the [PeripheralSpec]s.
     * It does not take under consideration the [Environment], i.e. packets using
     * LE Coded PHY will advertise, and it is up to the scanner to filter them or not.
     *
     * The peripherals are simulated in the scope of this advertiser until [cancel] is called.
     *
     * @param peripherals The list of peripheral specs simulating advertising.
     * @return A flow emitting [MockScanResult]s for each advertising event.
     */
    fun simulateAdvertising(peripherals: List<PeripheralSpec<ID>>) {
        peripherals.forEach { peripheralSpec ->
            // Both the advertising below and the simulation work of the peripheral itself
            // (timers, monitoring jobs) run in this scope. A peripheral given twice replaces
            // its previous simulation.
            val simulation = peripheralSpec.attach(scope, environment)
            simulations.put(peripheralSpec, simulation)?.let { peripheralSpec.detach(it) }

            // If a peripheral has defined advertising data, begin mock advertising.
            peripheralSpec.advertisingSets?.forEachIndexed { index, advertisingSet ->
                // Build the advertising parameters. We do this here once, as it doesn't change over time.
                val rawAdvertisingData = advertisingSet.advertisingData.raw
                val bluetooth5AdvertisingSetParameters =
                    advertisingSet.parameters as? Bluetooth5AdvertisingSetParameters
                val txPowerLevel = advertisingSet.parameters.txPowerLevel
                    .takeIf { bluetooth5AdvertisingSetParameters?.includeTxPowerLevel == true }
                val primaryPhy =
                    bluetooth5AdvertisingSetParameters?.primaryPhy ?: PrimaryPhy.PHY_LE_1M
                val secondaryPhy = bluetooth5AdvertisingSetParameters?.secondaryPhy

                // With the parameters ready, we can start mock advertising.
                // Advertising will continue until:
                // * the timeout is reached,
                // * the maximum number of advertising events is reached,
                // * the simulation is tear down
                // * the scope is closed
                simulation.launch {
                    // First, delay the advertising if needed.
                    delay(advertisingSet.delay)

                    // On real devices, the time between advertising events is the interval plus
                    // a random 0-10 ms (advDelay). See Bluetooth Core Specification v6.2, Vol 6,
                    // Part B, Section 4.4.2.2.1 Advertising interval:
                    // https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/low-energy-controller/link-layer-specification.html#UUID-485d8ae5-fba9-7177-831b-a5d4a8362f33
                    // The seed is fixed, so tests are reproducible, and differs per device and
                    // advertising set, so they drift apart like real ones.
                    val advDelays = Random(31 * peripheralSpec.identifier.hashCode() + index)

                    // Set up the timer to cancel advertising after the timeout.
                    // The default timeout is set to INFINITE.
                    withTimeoutOrNull(advertisingSet.timeout) {
                        // Set up event counter.
                        repeat(advertisingSet.maxAdvertisingEvents) {
                            // The device is advertising only when disconnected or when connected and
                            // the "isAdvertisingWhenConnected" flag is set.
                            if (!peripheralSpec.isConnected || advertisingSet.isAdvertisingWhenConnected) {
                                // Make sure the device is in range.
                                if (peripheralSpec.proximity != Proximity.OUT_OF_RANGE) {
                                    // Emit the advertising event.
                                    val scanResult = MockScanResult(
                                        peripheralSpec = peripheralSpec,
                                        isConnectable = advertisingSet.parameters.connectable,
                                        advertisingData = rawAdvertisingData,
                                        isBeacon = advertisingSet.isBeacon,
                                        rssi = peripheralSpec.proximity.randomRssi(),
                                        txPowerLevel = txPowerLevel,
                                        primaryPhy = primaryPhy,
                                        secondaryPhy = secondaryPhy,
                                        timestamp = clock.now()
                                    )
                                    events.emit(scanResult)
                                }
                            }

                            // Wait the advertising interval for the next advertising event.
                            delay(advertisingSet.parameters.interval + advDelays.nextInt(0, 11).milliseconds)
                        }
                    }
                }
            }
        }
    }

    fun cancel() {
        simulations.forEach { (peripheralSpec, simulation) -> peripheralSpec.detach(simulation) }
        simulations.clear()
    }

}