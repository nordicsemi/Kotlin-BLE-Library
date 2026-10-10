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

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests of the advertising semantics of [MockBluetoothLeAdvertiser].
 *
 * A real Bluetooth LE advertiser transmits on its advertising interval no matter who is
 * listening. A receiver can never slow the advertiser down, nor receive advertisements
 * from the past.
 */
class MockBluetoothLeAdvertiserTest {

    private fun advertisingSpec(
        address: String = "CA:FE:00:00:00:01",
        interval: Duration = 1.seconds,
    ): PeripheralSpec<String> =
        PeripheralSpec.simulatePeripheral(address) {
            advertising(
                parameters = LegacyAdvertisingSetParameters(
                    connectable = true,
                    interval = interval,
                ),
            ) {
                CompleteLocalName("Advertiser")
            }
        }

    @Test
    fun `a busy subscriber does not stall advertising for other subscribers`() = runTest {
        val advertiser = MockBluetoothLeAdvertiser<String>(backgroundScope)

        // A subscriber that receives one advertisement and then stops keeping up,
        // e.g. a scanner whose collector is busy processing the result.
        backgroundScope.launch {
            advertiser.events.collect { delay(10.minutes) }
        }
        // A healthy subscriber counting advertising events.
        var received = 0
        backgroundScope.launch {
            advertiser.events.collect { received++ }
        }
        runCurrent()

        advertiser.simulateAdvertising(listOf(advertisingSpec(interval = 1.seconds)))

        // The device advertises 5 times by 4.5 s, and the healthy subscriber must get every
        // one of them.
        advanceTimeBy(4500.milliseconds)
        runCurrent()
        assertEquals(5, received)

        advertiser.cancel()
    }

    @Test
    fun `devices advertising at the same time are all received`() = runTest {
        val advertiser = MockBluetoothLeAdvertiser<String>(backgroundScope)
        val received = mutableListOf<String>()
        backgroundScope.launch {
            advertiser.events.collect { received += it.peripheralSpec.identifier }
        }
        runCurrent()

        // All five devices start advertising at the same moment.
        val addresses = (1..5).map { "CA:FE:00:00:00:0$it" }
        advertiser.simulateAdvertising(
            addresses.map { advertisingSpec(address = it, interval = 100.milliseconds) }
        )
        runCurrent()
        assertEquals(addresses, received)

        advertiser.cancel()
    }

    @Test
    fun `advertisements are not replayed to subscribers that arrive late`() = runTest {
        val advertiser = MockBluetoothLeAdvertiser<String>(backgroundScope)
        advertiser.simulateAdvertising(listOf(advertisingSpec(interval = 1.seconds)))

        // Let a few advertising events pass with nobody listening: they are lost.
        advanceTimeBy(2500.milliseconds)

        var received = 0
        backgroundScope.launch {
            advertiser.events.collect { received++ }
        }
        runCurrent()
        assertEquals(0, received)

        // The subscriber only hears the device advertise again, at about 3 s.
        advanceTimeBy(600.milliseconds)
        runCurrent()
        assertEquals(1, received)

        advertiser.cancel()
    }
}
