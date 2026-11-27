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

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.exception.PeripheralNotConnectedException
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val ADDRESS = "CA:FE:00:00:00:01"

/**
 * Tests of reading RSSI of a connected peripheral.
 */
class ConnectionRssiTest {

    private fun spec(proximity: Proximity = Proximity.IMMEDIATE): PeripheralSpec<String> =
        PeripheralSpec.simulatePeripheral(
            identifier = ADDRESS,
            addressType = AddressType.PUBLIC,
            proximity = proximity,
        ) {
            connectable(name = "Device", eventHandler = object : PeripheralSpecEventHandler {}) {}
            advertising(
                parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds),
            ) {
                CompleteLocalName("Device")
            }
        }

    private suspend fun TestScope.connect(spec: PeripheralSpec<String>): Peripheral {
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec)) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        return peripheral
    }

    @Test
    fun `rssi of a device in immediate proximity is strong`() = runTest {
        val peripheral = connect(spec(Proximity.IMMEDIATE))

        val rssi = peripheral.readRssi()

        // Around -40 dBm.
        assertTrue(rssi in -50..-30, "RSSI was $rssi")
    }

    @Test
    fun `rssi of a device nearby is weaker than in immediate proximity`() = runTest {
        val peripheral = connect(spec(Proximity.NEAR))

        val rssi = peripheral.readRssi()

        // Around -70 dBm.
        assertTrue(rssi in -75..-50, "RSSI was $rssi")
    }

    @Test
    fun `rssi of a far device is weak`() = runTest {
        val peripheral = connect(spec(Proximity.FAR))

        val rssi = peripheral.readRssi()

        // Around -100 dBm.
        assertTrue(rssi in -105..-80, "RSSI was $rssi")
    }

    @Test
    fun `rssi follows the change of proximity`() = runTest {
        val spec = spec(Proximity.IMMEDIATE)
        val peripheral = connect(spec)
        val close = peripheral.readRssi()

        spec.simulateProximityChange(Proximity.FAR)
        val far = peripheral.readRssi()

        assertTrue(far < close, "Expected $far < $close")
    }

    @Test
    fun `reading rssi of a peripheral that was never connected throws`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec())) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<PeripheralNotConnectedException> { peripheral.readRssi() }
    }

    @Test
    fun `reading rssi after disconnecting throws`() = runTest {
        val peripheral = connect(spec())
        peripheral.disconnect()

        assertFailsWith<PeripheralNotConnectedException> { peripheral.readRssi() }
    }

    @Test
    fun `reading rssi fails when the peripheral disconnects before the response`() = runTest {
        val spec = spec()
        val peripheral = connect(spec)

        val result = async { runCatching { peripheral.readRssi() } }
        runCurrent()
        spec.simulateDisconnection()
        runCurrent()

        assertIs<PeripheralNotConnectedException>(result.await().exceptionOrNull())
    }
}
