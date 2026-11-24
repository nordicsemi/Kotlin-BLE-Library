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
package no.nordicsemi.kotlin.ble.server.android.mock

import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MockAndroidCentralTest {

    @Test
    fun `random addresses look like MAC addresses and differ`() = runTest {
        val manager = mockManager()

        val a = manager.simulateCentral()
        val b = manager.simulateCentral()

        val regex = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
        assertTrue(regex.matches(a.address))
        assertTrue(regex.matches(b.address))
        assertNotEquals(a.address, b.address)
    }

    @Test
    fun `address is used as identifier`() = runTest {
        val central = mockManager().simulateCentral("11:22:33:44:55:66")

        assertEquals("11:22:33:44:55:66", central.address)
        assertEquals("11:22:33:44:55:66", central.identifier)
    }

    @Test
    fun `central with the same address cannot be created twice`() = runTest {
        val manager = mockManager()
        val _ = manager.simulateCentral("11:22:33:44:55:66")

        assertFailsWith<IllegalArgumentException> { manager.simulateCentral("11:22:33:44:55:66") }
    }

    @Test
    fun `new central is disconnected and has no link information`() = runTest {
        val central = mockManager().simulateCentral()

        assertEquals(ConnectionState.Disconnected(), central.state.value)
        assertNull(central.mtu.value)
        assertNull(central.phy.value)
        assertNull(central.connectionParameters.value)
    }

    @Test
    fun `connect sets default link information`() = runTest {
        val manager = mockManager()
        val central = manager.simulateCentral()

        central.connect()

        assertEquals(ConnectionState.Connected, central.state.value)
        assertEquals(23, central.mtu.value)
        assertEquals(20, central.maximumUpdateValueLength)
        assertEquals(PhyInUse.PHY_LE_1M, central.phy.value)
        assertEquals(ConnectionParameters.Unknown, central.connectionParameters.value)
        assertSame(central, manager.connectedCentrals.value.single())
    }

    @Test
    fun `disconnect clears link information and removes the central`() = runTest {
        val manager = mockManager()
        val central = manager.simulateCentral()
        central.connect()

        central.disconnect()

        assertEquals(
            ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.TerminateLocalHost),
            central.state.value,
        )
        assertNull(central.mtu.value)
        assertNull(central.phy.value)
        assertNull(central.connectionParameters.value)
        assertTrue(manager.connectedCentrals.value.isEmpty())
    }

    @Test
    fun `link loss is reported with its reason`() = runTest {
        val central = mockManager().simulateCentral().also { it.connect() }

        central.simulateLinkLoss()

        assertEquals(ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.LinkLoss), central.state.value)
    }

    @Test
    fun `MTU change updates MTU and maximum update value length`() = runTest {
        val central = mockManager().simulateCentral().also { it.connect() }

        val mtu = central.simulateMtuChange(185)

        assertEquals(185, mtu)
        assertEquals(185, central.mtu.value)
        assertEquals(182, central.maximumUpdateValueLength)
    }

    @Test
    fun `MTU is limited to the maximum of the environment`() = runTest {
        // Android 4.4 supports only the default MTU.
        val manager = mockManager(MockAndroidEnvironment.Api19())
        val central = manager.simulateCentral().also { it.connect() }

        val _ = central.simulateMtuChange(517)

        assertEquals(23, central.mtu.value)
    }

    @Test
    fun `MTU is limited to 517`() = runTest {
        val central = mockManager().simulateCentral().also { it.connect() }

        val _ = central.simulateMtuChange(1000)

        assertEquals(517, central.mtu.value)
    }

    @Test
    fun `MTU is reset when reconnecting`() = runTest {
        val central = mockManager().simulateCentral().also { it.connect() }
        val _ = central.simulateMtuChange(185)
        central.disconnect()

        central.connect()

        assertEquals(23, central.mtu.value)
    }

    @Test
    fun `preferred PHY is applied when supported`() = runTest {
        val central = mockManager(MockAndroidEnvironment.Api31()).simulateCentral().also { it.connect() }

        val phy = central.setPreferredPhy(Phy.PHY_LE_2M)

        assertEquals(PhyInUse(Phy.PHY_LE_2M, Phy.PHY_LE_2M), phy)
        assertEquals(phy, central.phy.value)
    }

    @Test
    fun `preferred PHY falls back to 1M when not supported`() = runTest {
        val environment = MockAndroidEnvironment.Api31(isLe2MPhySupported = false, isLeCodedPhySupported = false)
        val central = mockManager(environment).simulateCentral().also { it.connect() }

        assertEquals(PhyInUse.PHY_LE_1M, central.setPreferredPhy(Phy.PHY_LE_2M))
        assertEquals(PhyInUse.PHY_LE_1M, central.setPreferredPhy(Phy.PHY_LE_CODED))
    }

    @Test
    fun `PHY can have different TX and RX`() = runTest {
        val central = mockManager().simulateCentral().also { it.connect() }

        central.simulatePhyChange(Phy.PHY_LE_CODED, Phy.PHY_LE_2M)

        assertEquals(PhyInUse(Phy.PHY_LE_CODED, Phy.PHY_LE_2M), central.phy.value)
    }

    @Test
    fun `connection parameters change is reported`() = runTest {
        val central = mockManager().simulateCentral().also { it.connect() }

        central.simulateConnectionParametersChange(connectionInterval = 24, latency = 2, supervisionTimeout = 400)

        assertEquals(ConnectionParameters(24, 2, 400), central.connectionParameters.value)
    }

    @Test
    fun `link changes fail when not connected`() = runTest {
        val central = mockManager().simulateCentral()

        assertFailsWith<IllegalStateException> { central.simulateMtuChange(100) }
        assertFailsWith<IllegalStateException> { central.simulatePhyChange(Phy.PHY_LE_2M) }
        assertFailsWith<IllegalStateException> { central.simulateConnectionParametersChange(ConnectionParameters.Unknown) }
        assertFailsWith<IllegalStateException> { central.setPreferredPhy(Phy.PHY_LE_2M) }
    }

    @Test
    fun `several centrals are connected independently`() = runTest {
        val manager = mockManager()
        val a = manager.simulateCentral("A").also { it.connect() }
        val b = manager.simulateCentral("B").also { it.connect() }
        val c = manager.simulateCentral("C")

        val _ = a.simulateMtuChange(100)

        assertEquals(setOf("A", "B"), manager.connectedCentrals.value.map { it.address }.toSet())
        assertEquals(100, a.mtu.value)
        assertEquals(23, b.mtu.value)
        assertFalse(c.state.value.isConnected)

        a.disconnect()
        assertEquals(listOf("B"), manager.connectedCentrals.value.map { it.address })
    }

    @Test
    fun `manager state follows the environment`() = runTest {
        val environment = MockAndroidEnvironment.Api31()
        val manager = mockManager(environment)
        assertEquals(Manager.State.POWERED_ON, manager.state.value)

        environment.simulatePowerOff()

        assertEquals(Manager.State.POWERED_OFF, manager.state.value)
    }

    @Test
    fun `turning Bluetooth off disconnects all centrals`() = runTest {
        val environment = MockAndroidEnvironment.Api31()
        val manager = mockManager(environment)
        val a = manager.simulateCentral().also { it.connect() }
        val b = manager.simulateCentral().also { it.connect() }
        testScheduler.runCurrent()

        environment.simulatePowerOff()
        testScheduler.runCurrent()

        assertFalse(a.state.value.isConnected)
        assertFalse(b.state.value.isConnected)
        assertTrue(manager.connectedCentrals.value.isEmpty())
    }
}
