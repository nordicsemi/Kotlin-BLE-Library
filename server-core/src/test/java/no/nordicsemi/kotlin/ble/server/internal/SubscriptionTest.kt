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

import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.OperationStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionTest {

    @Test
    fun `CCCD enables notifications`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.writeCccd(fixture.notify, 0x0001)

        assertEquals(OperationStatus.Success, response.status)
        assertEquals(setOf(fixture.client), fixture.notify.subscribers.value)
    }

    @Test
    fun `CCCD enables indications`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.writeCccd(fixture.indicate, 0x0002)

        assertEquals(OperationStatus.Success, response.status)
        assertEquals(setOf(fixture.client), fixture.indicate.subscribers.value)
    }

    @Test
    fun `CCCD disables the subscription`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)

        val response = fixture.writeCccd(fixture.notify, 0x0000)

        assertEquals(OperationStatus.Success, response.status)
        assertTrue(fixture.notify.subscribers.value.isEmpty())
    }

    @Test
    fun `CCCD enables notifications on a characteristic with both`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertEquals(OperationStatus.Success, fixture.writeCccd(fixture.both, 0x0001).status)
        assertEquals(OperationStatus.Success, fixture.writeCccd(fixture.both, 0x0002).status)
        assertEquals(setOf(fixture.client), fixture.both.subscribers.value)
    }

    @Test
    fun `indications on a notify only characteristic fail with ImproperlyConfigured`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.writeCccd(fixture.notify, 0x0002)

        assertEquals(OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured, response.status)
        assertTrue(fixture.notify.subscribers.value.isEmpty())
    }

    @Test
    fun `notifications on an indicate only characteristic fail with ImproperlyConfigured`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.writeCccd(fixture.indicate, 0x0001)

        assertEquals(OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured, response.status)
        assertTrue(fixture.indicate.subscribers.value.isEmpty())
    }

    @Test
    fun `notifications and indications together fail with ImproperlyConfigured`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.writeCccd(fixture.both, 0x0003)

        assertEquals(OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured, response.status)
        assertTrue(fixture.both.subscribers.value.isEmpty())
    }

    @Test
    fun `unknown bits of a CCCD fail with ImproperlyConfigured`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.writeCccd(fixture.both, 0x0004)

        assertEquals(OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured, response.status)
    }

    @Test
    fun `CCCD of a wrong length fails with InvalidAttributeLength`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val cccd = fixture.cccd(NOTIFY_UUID)

        assertEquals(OperationStatus.InvalidAttributeLength, fixture.write(cccd.instanceId, bytes(1))?.status)
        assertEquals(OperationStatus.InvalidAttributeLength, fixture.write(cccd.instanceId, bytes(1, 0, 0))?.status)
        assertEquals(OperationStatus.InvalidAttributeLength, fixture.write(cccd.instanceId, bytes())?.status)
        assertTrue(fixture.notify.subscribers.value.isEmpty())
    }

    @Test
    fun `read of a CCCD returns the value of the client`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val cccd = fixture.cccd(BOTH_UUID)

        assertContentEquals(bytes(0, 0), fixture.read(cccd.instanceId).value)
        fixture.subscribe(fixture.both, 1)
        assertContentEquals(bytes(1, 0), fixture.read(cccd.instanceId).value)
        fixture.subscribe(fixture.both, 2)
        assertContentEquals(bytes(2, 0), fixture.read(cccd.instanceId).value)
    }

    @Test
    fun `CCCD is per client`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val cccd = fixture.cccd(NOTIFY_UUID)

        fixture.subscribe(fixture.notify, 1, fixture.client)

        assertContentEquals(bytes(1, 0), fixture.read(cccd.instanceId, from = fixture.client).value)
        assertContentEquals(bytes(0, 0), fixture.read(cccd.instanceId, from = fixture.client2).value)
        assertEquals(setOf(fixture.client), fixture.notify.subscribers.value)
    }

    @Test
    fun `CCCD supports multiple subscribers`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        fixture.subscribe(fixture.notify, 1, fixture.client)
        fixture.subscribe(fixture.notify, 1, fixture.client2)
        assertEquals(setOf(fixture.client, fixture.client2), fixture.notify.subscribers.value)

        val _ = fixture.writeCccd(fixture.notify, 0, fixture.client)
        assertEquals(setOf(fixture.client2), fixture.notify.subscribers.value)
    }

    @Test
    fun `subscriptions are independent between characteristics`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        fixture.subscribe(fixture.notify)

        assertTrue(fixture.both.subscribers.value.isEmpty())
        assertTrue(fixture.indicate.subscribers.value.isEmpty())
    }

    @Test
    fun `write to a CCCD without response is not allowed`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val cccd = fixture.cccd(NOTIFY_UUID)

        val _ = fixture.write(cccd.instanceId, bytes(1, 0), responseNeeded = false)

        assertTrue(fixture.notify.subscribers.value.isEmpty())
    }

    @Test
    fun `subscription change updates the subscribers`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        fixture.emit(GattServerEvent.SubscriptionChanged(fixture.client, fixture.notify.instanceId, true, false))
        assertEquals(setOf(fixture.client), fixture.notify.subscribers.value)

        fixture.emit(GattServerEvent.SubscriptionChanged(fixture.client, fixture.notify.instanceId, false, false))
        assertTrue(fixture.notify.subscribers.value.isEmpty())
    }

    @Test
    fun `subscription change for indications`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        fixture.emit(GattServerEvent.SubscriptionChanged(fixture.client, fixture.indicate.instanceId, false, true))

        assertEquals(setOf(fixture.client), fixture.indicate.subscribers.value)
    }

    @Test
    fun `unsupported subscription change is ignored`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        fixture.emit(GattServerEvent.SubscriptionChanged(fixture.client, fixture.notify.instanceId, false, true))
        fixture.emit(GattServerEvent.SubscriptionChanged(fixture.client, fixture.ro.instanceId, true, false))
        fixture.emit(GattServerEvent.SubscriptionChanged(fixture.client, 9999, true, false))

        assertTrue(fixture.notify.subscribers.value.isEmpty())
        assertTrue(fixture.ro.subscribers.value.isEmpty())
    }

    @Test
    fun `subscription change is visible in the CCCD`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.emit(GattServerEvent.SubscriptionChanged(fixture.client, fixture.notify.instanceId, true, false))

        assertContentEquals(bytes(1, 0), fixture.read(fixture.cccd(NOTIFY_UUID).instanceId).value)
    }

    @Test
    fun `disconnect removes the subscriptions`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify, 1, fixture.client)
        fixture.subscribe(fixture.notify, 1, fixture.client2)
        fixture.subscribe(fixture.both, 2, fixture.client)

        fixture.emit(GattServerEvent.ClientDisconnected(fixture.client))

        assertEquals(setOf(fixture.client2), fixture.notify.subscribers.value)
        assertTrue(fixture.both.subscribers.value.isEmpty())
    }

    @Test
    fun `disconnect resets the CCCD value`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)

        fixture.emit(GattServerEvent.ClientDisconnected(fixture.client))

        assertContentEquals(bytes(0, 0), fixture.read(fixture.cccd(NOTIFY_UUID).instanceId).value)
    }

    @Test
    fun `disconnect drops the prepared writes`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true)

        fixture.emit(GattServerEvent.ClientDisconnected(fixture.client))
        val response = fixture.execute()

        assertEquals(OperationStatus.Success, response.status)
        assertTrue(fixture.rw.value.value.isEmpty())
    }

    @Test
    fun `disconnect of an unknown client is ignored`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)

        fixture.emit(GattServerEvent.ClientDisconnected(FakeClient("CC")))

        assertEquals(setOf(fixture.client), fixture.notify.subscribers.value)
    }

    @Test
    fun `requests to the attributes of replaced services are not routed`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)
        val old = fixture.notify

        val _ = fixture.manager.setup { Service(SERVICE_UUID) }

        // The old characteristic is no longer hosted, so requests are not routed to it.
        assertEquals(OperationStatus.InvalidHandle, fixture.read(old.instanceId).status)
    }
}
