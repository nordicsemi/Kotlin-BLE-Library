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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.GenericAccessService
import no.nordicsemi.kotlin.ble.client.mock.GenericAttributeService
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheralServer
import no.nordicsemi.kotlin.ble.client.mock.ServiceDiscoveryResult
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.Service
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Tests of notifications and indications sent by a hosted GATT server using `update()`,
 * of the MTU, the connection and of the Service Changed.
 */
class HostedNotificationsTest {

    private suspend fun TestScope.notifyingFixture(
        properties: Set<CharacteristicProperty> = setOf(CharacteristicProperty.NOTIFY),
        maxAttMtu: Int = 23,
    ) = hostedFixture(maxAttMtu) {
        Service(HOSTED_SERVICE_UUID) {
            Characteristic(HOSTED_CHAR_UUID, properties, setOf(Permission.READ))
            Characteristic(HOSTED_CHAR_2_UUID, properties, setOf(Permission.READ))
        }
    }

    /** Starts collecting [characteristic] and returns when notifications are enabled. */
    private suspend fun TestScope.collect(
        characteristic: RemoteCharacteristic,
        values: MutableList<ByteArray>,
    ) {
        val enabled = CompletableDeferred<Unit>()
        characteristic
            .subscribe { enabled.complete(Unit) }
            .onEach { values += it }
            .launchIn(backgroundScope)
        enabled.await()
    }

    // ---------------------------------------------------------------- Notifications

    @Test
    fun `notification sent with update is received`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)

            fixture.local().update(byteArrayOf(0x0A, 0x0B, 0x0C))
            hostedSettle()

            assertEquals(1, values.size)
            assertContentEquals(byteArrayOf(0x0A, 0x0B, 0x0C), values.single())
        }
    }

    @Test
    fun `update stores the value`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)

            fixture.local().update(byteArrayOf(0x01))

            assertContentEquals(byteArrayOf(0x01), fixture.local().value.value)
        }
    }

    @Test
    fun `subscribing makes the client a subscriber`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            assertTrue(fixture.local().subscribers.value.isEmpty())

            collect(fixture.remote(), values)

            assertEquals(setOf(fixture.server.client), fixture.local().subscribers.value)
            assertTrue(fixture.local(HOSTED_CHAR_2_UUID).subscribers.value.isEmpty())
        }
    }

    @Test
    fun `update without subscribers does not send anything`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            val characteristic = fixture.remote()
            val other = fixture.remote(HOSTED_CHAR_2_UUID)
            collect(other, values)

            fixture.local().update(byteArrayOf(0x01))
            hostedSettle()

            assertTrue(values.isEmpty())
            assertContentEquals(byteArrayOf(0x01), fixture.local().value.value)
            assertFalse(characteristic.isNotifying.value)
        }
    }

    @Test
    fun `update only reaches the updated characteristic`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val first = mutableListOf<ByteArray>()
            val second = mutableListOf<ByteArray>()
            collect(fixture.remote(HOSTED_CHAR_UUID), first)
            collect(fixture.remote(HOSTED_CHAR_2_UUID), second)

            fixture.local(HOSTED_CHAR_2_UUID).update(byteArrayOf(0x02))
            hostedSettle()

            assertTrue(first.isEmpty())
            assertContentEquals(byteArrayOf(0x02), second.single())
        }
    }

    @Test
    fun `updates are received in the order they were sent`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)

            for (i in 0 until 10) {
                fixture.local().update(byteArrayOf(i.toByte()))
            }
            hostedSettle()

            assertEquals((0..9).map { it.toByte() }, values.map { it.single() })
        }
    }

    @Test
    fun `update takes the time of the transfer`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)

            val start = testScheduler.currentTime
            fixture.local().update(byteArrayOf(0x01))
            val duration = testScheduler.currentTime - start

            assertTrue(duration > 0, "Update should take some time")
            assertTrue(duration < 31, "A notification is sent faster than a connection interval")
        }
    }

    @Test
    fun `notification from update arrives as soon as one sent by simulateValueUpdate`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val characteristic = fixture.remote()
            val arrivals = mutableListOf<Long>()
            characteristic.subscribe().onEach { arrivals += testScheduler.currentTime }.launchIn(backgroundScope)
            hostedSettle()

            val first = testScheduler.currentTime
            fixture.spec.simulateValueUpdate(fixture.local().instanceId, byteArrayOf(0x01))
            hostedSettle()
            val second = testScheduler.currentTime
            fixture.local().update(byteArrayOf(0x01))
            hostedSettle()

            assertEquals(2, arrivals.size)
            assertEquals(arrivals[0] - first, arrivals[1] - second)
        }
    }

    @Test
    fun `unsubscribing stops the notifications`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            val characteristic = fixture.remote()
            collect(characteristic, values)
            fixture.local().update(byteArrayOf(0x01))
            hostedSettle()

            characteristic.setNotifying(false)
            fixture.local().update(byteArrayOf(0x02))
            hostedSettle()

            assertEquals(1, values.size)
            assertTrue(fixture.local().subscribers.value.isEmpty())
        }
    }

    // ---------------------------------------------------------------- Indications

    @Test
    fun `indication sent with update is received`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture(properties = setOf(CharacteristicProperty.INDICATE))
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)

            fixture.local().update(byteArrayOf(0x05, 0x06))
            hostedSettle()

            assertContentEquals(byteArrayOf(0x05, 0x06), values.single())
        }
    }

    @Test
    fun `indication is confirmed after a connection interval`() = runTest {
        withTimeout(10.seconds) {
            val notification = notifyingFixture()
            val indication = hostedFixtureForIndication()
            collect(notification.remote(), mutableListOf())
            collect(indication.remote(), mutableListOf())

            val start = testScheduler.currentTime
            notification.local().update(byteArrayOf(0x01))
            val notificationTime = testScheduler.currentTime - start
            val indicationStart = testScheduler.currentTime
            indication.local().update(byteArrayOf(0x01))
            val indicationTime = testScheduler.currentTime - indicationStart

            // The confirmation takes a connection interval: 25 * 1.25 ms.
            assertEquals(31L, indicationTime - notificationTime)
        }
    }

    private suspend fun TestScope.hostedFixtureForIndication() = hostedFixtureWithAddress(
        properties = setOf(CharacteristicProperty.INDICATE),
    )

    // A second peripheral, so that both can be used in a single test.
    private suspend fun TestScope.hostedFixtureWithAddress(
        properties: Set<CharacteristicProperty>,
    ): HostedFixture {
        val server = MockPeripheralServer(backgroundScope)
        val services = server.setup {
            Service(HOSTED_SERVICE_UUID) {
                Characteristic(HOSTED_CHAR_UUID, properties, setOf(Permission.READ))
            }
        }
        val spec = PeripheralSpec.simulatePeripheral(
            identifier = "AA:BB:CC:DD:EE:03",
            addressType = no.nordicsemi.kotlin.ble.client.mock.AddressType.PUBLIC,
        ) {
            advertising { CompleteLocalName("Second") }
            connectable(name = "Second", server = server)
        }
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec)) }
        return HostedFixture(spec, manager, server, services)
    }

    // ---------------------------------------------------------------- MTU

    @Test
    fun `update longer than the MTU allows fails`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)

            assertEquals(20, fixture.server.client.maximumUpdateValueLength)
            assertFailsWith<IllegalArgumentException> { fixture.local().update(gattBytes(21)) }
            fixture.local().update(gattBytes(20))
            hostedSettle()

            assertContentEquals(gattBytes(20), values.single())
        }
    }

    @Test
    fun `update may be longer after the MTU was increased`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture(maxAttMtu = 247)
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)
            hostedSettle()

            assertEquals(247, fixture.server.client.mtu.value)
            assertEquals(244, fixture.server.client.maximumUpdateValueLength)
            fixture.local().update(gattBytes(244))
            hostedSettle()

            assertContentEquals(gattBytes(244), values.single())
            assertFailsWith<IllegalArgumentException> { fixture.local().update(gattBytes(245)) }
        }
    }

    @Test
    fun `client mtu follows the mtu negotiated by the client`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture(maxAttMtu = 185)
            fixture.connect()
            hostedSettle()

            assertEquals(185, fixture.peripheral.mtu.value)
            assertEquals(185, fixture.server.client.mtu.value)
        }
    }

    // ---------------------------------------------------------------- Connection

    @Test
    fun `client is connected as long as the client is`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            assertFalse(fixture.server.client.state.value.isConnected)

            fixture.connect()
            hostedSettle()
            assertTrue(fixture.server.client.state.value.isConnected)
            assertEquals(listOf(fixture.server.client), fixture.server.connectedClients.value)

            fixture.peripheral.disconnect()
            hostedSettle()
            assertFalse(fixture.server.client.state.value.isConnected)
            assertTrue(fixture.server.connectedClients.value.isEmpty())
        }
    }

    @Test
    fun `subscribers are removed when the client disconnects`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)

            fixture.peripheral.disconnect()
            hostedSettle()

            assertTrue(fixture.local().subscribers.value.isEmpty())
        }
    }

    @Test
    fun `subscribers are removed when the peripheral disconnects`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)

            fixture.spec.simulateDisconnection()
            hostedSettle()

            assertTrue(fixture.local().subscribers.value.isEmpty())
            assertIs<ConnectionState.Disconnected>(fixture.server.client.state.value)
        }
    }

    @Test
    fun `update after the client disconnected does not send anything`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.remote(), values)
            fixture.peripheral.disconnect()
            hostedSettle()

            fixture.local().update(byteArrayOf(0x01))
            hostedSettle()

            assertTrue(values.isEmpty())
        }
    }

    @Test
    fun `client has to subscribe again after reconnecting`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val first = mutableListOf<ByteArray>()
            collect(fixture.remote(), first)
            fixture.peripheral.disconnect()
            hostedSettle()

            val second = mutableListOf<ByteArray>()
            fixture.connect()
            val characteristic = fixture.remote()
            fixture.local().update(byteArrayOf(0x01))
            hostedSettle()
            assertTrue(second.isEmpty())
            assertTrue(fixture.local().subscribers.value.isEmpty())

            collect(characteristic, second)
            fixture.local().update(byteArrayOf(0x02))
            hostedSettle()

            assertContentEquals(byteArrayOf(0x02), second.single())
            assertTrue(first.isEmpty())
        }
    }

    @Test
    fun `reads work after reconnecting`() = runTest {
        withTimeout(10.seconds) {
            val fixture = hostedFixture {
                Service(HOSTED_SERVICE_UUID) {
                    Characteristic(HOSTED_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }
            fixture.local().setValue(byteArrayOf(0x09))
            val _ = fixture.discover()
            fixture.peripheral.disconnect()
            hostedSettle()

            fixture.connect()

            assertContentEquals(byteArrayOf(0x09), fixture.remote().read())
        }
    }

    @Test
    fun `link loss disconnects the client`() = runTest {
        withTimeout(30.seconds) {
            val fixture = notifyingFixture()
            fixture.connect()
            hostedSettle()

            fixture.spec.simulateReset()
            hostedSettle()
            testScheduler.advanceTimeBy(5.seconds.inWholeMilliseconds)
            testScheduler.runCurrent()

            assertFalse(fixture.server.client.state.value.isConnected)
        }
    }

    // ---------------------------------------------------------------- Two clients

    @Test
    fun `update reaches two clients of the same peripheral`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val secondManager = CentralManager.mock(scope = backgroundScope)
                .apply { simulatePeripherals(listOf(fixture.spec)) }
            val first = mutableListOf<ByteArray>()
            val second = mutableListOf<ByteArray>()
            collect(fixture.remote(), first)

            val secondPeripheral = secondManager.getPeripheralById(HOSTED_ADDRESS)!!
            secondManager.connect(secondPeripheral, CentralManager.ConnectionOptions.Direct())
            val secondCharacteristic = secondPeripheral.services()
                .first { it is RemoteServices.Discovered }
                .let { (it as RemoteServices.Discovered).services }
                .first { it.uuid == HOSTED_SERVICE_UUID }
                .characteristics
                .first { it.uuid == HOSTED_CHAR_UUID }
            collect(secondCharacteristic, second)

            fixture.local().update(byteArrayOf(0x42))
            hostedSettle()

            assertContentEquals(byteArrayOf(0x42), first.single())
            assertContentEquals(byteArrayOf(0x42), second.single())
            // The phone is connected to the peripheral once, so there is one client.
            assertEquals(1, fixture.server.connectedClients.value.size)
        }
    }

    @Test
    fun `client stays connected until the last client disconnects`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val secondManager = CentralManager.mock(scope = backgroundScope)
                .apply { simulatePeripherals(listOf(fixture.spec)) }
            fixture.connect()
            val secondPeripheral = secondManager.getPeripheralById(HOSTED_ADDRESS)!!
            secondManager.connect(secondPeripheral, CentralManager.ConnectionOptions.Direct())
            hostedSettle()

            fixture.peripheral.disconnect()
            hostedSettle()
            assertTrue(fixture.server.client.state.value.isConnected)

            secondPeripheral.disconnect()
            hostedSettle()
            assertFalse(fixture.server.client.state.value.isConnected)
        }
    }

    // ---------------------------------------------------------------- Service Changed

    private val newServiceUuid = Uuid.fromShortUuid(0xC000)

    private suspend fun TestScope.serviceChangedFixture(initiallyEnabled: Boolean = true) = hostedFixture {
        GenericAccessService()
        GenericAttributeService(initiallyEnabled)
        Service(HOSTED_SERVICE_UUID)
    }

    @Test
    fun `client discovers new services after service change`() = runTest {
        withTimeout(10.seconds) {
            val fixture = serviceChangedFixture()
            val _ = fixture.discover()
            hostedSettle()

            fixture.spec.simulateServiceChange {
                GenericAttributeService()
                Service(newServiceUuid)
            }

            val state = fixture.peripheral.services()
                .first { it is RemoteServices.Discovered && it.services.any { service -> service.uuid == newServiceUuid } }
            assertIs<RemoteServices.Discovered>(state)
            assertEquals(listOf(Service.GENERIC_ATTRIBUTE_UUID, newServiceUuid), state.services.map { it.uuid })
        }
    }

    @Test
    fun `characteristics of new services use the new handlers`() = runTest {
        withTimeout(10.seconds) {
            val fixture = serviceChangedFixture()
            val _ = fixture.discover()
            hostedSettle()

            fixture.spec.simulateServiceChange {
                GenericAttributeService()
                Service(newServiceUuid) {
                    Characteristic(HOSTED_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }
            hostedSettle()
            val newService = fixture.server.services.value.first { it.uuid == newServiceUuid }
            newService.characteristics.single().setValue(byteArrayOf(0x77))
            val remote = fixture.peripheral.services()
                .first { it is RemoteServices.Discovered && it.services.any { service -> service.uuid == newServiceUuid } }
                .let { (it as RemoteServices.Discovered).services }
                .first { it.uuid == newServiceUuid }
                .characteristics
                .single()

            assertContentEquals(byteArrayOf(0x77), remote.read())
        }
    }

    @Test
    fun `second service change is notified too`() = runTest {
        withTimeout(10.seconds) {
            val fixture = serviceChangedFixture()
            val _ = fixture.discover()
            hostedSettle()
            fixture.spec.simulateServiceChange {
                GenericAttributeService()
                Service(newServiceUuid)
            }
            fixture.peripheral.services()
                .first { it is RemoteServices.Discovered && it.services.any { service -> service.uuid == newServiceUuid } }
            hostedSettle()

            fixture.spec.simulateServiceChange {
                GenericAttributeService()
                Service(HOSTED_SERVICE_UUID)
            }

            val state = fixture.peripheral.services()
                .first { it is RemoteServices.Discovered && it.services.any { service -> service.uuid == HOSTED_SERVICE_UUID } }
            assertIs<RemoteServices.Discovered>(state)
        }
    }

    @Test
    fun `service change is not notified when the peripheral has no Service Changed characteristic`() = runTest {
        withTimeout(10.seconds) {
            val fixture = hostedFixture { Service(HOSTED_SERVICE_UUID) }
            val _ = fixture.discover()
            hostedSettle()

            fixture.spec.simulateServiceChange {
                Service(newServiceUuid)
            }
            hostedSettle()

            val state = fixture.peripheral.services().value
            assertIs<RemoteServices.Discovered>(state)
            assertTrue(state.services.any { it.uuid == HOSTED_SERVICE_UUID })
            assertFalse(state.services.any { it.uuid == newServiceUuid })
        }
    }

    @Test
    fun `client enables indications of Service Changed when it was not enabled by default`() = runTest {
        withTimeout(10.seconds) {
            val fixture = serviceChangedFixture(initiallyEnabled = false)
            val _ = fixture.discover()
            hostedSettle()

            val serviceChanged = fixture.services
                .first { it.uuid == Service.GENERIC_ATTRIBUTE_UUID }
                .characteristics
                .single()
            assertEquals(setOf(fixture.server.client), serviceChanged.subscribers.value)

            fixture.spec.simulateServiceChange {
                GenericAttributeService(initiallyEnabled = false)
                Service(newServiceUuid)
            }

            val state = fixture.peripheral.services()
                .first { it is RemoteServices.Discovered && it.services.any { service -> service.uuid == newServiceUuid } }
            assertIs<RemoteServices.Discovered>(state)
        }
    }

    // ---------------------------------------------------------------- Caching

    @Test
    fun `services are discovered when they were not cached`() = runTest {
        withTimeout(10.seconds) {
            var discoveries = 0
            val eventHandler = object : PeripheralSpecEventHandler {
                override fun onServiceDiscoveryRequest(peripheral: MockPeripheral, uuids: List<Uuid>): ServiceDiscoveryResult {
                    discoveries++
                    return ServiceDiscoveryResult.Success
                }
            }
            val fixture = hostedFixture(eventHandler = eventHandler) { Service(HOSTED_SERVICE_UUID) }

            val _ = fixture.discover()

            assertEquals(1, discoveries)
        }
    }

    @Test
    fun `cached services of a bonded peripheral are returned without discovery`() = runTest {
        withTimeout(10.seconds) {
            var discoveries = 0
            val eventHandler = object : PeripheralSpecEventHandler {
                override fun onServiceDiscoveryRequest(peripheral: MockPeripheral, uuids: List<Uuid>): ServiceDiscoveryResult {
                    discoveries++
                    return ServiceDiscoveryResult.Success
                }
            }
            val fixture = hostedFixture(bonded = true, eventHandler = eventHandler) {
                Service(HOSTED_SERVICE_UUID) {
                    Characteristic(HOSTED_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }
            fixture.local().setValue(byteArrayOf(0x21))

            assertContentEquals(byteArrayOf(0x21), fixture.remote().read())
            assertEquals(0, discoveries)
        }
    }

    @Test
    fun `stale cached services fail with invalid handle until the services are discovered again`() = runTest {
        withTimeout(10.seconds) {
            val server = MockPeripheralServer(backgroundScope)
            val services = server.setup {
                Service(HOSTED_SERVICE_UUID) {
                    Characteristic(HOSTED_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }
            services.single().characteristics.single().setValue(byteArrayOf(0x21))
            val spec = PeripheralSpec.simulatePeripheral(
                identifier = HOSTED_ADDRESS,
                addressType = no.nordicsemi.kotlin.ble.client.mock.AddressType.PUBLIC,
            ) {
                advertising { CompleteLocalName("Stale") }
                connectable(
                    name = "Stale",
                    isServiceCached = true,
                    staleCachedServices = {
                        Service(HOSTED_SERVICE_UUID) {
                            Characteristic(HOSTED_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                        }
                    },
                    server = server,
                )
            }
            val manager = CentralManager.mock(scope = backgroundScope)
                .apply { simulatePeripherals(listOf(spec)) }
            val fixture = HostedFixture(spec, manager, server, services)

            val exception = assertFailsWith<OperationFailedException> { fixture.remote().read() }

            assertEquals(OperationStatus.InvalidHandle, exception.reason)
        }
    }
}
