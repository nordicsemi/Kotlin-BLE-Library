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
package no.nordicsemi.kotlin.ble.server.mock

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.OperationStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MockCentralTest {

    private class Setup(val server: TestServer, val app: FakeApp, val central: TestCentral) {
        val readWrite = server.findCharacteristic(SERVICE_UUID, READ_WRITE_UUID)!!
        val notify = server.findCharacteristic(SERVICE_UUID, NOTIFY_UUID)!!
    }

    private suspend fun kotlinx.coroutines.test.TestScope.setup(maxMtu: Int = 517, connect: Boolean = true): Setup {
        val server = TestServer()
        server.setServices(buildServices())
        val app = FakeApp(server, this)
        val central = TestCentral("AA:BB", server, maxMtu)
        if (connect) central.connect()
        return Setup(server, app, central)
    }

    // ---- Connection ----

    @Test
    fun `connect sets state and default MTU`() = runTest {
        val s = setup()

        assertEquals(ConnectionState.Connected, s.central.connectionState.value)
        assertEquals(23, s.central.attMtu.value)
        assertEquals(20, s.central.maximumUpdateValueLength)
        assertTrue(s.server.connectedMockCentrals.value.contains(s.central))
    }

    @Test
    fun `connect twice fails`() = runTest {
        val s = setup()

        assertFailsWith<IllegalStateException> { s.central.connect() }
    }

    @Test
    fun `disconnect emits event and updates state`() = runTest {
        val s = setup()

        s.central.disconnect()

        assertEquals(1, s.app.disconnects.size)
        assertEquals(
            ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.TerminatePeerUser),
            s.central.connectionState.value,
        )
        assertTrue(s.server.connectedMockCentrals.value.isEmpty())
    }

    @Test
    fun `disconnect when not connected does nothing`() = runTest {
        val s = setup(connect = false)

        s.central.disconnect()

        assertTrue(s.app.disconnects.isEmpty())
    }

    @Test
    fun `link loss uses the link loss reason`() = runTest {
        val s = setup()

        s.central.simulateLinkLoss()

        assertEquals(
            ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.LinkLoss),
            s.central.connectionState.value,
        )
    }

    @Test
    fun `can reconnect after disconnecting and MTU is reset`() = runTest {
        val s = setup()
        val _ = s.central.exchangeMtu(185)
        s.central.disconnect()

        s.central.connect()

        assertTrue(s.central.isConnected)
        assertEquals(23, s.central.attMtu.value)
    }

    @Test
    fun `requests fail when not connected`() = runTest {
        val s = setup(connect = false)

        assertFailsWith<IllegalStateException> { s.central.read(s.readWrite) }
        assertFailsWith<IllegalStateException> { s.central.write(s.readWrite, byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { s.central.exchangeMtu(100) }
    }

    @Test
    fun `invalid maximum MTU is rejected`() = runTest {
        val server = TestServer()

        assertFailsWith<IllegalArgumentException> { TestCentral("A", server, 22) }
        assertFailsWith<IllegalArgumentException> { TestCentral("A", server, 518) }
    }

    // ---- MTU ----

    @Test
    fun `MTU exchange is limited to the maximum`() = runTest {
        val s = setup(maxMtu = 247)

        assertEquals(185, s.central.exchangeMtu(185))
        assertEquals(247, s.central.exchangeMtu(517))
        assertEquals(23, s.central.exchangeMtu(5))
        assertEquals(20, s.central.maximumUpdateValueLength)
    }

    @Test
    fun `MTU exchange updates the maximum update value length`() = runTest {
        val s = setup()

        val _ = s.central.exchangeMtu(517)

        assertEquals(514, s.central.maximumUpdateValueLength)
    }

    // ---- Reads ----

    @Test
    fun `read returns the value`() = runTest {
        val s = setup()
        s.app.values[s.readWrite] = byteArrayOf(1, 2, 3)

        val result = s.central.read(s.readWrite)

        assertContentEquals(byteArrayOf(1, 2, 3), result.getOrThrow())
        assertEquals(0, s.app.reads.single().offset)
        assertEquals(s.readWrite, s.app.reads.single().attribute)
    }

    @Test
    fun `read is truncated to MTU minus 1`() = runTest {
        val s = setup()
        s.app.values[s.readWrite] = bytes(40)

        val result = s.central.read(s.readWrite)

        assertContentEquals(bytes(22), result.getOrThrow())
    }

    @Test
    fun `read failure returns the status`() = runTest {
        val s = setup()
        s.app.failing[s.readWrite] = OperationStatus.ReadNotPermitted

        val result = s.central.read(s.readWrite)

        assertEquals(ReadResult.Failure(OperationStatus.ReadNotPermitted), result)
        assertFailsWith<GattOperationException> { result.getOrThrow() }
    }

    @Test
    fun `readBlob sends the offset`() = runTest {
        val s = setup()
        s.app.values[s.readWrite] = bytes(30)

        val result = s.central.readBlob(s.readWrite, 10)

        assertContentEquals(bytes(20, start = 10), result.getOrThrow())
        assertEquals(10, s.app.reads.single().offset)
    }

    @Test
    fun `readLong reads the whole value in blobs`() = runTest {
        val s = setup()
        s.app.values[s.readWrite] = bytes(100)

        val result = s.central.readLong(s.readWrite)

        assertContentEquals(bytes(100), result.getOrThrow())
        assertEquals(listOf(0, 22, 44, 66, 88), s.app.reads.map { it.offset })
    }

    @Test
    fun `readLong of a value that is a multiple of the chunk size ends with an empty read`() = runTest {
        val s = setup()
        s.app.values[s.readWrite] = bytes(44)

        val result = s.central.readLong(s.readWrite)

        assertContentEquals(bytes(44), result.getOrThrow())
        assertEquals(listOf(0, 22, 44), s.app.reads.map { it.offset })
    }

    @Test
    fun `readLong uses a single request when the MTU is large enough`() = runTest {
        val s = setup()
        val _ = s.central.exchangeMtu(517)
        s.app.values[s.readWrite] = bytes(300)

        val result = s.central.readLong(s.readWrite)

        assertContentEquals(bytes(300), result.getOrThrow())
        assertEquals(1, s.app.reads.size)
    }

    @Test
    fun `readLong stops at the first error`() = runTest {
        val s = setup()
        s.app.failing[s.readWrite] = OperationStatus.InsufficientAuthentication

        val result = s.central.readLong(s.readWrite)

        assertEquals(OperationStatus.InsufficientAuthentication, result.statusOrNull())
        assertEquals(1, s.app.reads.size)
    }

    // ---- Writes ----

    @Test
    fun `write with response waits for the response`() = runTest {
        val s = setup()

        val result = s.central.write(s.readWrite, byteArrayOf(5, 6), withResponse = true)

        assertEquals(WriteResult.Success, result)
        val event = s.app.writes.single()
        assertTrue(event.responseNeeded)
        assertFalse(event.prepared)
        assertEquals(0, event.offset)
        assertContentEquals(byteArrayOf(5, 6), event.value)
        assertContentEquals(byteArrayOf(5, 6), s.app.values[s.readWrite])
    }

    @Test
    fun `write without response does not wait`() = runTest {
        val s = setup()

        val result = s.central.write(s.readWrite, byteArrayOf(1), withResponse = false)

        assertEquals(WriteResult.Success, result)
        assertFalse(s.app.writes.single().responseNeeded)
    }

    @Test
    fun `write command that does not fit in a single packet is rejected`() = runTest {
        val s = setup()

        assertFailsWith<IllegalArgumentException> { s.central.write(s.readWrite, bytes(21), withResponse = false) }
        val _ = s.central.write(s.readWrite, bytes(20), withResponse = false)
    }

    @Test
    fun `write failure returns the status`() = runTest {
        val s = setup()
        s.app.failing[s.readWrite] = OperationStatus.WriteNotPermitted

        val result = s.central.write(s.readWrite, byteArrayOf(1))

        assertEquals(WriteResult.Failure(OperationStatus.WriteNotPermitted), result)
        assertFailsWith<GattOperationException> { result.getOrThrow() }
    }

    @Test
    fun `write copies the value`() = runTest {
        val s = setup()
        val value = byteArrayOf(1, 2)

        val _ = s.central.write(s.readWrite, value)
        value[0] = 99

        assertEquals(1, s.app.writes.single().value[0].toInt())
    }

    @Test
    fun `long value written with response uses prepared writes`() = runTest {
        val s = setup()

        val result = s.central.write(s.readWrite, bytes(50), withResponse = true)

        assertEquals(WriteResult.Success, result)
        // 23 - 5 = 18 bytes per prepare write.
        assertEquals(listOf(0, 18, 36), s.app.writes.map { it.offset })
        assertEquals(listOf(18, 18, 14), s.app.writes.map { it.value.size })
        assertTrue(s.app.writes.all { it.prepared && it.responseNeeded })
        assertEquals(listOf(true), s.app.executes.map { it.execute })
        assertContentEquals(bytes(50), s.app.values[s.readWrite])
    }

    @Test
    fun `long write fragments according to the MTU`() = runTest {
        val s = setup()
        val _ = s.central.exchangeMtu(100)

        val _ = s.central.writeLong(s.readWrite, bytes(300))

        assertEquals(listOf(0, 95, 190, 285), s.app.writes.map { it.offset })
        assertTrue(s.app.writes.all { it.value.size <= 95 })
        assertContentEquals(bytes(300), s.app.values[s.readWrite])
    }

    @Test
    fun `value that fits in a single packet is not fragmented`() = runTest {
        val s = setup()

        val _ = s.central.write(s.readWrite, bytes(20))

        assertEquals(1, s.app.writes.size)
        assertFalse(s.app.writes.single().prepared)
        assertTrue(s.app.executes.isEmpty())
    }

    @Test
    fun `long write is cancelled when a part fails`() = runTest {
        val s = setup()
        s.app.failing[s.readWrite] = OperationStatus.InvalidAttributeLength

        val result = s.central.writeLong(s.readWrite, bytes(50))

        assertEquals(WriteResult.Failure(OperationStatus.InvalidAttributeLength), result)
        assertEquals(1, s.app.writes.size)
        assertEquals(listOf(false), s.app.executes.map { it.execute })
        assertEquals(null, s.app.values[s.readWrite])
    }

    @Test
    fun `empty long write sends one empty prepare write`() = runTest {
        val s = setup()

        val result = s.central.writeLong(s.readWrite, ByteArray(0))

        assertEquals(WriteResult.Success, result)
        assertEquals(1, s.app.writes.size)
        assertEquals(0, s.app.writes.single().value.size)
    }

    // ---- Reliable write ----

    @Test
    fun `reliable write executes all queued values`() = runTest {
        val s = setup()
        val other = s.server.findDescriptor(SERVICE_UUID, READ_WRITE_UUID, DESCRIPTOR_UUID)!!

        val result = s.central.reliableWrite {
            val _ = write(s.readWrite, bytes(30))
            val _ = write(other, byteArrayOf(7))
        }

        assertEquals(WriteResult.Success, result)
        assertEquals(1, s.app.executes.size)
        assertTrue(s.app.executes.single().execute)
        assertContentEquals(bytes(30), s.app.values[s.readWrite])
        assertContentEquals(byteArrayOf(7), s.app.values[other])
        // Nothing is written until the execute.
        assertTrue(s.app.writes.all { it.prepared })
    }

    @Test
    fun `reliable write is cancelled when the block throws`() = runTest {
        val s = setup()

        assertFailsWith<IllegalArgumentException> {
            s.central.reliableWrite {
                val _ = write(s.readWrite, bytes(10))
                throw IllegalArgumentException("Stop")
            }
        }

        assertEquals(listOf(false), s.app.executes.map { it.execute })
        assertEquals(null, s.app.values[s.readWrite])
    }

    @Test
    fun `reliable write is cancelled when a write fails`() = runTest {
        val s = setup()
        val other = s.server.findDescriptor(SERVICE_UUID, READ_WRITE_UUID, DESCRIPTOR_UUID)!!
        s.app.failing[other] = OperationStatus.WriteNotPermitted

        val result = s.central.reliableWrite {
            val _ = write(s.readWrite, bytes(10))
            val _ = write(other, byteArrayOf(1))
        }

        assertEquals(WriteResult.Failure(OperationStatus.WriteNotPermitted), result)
        assertEquals(listOf(false), s.app.executes.map { it.execute })
        assertEquals(null, s.app.values[s.readWrite])
    }

    @Test
    fun `reliable write can be cancelled explicitly`() = runTest {
        val s = setup()
        val write = s.central.beginReliableWrite()
        val _ = write.write(s.readWrite, bytes(10))

        val result = write.cancel()

        assertEquals(WriteResult.Success, result)
        assertEquals(listOf(false), s.app.executes.map { it.execute })
        assertFailsWith<IllegalStateException> { write.execute() }
    }

    // ---- Subscriptions ----

    @Test
    fun `subscribe writes the CCCD with notifications enabled`() = runTest {
        val s = setup()

        val result = s.central.subscribe(s.notify)

        assertEquals(WriteResult.Success, result)
        val write = s.app.writes.single()
        assertEquals(s.server.findCccd(s.notify), write.attribute)
        assertContentEquals(byteArrayOf(0x01, 0x00), write.value)
        assertTrue(write.responseNeeded)
    }

    @Test
    fun `subscribe for indications writes the CCCD with indications enabled`() = runTest {
        val s = setup()

        val _ = s.central.subscribe(s.notify, indications = true)

        assertContentEquals(byteArrayOf(0x02, 0x00), s.app.writes.single().value)
    }

    @Test
    fun `unsubscribe writes zeros to the CCCD`() = runTest {
        val s = setup()

        val _ = s.central.unsubscribe(s.notify)

        assertContentEquals(byteArrayOf(0x00, 0x00), s.app.writes.single().value)
    }

    @Test
    fun `subscribe to a characteristic without CCCD fails`() = runTest {
        val s = setup()

        val result = s.central.subscribe(s.readWrite)

        assertEquals(WriteResult.Failure(OperationStatus.SubscribeNotPermitted), result)
        assertTrue(s.app.writes.isEmpty())
    }

    @Test
    fun `subscribe fails with the status returned by the server`() = runTest {
        val s = setup()
        s.app.failing[s.server.findCccd(s.notify)!!] = OperationStatus.InsufficientEncryption

        val result = s.central.subscribe(s.notify)

        assertEquals(OperationStatus.InsufficientEncryption, result.statusOrNull())
    }

    // ---- Misc ----

    @Test
    fun `pending request fails when the central disconnects`() = runTest {
        val server = TestServer()
        server.setServices(buildServices())
        val characteristic = server.findCharacteristic(SERVICE_UUID, READ_WRITE_UUID)!!
        val central = TestCentral("A", server).also { it.connect() }
        // Nobody answers the request.
        var result: ReadResult? = null
        backgroundScope.launch { result = central.read(characteristic) }
        testScheduler.runCurrent()

        central.disconnect()
        testScheduler.runCurrent()

        assertIs<ReadResult.Failure>(result)
    }

    @Test
    fun `request IDs are unique`() = runTest {
        val s = setup()
        s.app.values[s.readWrite] = byteArrayOf(1)

        repeat(3) { val _ = s.central.read(s.readWrite) }

        assertEquals(3, s.app.reads.map { it.requestId }.toSet().size)
    }

    @Test
    fun `findCharacteristic is available on the central`() = runTest {
        val s = setup()

        assertEquals(s.readWrite, s.central.findCharacteristic(SERVICE_UUID, READ_WRITE_UUID))
        assertEquals(null, s.central.findCharacteristic(SERVICE_UUID, DESCRIPTOR_UUID))
        assertEquals(s.server.findService(SERVICE_UUID), s.central.findService(SERVICE_UUID))
    }

    @Test
    fun `several centrals have independent MTUs`() = runTest {
        val s = setup()
        val other = TestCentral("CC", s.server).also { it.connect() }

        val _ = other.exchangeMtu(200)

        assertEquals(23, s.central.attMtu.value)
        assertEquals(200, other.attMtu.value)
    }
}
