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

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.server.android.LocalServer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Discovers the characteristic of the server, the way a client application does. */
private suspend fun MockAndroidClient.characteristic(uuid: Uuid): RemoteCharacteristic =
    server.services(listOf(SERVICE_UUID))
        .filterIsInstance<RemoteServices.Discovered>()
        .first { it.services.isNotEmpty() }
        .services.single()
        .characteristics.first { it.uuid == uuid }

class ClientServerTest {

    @Test
    fun `server is not available before connecting`() = runTest {
        val client = mockManager().simulateClient()

        assertFailsWith<IllegalStateException> { client.server }
    }

    @Test
    fun `client discovers the services of the server`() = runTest {
        val manager = mockManager()
        val _ = manager.defineServices()
        val client = manager.simulateClient()
        client.connect()

        val services = client.server.services(listOf(SERVICE_UUID))
            .filterIsInstance<RemoteServices.Discovered>()
            .first { it.services.isNotEmpty() }.services

        assertEquals(listOf(SERVICE_UUID), services.map { it.uuid })
        assertEquals(setOf(RW_UUID, NOTIFY_UUID, INDICATE_UUID), services.single().characteristics.map { it.uuid }.toSet())
    }

    @Test
    fun `client writes and reads a characteristic of the server`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val client = manager.simulateClient()
        client.connect()
        val rw = client.characteristic(RW_UUID)

        rw.write(byteArrayOf(1, 2, 3), WriteType.WITH_RESPONSE)

        assertContentEquals(byteArrayOf(1, 2, 3), service.characteristic(RW_UUID).value.value)
        assertContentEquals(byteArrayOf(1, 2, 3), rw.read())
    }

    @Test
    fun `client receives notifications after subscribing`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val client = manager.simulateClient()
        client.connect()
        val notifying = client.characteristic(NOTIFY_UUID)

        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { notifying.subscribe().collect { received += it } }
        runCurrent()
        assertEquals(1, service.characteristic(NOTIFY_UUID).subscribers.value.size)

        service.characteristic(NOTIFY_UUID).update(byteArrayOf(7))
        runCurrent()

        assertContentEquals(byteArrayOf(7), received.single())
    }

    @Test
    fun `server peripheral reports disconnection and a new one is created on reconnection`() = runTest {
        val manager = mockManager()
        val _ = manager.defineServices()
        val client = manager.simulateClient()
        client.connect()
        val first = client.server
        runCurrent()

        client.disconnect()
        runCurrent()
        assertTrue(first.state.value is ConnectionState.Disconnected)

        client.connect()
        assertNotSame(first, client.server)
        assertEquals(ConnectionState.Connected, client.server.state.value)
    }

    @Test
    fun `peripheral view works with a server created using the factory`() = runTest {
        val manager: LocalServer = mockManager()
        val _ = (manager as MockLocalServer).defineServices()
        val client = (manager as MockLocalServer).simulateClient()
        client.connect()

        assertEquals(RW_UUID, client.characteristic(RW_UUID).uuid)
    }
}
