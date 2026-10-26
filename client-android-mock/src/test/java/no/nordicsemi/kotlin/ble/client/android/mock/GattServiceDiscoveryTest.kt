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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.mock.ServiceDiscoveryResult
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.Service
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class GattServiceDiscoveryTest {
    private val serviceA = Uuid.fromShortUuid(0xA100)
    private val serviceB = Uuid.fromShortUuid(0xA200)
    private val includedUuid = Uuid.fromShortUuid(0xA300)

    @Test
    fun `services are Unknown before connecting`() = runTest {
        val fixture = gattFixture { Service(serviceA) }
        val peripheral = fixture.manager.getPeripheralById(fixture.spec.identifier)!!

        assertEquals(RemoteServices.Unknown, peripheral.services().value)
    }

    @Test
    fun `services state goes Unknown, Discovering, Discovered`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture { Service(serviceA) }
            val peripheral = fixture.manager.getPeripheralById(fixture.spec.identifier)!!
            val states = mutableListOf<RemoteServices>()
            peripheral.services().onEach { states += it }.launchIn(backgroundScope)
            runCurrent()

            fixture.connect()
            peripheral.services().first { it is RemoteServices.Discovered }
            gattSettle()

            assertEquals(3, states.size, "States: $states")
            assertEquals(RemoteServices.Unknown, states[0])
            assertEquals(RemoteServices.Discovering, states[1])
            assertIs<RemoteServices.Discovered>(states[2])
        }
    }

    @Test
    fun `discovery returns all primary services with their UUIDs`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(serviceA)
                Service(serviceB)
            }

            val services = fixture.discover()

            assertEquals(listOf(serviceA, serviceB), services.map { it.uuid })
            assertTrue(services.all { it.isPrimary })
        }
    }

    @Test
    fun `discovered characteristics report UUIDs and properties`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(serviceA) {
                    Characteristic(
                        uuid = GATT_CHAR_UUID,
                        properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
                        permissions = Permission.READ and Permission.WRITE,
                    )
                    Characteristic(
                        uuid = GATT_CHAR_2_UUID,
                        property = CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
                        permission = Permission.WRITE,
                    )
                }
            }

            val characteristics = fixture.discover().single().characteristics

            assertEquals(listOf(GATT_CHAR_UUID, GATT_CHAR_2_UUID), characteristics.map { it.uuid })
            assertEquals(
                setOf(CharacteristicProperty.READ, CharacteristicProperty.WRITE),
                characteristics[0].properties,
            )
            assertEquals(setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE), characteristics[1].properties)
        }
    }

    @Test
    fun `notifiable characteristic gets Client Characteristic Configuration descriptor`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(serviceA) {
                    Characteristic(GATT_CHAR_UUID, CharacteristicProperty.NOTIFY, Permission.READ)
                    Characteristic(GATT_CHAR_2_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }

            val (notifiable, readOnly) = fixture.discover().single().characteristics

            assertEquals(listOf(Descriptor.CLIENT_CHAR_CONF_UUID), notifiable.descriptors.map { it.uuid })
            assertTrue(notifiable.descriptors.single().isClientCharacteristicConfiguration)
            assertTrue(readOnly.descriptors.isEmpty())
        }
    }

    @Test
    fun `discovered descriptors report their UUIDs in declaration order`() = runTest {
        withTimeout(10.seconds) {
            val otherDescriptor = Uuid.fromShortUuid(0xA0D2)
            val fixture = gattFixture {
                Service(serviceA) {
                    Characteristic(GATT_CHAR_UUID, CharacteristicProperty.READ, Permission.READ) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.READ)
                        CharacteristicUserDescriptionDescriptor("Name")
                        Descriptor(otherDescriptor, Permission.WRITE)
                    }
                }
            }

            val descriptors = fixture.discover().single().characteristics.single().descriptors

            assertEquals(
                listOf(GATT_DESCRIPTOR_UUID, Descriptor.CHAR_USER_DESC_UUID, otherDescriptor),
                descriptors.map { it.uuid },
            )
        }
    }

    @Test
    fun `included services are reachable from their parent and are not primary`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(serviceA) {
                    Characteristic(GATT_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                    IncludedService(includedUuid) {
                        Characteristic(GATT_CHAR_2_UUID, CharacteristicProperty.READ, Permission.READ)
                    }
                }
            }

            val services = fixture.discover()

            // Included services are not reported as top-level services.
            assertEquals(listOf(serviceA), services.map { it.uuid })
            val included = services.single().includedServices.single()
            assertEquals(includedUuid, included.uuid)
            assertFalse(included.isPrimary)
            assertEquals(listOf(GATT_CHAR_2_UUID), included.characteristics.map { it.uuid })
        }
    }

    @Test
    fun `attribute handles are unique and ascending in declaration order`() = runTest {
        withTimeout(10.seconds) {
            var charHandle = -1
            val fixture = gattFixture {
                Service(serviceA) {
                    charHandle = Characteristic(GATT_CHAR_UUID, CharacteristicProperty.NOTIFY, Permission.READ) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.READ)
                    }
                    Characteristic(GATT_CHAR_2_UUID, CharacteristicProperty.READ, Permission.READ)
                }
                Service(serviceB)
            }

            val services = fixture.discover()

            val handles = buildList {
                services.forEach { service ->
                    add(service.instanceId)
                    service.characteristics.forEach { characteristic ->
                        add(characteristic.instanceId)
                        characteristic.descriptors.forEach { add(it.instanceId) }
                    }
                }
            }
            assertEquals(handles.sorted(), handles, "Handles should ascend in declaration order")
            assertEquals(handles.size, handles.toSet().size, "Handles should be unique")
            // The handle returned by the DSL identifies the characteristic.
            assertEquals(charHandle, services[0].characteristics[0].instanceId)
        }
    }

    @Test
    fun `several service instances with the same UUID are discovered separately`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(serviceA) {
                    Characteristic(GATT_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                }
                Service(serviceA) {
                    Characteristic(GATT_CHAR_2_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }

            val services = fixture.discover()

            assertEquals(2, services.size)
            assertEquals(listOf(serviceA, serviceA), services.map { it.uuid })
            assertNotEquals(services[0].instanceId, services[1].instanceId)
            assertEquals(listOf(GATT_CHAR_UUID), services[0].characteristics.map { it.uuid })
            assertEquals(listOf(GATT_CHAR_2_UUID), services[1].characteristics.map { it.uuid })
        }
    }

    @Test
    fun `several characteristic instances with the same UUID can be told apart by instance id`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                readHandler = { characteristic ->
                    // Respond with the handle, so that it is known which instance was read.
                    no.nordicsemi.kotlin.ble.client.mock.ReadResponse.Success(
                        byteArrayOf(characteristic.instanceId.toByte())
                    )
                }
            }
            val fixture = gattFixture(handler) {
                Service(serviceA) {
                    Characteristic(GATT_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                    Characteristic(GATT_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }

            val (first, second) = fixture.discover().single().characteristics

            assertNotEquals(first.instanceId, second.instanceId)
            assertContentEquals(byteArrayOf(first.instanceId.toByte()), first.read())
            assertContentEquals(byteArrayOf(second.instanceId.toByte()), second.read())
        }
    }

    @Test
    fun `services filtered by UUID contain only matching services`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(serviceA)
                Service(serviceB)
            }
            fixture.connect()

            val filtered = fixture.peripheral.services(listOf(serviceB))
                .first { it is RemoteServices.Discovered } as RemoteServices.Discovered

            assertEquals(listOf(serviceB), filtered.services.map { it.uuid })
        }
    }

    @Test
    fun `services filtered by unknown UUID are an empty list`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(serviceA)
            }
            fixture.connect()

            val filtered = fixture.peripheral.services(listOf(Uuid.fromShortUuid(0xDEAD)))
                .first { it is RemoteServices.Discovered } as RemoteServices.Discovered

            assertTrue(filtered.services.isEmpty())
        }
    }

    @Test
    fun `discovery of a device without any services is reported as failed with EmptyResult`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture { }
            fixture.connect()

            val result = fixture.peripheral.services()
                .first { it is RemoteServices.Discovered || it is RemoteServices.Failed }

            // Documented in RemoteServices.Failed.Reason.EmptyResult.
            assertEquals(RemoteServices.Failed(RemoteServices.Failed.Reason.EmptyResult), result)
        }
    }

    @Test
    fun `failed discovery reported by the peripheral is not reported as Discovered`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { discoveryResult = ServiceDiscoveryResult.Failure }
            val fixture = gattFixture(handler) { Service(serviceA) }
            fixture.connect()

            val result = fixture.peripheral.services()
                .first { it is RemoteServices.Discovered || it is RemoteServices.Failed }

            // ServiceDiscoveryResult.Failure "will return an empty service list to the client".
            assertIs<RemoteServices.Failed>(result)
        }
    }

    @Test
    fun `Generic Access and Generic Attribute services are discovered when declared`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                GenericAccessService()
                GenericAttributeService()
                Service(serviceA)
            }

            val services = fixture.discover()

            assertEquals(
                listOf(Service.GENERIC_ACCESS_UUID, Service.GENERIC_ATTRIBUTE_UUID, serviceA),
                services.map { it.uuid },
            )
            val attribute = services.first { it.uuid == Service.GENERIC_ATTRIBUTE_UUID }
            assertEquals(
                no.nordicsemi.kotlin.ble.core.Characteristic.SERVICE_CHANGED,
                attribute.characteristics.single().uuid,
            )
            assertEquals(setOf(CharacteristicProperty.INDICATE), attribute.characteristics.single().properties)
        }
    }

    @Test
    fun `Service Changed indications are enabled automatically after discovery`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattFixture(handler) {
                GenericAttributeService(initiallyEnabled = false)
                Service(serviceA)
            }

            val _ = fixture.discover()
            gattSettle()

            val cccdWrite = handler.descriptorWrites.single { it.first == Descriptor.CLIENT_CHAR_CONF_UUID }
            assertContentEquals(byteArrayOf(0x02, 0x00), cccdWrite.second)
        }
    }

    @Test
    fun `services are invalidated and discovered again after Service Changed`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                GenericAttributeService()
                Service(serviceA)
            }
            val oldServices = fixture.discover()
            gattSettle()

            fixture.spec.simulateServiceChange {
                GenericAttributeService()
                Service(serviceB)
            }

            val newServices = fixture.peripheral.services()
                .first { state -> state is RemoteServices.Discovered && state.services.any { it.uuid == serviceB } }
            assertIs<RemoteServices.Discovered>(newServices)
            assertEquals(listOf(Service.GENERIC_ATTRIBUTE_UUID, serviceB), newServices.services.map { it.uuid })
            // The old service objects must no longer be usable.
            assertEquals(null, oldServices.first { it.uuid == serviceA }.owner)
        }
    }

    @Test
    fun `services are not changed without Service Changed indications`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                // The peripheral refuses to enable Service Changed indications.
                descriptorWriteHandler = { _, _ ->
                    WriteResponse.Failure(OperationStatus.WriteNotPermitted)
                }
            }
            val fixture = gattFixture(handler) {
                GenericAttributeService(initiallyEnabled = false)
                Service(serviceA)
            }
            val _ = fixture.discover()
            gattSettle()

            fixture.spec.simulateServiceChange {
                GenericAttributeService(initiallyEnabled = false)
                Service(serviceB)
            }
            gattSettle()

            // The client was not informed, so it keeps what it discovered.
            val state = fixture.peripheral.services().value
            assertIs<RemoteServices.Discovered>(state)
            assertTrue(state.services.any { it.uuid == serviceA })
            assertFalse(state.services.any { it.uuid == serviceB })
        }
    }

    @Test
    fun `services become Unknown after disconnection`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture { Service(serviceA) }
            val services = fixture.discover()

            fixture.peripheral.disconnect()
            gattSettle()

            assertEquals(RemoteServices.Unknown, fixture.peripheral.services().value)
            assertEquals(null, services.single().owner)
        }
    }

    @Test
    fun `services are not discovered again after reconnection when the cache is valid`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture { Service(serviceA) }
            val _ = fixture.discover()
            fixture.peripheral.disconnect()
            gattSettle()
            assertEquals(1, fixture.handler.discoveryRequests.size)

            val services = fixture.discover()

            assertEquals(listOf(serviceA), services.map { it.uuid })
            // Android returns the cached services, no discovery is performed on the peripheral.
            assertEquals(1, fixture.handler.discoveryRequests.size)
        }
    }

    @Test
    fun `services are discovered again after reconnection when they changed while disconnected`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                GenericAttributeService()
                Service(serviceA)
            }
            val _ = fixture.discover()
            fixture.peripheral.disconnect()
            gattSettle()

            // Services change while no client is connected.
            fixture.spec.simulateServiceChange {
                GenericAttributeService()
                Service(serviceB)
            }
            val services = fixture.discover()

            assertTrue(services.any { it.uuid == serviceB })
            assertFalse(services.any { it.uuid == serviceA })
            assertEquals(2, fixture.handler.discoveryRequests.size)
        }
    }

    @Test
    fun `disconnection during discovery does not leave services in Discovering state`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(serviceA) {
                    repeat(5) {
                        Characteristic(Uuid.fromShortUuid(0xB000 + it), CharacteristicProperty.READ, Permission.READ)
                    }
                }
            }
            val peripheral = fixture.manager.getPeripheralById(fixture.spec.identifier)!!
            // Requesting services before connecting starts discovery right after connection.
            val services = peripheral.services()
            fixture.connect()
            runCurrent()
            assertEquals(RemoteServices.Discovering, services.value)

            fixture.spec.simulateDisconnection()
            gattSettle()

            assertEquals(RemoteServices.Unknown, services.value)
        }
    }
}
