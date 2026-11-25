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

class MockAndroidClientTest {

    @Test
    fun `random addresses look like MAC addresses and differ`() = runTest {
        val manager = mockManager()

        val a = manager.simulateClient()
        val b = manager.simulateClient()

        val regex = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
        assertTrue(regex.matches(a.address))
        assertTrue(regex.matches(b.address))
        assertNotEquals(a.address, b.address)
    }

    @Test
    fun `address is used as identifier`() = runTest {
        val client = mockManager().simulateClient("11:22:33:44:55:66")

        assertEquals("11:22:33:44:55:66", client.address)
        assertEquals("11:22:33:44:55:66", client.identifier)
    }

    @Test
    fun `client with the same address cannot be created twice`() = runTest {
        val manager = mockManager()
        val _ = manager.simulateClient("11:22:33:44:55:66")

        assertFailsWith<IllegalArgumentException> { manager.simulateClient("11:22:33:44:55:66") }
    }

    @Test
    fun `new client is disconnected and has no link information`() = runTest {
        val client = mockManager().simulateClient()

        assertEquals(ConnectionState.Disconnected(), client.state.value)
        assertNull(client.mtu.value)
        assertNull(client.phy.value)
        assertNull(client.connectionParameters.value)
    }

    @Test
    fun `connect sets default link information`() = runTest {
        val manager = mockManager()
        val client = manager.simulateClient()

        client.connect()

        assertEquals(ConnectionState.Connected, client.state.value)
        assertEquals(23, client.mtu.value)
        assertEquals(20, client.maximumUpdateValueLength)
        assertEquals(PhyInUse.PHY_LE_1M, client.phy.value)
        assertEquals(ConnectionParameters.Unknown, client.connectionParameters.value)
        assertSame(client, manager.connectedClients.value.single())
    }

    @Test
    fun `disconnect clears link information and removes the client`() = runTest {
        val manager = mockManager()
        val client = manager.simulateClient()
        client.connect()

        client.disconnect()

        assertEquals(
            ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.TerminateLocalHost),
            client.state.value,
        )
        assertNull(client.mtu.value)
        assertNull(client.phy.value)
        assertNull(client.connectionParameters.value)
        assertTrue(manager.connectedClients.value.isEmpty())
    }

    @Test
    fun `link loss is reported with its reason`() = runTest {
        val client = mockManager().simulateClient().also { it.connect() }

        client.simulateLinkLoss()

        assertEquals(ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.LinkLoss), client.state.value)
    }

    @Test
    fun `MTU change updates MTU and maximum update value length`() = runTest {
        val client = mockManager().simulateClient().also { it.connect() }

        val mtu = client.simulateMtuChange(185)

        assertEquals(185, mtu)
        assertEquals(185, client.mtu.value)
        assertEquals(182, client.maximumUpdateValueLength)
    }

    @Test
    fun `MTU is limited to the maximum of the environment`() = runTest {
        // Android 4.4 supports only the default MTU.
        val manager = mockManager(MockAndroidEnvironment.Api19())
        val client = manager.simulateClient().also { it.connect() }

        val _ = client.simulateMtuChange(517)

        assertEquals(23, client.mtu.value)
    }

    @Test
    fun `MTU is limited to 517`() = runTest {
        val client = mockManager().simulateClient().also { it.connect() }

        val _ = client.simulateMtuChange(1000)

        assertEquals(517, client.mtu.value)
    }

    @Test
    fun `MTU is reset when reconnecting`() = runTest {
        val client = mockManager().simulateClient().also { it.connect() }
        val _ = client.simulateMtuChange(185)
        client.disconnect()

        client.connect()

        assertEquals(23, client.mtu.value)
    }

    @Test
    fun `preferred PHY is applied when supported`() = runTest {
        val client = mockManager(MockAndroidEnvironment.Api31()).simulateClient().also { it.connect() }

        val phy = client.setPreferredPhy(Phy.PHY_LE_2M)

        assertEquals(PhyInUse(Phy.PHY_LE_2M, Phy.PHY_LE_2M), phy)
        assertEquals(phy, client.phy.value)
    }

    @Test
    fun `preferred PHY falls back to 1M when not supported`() = runTest {
        val environment = MockAndroidEnvironment.Api31(isLe2MPhySupported = false, isLeCodedPhySupported = false)
        val client = mockManager(environment).simulateClient().also { it.connect() }

        assertEquals(PhyInUse.PHY_LE_1M, client.setPreferredPhy(Phy.PHY_LE_2M))
        assertEquals(PhyInUse.PHY_LE_1M, client.setPreferredPhy(Phy.PHY_LE_CODED))
    }

    @Test
    fun `PHY can have different TX and RX`() = runTest {
        val client = mockManager().simulateClient().also { it.connect() }

        client.simulatePhyChange(Phy.PHY_LE_CODED, Phy.PHY_LE_2M)

        assertEquals(PhyInUse(Phy.PHY_LE_CODED, Phy.PHY_LE_2M), client.phy.value)
    }

    @Test
    fun `connection parameters change is reported`() = runTest {
        val client = mockManager().simulateClient().also { it.connect() }

        client.simulateConnectionParametersChange(connectionInterval = 24, latency = 2, supervisionTimeout = 400)

        assertEquals(ConnectionParameters(24, 2, 400), client.connectionParameters.value)
    }

    @Test
    fun `link changes fail when not connected`() = runTest {
        val client = mockManager().simulateClient()

        assertFailsWith<IllegalStateException> { client.simulateMtuChange(100) }
        assertFailsWith<IllegalStateException> { client.simulatePhyChange(Phy.PHY_LE_2M) }
        assertFailsWith<IllegalStateException> { client.simulateConnectionParametersChange(ConnectionParameters.Unknown) }
        assertFailsWith<IllegalStateException> { client.setPreferredPhy(Phy.PHY_LE_2M) }
    }

    @Test
    fun `several clients are connected independently`() = runTest {
        val manager = mockManager()
        val a = manager.simulateClient("A").also { it.connect() }
        val b = manager.simulateClient("B").also { it.connect() }
        val c = manager.simulateClient("C")

        val _ = a.simulateMtuChange(100)

        assertEquals(setOf("A", "B"), manager.connectedClients.value.map { it.address }.toSet())
        assertEquals(100, a.mtu.value)
        assertEquals(23, b.mtu.value)
        assertFalse(c.state.value.isConnected)

        a.disconnect()
        assertEquals(listOf("B"), manager.connectedClients.value.map { it.address })
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
    fun `turning Bluetooth off disconnects all clients`() = runTest {
        val environment = MockAndroidEnvironment.Api31()
        val manager = mockManager(environment)
        val a = manager.simulateClient().also { it.connect() }
        val b = manager.simulateClient().also { it.connect() }
        testScheduler.runCurrent()

        environment.simulatePowerOff()
        testScheduler.runCurrent()

        assertFalse(a.state.value.isConnected)
        assertFalse(b.state.value.isConnected)
        assertTrue(manager.connectedClients.value.isEmpty())
    }
}
