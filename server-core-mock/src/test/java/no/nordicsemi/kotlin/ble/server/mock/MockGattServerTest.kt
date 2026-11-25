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
package no.nordicsemi.kotlin.ble.server.mock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MockGattServerTest {

    private fun TestServer.client(id: String = "AA") = TestClient(id, this)

    @Test
    fun `setServices stores definitions for inspection`() = runTest {
        val server = TestServer()
        val services = buildServices()

        server.setServices(services)

        assertEquals(services.size, server.services.value.size)
        assertEquals(SERVICE_UUID, server.services.value.first().uuid)
    }

    @Test
    fun `setServices replaces previous definitions`() = runTest {
        val server = TestServer()
        server.setServices(buildServices())

        server.setServices(emptyList())

        assertTrue(server.services.value.isEmpty())
    }

    @Test
    fun `setServices fails when Bluetooth is off`() = runTest {
        val state = MutableStateFlow(Manager.State.POWERED_OFF)
        val server = TestServer(state)

        assertFailsWith<BluetoothUnavailableException> { server.setServices(buildServices()) }

        state.value = Manager.State.POWERED_ON
        server.setServices(buildServices())
        assertEquals(1, server.services.value.size)
    }

    @Test
    fun `setServices fails when Bluetooth is unsupported`() = runTest {
        val server = TestServer(MutableStateFlow(Manager.State.UNSUPPORTED))

        assertFailsWith<BluetoothUnavailableException> { server.setServices(buildServices()) }
    }

    @Test
    fun `finds service, characteristic and descriptor by UUID`() = runTest {
        val server = TestServer()
        server.setServices(buildServices())

        val service = server.findService(SERVICE_UUID)
        val characteristic = server.findCharacteristic(SERVICE_UUID, READ_WRITE_UUID)
        val descriptor = server.findDescriptor(SERVICE_UUID, READ_WRITE_UUID, DESCRIPTOR_UUID)

        assertNotNull(service)
        assertNotNull(characteristic)
        assertNotNull(descriptor)
        assertEquals(3, setOf(service, characteristic, descriptor).size)
    }

    @Test
    fun `finds included services and their characteristics`() = runTest {
        val server = TestServer()
        server.setServices(buildServices())

        assertNotNull(server.findService(INCLUDED_SERVICE_UUID))
    }

    @Test
    fun `lookup returns null for unknown attributes`() = runTest {
        val server = TestServer()
        server.setServices(buildServices())

        assertNull(server.findService(NOTIFY_UUID))
        assertNull(server.findCharacteristic(SERVICE_UUID, DESCRIPTOR_UUID))
        assertNull(server.findCharacteristic(NOTIFY_UUID, READ_WRITE_UUID))
        assertNull(server.findDescriptor(SERVICE_UUID, READ_WRITE_UUID, NOTIFY_UUID))
    }

    @Test
    fun `finds CCCD of a notifying characteristic only`() = runTest {
        val server = TestServer()
        server.setServices(buildServices())
        val notify = server.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!
        val readWrite = server.findCharacteristic(SERVICE_UUID, READ_WRITE_UUID)!!

        val cccd = server.findCccd(notify)

        assertNotNull(cccd)
        assertEquals(cccd, server.findDescriptor(SERVICE_UUID, NOTIFY_UUID, Descriptor.CLIENT_CHAR_CONF_UUID))
        assertNull(server.findCccd(readWrite))
        assertEquals(Descriptor.CLIENT_CHAR_CONF_UUID, server.findDescriptorDefinition(cccd)?.uuid)
    }

    @Test
    fun `lookup is empty before services are set`() {
        val server = TestServer()

        assertNull(server.findService(SERVICE_UUID))
        assertNull(server.findCccd(1))
    }

    @Test
    fun `close disconnects all clients and emits events`() = runTest {
        val server = TestServer()
        val app = FakeApp(server, this)
        val a = server.client("A").also { it.connect() }
        val b = server.client("B").also { it.connect() }

        server.close()

        assertFalse(a.isConnected)
        assertFalse(b.isConnected)
        assertTrue(server.connectedClients.value.isEmpty())
        assertEquals(2, app.disconnects.size)
        assertTrue(server.isClosed)
    }

    @Test
    fun `closed server rejects new connections`() = runTest {
        val server = TestServer()
        server.close()

        assertFailsWith<IllegalStateException> { server.client().connect() }
    }

    @Test
    fun `disconnectAll disconnects with the given reason`() = runTest {
        val server = TestServer()
        val a = server.client("A").also { it.connect() }

        server.disconnectAll(ConnectionState.Disconnected.Reason.LinkLoss)

        assertEquals(ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.LinkLoss), a.connectionState.value)
    }

    @Test
    fun `notify delivers a notification to the client`() = runTest {
        val server = TestServer()
        val client = server.client().also { it.connect() }
        val updates = collectUpdates(client)

        server.notify(client, attribute = 7, value = byteArrayOf(1, 2, 3), confirm = false)

        assertEquals(1, updates.size)
        assertEquals(7, updates[0].attribute)
        assertContentEquals(byteArrayOf(1, 2, 3), updates[0].value)
        assertFalse(updates[0].isIndication)
    }

    @Test
    fun `notify with confirm delivers an indication`() = runTest {
        val server = TestServer()
        val client = server.client().also { it.connect() }
        val updates = collectUpdates(client)

        server.notify(client, attribute = 7, value = byteArrayOf(9), confirm = true)

        assertTrue(updates.single().isIndication)
    }

    @Test
    fun `notify only reaches the target client`() = runTest {
        val server = TestServer()
        val a = server.client("A").also { it.connect() }
        val b = server.client("B").also { it.connect() }
        val updatesA = collectUpdates(a)
        val updatesB = collectUpdates(b)

        server.notify(b, 1, byteArrayOf(1), false)

        assertTrue(updatesA.isEmpty())
        assertEquals(1, updatesB.size)
    }

    @Test
    fun `notify copies the value`() = runTest {
        val server = TestServer()
        val client = server.client().also { it.connect() }
        val updates = collectUpdates(client)
        val value = byteArrayOf(1, 2)

        server.notify(client, 1, value, false)
        value[0] = 100

        assertEquals(1, updates.single().value[0].toInt())
    }

    @Test
    fun `notify to a disconnected client fails`() = runTest {
        val server = TestServer()
        val client = server.client()

        val exception = assertFailsWith<GattOperationException> {
            server.notify(client, 1, byteArrayOf(1), false)
        }

        assertEquals(OperationStatus.RequestFailed, exception.status)
    }

    @Test
    fun `respond to an unknown request is ignored`() = runTest {
        val server = TestServer()
        val client = server.client().also { it.connect() }

        server.respond(client, 12345, OperationStatus.Success, 0, ByteArray(0))
    }

    @Test
    fun `connected clients are exposed as the server sees them`() = runTest {
        val server = TestServer()
        val client = server.client().also { it.connect() }

        assertSame(client, server.connectedClients.value.single())
        assertSame(client, server.connectedMockClients.value.single())

        client.disconnect()

        assertTrue(server.connectedClients.value.isEmpty())
    }
}
