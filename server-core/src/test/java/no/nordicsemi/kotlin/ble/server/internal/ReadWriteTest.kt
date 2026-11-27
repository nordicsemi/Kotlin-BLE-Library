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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.server.ReadResponse
import no.nordicsemi.kotlin.ble.server.WriteRequest
import no.nordicsemi.kotlin.ble.server.WriteResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadWriteTest {

    // ----- Reads -----

    @Test
    fun `read returns the value`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.setValue(bytes(1, 2, 3))

        val response = fixture.read(fixture.ro.instanceId)

        assertEquals(OperationStatus.Success, response.status)
        assertContentEquals(bytes(1, 2, 3), response.value)
        assertEquals(0, response.offset)
        assertEquals(fixture.client, response.client)
    }

    @Test
    fun `read echoes the request id`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.emit(GattServerEvent.ReadRequested(fixture.client, 42, fixture.ro.instanceId, 0))

        assertEquals(42, fixture.server.responses.last().requestId)
    }

    @Test
    fun `read returns an empty value by default`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.read(fixture.ro.instanceId)

        assertEquals(OperationStatus.Success, response.status)
        assertTrue(response.value.isEmpty())
    }

    @Test
    fun `read of an unknown attribute fails with InvalidHandle`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertEquals(OperationStatus.InvalidHandle, fixture.read(9999).status)
    }

    @Test
    fun `read before setup fails with InvalidHandle`() = runTest {
        val fixture = Fixture(this)

        assertEquals(OperationStatus.InvalidHandle, fixture.read(1).status)
    }

    @Test
    fun `read without the read property fails with ReadNotPermitted`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertEquals(OperationStatus.ReadNotPermitted, fixture.read(fixture.wo.instanceId).status)
        assertEquals(OperationStatus.ReadNotPermitted, fixture.read(fixture.indicate.instanceId).status)
    }

    @Test
    fun `long read slices the value from the offset`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.setValue(bytes(0, 1, 2, 3, 4, 5))

        val response = fixture.read(fixture.ro.instanceId, offset = 2)

        assertEquals(OperationStatus.Success, response.status)
        assertEquals(2, response.offset)
        assertContentEquals(bytes(2, 3, 4, 5), response.value)
    }

    @Test
    fun `read with the offset equal to the size returns an empty value`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.setValue(bytes(0, 1, 2))

        val response = fixture.read(fixture.ro.instanceId, offset = 3)

        assertEquals(OperationStatus.Success, response.status)
        assertTrue(response.value.isEmpty())
    }

    @Test
    fun `read with the offset beyond the size fails with InvalidOffset`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.setValue(bytes(0, 1, 2))

        assertEquals(OperationStatus.InvalidOffset, fixture.read(fixture.ro.instanceId, offset = 4).status)
    }

    @Test
    fun `read handler provides the value`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.setValue(bytes(1))
        fixture.ro.onRead { ReadResponse.Success(bytes(9, 8)) }

        assertContentEquals(bytes(9, 8), fixture.read(fixture.ro.instanceId).value)
    }

    @Test
    fun `read handler is called for each read`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        var counter = 0
        fixture.ro.provideValue { bytes((++counter).toByte().toInt()) }

        assertContentEquals(bytes(1), fixture.read(fixture.ro.instanceId).value)
        assertContentEquals(bytes(2), fixture.read(fixture.ro.instanceId).value)
    }

    @Test
    fun `read handler sees the client`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.onRead { ReadResponse.Success(client.identifier.toString().encodeToByteArray()) }

        assertContentEquals("AA".encodeToByteArray(), fixture.read(fixture.ro.instanceId).value)
        assertContentEquals("BB".encodeToByteArray(), fixture.read(fixture.ro.instanceId, from = fixture.client2).value)
    }

    @Test
    fun `read handler can suspend`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.onRead {
            kotlinx.coroutines.delay(1000)
            ReadResponse.Success(bytes(7))
        }
        fixture.emit(GattServerEvent.ReadRequested(fixture.client, 1, fixture.ro.instanceId, 0))
        assertTrue(fixture.server.responses.isEmpty())

        testScheduler.advanceTimeBy(1001)
        testScheduler.runCurrent()

        assertContentEquals(bytes(7), fixture.server.responses.single().value)
    }

    @Test
    fun `long value of the read handler is sliced by the library`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.onRead { ReadResponse.Success(bytes(1, 2, 3, 4)) }

        assertContentEquals(bytes(3, 4), fixture.read(fixture.ro.instanceId, offset = 2).value)
    }

    @Test
    fun `read returns the status of a failed handler`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.onRead { ReadResponse.Failure(OperationStatus.InsufficientAuthorization) }

        val response = fixture.read(fixture.ro.instanceId)

        assertEquals(OperationStatus.InsufficientAuthorization, response.status)
        assertTrue(response.value.isEmpty())
    }

    @Test
    fun `read returns UnlikelyError when the handler throws`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.onRead { throw IllegalStateException("Boom") }

        assertEquals(OperationStatus.UnlikelyError, fixture.read(fixture.ro.instanceId).status)

        // The manager still works.
        fixture.ro.onRead(null)
        assertEquals(OperationStatus.Success, fixture.read(fixture.ro.instanceId).status)
    }

    @Test
    fun `read returns the stored value after the handler was removed`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.ro.setValue(bytes(5))
        fixture.ro.onRead { ReadResponse.Success(bytes(6)) }
        fixture.ro.onRead(null)

        assertContentEquals(bytes(5), fixture.read(fixture.ro.instanceId).value)
    }

    @Test
    fun `read of a descriptor returns the value`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val cud = fixture.rw.descriptors.first { it.uuid == Descriptor.CHAR_USER_DESC_UUID }

        val response = fixture.read(cud.instanceId)

        assertEquals(OperationStatus.Success, response.status)
        assertContentEquals("Some value".encodeToByteArray(), response.value)
    }

    @Test
    fun `read of a descriptor uses the handler`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val descriptor = fixture.rw.descriptors.first { it.uuid == CUSTOM_DESCRIPTOR_UUID }
        descriptor.onRead { ReadResponse.Success(bytes(4)) }

        assertContentEquals(bytes(4), fixture.read(descriptor.instanceId).value)
    }

    @Test
    fun `read of a characteristic of an included service`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val included = fixture.services[0].includedServices[0].characteristics[0]
        included.setValue(bytes(3))

        assertContentEquals(bytes(3), fixture.read(included.instanceId).value)
    }

    // ----- Writes -----

    @Test
    fun `write stores the value and emits it by default`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val writes = mutableListOf<WriteRequest>()
        backgroundScope.launch { fixture.rw.writes.toList(writes) }
        testScheduler.runCurrent()

        val response = fixture.write(fixture.rw.instanceId, bytes(1, 2))

        assertNotNull(response)
        assertEquals(OperationStatus.Success, response.status)
        assertContentEquals(bytes(1, 2), fixture.rw.value.value)
        testScheduler.runCurrent()
        assertEquals(1, writes.size)
        assertContentEquals(bytes(1, 2), writes[0].value)
        assertEquals(fixture.client, writes[0].client)
        assertTrue(writes[0].withResponse)
    }

    @Test
    fun `read after write returns the written value`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val _ = fixture.write(fixture.rw.instanceId, bytes(5, 6))

        assertContentEquals(bytes(5, 6), fixture.read(fixture.rw.instanceId).value)
    }

    @Test
    fun `write to an unknown attribute fails with InvalidHandle`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertEquals(OperationStatus.InvalidHandle, fixture.write(9999, bytes(1))?.status)
    }

    @Test
    fun `write without the write property fails with WriteNotPermitted`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertEquals(OperationStatus.WriteNotPermitted, fixture.write(fixture.ro.instanceId, bytes(1))?.status)
        assertTrue(fixture.ro.value.value.isEmpty())
    }

    @Test
    fun `write without response is not responded to`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.write(fixture.rw.instanceId, bytes(1), responseNeeded = false)

        assertNull(response)
        assertContentEquals(bytes(1), fixture.rw.value.value)
    }

    @Test
    fun `write without response requires the write without response property`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        // WO has only WRITE.
        val response = fixture.write(fixture.wo.instanceId, bytes(1), responseNeeded = false)

        assertNull(response)
        assertTrue(fixture.wo.value.value.isEmpty())
    }

    @Test
    fun `write with response requires the write property`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val writeOnlyWithoutResponse = fixture.services[0].characteristics.first { it.uuid == NONE_UUID }

        assertEquals(
            OperationStatus.WriteNotPermitted,
            fixture.write(writeOnlyWithoutResponse.instanceId, bytes(1))?.status
        )
    }

    @Test
    fun `handler sees withResponse false for write without response`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        var seen: Boolean? = null
        fixture.rw.onWrite { seen = withResponse; WriteResponse.Success }

        val _ = fixture.write(fixture.rw.instanceId, bytes(1), responseNeeded = false)

        assertEquals(false, seen)
    }

    @Test
    fun `write of a too long value fails with InvalidAttributeLength`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.write(fixture.rw.instanceId, ByteArray(513))

        assertEquals(OperationStatus.InvalidAttributeLength, response?.status)
        assertTrue(fixture.rw.value.value.isEmpty())
    }

    @Test
    fun `write of the maximum length is accepted`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.write(fixture.rw.instanceId, ByteArray(512))

        assertEquals(OperationStatus.Success, response?.status)
        assertEquals(512, fixture.rw.value.value.size)
    }

    @Test
    fun `too long write without response is not responded to`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertNull(fixture.write(fixture.rw.instanceId, ByteArray(513), responseNeeded = false))
        assertTrue(fixture.rw.value.value.isEmpty())
    }

    @Test
    fun `write accepted by the handler is stored and emitted`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.rw.onWrite { WriteResponse.Success }
        val writes = mutableListOf<WriteRequest>()
        backgroundScope.launch { fixture.rw.writes.toList(writes) }
        testScheduler.runCurrent()

        val response = fixture.write(fixture.rw.instanceId, bytes(3))
        testScheduler.runCurrent()

        assertEquals(OperationStatus.Success, response?.status)
        assertContentEquals(bytes(3), fixture.rw.value.value)
        assertEquals(1, writes.size)
    }

    @Test
    fun `write rejected by the handler is not stored`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.rw.setValue(bytes(1))
        fixture.rw.onWrite { WriteResponse.Failure(OperationStatus.ValueNotAllowed) }
        val writes = mutableListOf<WriteRequest>()
        backgroundScope.launch { fixture.rw.writes.toList(writes) }
        testScheduler.runCurrent()

        val response = fixture.write(fixture.rw.instanceId, bytes(3))
        testScheduler.runCurrent()

        assertEquals(OperationStatus.ValueNotAllowed, response?.status)
        assertContentEquals(bytes(1), fixture.rw.value.value)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `write returns UnlikelyError when the handler throws`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.rw.onWrite { throw RuntimeException("Boom") }

        val response = fixture.write(fixture.rw.instanceId, bytes(3))

        assertEquals(OperationStatus.UnlikelyError, response?.status)
        assertTrue(fixture.rw.value.value.isEmpty())

        // The manager still works.
        fixture.rw.onWrite(null)
        assertEquals(OperationStatus.Success, fixture.write(fixture.rw.instanceId, bytes(1))?.status)
    }

    @Test
    fun `write handler sees the value and the client`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        var request: WriteRequest? = null
        fixture.rw.onWrite { request = this; WriteResponse.Success }

        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2, 3), from = fixture.client2)

        assertSame(fixture.client2, request?.client)
        assertContentEquals(bytes(1, 2, 3), request?.value)
    }

    @Test
    fun `CancellationException in the write handler fails only the request`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.rw.onWrite { throw CancellationException("Cancelled") }

        assertEquals(OperationStatus.UnlikelyError, fixture.write(fixture.rw.instanceId, bytes(1))?.status)

        // The event loop is still running.
        fixture.rw.onWrite(null)
        assertEquals(OperationStatus.Success, fixture.write(fixture.rw.instanceId, bytes(1))?.status)
    }

    @Test
    fun `write to a descriptor is stored`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val descriptor = fixture.rw.descriptors.first { it.uuid == CUSTOM_DESCRIPTOR_UUID }

        val response = fixture.write(descriptor.instanceId, bytes(1, 2))

        assertEquals(OperationStatus.Success, response?.status)
        assertContentEquals(bytes(1, 2), descriptor.value.value)
    }

    @Test
    fun `write to a read only descriptor fails with WriteNotPermitted`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val cud = fixture.rw.descriptors.first { it.uuid == Descriptor.CHAR_USER_DESC_UUID }

        val response = fixture.write(cud.instanceId, bytes(1))

        assertEquals(OperationStatus.WriteNotPermitted, response?.status)
        assertContentEquals("Some value".encodeToByteArray(), cud.value.value)
    }

    @Test
    fun `write to a descriptor rejected by the handler fails`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val descriptor = fixture.rw.descriptors.first { it.uuid == CUSTOM_DESCRIPTOR_UUID }
        descriptor.onWrite { WriteResponse.Failure(OperationStatus.WriteRequestRejected) }

        assertEquals(OperationStatus.WriteRequestRejected, fixture.write(descriptor.instanceId, bytes(1))?.status)
        assertTrue(descriptor.value.value.isEmpty())
    }

    @Test
    fun `write to a descriptor without response fails with WriteNotPermitted`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val descriptor = fixture.rw.descriptors.first { it.uuid == CUSTOM_DESCRIPTOR_UUID }

        val _ = fixture.write(descriptor.instanceId, bytes(1), responseNeeded = false)

        assertTrue(descriptor.value.value.isEmpty())
    }

    @Test
    fun `handlers on a CCCD throw`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val cccd = fixture.cccd(NOTIFY_UUID)

        assertFailsWith<IllegalStateException> { cccd.onRead { ReadResponse.Success(bytes()) } }
        assertFailsWith<IllegalStateException> { cccd.onWrite { WriteResponse.Success } }
    }

    // ----- setValue -----

    @Test
    fun `setValue stores the value without notifying`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)

        fixture.notify.setValue(bytes(1, 2))

        assertContentEquals(bytes(1, 2), fixture.notify.value.value)
        assertTrue(fixture.server.notifications.isEmpty())
    }

    @Test
    fun `setValue accepts the maximum length`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        fixture.ro.setValue(ByteArray(512))

        assertEquals(512, fixture.ro.value.value.size)
    }

    @Test
    fun `setValue throws when the value is too long`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertFailsWith<IllegalArgumentException> { fixture.ro.setValue(ByteArray(513)) }
        assertTrue(fixture.ro.value.value.isEmpty())
    }

    @Test
    fun `setValue copies the array`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val array = bytes(1, 2)

        fixture.ro.setValue(array)
        array[0] = 9

        assertContentEquals(bytes(1, 2), fixture.ro.value.value)
    }

    @Test
    fun `setValue of a descriptor throws when the value is too long`() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val descriptor = fixture.rw.descriptors.first { it.uuid == CUSTOM_DESCRIPTOR_UUID }

        assertFailsWith<IllegalArgumentException> { descriptor.setValue(ByteArray(513)) }
        descriptor.setValue(bytes(1))
        assertContentEquals(bytes(1), descriptor.value.value)
    }
}
