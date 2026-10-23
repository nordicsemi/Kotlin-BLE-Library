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

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.exception.PeripheralNotConnectedException
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

private const val ADDRESS = "CA:FE:00:00:00:01"

/**
 * Tests of reading and changing the PHY of the connection.
 */
class ConnectionPhyTest {

    private fun spec(supports2M: Boolean = true): PeripheralSpec<String> = PeripheralSpec.simulatePeripheral(
        identifier = ADDRESS,
        addressType = AddressType.PUBLIC,
    ) {
        connectable(
            name = "Device",
            isPhyLe2MSupported = supports2M,
            eventHandler = object : PeripheralSpecEventHandler {},
        ) {}
        advertising(
            parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds),
        ) {
            CompleteLocalName("Device")
        }
    }

    private suspend fun TestScope.connect(
        spec: PeripheralSpec<String> = spec(),
        environment: MockAndroidEnvironment = MockAndroidEnvironment.Api37(),
    ): Peripheral {
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec)) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        runCurrent()
        return peripheral
    }

    @Test
    fun `phy is null when disconnected`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec())) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        runCurrent()

        assertNull(peripheral.phy.value)
    }

    @Test
    fun `phy is read on connection`() = runTest {
        val peripheral = connect()

        assertEquals(PhyInUse.PHY_LE_1M, peripheral.phy.value)
    }

    @Test
    fun `phy is null after disconnecting`() = runTest {
        val peripheral = connect()

        peripheral.disconnect()
        runCurrent()

        assertNull(peripheral.phy.value)
    }

    @Test
    fun `phy update takes 6 connection intervals`() = runTest {
        val peripheral = connect(spec(supports2M = true))
        val start = testScheduler.currentTime

        peripheral.setPreferredPhy(Phy.PHY_LE_2M)

        // The Central allows a minimum of 6 connection events before the instant.
        // The connection interval of 25 units is 31 ms.
        assertEquals(6 * 31L, testScheduler.currentTime - start)
    }

    @Test
    fun `preferred phy is applied when supported by both devices`() = runTest {
        val peripheral = connect(spec(supports2M = true))

        val phy = peripheral.setPreferredPhy(Phy.PHY_LE_2M)
        runCurrent()

        assertEquals(PhyInUse(Phy.PHY_LE_2M, Phy.PHY_LE_2M), phy)
        assertEquals(phy, peripheral.phy.value)
    }

    @Test
    fun `preferred phy not supported by the peripheral is not applied`() = runTest {
        val peripheral = connect(spec(supports2M = false))

        val phy = peripheral.setPreferredPhy(Phy.PHY_LE_2M)
        runCurrent()

        assertEquals(PhyInUse.PHY_LE_1M, phy)
        assertEquals(PhyInUse.PHY_LE_1M, peripheral.phy.value)
    }

    @Test
    fun `preferred phy not supported by the phone is not applied`() = runTest {
        // Android 6 does not support PHY LE 2M.
        val peripheral = connect(spec(supports2M = true), MockAndroidEnvironment.Api23())

        val phy = peripheral.setPreferredPhy(Phy.PHY_LE_2M)
        runCurrent()

        assertEquals(PhyInUse.PHY_LE_1M, phy)
    }

    @Test
    fun `preferred phy not supported by the phone hardware is not applied`() = runTest {
        // Android 8+, but the phone does not support LE 2M.
        val environment = MockAndroidEnvironment.Api31(isLe2MPhySupported = false)
        val peripheral = connect(spec(supports2M = true), environment)

        val phy = peripheral.setPreferredPhy(Phy.PHY_LE_2M)
        runCurrent()

        assertEquals(PhyInUse.PHY_LE_1M, phy)
    }

    @Test
    fun `requesting the phy already in use returns it`() = runTest {
        val peripheral = connect()

        val phy = peripheral.setPreferredPhy(Phy.PHY_LE_1M)

        assertEquals(PhyInUse.PHY_LE_1M, phy)
    }

    @Test
    fun `phy can be changed back to 1M`() = runTest {
        val peripheral = connect()
        peripheral.setPreferredPhy(Phy.PHY_LE_2M)

        val phy = peripheral.setPreferredPhy(Phy.PHY_LE_1M)
        runCurrent()

        assertEquals(PhyInUse.PHY_LE_1M, phy)
        assertEquals(PhyInUse.PHY_LE_1M, peripheral.phy.value)
    }

    @Test
    fun `phy is back to 1M after reconnecting`() = runTest {
        val spec = spec()
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec)) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        peripheral.setPreferredPhy(Phy.PHY_LE_2M)
        peripheral.disconnect()

        manager.connect(peripheral)
        runCurrent()

        assertEquals(PhyInUse.PHY_LE_1M, peripheral.phy.value)
    }

    @Test
    fun `changing phy when disconnected throws`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec())) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<PeripheralNotConnectedException> { peripheral.setPreferredPhy(Phy.PHY_LE_2M) }
    }
}
