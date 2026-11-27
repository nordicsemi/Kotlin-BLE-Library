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
import no.nordicsemi.kotlin.ble.server.WriteRequest
import no.nordicsemi.kotlin.ble.server.WriteResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedWriteTest {

    @Test
    fun prepared_isEchoedAndNotApplied() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true, offset = 0)

        assertEquals(OperationStatus.Success, response?.status)
        assertEquals(0, response?.offset)
        assertContentEquals(bytes(1, 2), response?.value)
        assertTrue(fixture.rw.value.value.isEmpty())
    }

    @Test
    fun prepared_echoesOffset() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        val response = fixture.write(fixture.rw.instanceId, bytes(3, 4), prepared = true, offset = 2)

        assertEquals(2, response?.offset)
        assertContentEquals(bytes(3, 4), response?.value)
    }

    @Test
    fun execute_assemblesFragments() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true, offset = 0)
        val _ = fixture.write(fixture.rw.instanceId, bytes(3, 4), prepared = true, offset = 2)
        val _ = fixture.write(fixture.rw.instanceId, bytes(5), prepared = true, offset = 4)

        val response = fixture.execute()

        assertEquals(OperationStatus.Success, response.status)
        assertContentEquals(bytes(1, 2, 3, 4, 5), fixture.rw.value.value)
    }

    @Test
    fun execute_deliversOneWriteRequest() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val requests = mutableListOf<WriteRequest>()
        fixture.rw.onWrite { requests += this; WriteResponse.Success }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true, offset = 0)
        val _ = fixture.write(fixture.rw.instanceId, bytes(3), prepared = true, offset = 2)

        val _ = fixture.execute()

        assertEquals(1, requests.size)
        assertContentEquals(bytes(1, 2, 3), requests[0].value)
        assertTrue(requests[0].withResponse)
        assertSame(fixture.client, requests[0].client)
    }

    @Test
    fun execute_multipleAttributes() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1), prepared = true, offset = 0)
        val _ = fixture.write(fixture.wo.instanceId, bytes(7, 8), prepared = true, offset = 0)
        val _ = fixture.write(fixture.rw.instanceId, bytes(2), prepared = true, offset = 1)

        val response = fixture.execute()

        assertEquals(OperationStatus.Success, response.status)
        assertContentEquals(bytes(1, 2), fixture.rw.value.value)
        assertContentEquals(bytes(7, 8), fixture.wo.value.value)
    }

    @Test
    fun execute_clearsTheQueue() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1), prepared = true)
        val _ = fixture.execute()
        fixture.rw.setValue(bytes())

        val _ = fixture.execute()

        assertTrue(fixture.rw.value.value.isEmpty())
    }

    @Test
    fun execute_emptyQueue_success() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertEquals(OperationStatus.Success, fixture.execute().status)
    }

    @Test
    fun execute_gap_invalidOffset() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true, offset = 0)
        val _ = fixture.write(fixture.rw.instanceId, bytes(5), prepared = true, offset = 3)

        val response = fixture.execute()

        assertEquals(OperationStatus.InvalidOffset, response.status)
        assertTrue(fixture.rw.value.value.isEmpty())
    }

    @Test
    fun execute_overlap_invalidOffset() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true, offset = 0)
        val _ = fixture.write(fixture.rw.instanceId, bytes(5), prepared = true, offset = 1)

        assertEquals(OperationStatus.InvalidOffset, fixture.execute().status)
        assertTrue(fixture.rw.value.value.isEmpty())
    }

    @Test
    fun execute_firstFragmentNotAtZero_invalidOffset() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true, offset = 2)

        assertEquals(OperationStatus.InvalidOffset, fixture.execute().status)
    }

    @Test
    fun execute_tooLong_invalidAttributeLength() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, ByteArray(300), prepared = true, offset = 0)
        val _ = fixture.write(fixture.rw.instanceId, ByteArray(300), prepared = true, offset = 300)

        assertEquals(OperationStatus.InvalidAttributeLength, fixture.execute().status)
        assertTrue(fixture.rw.value.value.isEmpty())
    }

    @Test
    fun execute_handlerFailure_returnsStatusAndDropsTheRest() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.rw.onWrite { WriteResponse.Failure(OperationStatus.ValueNotAllowed) }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1), prepared = true)
        val _ = fixture.write(fixture.wo.instanceId, bytes(2), prepared = true)

        val response = fixture.execute()

        assertEquals(OperationStatus.ValueNotAllowed, response.status)
        assertTrue(fixture.rw.value.value.isEmpty())
        // The rest is dropped.
        assertTrue(fixture.wo.value.value.isEmpty())
        // The queue is empty afterwards.
        assertEquals(OperationStatus.Success, fixture.execute().status)
        assertTrue(fixture.wo.value.value.isEmpty())
    }

    @Test
    fun execute_handlerException_unlikelyError() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.rw.onWrite { throw RuntimeException("Boom") }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1), prepared = true)

        assertEquals(OperationStatus.UnlikelyError, fixture.execute().status)
    }

    @Test
    fun cancel_clearsTheQueue() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true)

        val response = fixture.execute(execute = false)

        assertEquals(OperationStatus.Success, response.status)
        assertTrue(fixture.rw.value.value.isEmpty())
        val _ = fixture.execute()
        assertTrue(fixture.rw.value.value.isEmpty())
    }

    @Test
    fun disconnect_dropsTheQueueOfTheClientOnly() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true, from = fixture.client)
        val _ = fixture.write(fixture.wo.instanceId, bytes(9), prepared = true, from = fixture.client2)

        fixture.emit(GattServerEvent.ClientDisconnected(fixture.client))

        // The same client connecting again starts with an empty queue.
        assertEquals(OperationStatus.Success, fixture.execute(from = fixture.client).status)
        assertTrue(fixture.rw.value.value.isEmpty())
        // The queue of the other client is not affected.
        assertEquals(OperationStatus.Success, fixture.execute(from = fixture.client2).status)
        assertContentEquals(bytes(9), fixture.wo.value.value)
    }

    @Test
    fun prepared_queueFull() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        var last: Response? = null
        repeat(64) { last = fixture.write(fixture.rw.instanceId, bytes(1), prepared = true, offset = it) }
        assertEquals(OperationStatus.Success, last?.status)

        val response = fixture.write(fixture.rw.instanceId, bytes(1), prepared = true, offset = 64)

        assertEquals(OperationStatus.PrepareQueueFull, response?.status)
    }

    @Test
    fun prepared_queuesAreIndependentPerClient() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1, 2), prepared = true, from = fixture.client)
        val _ = fixture.write(fixture.rw.instanceId, bytes(9), prepared = true, from = fixture.client2)

        val _ = fixture.execute(from = fixture.client)
        assertContentEquals(bytes(1, 2), fixture.rw.value.value)

        val _ = fixture.execute(from = fixture.client2)
        assertContentEquals(bytes(9), fixture.rw.value.value)
    }

    @Test
    fun prepared_unknownAttribute_invalidHandle() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertEquals(OperationStatus.InvalidHandle, fixture.write(9999, bytes(1), prepared = true)?.status)
    }

    @Test
    fun prepared_notWritable_notPermitted() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertEquals(
            OperationStatus.WriteNotPermitted,
            fixture.write(fixture.ro.instanceId, bytes(1), prepared = true)?.status
        )
        assertEquals(OperationStatus.Success, fixture.execute().status)
        assertTrue(fixture.ro.value.value.isEmpty())
    }

    @Test
    fun execute_toCccd_updatesSubscription() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.cccd(NOTIFY_UUID).instanceId, bytes(1, 0), prepared = true)

        assertEquals(OperationStatus.Success, fixture.execute().status)

        assertEquals(setOf(fixture.client), fixture.notify.subscribers.value)
    }

    @Test
    fun execute_afterServicesReplaced_invalidHandle() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val _ = fixture.write(fixture.rw.instanceId, bytes(1), prepared = true)
        val old = fixture.rw

        val _ = fixture.manager.setup {
            Service(SERVICE_UUID) {
                Characteristic(RW_UUID, no.nordicsemi.kotlin.ble.core.CharacteristicProperty.WRITE, no.nordicsemi.kotlin.ble.core.Permission.WRITE)
            }
        }
        val response = fixture.execute()

        assertEquals(OperationStatus.InvalidHandle, response.status)
        assertTrue(old.value.value.isEmpty())
    }
}
