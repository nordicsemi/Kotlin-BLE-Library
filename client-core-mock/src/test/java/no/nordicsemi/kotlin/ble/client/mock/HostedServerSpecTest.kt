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

package no.nordicsemi.kotlin.ble.client.mock

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.Service
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import no.nordicsemi.kotlin.ble.server.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.LocalService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests of a [PeripheralSpec] hosting a [MockPeripheralServer]: building, the state of the central
 * which follows the connection, and the services.
 */
class HostedServerSpecTest {
    private val serviceUuid = Uuid.fromShortUuid(0xB000)
    private val characteristicUuid = Uuid.fromShortUuid(0xB001)

    private suspend fun TestScope.hostedServer(
        initiallyEnabled: Boolean = true,
    ): Pair<MockPeripheralServer, List<LocalService<SimulatedCentral>>> {
        val server = MockPeripheralServer(backgroundScope)
        val services = server.setup {
            GenericAccessService()
            GenericAttributeService(initiallyEnabled)
            Service(serviceUuid) {
                Characteristic(
                    uuid = characteristicUuid,
                    properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
                    permissions = Permission.READ and Permission.WRITE,
                )
            }
        }
        return server to services
    }

    private fun hostedSpec(
        server: MockPeripheralServer,
        maxAttMtu: Int = 247,
        block: PeripheralSpec.Builder<String>.() -> Unit = {
            connectable(name = "Hosted", maxAttMtu = maxAttMtu, server = server)
        },
    ) = PeripheralSpec.simulatePeripheral("AA:BB:CC:DD:EE:01", builder = block)

    private fun List<LocalService<SimulatedCentral>>.serviceChanged(): LocalCharacteristic<SimulatedCentral> =
        first { it.uuid == Service.GENERIC_ATTRIBUTE_UUID }
            .characteristics
            .first { it.uuid == Characteristic.SERVICE_CHANGED }

    // Building

    @Test
    fun `hosted spec is connectable and keeps its server`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server)

        assertSame(server, spec.server)
        assertEquals("Hosted", spec.name)
        assertNotNull(spec.eventHandler)
        assertEquals(247, spec.maxAttMtu)
    }

    @Test
    fun `spec using an event handler does not have a server`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connectable(name = "Device", eventHandler = object : PeripheralSpecEventHandler {}, services = { })
        }

        assertNull(spec.server)
    }

    @Test
    fun `hosted spec is not connected, known nor bonded by default`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server)

        assertFalse(spec.isConnected)
        assertFalse(spec.isKnown)
        assertFalse(spec.isBonded)
        assertFalse(spec.isServiceCacheValid)
    }

    @Test
    fun `server can not be used by two peripherals`() = runTest {
        val (server, _) = hostedServer()
        val _ = hostedSpec(server)

        assertFailsWith<IllegalArgumentException> { hostedSpec(server) }
    }

    @Test
    fun `stale cached services require isServiceCached`() = runTest {
        val (server, _) = hostedServer()

        assertFailsWith<IllegalArgumentException> {
            hostedSpec(server) {
                connectable(name = "Hosted", staleCachedServices = { }, server = server)
            }
        }
    }

    @Test
    fun `isServiceCached makes the device known and its cache valid`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server) {
            connectable(name = "Hosted", isServiceCached = true, server = server)
        }

        assertTrue(spec.isKnown)
        assertTrue(spec.isServiceCacheValid)
        assertFalse(spec.isBonded)
    }

    @Test
    fun `bonded hosted device is known`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server) {
            connectable(name = "Hosted", isBonded = true, server = server)
        }

        assertTrue(spec.isBonded)
        assertTrue(spec.peripheralHasBond)
        assertTrue(spec.isKnown)
    }

    @Test
    fun `stale cached services make the cache invalid`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server) {
            connectable(name = "Hosted", isServiceCached = true, staleCachedServices = { Service(serviceUuid) }, server = server)
        }

        assertTrue(spec.isKnown)
        assertFalse(spec.isServiceCacheValid)
    }

    // The central

    @Test
    fun `central is not connected until the peripheral is`() = runTest {
        val (server, _) = hostedServer()
        val _ = hostedSpec(server)

        assertFalse(server.central.state.value.isConnected)
        assertTrue(server.connectedCentrals.value.isEmpty())
    }

    @Test
    fun `connecting the peripheral connects the central`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server)

        spec.simulateConnection(mtu = 23)

        assertTrue(spec.isConnected)
        assertTrue(server.central.state.value.isConnected)
        assertEquals(listOf(server.central), server.connectedCentrals.value)
        assertEquals(23, server.central.mtu.value)
        assertEquals(20, server.central.maximumUpdateValueLength)
    }

    @Test
    fun `peripheral created as connected has the central connected`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server) {
            connected(name = "Hosted", maxAttMtu = 247, server = server)
        }

        assertTrue(spec.isConnected)
        assertTrue(server.central.state.value.isConnected)
        assertTrue(spec.isServiceCacheValid)
    }

    @Test
    fun `central mtu follows the mtu of the connection`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server)

        spec.simulateConnection(mtu = 100)

        assertEquals(100, spec.mtu)
        assertEquals(100, server.central.mtu.value)
        assertEquals(97, server.central.maximumUpdateValueLength)
    }

    @Test
    fun `central mtu is limited to the maximum mtu of the peripheral`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server, maxAttMtu = 50)

        spec.simulateConnection(mtu = 247)

        assertEquals(50, server.central.mtu.value)
    }

    @Test
    fun `disconnecting the peripheral disconnects the central`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server)
        spec.simulateConnection()

        spec.simulateDisconnection()

        assertFalse(spec.isConnected)
        assertIs<ConnectionState.Disconnected>(server.central.state.value)
        assertTrue(server.connectedCentrals.value.isEmpty())
    }

    @Test
    fun `peripheral can be connected again after disconnection`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server)
        spec.simulateConnection(mtu = 100)
        spec.simulateDisconnection()

        spec.simulateConnection(mtu = 23)

        assertTrue(server.central.state.value.isConnected)
        // The MTU is negotiated again for each connection.
        assertEquals(23, server.central.mtu.value)
    }

    @Test
    fun `subscriptions are dropped when the peripheral disconnects`() = runTest {
        val (server, services) = hostedServer()
        val characteristic = server.setup {
            Service(serviceUuid) {
                Characteristic(characteristicUuid, CharacteristicProperty.NOTIFY, Permission.READ)
            }
        }.single().characteristics.single()
        val spec = hostedSpec(server)
        spec.simulateConnection()
        val cccd = server.simulatedCentral.findDescriptor(
            serviceUuid,
            characteristicUuid,
            no.nordicsemi.kotlin.ble.core.Descriptor.CLIENT_CHAR_CONF_UUID,
        )
        assertNotNull(cccd)
        assertTrue(server.simulatedCentral.write(cccd, byteArrayOf(1, 0)).isSuccess)
        assertEquals(setOf<SimulatedCentral>(server.central), characteristic.subscribers.value)

        spec.simulateDisconnection()
        runCurrent()

        assertTrue(characteristic.subscribers.value.isEmpty())
        // The services of the previous setup are not used any more.
        assertNull(services.first().manager)
    }

    // Service Changed

    @Test
    fun `central subscribes to Service Changed when it was declared as enabled`() = runTest {
        val (server, services) = hostedServer(initiallyEnabled = true)
        val spec = hostedSpec(server)

        spec.simulateConnection()
        runCurrent()

        assertTrue(server.central in services.serviceChanged().subscribers.value)
    }

    @Test
    fun `central does not subscribe to Service Changed when it was declared as disabled`() = runTest {
        val (server, services) = hostedServer(initiallyEnabled = false)
        val spec = hostedSpec(server)

        spec.simulateConnection()
        runCurrent()

        assertTrue(services.serviceChanged().subscribers.value.isEmpty())
    }

    // Services

    @Test
    fun `service change replaces the hosted services and invalidates the cache`() = runTest {
        val (server, services) = hostedServer()
        val spec = hostedSpec(server) {
            connectable(name = "Hosted", isServiceCached = true, server = server)
        }
        val newUuid = Uuid.fromShortUuid(0xC000)

        spec.simulateServiceChange {
            Service(newUuid)
        }

        assertEquals(listOf(newUuid), server.services.value.map { it.uuid })
        assertEquals(listOf(newUuid), server.definitions.map { it.uuid })
        assertFalse(spec.isServiceCacheValid)
        // The old services are invalid.
        assertTrue(services.all { it.manager == null })
    }

    @Test
    fun `service change keeps the subscription to Service Changed`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server)
        spec.simulateConnection()
        runCurrent()

        spec.simulateServiceChange {
            GenericAttributeService(initiallyEnabled = false)
            Service(Uuid.fromShortUuid(0xC000))
        }
        runCurrent()

        assertTrue(server.central in server.services.value.serviceChanged().subscribers.value)
    }

    @Test
    fun `service change of a disconnected peripheral does not subscribe the central`() = runTest {
        val (server, _) = hostedServer()
        val spec = hostedSpec(server)

        spec.simulateServiceChange {
            GenericAttributeService(initiallyEnabled = true)
        }
        runCurrent()

        assertTrue(server.services.value.serviceChanged().subscribers.value.isEmpty())
    }

    // Server scope

    @Test
    fun `Generic Access service of a server scope has the standard characteristics`() = runTest {
        val (_, services) = hostedServer()

        val gas = services.first { it.uuid == Service.GENERIC_ACCESS_UUID }
        assertEquals(
            listOf(
                Characteristic.DEVICE_NAME,
                Characteristic.APPEARANCE,
                Characteristic.PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS,
            ),
            gas.characteristics.map { it.uuid },
        )
    }

    @Test
    fun `attributes of services of a server have unique handles`() = runTest {
        val (server, _) = hostedServer()

        val handles = server.definitions.flatMap { service ->
            listOf(service.instanceId) + service.characteristics.flatMap { characteristic ->
                listOf(characteristic.instanceId) + characteristic.descriptors.map { it.instanceId }
            }
        }
        assertEquals(handles.size, handles.toSet().size)
    }

    @Test
    fun `reading the central directly reads the value of the hosted characteristic`() = runTest {
        val (server, services) = hostedServer()
        val characteristic = services.first { it.uuid == serviceUuid }.characteristics.single()
        characteristic.setValue(byteArrayOf(1, 2, 3))
        val spec = hostedSpec(server)
        spec.simulateConnection()

        val handle = server.simulatedCentral.findCharacteristic(serviceUuid, characteristicUuid)
        assertNotNull(handle)
        val result = server.simulatedCentral.read(handle)

        assertContentEquals(byteArrayOf(1, 2, 3), result.getOrThrow())
    }
}
