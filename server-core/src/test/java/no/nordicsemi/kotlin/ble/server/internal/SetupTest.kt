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

package no.nordicsemi.kotlin.ble.server.internal

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import no.nordicsemi.kotlin.ble.server.LocalService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SetupTest {

    @Test
    fun `setup builds the services`() = runTest {
        val fixture = Fixture(this)
        val services = fixture.setup()

        assertEquals(1, services.size)
        val service = services[0]
        assertEquals(SERVICE_UUID, service.uuid)
        assertTrue(service.isPrimary)
        assertNull(service.owner)
        assertSame(fixture.manager, service.manager)
        assertEquals(8, service.characteristics.size)
        assertEquals(1, service.includedServices.size)
        assertEquals(INCLUDED_UUID, service.includedServices[0].uuid)
        assertFalse(service.includedServices[0].isPrimary)
        assertEquals(1, service.includedServices[0].characteristics.size)
    }

    @Test
    fun `setup passes the definitions to the server`() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()

        assertEquals(1, fixture.server.setServicesCalls.size)
        val definitions = fixture.server.setServicesCalls[0]
        assertEquals(1, definitions.size)
        assertEquals(SERVICE_UUID, definitions[0].uuid)
    }

    @Test
    fun `setup publishes the services`() = runTest {
        val fixture = Fixture(this)
        assertTrue(fixture.manager.services.value.isEmpty())
        val services = fixture.setup()
        assertEquals(services, fixture.manager.services.value)
    }

    @Test
    fun `setup keeps the properties and parents of the characteristics`() = runTest {
        val fixture = Fixture(this)
        val services = fixture.setup()

        val rw = fixture.rw
        assertEquals(
            CharacteristicProperty.READ and CharacteristicProperty.WRITE and
                    CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
            rw.properties
        )
        assertSame(services[0], rw.service)
        assertNull(rw.owner)
        assertTrue(rw.value.value.isEmpty())
        assertTrue(rw.subscribers.value.isEmpty())
    }

    @Test
    fun `setup assigns unique instance ids`() = runTest {
        val fixture = Fixture(this)
        val services = fixture.setup()

        val ids = mutableListOf<Int>()
        fun collect(service: no.nordicsemi.kotlin.ble.server.AnyLocalService) {
            ids += service.instanceId
            service.characteristics.forEach { characteristic ->
                ids += characteristic.instanceId
                characteristic.descriptors.forEach { ids += it.instanceId }
            }
            service.includedServices.forEach { collect(it) }
        }
        services.forEach { collect(it) }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun `setup adds a CCCD for notify and indicate`() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()

        assertEquals(1, fixture.notify.descriptors.count { it.isClientCharacteristicConfiguration })
        assertEquals(1, fixture.indicate.descriptors.count { it.isClientCharacteristicConfiguration })
        assertEquals(1, fixture.both.descriptors.count { it.isClientCharacteristicConfiguration })
        assertEquals(0, fixture.ro.descriptors.size)
    }

    @Test
    fun `initial value of a CCCD is zero`() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()

        assertContentEquals(bytes(0, 0), fixture.cccd(NOTIFY_UUID).value.value)
    }

    @Test
    fun `value of a CUD is the description`() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()

        val cud = fixture.rw.descriptors.first { it.uuid == Descriptor.CHAR_USER_DESC_UUID }
        assertContentEquals("Some value".encodeToByteArray(), cud.value.value)
        assertSame(fixture.rw, cud.characteristic)
    }

    @Test
    fun `value of a CEPD is the extended properties`() = runTest {
        val manager = LocalServerImpl<String>(backgroundScope, FakeGattServer())
        val services = manager.setup {
            Service(SERVICE_UUID) {
                Characteristic(RW_UUID, CharacteristicProperty.READ, Permission.READ) {
                    CharacteristicExtendedPropertiesDescriptor(reliableWrite = true, writableAuxiliaries = false)
                }
                Characteristic(RO_UUID, CharacteristicProperty.READ, Permission.READ) {
                    CharacteristicUserDescriptionDescriptor("Writable", writable = true)
                }
            }
        }
        val reliable = services[0].characteristics[0].descriptors.first { it.uuid == Descriptor.CHAR_EXT_PROP_UUID }
        assertContentEquals(bytes(0x01, 0x00), reliable.value.value)

        // The CEPD is added automatically when CUD is writable.
        val auxiliaries = services[0].characteristics[1].descriptors.first { it.uuid == Descriptor.CHAR_EXT_PROP_UUID }
        assertContentEquals(bytes(0x02, 0x00), auxiliaries.value.value)
    }

    @Test
    fun `custom descriptor has an empty value`() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()

        val descriptor = fixture.rw.descriptors.first { it.uuid == CUSTOM_DESCRIPTOR_UUID }
        assertTrue(descriptor.value.value.isEmpty())
    }

    @Test
    fun `included service references its parent`() = runTest {
        val fixture = Fixture(this)
        val services = fixture.setup()

        val included = services[0].includedServices[0]
        assertSame(services[0], included.service)
        assertSame(fixture.manager, included.manager)
        assertNull(included.owner)
    }

    @Test
    fun `setup supports nested included services`() = runTest {
        val manager = LocalServerImpl<String>(backgroundScope, FakeGattServer())
        val services = manager.setup {
            Service(SERVICE_UUID) {
                IncludedService(INCLUDED_UUID) {
                    Characteristic(RO_UUID, CharacteristicProperty.READ, Permission.READ)
                    IncludedService(RW_UUID) {
                        Characteristic(WO_UUID, CharacteristicProperty.WRITE, Permission.WRITE)
                    }
                }
            }
        }
        val nested = services[0].includedServices[0].includedServices[0]
        assertEquals(RW_UUID, nested.uuid)
        assertEquals(WO_UUID, nested.characteristics[0].uuid)
        assertSame(manager, nested.manager)
    }

    @Test
    fun `setup supports multiple services`() = runTest {
        val manager = LocalServerImpl<String>(backgroundScope, FakeGattServer())
        val services = manager.setup {
            Service(SERVICE_UUID)
            Service(INCLUDED_UUID)
        }
        assertEquals(listOf(SERVICE_UUID, INCLUDED_UUID), services.map { it.uuid })
    }

    @Test
    fun `setup replaces and invalidates the previous services`() = runTest {
        val fixture = Fixture(this)
        val old = fixture.setup()
        val oldIncluded = old[0].includedServices[0]

        val new = fixture.manager.setup {
            Service(INCLUDED_UUID)
        }

        assertEquals(listOf(INCLUDED_UUID), new.map { it.uuid })
        assertEquals(new, fixture.manager.services.value)
        assertNull(old[0].manager)
        assertNull(oldIncluded.manager)
        assertSame(fixture.manager, new[0].manager)
        assertEquals(2, fixture.server.setServicesCalls.size)
    }

    @Test
    fun `removeServices removes and invalidates the services`() = runTest {
        val fixture = Fixture(this)
        val old = fixture.setup()

        fixture.manager.removeServices()

        assertTrue(fixture.manager.services.value.isEmpty())
        assertNull(old[0].manager)
        assertTrue(fixture.server.setServicesCalls.last().isEmpty())
        assertEquals(0, fixture.server.closeCount)
    }

    @Test
    fun `server can host services again after removeServices`() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()
        fixture.manager.removeServices()

        val new = fixture.manager.setup { Service(INCLUDED_UUID) }

        assertEquals(new, fixture.manager.services.value)
        assertSame(fixture.manager, new[0].manager)
    }

    @Test
    fun `removeServices without services does nothing`() = runTest {
        val fixture = Fixture(this)

        fixture.manager.removeServices()

        assertTrue(fixture.server.setServicesCalls.isEmpty())
    }

    @Test
    fun `removeServices on a closed manager throws`() = runTest {
        val fixture = Fixture(this)
        fixture.manager.close()

        assertFailsWith<ManagerClosedException> { fixture.manager.removeServices() }
    }

    @Test
    fun `failed setup keeps the previous services`() = runTest {
        val fixture = Fixture(this)
        val old = fixture.setup()
        fixture.server.setServicesError = IllegalStateException("Failed")

        assertFailsWith<IllegalStateException> {
            fixture.manager.setup { Service(INCLUDED_UUID) }
        }

        assertEquals(old, fixture.manager.services.value)
        assertNotNull(old[0].manager)
    }

    @Test
    fun `concurrent setup calls are serialized`() = runTest {
        val fixture = Fixture(this)
        val results = mutableListOf<List<LocalService>>()
        val jobs = List(5) { index ->
            launch {
                results += fixture.manager.setup {
                    Service(SERVICE_UUID) {
                        repeat(index + 1) { Characteristic(RO_UUID, CharacteristicProperty.READ, Permission.READ) }
                    }
                }
            }
        }
        jobs.forEach { it.join() }

        assertEquals(5, fixture.server.setServicesCalls.size)
        // Only the last one remains valid.
        val valid = results.filter { it[0].manager != null }
        assertEquals(1, valid.size)
        assertEquals(valid[0], fixture.manager.services.value)
    }

    @Test
    fun `setup on a closed manager throws`() = runTest {
        val fixture = Fixture(this)
        fixture.manager.close()

        assertFailsWith<ManagerClosedException> { fixture.manager.setup { Service(SERVICE_UUID) } }
    }
}
