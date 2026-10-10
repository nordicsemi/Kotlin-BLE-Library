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

package no.nordicsemi.kotlin.ble.client.android.mock

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"
private const val NAME = "Mock Device"

/**
 * Tests of how the mock central manager receives advertisements of simulated peripherals.
 */
class AdvertisementsTest {

    @Test
    fun `connect started after the device disappeared does not use stale advertisements`() = runTest {
        val spec = PeripheralSpec.simulatePeripheral(
            identifier = ADDRESS,
            // Android assumes PUBLIC address type for devices that weren't scanned.
            addressType = AddressType.PUBLIC,
        ) {
            connectable(
                name = NAME,
                eventHandler = object : PeripheralSpecEventHandler {},
                services = {},
            )
            advertising(
                parameters = LegacyAdvertisingSetParameters(
                    connectable = true,
                    interval = 1.seconds,
                ),
            ) {
                CompleteLocalName(NAME)
            }
        }
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))

        // Let the device advertise a few times with nobody listening, then disappear.
        advanceTimeBy(2500.milliseconds)
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)

        // A connection attempt started now must not use any of the past advertisements.
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(5.seconds) {
                manager.connect(peripheral)
            }
        }
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
    }
}
