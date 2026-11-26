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


package no.nordicsemi.kotlin.ble.client.android.mock

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import no.nordicsemi.kotlin.ble.server.WriteRequest
import no.nordicsemi.kotlin.ble.server.android.LocalServer
import no.nordicsemi.kotlin.ble.server.android.mock.MockLocalServer
import no.nordicsemi.kotlin.ble.server.android.mock.mock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val ALERT_SERVICE_UUID = Uuid.parse("00001802-0000-1000-8000-00805f9b34fb")
private val ALERT_LEVEL_UUID = Uuid.parse("00002a06-0000-1000-8000-00805f9b34fb")
private val BUTTON_UUID = Uuid.parse("00001524-1212-efde-1523-785feabcd123")
private const val TAG_ADDRESS = "AA:BB:CC:DD:EE:01"

/** Tests of a mock peripheral, which is a client of the GATT server of the phone. */
class ClientRoleTest {

    /** The phone's server with the Immediate Alert Service and a characteristic that notifies. */
    private suspend fun MockLocalServer.defineServices() {
        val _ = setup {
            Service(ALERT_SERVICE_UUID) {
                Characteristic(
                    ALERT_LEVEL_UUID,
                    CharacteristicProperty.READ and CharacteristicProperty.WRITE_WITHOUT_RESPONSE and
                        CharacteristicProperty.NOTIFY,
                    Permission.READ and Permission.WRITE,
                )
            }
        }
    }

    private fun TestScope.setUp(
        server: MockLocalServer,
        role: suspend kotlinx.coroutines.CoroutineScope.(no.nordicsemi.kotlin.ble.client.Peripheral<String, *>) -> Unit,
    ): Pair<MockCentralManager, no.nordicsemi.kotlin.ble.client.android.Peripheral> {
        val spec = PeripheralSpec.simulatePeripheral(identifier = TAG_ADDRESS, addressType = AddressType.PUBLIC) {
            advertising { CompleteLocalName("Tag") }
            connectable(name = "Tag", eventHandler = object : PeripheralSpecEventHandler {}, services = { })
            client(role)
        }
        val manager = CentralManager.mock(environment = LatestApi(), scope = backgroundScope, server = server)
            .apply { simulatePeripherals(listOf(spec)) }
        return manager to manager.getPeripheralById(TAG_ADDRESS)!!
    }

    private fun tagSpec(
        role: suspend kotlinx.coroutines.CoroutineScope.(no.nordicsemi.kotlin.ble.client.Peripheral<String, *>) -> Unit,
    ) = PeripheralSpec.simulatePeripheral(identifier = TAG_ADDRESS, addressType = AddressType.PUBLIC) {
        advertising { CompleteLocalName("Tag") }
        connectable(name = "Tag", eventHandler = object : PeripheralSpecEventHandler {}, services = { })
        client(role)
    }

    private suspend fun no.nordicsemi.kotlin.ble.client.Peripheral<String, *>.alertLevel(): RemoteCharacteristic =
        services(listOf(ALERT_SERVICE_UUID))
            .filterIsInstance<RemoteServices.Discovered>()
            .first { it.services.isNotEmpty() }
            .services.single()
            .characteristics.first { it.uuid == ALERT_LEVEL_UUID }

    @Test
    fun `peripheral writes to the characteristic of the phone`() = runTest {
        withTimeout(10.seconds) {
            val server = LocalServer.mock(LatestApi(), backgroundScope).apply { defineServices() }
            val requests = mutableListOf<WriteRequest>()
            server.services.value.single().characteristics.single().writes
                .onEach { requests += it }
                .launchIn(backgroundScope)
            val (manager, peripheral) = setUp(server) { phone ->
                phone.alertLevel().write(byteArrayOf(2), WriteType.WITHOUT_RESPONSE)
            }

            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()

            assertEquals(1, requests.size)
            assertEquals(TAG_ADDRESS, requests.single().client.identifier)
            assertContentEquals(byteArrayOf(2), requests.single().value)
        }
    }

    @Test
    fun `peripheral reads the characteristic of the phone`() = runTest {
        withTimeout(10.seconds) {
            val server = LocalServer.mock(LatestApi(), backgroundScope).apply { defineServices() }
            server.services.value.single().characteristics.single().setValue(byteArrayOf(7, 8))
            var value: ByteArray? = null
            val (manager, peripheral) = setUp(server) { phone ->
                value = phone.alertLevel().read()
            }

            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()

            assertContentEquals(byteArrayOf(7, 8), value)
        }
    }

    @Test
    fun `peripheral is a client of the phone only while it is connected`() = runTest {
        withTimeout(10.seconds) {
            val server = LocalServer.mock(LatestApi(), backgroundScope).apply { defineServices() }
            val (manager, peripheral) = setUp(server) { }

            assertTrue(server.connectedClients.value.isEmpty())

            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()
            assertEquals(listOf(TAG_ADDRESS), server.connectedClients.value.map { it.address })

            peripheral.disconnect()
            gattSettle()
            assertTrue(server.connectedClients.value.isEmpty())

            // And again after reconnecting.
            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()
            assertEquals(listOf(TAG_ADDRESS), server.connectedClients.value.map { it.address })
        }
    }

    @Test
    fun `peripheral receives notifications of the phone`() = runTest {
        withTimeout(10.seconds) {
            val server = LocalServer.mock(LatestApi(), backgroundScope).apply { defineServices() }
            val received = mutableListOf<ByteArray>()
            val (manager, peripheral) = setUp(server) { phone ->
                phone.alertLevel().subscribe().onEach { received += it }.launchIn(this)
            }

            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()
            val characteristic = server.services.value.single().characteristics.single()
            assertEquals(1, characteristic.subscribers.value.size)

            characteristic.update(byteArrayOf(1))
            gattSettle()

            assertEquals(1, received.size)
            assertContentEquals(byteArrayOf(1), received.single())
        }
    }

    @Test
    fun `peripheral discovers the services set up after the connection`() = runTest {
        withTimeout(10.seconds) {
            val server = LocalServer.mock(LatestApi(), backgroundScope)
            val requests = mutableListOf<WriteRequest>()
            val (manager, peripheral) = setUp(server) { phone ->
                phone.alertLevel().write(byteArrayOf(1), WriteType.WITHOUT_RESPONSE)
            }

            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()
            // There are no services yet, so there is nothing to write to.
            assertTrue(requests.isEmpty())

            server.defineServices()
            server.services.value.single().characteristics.single().writes
                .onEach { requests += it }
                .launchIn(backgroundScope)
            gattSettle()

            // The services were discovered again, as they have changed.
            assertEquals(1, requests.size)
            assertContentEquals(byteArrayOf(1), requests.single().value)
        }
    }

    @Test
    fun `the manager creates the server when it is first accessed`() = runTest {
        withTimeout(10.seconds) {
            val manager = CentralManager.mock(environment = LatestApi(), scope = backgroundScope)
                .apply { simulatePeripherals(listOf(tagSpec { phone ->
                    phone.alertLevel().write(byteArrayOf(3), WriteType.WITHOUT_RESPONSE)
                })) }
            val requests = mutableListOf<WriteRequest>()
            // Services of the manager's own server, defined after the manager was created.
            manager.server.defineServices()
            manager.server.services.value.single().characteristics.single().writes
                .onEach { requests += it }
                .launchIn(backgroundScope)

            val peripheral = manager.getPeripheralById(TAG_ADDRESS)!!
            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()

            assertContentEquals(byteArrayOf(3), requests.single().value)
        }
    }

    @Test
    fun `the manager closes the server it created, but not the one it was given`() = runTest {
        withTimeout(10.seconds) {
            val own = CentralManager.mock(environment = LatestApi(), scope = backgroundScope)
            val ownServer = own.server
            own.close()
            assertFailsWith<ManagerClosedException> { ownServer.setup { } }

            val given = LocalServer.mock(LatestApi(), backgroundScope)
            val manager = CentralManager.mock(environment = LatestApi(), scope = backgroundScope, server = given)
            assertSame(given, manager.server)
            manager.close()
            val _ = given.setup { }
        }
    }
}
