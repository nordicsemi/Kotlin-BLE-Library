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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.ble.server.ReadResponse
import no.nordicsemi.kotlin.ble.server.android.WriteRequest
import no.nordicsemi.kotlin.ble.server.WriteResponse
import no.nordicsemi.kotlin.ble.server.android.Central
import no.nordicsemi.kotlin.ble.server.mock.ReadResult
import no.nordicsemi.kotlin.ble.server.mock.WriteResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end tests of the mock local server: simulated centrals talking to the shared
 * [no.nordicsemi.kotlin.ble.server.internal.LocalServerImpl].
 */
class MockLocalServerTest {

    // ---- Setup ----

    @Test
    fun `setup defines services and makes them visible to centrals`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }

        assertEquals(service, manager.services.value.single())
        assertEquals(service.characteristic(RW_UUID).instanceId, central.findCharacteristic(SERVICE_UUID, RW_UUID))
        assertEquals(3, service.characteristics.size)
    }

    @Test
    fun `setup fails when Bluetooth is off`() = runTest {
        val environment = MockAndroidEnvironment.Api31(isBluetoothEnabled = false)
        val manager = mockManager(environment)

        assertFailsWith<BluetoothUnavailableException> { manager.defineServices() }

        environment.simulatePowerOn()
        manager.defineServices()
    }

    @Test
    fun `setup fails when Bluetooth is not supported`() = runTest {
        val manager = mockManager(MockAndroidEnvironment.Api31(isBluetoothSupported = false))

        assertFailsWith<BluetoothUnavailableException> { manager.defineServices() }
    }

    @Test
    fun `setup fails when the connect permission is not granted`() = runTest {
        val manager = mockManager(MockAndroidEnvironment.Api31(isBluetoothConnectPermissionGranted = false))

        assertFailsWith<SecurityException> { manager.defineServices() }
    }

    @Test
    fun `setup fails after the manager is closed`() = runTest {
        val manager = mockManager()

        manager.close()

        assertFailsWith<ManagerClosedException> { manager.defineServices() }
    }

    @Test
    fun `setup again replaces the services`() = runTest {
        val manager = mockManager()
        val old = manager.defineServices()

        val new = manager.defineServices()

        assertNull(old.manager)
        assertSame(manager.services.value.single(), new)
    }

    // ---- Reads ----

    @Test
    fun `read returns the value of the characteristic`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        service.characteristic(RW_UUID).setValue(byteArrayOf(1, 2, 3))
        val central = manager.simulateCentral().also { it.connect() }

        val result = central.read(central.findCharacteristic(SERVICE_UUID, RW_UUID)!!)

        assertContentEquals(byteArrayOf(1, 2, 3), result.getOrThrow())
    }

    @Test
    fun `read returns empty value by default`() = runTest {
        val manager = mockManager()
        manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }

        val result = central.read(central.findCharacteristic(SERVICE_UUID, RW_UUID)!!)

        assertEquals(0, result.getOrThrow().size)
    }

    @Test
    fun `read handler receives the central and provides the value`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        var requester: Central? = null
        service.characteristic(RW_UUID).onRead {
            requester = central
            ReadResponse.Success(byteArrayOf(42))
        }
        val central = manager.simulateCentral().also { it.connect() }

        val result = central.read(central.findCharacteristic(SERVICE_UUID, RW_UUID)!!)

        assertContentEquals(byteArrayOf(42), result.getOrThrow())
        assertSame(central, requester)
    }

    @Test
    fun `read handler can fail the request`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        service.characteristic(RW_UUID).onRead { ReadResponse.Failure(OperationStatus.InsufficientAuthentication) }
        val central = manager.simulateCentral().also { it.connect() }

        val result = central.read(central.findCharacteristic(SERVICE_UUID, RW_UUID)!!)

        assertEquals(ReadResult.Failure(OperationStatus.InsufficientAuthentication), result)
    }

    @Test
    fun `long value is read using long read procedure`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        service.characteristic(RW_UUID).setValue(bytes(100))
        val central = manager.simulateCentral().also { it.connect() }
        val attribute = central.findCharacteristic(SERVICE_UUID, RW_UUID)!!

        // A single read is truncated to MTU - 1 bytes.
        assertContentEquals(bytes(22), central.read(attribute).getOrThrow())
        assertContentEquals(bytes(100), central.readLong(attribute).getOrThrow())
    }

    @Test
    fun `read of a characteristic without read property fails`() = runTest {
        val manager = mockManager()
        manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }

        val result = central.read(central.findCharacteristic(SERVICE_UUID, INDICATE_UUID)!!)

        assertEquals(OperationStatus.ReadNotPermitted, result.statusOrNull() ?: OperationStatus.Success)
    }

    @Test
    fun `read of an unknown attribute fails with invalid handle`() = runTest {
        val manager = mockManager()
        manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }

        val result = central.read(9999)

        assertEquals(ReadResult.Failure(OperationStatus.InvalidHandle), result)
    }

    // ---- Writes ----

    @Test
    fun `write with response is stored and emitted`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(RW_UUID)
        val writes = mutableListOf<WriteRequest>()
        backgroundScope.launch { characteristic.writes.collect { writes += it } }
        testScheduler.runCurrent()
        val central = manager.simulateCentral().also { it.connect() }

        val result = central.write(central.findCharacteristic(SERVICE_UUID, RW_UUID)!!, byteArrayOf(7, 8))
        testScheduler.runCurrent()

        assertEquals(WriteResult.Success, result)
        assertContentEquals(byteArrayOf(7, 8), characteristic.value.value)
        assertEquals(1, writes.size)
        assertSame(central, writes[0].central)
        assertTrue(writes[0].withResponse)
        assertContentEquals(byteArrayOf(7, 8), writes[0].value)
    }

    @Test
    fun `write without response is stored`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }
        val writes = mutableListOf<WriteRequest>()
        backgroundScope.launch { service.characteristic(RW_UUID).writes.collect { writes += it } }
        testScheduler.runCurrent()

        central.write(central.findCharacteristic(SERVICE_UUID, RW_UUID)!!, byteArrayOf(9), withResponse = false)
        testScheduler.runCurrent()

        assertContentEquals(byteArrayOf(9), service.characteristic(RW_UUID).value.value)
        assertFalse(writes.single().withResponse)
    }

    @Test
    fun `write handler can reject the write`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(RW_UUID)
        characteristic.setValue(byteArrayOf(1))
        characteristic.onWrite {
            if (value.size == 1) WriteResponse.Success else WriteResponse.Failure(OperationStatus.InvalidAttributeLength)
        }
        val central = manager.simulateCentral().also { it.connect() }
        val attribute = central.findCharacteristic(SERVICE_UUID, RW_UUID)!!

        val rejected = central.write(attribute, byteArrayOf(1, 2, 3))
        val accepted = central.write(attribute, byteArrayOf(5))

        assertEquals(WriteResult.Failure(OperationStatus.InvalidAttributeLength), rejected)
        assertEquals(WriteResult.Success, accepted)
        assertContentEquals(byteArrayOf(5), characteristic.value.value)
    }

    @Test
    fun `long write is delivered to the handler once`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val received = mutableListOf<ByteArray>()
        service.characteristic(RW_UUID).onWrite {
            received += value
            WriteResponse.Success
        }
        val central = manager.simulateCentral().also { it.connect() }
        central.simulateMtuChange(50)

        val result = central.writeLong(central.findCharacteristic(SERVICE_UUID, RW_UUID)!!, bytes(200))

        assertEquals(WriteResult.Success, result)
        assertEquals(1, received.size)
        assertContentEquals(bytes(200), received[0])
        assertContentEquals(bytes(200), service.characteristic(RW_UUID).value.value)
    }

    @Test
    fun `value longer than MTU written with response is delivered whole`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }

        central.write(central.findCharacteristic(SERVICE_UUID, RW_UUID)!!, bytes(60))

        assertContentEquals(bytes(60), service.characteristic(RW_UUID).value.value)
    }

    @Test
    fun `reliable write is applied only after execute`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(RW_UUID)
        val central = manager.simulateCentral().also { it.connect() }
        val attribute = central.findCharacteristic(SERVICE_UUID, RW_UUID)!!

        val write = central.beginReliableWrite()
        write.write(attribute, bytes(10))
        assertEquals(0, characteristic.value.value.size)

        write.execute().getOrThrow()

        assertContentEquals(bytes(10), characteristic.value.value)
    }

    @Test
    fun `cancelled reliable write does not change the value`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(RW_UUID)
        characteristic.setValue(byteArrayOf(1))
        val central = manager.simulateCentral().also { it.connect() }
        val attribute = central.findCharacteristic(SERVICE_UUID, RW_UUID)!!

        val write = central.beginReliableWrite()
        write.write(attribute, bytes(10))
        write.cancel()

        assertContentEquals(byteArrayOf(1), characteristic.value.value)
    }

    @Test
    fun `reliable writes of two centrals are independent`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(RW_UUID)
        val a = manager.simulateCentral("A").also { it.connect() }
        val b = manager.simulateCentral("B").also { it.connect() }
        val attribute = a.findCharacteristic(SERVICE_UUID, RW_UUID)!!

        val writeA = a.beginReliableWrite()
        val writeB = b.beginReliableWrite()
        writeA.write(attribute, byteArrayOf(1, 1))
        writeB.write(attribute, byteArrayOf(2, 2))
        writeB.cancel()
        writeA.execute().getOrThrow()

        assertContentEquals(byteArrayOf(1, 1), characteristic.value.value)
    }

    // ---- Subscriptions and updates ----

    @Test
    fun `subscribed central receives a notification`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val central = manager.simulateCentral().also { it.connect() }
        val updates = collectUpdates(central)

        central.subscribe(central.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!).getOrThrow()
        characteristic.update(byteArrayOf(1, 2, 3))

        assertEquals(setOf(central), characteristic.subscribers.value)
        assertEquals(1, updates.size)
        assertEquals(characteristic.instanceId, updates[0].attribute)
        assertContentEquals(byteArrayOf(1, 2, 3), updates[0].value)
        assertFalse(updates[0].isIndication)
        assertContentEquals(byteArrayOf(1, 2, 3), characteristic.value.value)
    }

    @Test
    fun `subscribed central receives an indication`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(INDICATE_UUID)
        val central = manager.simulateCentral().also { it.connect() }
        val updates = collectUpdates(central)

        central.subscribe(central.findCharacteristic(SERVICE_UUID, INDICATE_UUID)!!, indications = true).getOrThrow()
        characteristic.update(byteArrayOf(4))

        assertTrue(updates.single().isIndication)
    }

    @Test
    fun `not subscribed central does not receive updates`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }
        val updates = collectUpdates(central)

        service.characteristic(NOTIFY_UUID).update(byteArrayOf(1))

        assertTrue(updates.isEmpty())
        assertTrue(service.characteristic(NOTIFY_UUID).subscribers.value.isEmpty())
    }

    @Test
    fun `unsubscribed central stops receiving updates`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val central = manager.simulateCentral().also { it.connect() }
        val updates = collectUpdates(central)
        val attribute = central.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!
        central.subscribe(attribute).getOrThrow()
        characteristic.update(byteArrayOf(1))

        central.unsubscribe(attribute).getOrThrow()

        assertTrue(characteristic.subscribers.value.isEmpty())
        assertEquals(1, updates.size)
    }

    @Test
    fun `subscribing for indications to a notify-only characteristic fails`() = runTest {
        val manager = mockManager()
        manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }

        val result = central.subscribe(central.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!, indications = true)

        assertEquals(
            WriteResult.Failure(OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured),
            result,
        )
    }

    @Test
    fun `several centrals subscribe independently`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val a = manager.simulateCentral("A").also { it.connect() }
        val b = manager.simulateCentral("B").also { it.connect() }
        val updatesA = collectUpdates(a)
        val updatesB = collectUpdates(b)
        val attribute = a.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!

        a.subscribe(attribute).getOrThrow()
        characteristic.update(byteArrayOf(1))
        b.subscribe(attribute).getOrThrow()
        characteristic.update(byteArrayOf(2))
        a.unsubscribe(attribute).getOrThrow()
        characteristic.update(byteArrayOf(3))

        assertEquals(listOf<Byte>(1, 2), updatesA.map { it.value[0] })
        assertEquals(listOf<Byte>(2, 3), updatesB.map { it.value[0] })
    }

    @Test
    fun `update to a single central reaches only that central`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val a = manager.simulateCentral("A").also { it.connect() }
        val b = manager.simulateCentral("B").also { it.connect() }
        val updatesA = collectUpdates(a)
        val updatesB = collectUpdates(b)
        val attribute = a.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!
        a.subscribe(attribute).getOrThrow()
        b.subscribe(attribute).getOrThrow()

        characteristic.update(byteArrayOf(5), to = b)

        assertTrue(updatesA.isEmpty())
        assertEquals(1, updatesB.size)
    }

    @Test
    fun `update longer than the MTU allows is rejected`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val central = manager.simulateCentral().also { it.connect() }
        val attribute = central.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!
        central.subscribe(attribute).getOrThrow()

        assertFailsWith<IllegalArgumentException> { characteristic.update(bytes(21)) }
        characteristic.update(bytes(20))
    }

    @Test
    fun `MTU change allows longer updates`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val central = manager.simulateCentral().also { it.connect() }
        val updates = collectUpdates(central)
        central.subscribe(central.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!).getOrThrow()

        central.simulateMtuChange(185)
        characteristic.update(bytes(182))

        assertEquals(182, updates.single().value.size)
        assertFailsWith<IllegalArgumentException> { characteristic.update(bytes(183)) }
    }

    // ---- Disconnection and lifecycle ----

    @Test
    fun `disconnected central is removed from subscribers`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val a = manager.simulateCentral("A").also { it.connect() }
        val b = manager.simulateCentral("B").also { it.connect() }
        val attribute = a.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!
        a.subscribe(attribute).getOrThrow()
        b.subscribe(attribute).getOrThrow()

        a.disconnect()
        testScheduler.runCurrent()

        assertEquals(setOf(b), characteristic.subscribers.value)
    }

    @Test
    fun `link loss removes the central from subscribers`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val central = manager.simulateCentral().also { it.connect() }
        central.subscribe(central.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!).getOrThrow()

        central.simulateLinkLoss()
        testScheduler.runCurrent()

        assertTrue(characteristic.subscribers.value.isEmpty())
    }

    @Test
    fun `reconnected central has to subscribe again`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val central = manager.simulateCentral().also { it.connect() }
        val attribute = central.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!
        central.subscribe(attribute).getOrThrow()
        central.disconnect()
        testScheduler.runCurrent()

        central.connect()

        assertTrue(characteristic.subscribers.value.isEmpty())
        central.subscribe(attribute).getOrThrow()
        assertEquals(setOf(central), characteristic.subscribers.value)
    }

    @Test
    fun `turning Bluetooth off disconnects centrals and clears subscriptions`() = runTest {
        val environment = MockAndroidEnvironment.Api31()
        val manager = mockManager(environment)
        val service = manager.defineServices()
        val characteristic = service.characteristic(NOTIFY_UUID)
        val central = manager.simulateCentral().also { it.connect() }
        central.subscribe(central.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!).getOrThrow()

        environment.simulatePowerOff()
        testScheduler.runCurrent()

        assertFalse(central.state.value.isConnected)
        assertTrue(characteristic.subscribers.value.isEmpty())
    }

    @Test
    fun `closing the manager disconnects centrals and invalidates services`() = runTest {
        val manager = mockManager()
        val service = manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }

        manager.close()

        assertFalse(central.state.value.isConnected)
        assertNull(service.manager)
        assertTrue(manager.services.value.isEmpty())
    }

    @Test
    fun `request cannot be answered after the manager is closed`() = runTest {
        val manager = mockManager()
        manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }
        val attribute = central.findCharacteristic(SERVICE_UUID, RW_UUID)!!

        manager.close()

        assertFailsWith<IllegalStateException> { central.read(attribute) }
    }

    @Test
    fun `connected centrals are reported by the manager`() = runTest {
        val manager = mockManager()
        manager.defineServices()
        val central = manager.simulateCentral().also { it.connect() }

        assertEquals(listOf(central), manager.connectedCentrals.first())
    }
}
