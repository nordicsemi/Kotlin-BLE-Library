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

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.exception.ValueDoesNotMatchException
import no.nordicsemi.kotlin.ble.client.mock.PrepareWriteResponse
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.and
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests of Long Read, Long Write and Reliable Write. With MTU 23, a single Read Response carries
 * up to 22 bytes (MTU - 1) and a single Write Request up to 20 bytes (MTU - 3).
 */
class GattLongOperationsTest {

    private val readable = setOf(CharacteristicProperty.READ)
    private val writable = setOf(CharacteristicProperty.WRITE)

    // ---------------------------------------------------------------- Long Read

    @Test
    fun `read of a value that fits in one packet is returned unchanged`() = runTest {
        withTimeout(10.seconds) {
            val value = gattBytes(22)
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(value) } }
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = setOf(Permission.READ))

            assertContentEquals(value, fixture.characteristic().read())
        }
    }

    @Test
    fun `read of a value longer than MTU - 1 is returned in full using long read`() = runTest {
        withTimeout(10.seconds) {
            val value = gattBytes(100)
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(value) } }
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = setOf(Permission.READ))

            assertContentEquals(value, fixture.characteristic().read())
        }
    }

    @Test
    fun `read of a value one byte longer than MTU - 1 is returned in full`() = runTest {
        withTimeout(10.seconds) {
            val value = gattBytes(23)
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(value) } }
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = setOf(Permission.READ))

            assertContentEquals(value, fixture.characteristic().read())
        }
    }

    @Test
    fun `read of a value longer than 512 bytes is limited to the maximum attribute length`() = runTest {
        withTimeout(10.seconds) {
            val value = gattBytes(600)
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(value) } }
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = setOf(Permission.READ))

            // Bluetooth Core Spec, Vol 3, Part F, 3.2.9: max length of an attribute value is 512 octets.
            assertContentEquals(value.copyOf(512), fixture.characteristic().read())
        }
    }

    @Test
    fun `long read takes longer than a single packet read`() = runTest {
        withTimeout(30.seconds) {
            var value = gattBytes(10)
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(value) } }
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = setOf(Permission.READ))
            val characteristic = fixture.characteristic()

            var start = testScheduler.currentTime
            val _ = characteristic.read()
            val short = testScheduler.currentTime - start

            value = gattBytes(200)
            start = testScheduler.currentTime
            val _ = characteristic.read()
            val long = testScheduler.currentTime - start

            // Each Read Blob Request takes at least one connection interval.
            assertTrue(long > short, "Long read took $long ms, short read $short ms")
        }
    }

    // ---------------------------------------------------------------- Long Write

    @Test
    fun `write of exactly MTU - 3 bytes uses a single write request`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val data = gattBytes(20)

            fixture.characteristic().write(data)

            assertContentEquals(data, handler.writeRequests.single())
            assertTrue(handler.prepareWrites.isEmpty())
            assertTrue(handler.executeWrites.isEmpty())
        }
    }

    @Test
    fun `write longer than MTU - 3 uses prepare write and execute write`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val data = gattBytes(21)

            fixture.characteristic().write(data)

            assertContentEquals(data, handler.prepareWrites.single())
            assertTrue(handler.writeRequests.isEmpty())
            assertEquals(listOf(true), handler.executeWrites)
        }
    }

    @Test
    fun `long write delivers the whole value`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val data = gattBytes(300)

            fixture.characteristic().write(data)

            assertContentEquals(data, handler.prepareWrites.single())
            assertEquals(listOf(true), handler.executeWrites)
        }
    }

    @Test
    fun `write longer than 512 bytes is limited to the maximum attribute length`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val data = gattBytes(600)

            fixture.characteristic().write(data)

            assertContentEquals(data.copyOf(512), handler.prepareWrites.single())
        }
    }

    @Test
    fun `value that fits the larger negotiated MTU is not written using long write`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                maxAttMtu = 100,
                properties = writable,
                permissions = setOf(Permission.WRITE),
            )
            val data = gattBytes(97)

            fixture.characteristic().write(data)

            assertContentEquals(data, handler.writeRequests.single())
            assertTrue(handler.prepareWrites.isEmpty())
        }
    }

    @Test
    fun `long write rejected on prepare fails with the status sent by the peripheral`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                prepareHandler = { _, _ -> PrepareWriteResponse.Failure(OperationStatus.PrepareQueueFull) }
            }
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().write(gattBytes(50))
            }

            assertEquals(OperationStatus.PrepareQueueFull, exception.reason)
        }
    }

    @Test
    fun `long write rejected on execute fails with the status sent by the peripheral`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                executeHandler = { WriteResponse.Failure(OperationStatus.InvalidAttributeLength) }
            }
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().write(gattBytes(50))
            }

            assertEquals(OperationStatus.InvalidAttributeLength, exception.reason)
        }
    }

    @Test
    fun `long write fails with ValueDoesNotMatchException when the echoed value differs`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                prepareHandler = { _, value -> PrepareWriteResponse.Success(value.reversedArray()) }
            }
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))

            assertFailsWith<ValueDoesNotMatchException> {
                fixture.characteristic().write(gattBytes(50))
            }

            // The prepared writes must be cancelled.
            assertEquals(listOf(false), handler.executeWrites)
        }
    }

    @Test
    fun `write works after a failed long write`() = runTest {
        withTimeout(10.seconds) {
            var fail = true
            val handler = GattHandler().apply {
                prepareHandler = { _, value ->
                    if (fail) PrepareWriteResponse.Failure(OperationStatus.PrepareQueueFull)
                    else PrepareWriteResponse.Success(value)
                }
            }
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val characteristic = fixture.characteristic()
            assertFailsWith<OperationFailedException> { characteristic.write(gattBytes(50)) }

            fail = false
            characteristic.write(gattBytes(50))

            assertEquals(2, handler.prepareWrites.size)
        }
    }

    @Test
    fun `long write to a descriptor delivers the whole value`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattFixture(handler) {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, Permission.READ) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.WRITE)
                    }
                }
            }
            val data = gattBytes(60)

            fixture.descriptor().write(data)

            val (uuid, value) = handler.descriptorPrepareWrites.single()
            assertEquals(GATT_DESCRIPTOR_UUID, uuid)
            assertContentEquals(data, value)
            assertEquals(listOf(true), handler.executeWrites)
        }
    }

    // ---------------------------------------------------------------- Reliable Write

    @Test
    fun `write during reliable write is not executed until executeReliableWrite`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val characteristic = fixture.characteristic()
            val data = byteArrayOf(0x01, 0x02)

            fixture.peripheral.beginReliableWrite()
            characteristic.write(data)

            // Even a short value is queued using Prepare Write Request.
            assertContentEquals(data, handler.prepareWrites.single())
            assertTrue(handler.executeWrites.isEmpty(), "Nothing should be executed yet")

            fixture.peripheral.executeReliableWrite()

            assertEquals(listOf(true), handler.executeWrites)
        }
    }

    @Test
    fun `abortReliableWrite cancels queued writes`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val characteristic = fixture.characteristic()

            fixture.peripheral.beginReliableWrite()
            characteristic.write(byteArrayOf(0x01))
            fixture.peripheral.abortReliableWrite()

            assertEquals(listOf(false), handler.executeWrites)
        }
    }

    @Test
    fun `reliable write allows several characteristic values to be queued`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattFixture(handler) {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, writable, Permission.WRITE)
                    Characteristic(GATT_CHAR_2_UUID, writable, Permission.WRITE)
                }
            }
            val (first, second) = fixture.discover().single().characteristics

            fixture.peripheral.beginReliableWrite()
            first.write(byteArrayOf(0x01))
            second.write(byteArrayOf(0x02))
            assertEquals(2, handler.prepareWrites.size)
            assertTrue(handler.executeWrites.isEmpty())
            fixture.peripheral.executeReliableWrite()

            // All queued writes are executed with a single Execute Write Request.
            assertEquals(listOf(true), handler.executeWrites)
        }
    }

    @Test
    fun `reliable write rejected on execute fails with the status sent by the peripheral`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                executeHandler = { WriteResponse.Failure(OperationStatus.WriteRequestRejected) }
            }
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val characteristic = fixture.characteristic()
            fixture.peripheral.beginReliableWrite()
            characteristic.write(byteArrayOf(0x01))

            val exception = assertFailsWith<OperationFailedException> {
                fixture.peripheral.executeReliableWrite()
            }

            assertEquals(OperationStatus.WriteRequestRejected, exception.reason)
        }
    }

    @Test
    fun `reliable write fails with ValueDoesNotMatchException when the echoed value differs`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                prepareHandler = { _, _ -> PrepareWriteResponse.Success(byteArrayOf(0x7F)) }
            }
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val characteristic = fixture.characteristic()
            fixture.peripheral.beginReliableWrite()

            assertFailsWith<ValueDoesNotMatchException> {
                characteristic.write(byteArrayOf(0x01))
            }
        }
    }

    @Test
    fun `executeReliableWrite without beginReliableWrite does nothing`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val _ = fixture.characteristic()

            fixture.peripheral.executeReliableWrite()

            assertTrue(handler.executeWrites.isEmpty())
        }
    }

    @Test
    fun `write after reliable write was executed is a regular write`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = writable, permissions = setOf(Permission.WRITE))
            val characteristic = fixture.characteristic()
            fixture.peripheral.beginReliableWrite()
            characteristic.write(byteArrayOf(0x01))
            fixture.peripheral.executeReliableWrite()

            characteristic.write(byteArrayOf(0x02))

            assertContentEquals(byteArrayOf(0x02), handler.writeRequests.single())
        }
    }

    @Test
    fun `write without response is not affected by reliable write`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = writable and CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
                permissions = setOf(Permission.WRITE),
            )
            val characteristic = fixture.characteristic()

            fixture.peripheral.beginReliableWrite()
            characteristic.write(byteArrayOf(0x01), WriteType.WITHOUT_RESPONSE)

            // Write Command cannot be queued.
            assertContentEquals(byteArrayOf(0x01), handler.writeCommands.single())
            assertTrue(handler.prepareWrites.isEmpty())
            fixture.peripheral.abortReliableWrite()
        }
    }
}
