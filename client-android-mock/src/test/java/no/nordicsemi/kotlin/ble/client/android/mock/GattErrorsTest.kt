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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.and
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests of operations on attributes that were invalidated, cancelled operations
 * and serialisation of operations.
 */
class GattErrorsTest {

    private val allProperties = CharacteristicProperty.READ and CharacteristicProperty.WRITE
    private val allPermissions = Permission.READ and Permission.WRITE

    private fun kotlinx.coroutines.test.TestScope.readWriteFixture(handler: GattHandler = GattHandler()) =
        gattFixture(handler) {
            Service(GATT_SERVICE_UUID) {
                Characteristic(GATT_CHAR_UUID, allProperties, allPermissions) {
                    Descriptor(GATT_DESCRIPTOR_UUID, allPermissions)
                }
                Characteristic(GATT_CHAR_2_UUID, CharacteristicProperty.NOTIFY, Permission.READ)
            }
        }

    // ---------------------------------------------------------------- Disconnected peripheral

    @Test
    fun `read after disconnection fails with InvalidAttributeException`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.characteristic()
            fixture.peripheral.disconnect()
            gattSettle()

            assertFailsWith<InvalidAttributeException> { characteristic.read() }
        }
    }

    @Test
    fun `write after disconnection fails with InvalidAttributeException`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.characteristic()
            fixture.peripheral.disconnect()
            gattSettle()

            assertFailsWith<InvalidAttributeException> { characteristic.write(byteArrayOf(0x01)) }
            assertFailsWith<InvalidAttributeException> {
                characteristic.write(byteArrayOf(0x01), WriteType.WITHOUT_RESPONSE)
            }
        }
    }

    @Test
    fun `setNotifying and subscribe after disconnection fail with InvalidAttributeException`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val notifying = fixture.characteristic(GATT_CHAR_2_UUID)
            fixture.peripheral.disconnect()
            gattSettle()

            assertFailsWith<InvalidAttributeException> { notifying.setNotifying(true) }
            assertFailsWith<InvalidAttributeException> { notifying.subscribe() }
        }
    }

    @Test
    fun `descriptor operations after disconnection fail with InvalidAttributeException`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val descriptor = fixture.descriptor()
            fixture.peripheral.disconnect()
            gattSettle()

            assertFailsWith<InvalidAttributeException> { descriptor.read() }
            assertFailsWith<InvalidAttributeException> { descriptor.write(byteArrayOf(0x01)) }
        }
    }

    @Test
    fun `read after the link was lost fails with InvalidAttributeException`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.characteristic()
            fixture.spec.simulateDisconnection()
            gattSettle()

            assertFailsWith<InvalidAttributeException> { characteristic.read() }
        }
    }

    @Test
    fun `read in progress when the peripheral disconnects fails instead of hanging`() = runTest {
        withTimeout(10.seconds) {
            // An empty value is read immediately. A long value takes a few connection intervals.
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(gattBytes(100)) } }
            val fixture = readWriteFixture(handler)
            val characteristic = fixture.characteristic()
            val read = async { runCatching { characteristic.read() } }
            runCurrent()

            fixture.spec.simulateDisconnection()

            val result = read.await()
            assertIs<InvalidAttributeException>(result.exceptionOrNull())
        }
    }

    @Test
    fun `write in progress when the peripheral disconnects fails instead of hanging`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.characteristic()
            val write = async { runCatching { characteristic.write(gattBytes(100)) } }
            runCurrent()

            fixture.spec.simulateDisconnection()

            val result = write.await()
            assertIs<InvalidAttributeException>(result.exceptionOrNull())
        }
    }

    @Test
    fun `short write in progress when the peripheral disconnects fails instead of hanging`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.characteristic()
            val write = async { runCatching { characteristic.write(gattBytes(1)) } }
            runCurrent()

            fixture.spec.simulateDisconnection()

            assertIs<InvalidAttributeException>(write.await().exceptionOrNull())
        }
    }

    @Test
    fun `descriptor read in progress when the peripheral disconnects fails instead of hanging`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { descriptorReadHandler = { ReadResponse.Success(gattBytes(100)) } }
            val fixture = readWriteFixture(handler)
            val descriptor = fixture.descriptor()
            val read = async { runCatching { descriptor.read() } }
            runCurrent()

            fixture.spec.simulateDisconnection()

            assertIs<InvalidAttributeException>(read.await().exceptionOrNull())
        }
    }

    @Test
    fun `descriptor write in progress when the peripheral disconnects fails instead of hanging`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val descriptor = fixture.descriptor()
            val write = async { runCatching { descriptor.write(gattBytes(100)) } }
            runCurrent()

            fixture.spec.simulateDisconnection()

            assertIs<InvalidAttributeException>(write.await().exceptionOrNull())
        }
    }

    @Test
    fun `operation that completes before the peripheral disconnects is not affected`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(gattBytes(10)) } }
            val fixture = readWriteFixture(handler)
            val characteristic = fixture.characteristic()

            val value = characteristic.read()
            fixture.spec.simulateDisconnection()
            gattSettle()

            assertContentEquals(gattBytes(10), value)
        }
    }

    // ---------------------------------------------------------------- Service Changed

    @Test
    fun `attributes of services that changed can no longer be used`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                GenericAttributeService()
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }
            val oldCharacteristic = fixture.characteristic()
            gattSettle()

            fixture.spec.simulateServiceChange {
                GenericAttributeService()
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }
            gattSettle()

            assertFailsWith<InvalidAttributeException> { oldCharacteristic.read() }
        }
    }

    // ---------------------------------------------------------------- Cancellation

    @Test
    fun `cancelled read does not block following operations`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(gattBytes(200)) } }
            val fixture = readWriteFixture(handler)
            val characteristic = fixture.characteristic()

            val job = launch { val _ = characteristic.read() }
            runCurrent()
            job.cancelAndJoin()

            assertContentEquals(gattBytes(200), withTimeout(5.seconds) { characteristic.read() })
        }
    }

    @Test
    fun `cancelled long write does not block following operations`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = readWriteFixture(handler)
            val characteristic = fixture.characteristic()

            val job = launch { characteristic.write(gattBytes(200)) }
            runCurrent()
            job.cancelAndJoin()

            withTimeout(5.seconds) { characteristic.write(byteArrayOf(0x01)) }
            assertContentEquals(byteArrayOf(0x01), handler.writeRequests.last())
        }
    }

    @Test
    fun `cancelled operation waiting for the previous one does not run`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = readWriteFixture(handler)
            val characteristic = fixture.characteristic()

            val first = async { characteristic.write(byteArrayOf(0x01)) }
            val second = launch { characteristic.write(byteArrayOf(0x02)) }
            val third = async { characteristic.write(byteArrayOf(0x03)) }
            runCurrent()
            second.cancelAndJoin()
            listOf(first, third).awaitAll()

            assertEquals(listOf<Byte>(1, 3), handler.writeRequests.map { it.single() })
        }
    }

    @Test
    fun `operation timeout set by the caller cancels the pending operation`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(gattBytes(500)) } }
            val fixture = readWriteFixture(handler)
            val characteristic = fixture.characteristic()

            // Reading 500 bytes with MTU 23 takes much longer than a connection interval.
            val result = runCatching {
                withTimeout(1.milliseconds) { characteristic.read() }
            }

            assertIs<kotlinx.coroutines.TimeoutCancellationException>(result.exceptionOrNull())
            // The next operation is not blocked.
            handler.readHandler = { ReadResponse.Success(byteArrayOf(0x01)) }
            assertContentEquals(byteArrayOf(0x01), characteristic.read())
        }
    }

    // ---------------------------------------------------------------- Serialisation

    @Test
    fun `read and write started together do not interleave`() = runTest {
        withTimeout(10.seconds) {
            val events = mutableListOf<String>()
            val handler = GattHandler().apply {
                readHandler = {
                    events += "read"
                    ReadResponse.Success(byteArrayOf(0x01))
                }
                writeHandler = { _, _ ->
                    events += "write"
                    no.nordicsemi.kotlin.ble.client.mock.WriteResponse.Success
                }
            }
            val fixture = readWriteFixture(handler)
            val characteristic = fixture.characteristic()
            val before = testScheduler.currentTime
            val _ = characteristic.read()
            val readDuration = testScheduler.currentTime - before
            events.clear()

            val read = async { characteristic.read() }
            val write = async { characteristic.write(byteArrayOf(0x02)) }
            // The second request must not be sent before the first one completes.
            runCurrent()
            advanceTimeBy((readDuration - 1).coerceAtLeast(0))
            assertEquals(listOf("read"), events)
            listOf(read, write).awaitAll()

            assertEquals(listOf("read", "write"), events)
        }
    }

    @Test
    fun `failed operation reports the error and following operations still work`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                readHandler = { ReadResponse.Failure(OperationStatus.InsufficientAuthorization) }
            }
            val fixture = readWriteFixture(handler)
            val characteristic = fixture.characteristic()

            val failing = List(3) { async { runCatching { characteristic.read() } } }.awaitAll()

            failing.forEach { result ->
                val exception = result.exceptionOrNull()
                assertTrue(exception is OperationFailedException)
                assertEquals(OperationStatus.InsufficientAuthorization, exception.reason)
            }
            characteristic.write(byteArrayOf(0x01))
            assertEquals(1, handler.writeRequests.size)
        }
    }
}
