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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.mock.BondResult
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.client.android.BondState
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.and
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GattReadWriteTest {

    private val readable = setOf(CharacteristicProperty.READ)
    private val readPermission = setOf(Permission.READ)

    // ---------------------------------------------------------------- Read

    @Test
    fun `read returns the value sent by the peripheral`() = runTest {
        withTimeout(10.seconds) {
            val value = byteArrayOf(0x01, 0x02, 0x03)
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(value) } }
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = readPermission)

            val result = fixture.characteristic().read()

            assertContentEquals(value, result)
            assertEquals(listOf(GATT_CHAR_UUID), handler.reads)
        }
    }

    @Test
    fun `read of an empty value returns an empty array`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(byteArrayOf()) } }
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = readPermission)

            val result = fixture.characteristic().read()

            assertEquals(0, result.size)
        }
    }

    @Test
    fun `read of a characteristic without READ property fails with ReadNotPermitted`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = setOf(Permission.WRITE),
            )

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().read()
            }

            assertEquals(OperationStatus.ReadNotPermitted, exception.reason)
            assertTrue(handler.reads.isEmpty(), "Request should not be sent to the peripheral")
        }
    }

    @Test
    fun `read of a characteristic without read permission is rejected by the peripheral`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattSingleCharacteristic(properties = readable, permissions = emptySet())

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().read()
            }

            assertEquals(OperationStatus.ReadNotPermitted, exception.reason)
        }
    }

    @Test
    fun `read of an encrypted characteristic on a not bonded device bonds and succeeds`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(byteArrayOf(0x01)) } }
            val fixture = gattSingleCharacteristic(
                handler,
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            val result = fixture.characteristic().read()

            assertContentEquals(byteArrayOf(0x01), result)
            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
        }
    }

    @Test
    fun `read of an encrypted characteristic fails with InsufficientAuthentication when bonding is rejected`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { bondResult = BondResult.Reject() }
            val fixture = gattSingleCharacteristic(
                handler,
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().read()
            }

            assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
            assertIs<BondState.None>(fixture.peripheral.bondState.value)
            assertTrue(handler.reads.isEmpty(), "Request should not be sent to the peripheral")
        }
    }

    @Test
    fun `read of an encrypted characteristic on a bonded device succeeds`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(byteArrayOf(0x55)) } }
            val fixture = gattSingleCharacteristic(
                handler,
                bonded = true,
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            assertContentEquals(byteArrayOf(0x55), fixture.characteristic().read())
        }
    }

    @Test
    fun `read error returned by the peripheral is reported with the same status`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                readHandler = { ReadResponse.Failure(OperationStatus.InsufficientEncryption) }
            }
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = readPermission)

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().read()
            }

            assertEquals(OperationStatus.InsufficientEncryption, exception.reason)
        }
    }

    @Test
    fun `read still works after a failed read`() = runTest {
        withTimeout(10.seconds) {
            var fail = true
            val handler = GattHandler().apply {
                readHandler = {
                    if (fail) ReadResponse.Failure(OperationStatus.UnlikelyError)
                    else ReadResponse.Success(byteArrayOf(0x07))
                }
            }
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = readPermission)
            val characteristic = fixture.characteristic()
            assertFailsWith<OperationFailedException> { characteristic.read() }

            fail = false

            assertContentEquals(byteArrayOf(0x07), characteristic.read())
        }
    }

    // ---------------------------------------------------------------- Read descriptor

    @Test
    fun `descriptor read returns the value sent by the peripheral`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                descriptorReadHandler = { ReadResponse.Success(byteArrayOf(0x0A, 0x0B)) }
            }
            val fixture = gattFixture(handler) {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, readPermission) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.READ)
                    }
                }
            }

            val value = fixture.descriptor().read()

            assertContentEquals(byteArrayOf(0x0A, 0x0B), value)
            assertEquals(listOf(GATT_DESCRIPTOR_UUID), handler.descriptorReads)
        }
    }

    @Test
    fun `Characteristic User Description descriptor returns its description`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, readPermission) {
                        CharacteristicUserDescriptionDescriptor("Temperature")
                    }
                }
            }

            val value = fixture.descriptor(Descriptor.CHAR_USER_DESC_UUID).read()

            assertEquals("Temperature", value.decodeToString())
        }
    }

    @Test
    fun `descriptor without read permission fails with ReadNotPermitted`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, readPermission) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.WRITE)
                    }
                }
            }

            val exception = assertFailsWith<OperationFailedException> {
                fixture.descriptor().read()
            }

            assertEquals(OperationStatus.ReadNotPermitted, exception.reason)
        }
    }

    @Test
    fun `descriptor write is delivered to the peripheral`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattFixture(handler) {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, readPermission) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.WRITE)
                    }
                }
            }

            fixture.descriptor().write(byteArrayOf(0x01, 0x02))

            val (uuid, value) = handler.descriptorWrites.single()
            assertEquals(GATT_DESCRIPTOR_UUID, uuid)
            assertContentEquals(byteArrayOf(0x01, 0x02), value)
        }
    }

    @Test
    fun `descriptor write rejected by the peripheral fails with the same status`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                descriptorWriteHandler = { _, _ -> WriteResponse.Failure(OperationStatus.ValueNotAllowed) }
            }
            val fixture = gattFixture(handler) {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, readPermission) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.WRITE)
                    }
                }
            }

            val exception = assertFailsWith<OperationFailedException> {
                fixture.descriptor().write(byteArrayOf(0x01))
            }

            assertEquals(OperationStatus.ValueNotAllowed, exception.reason)
        }
    }

    @Test
    fun `Characteristic Extended Properties descriptor is read only`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, readPermission) {
                        CharacteristicExtendedPropertiesDescriptor(reliableWrite = true)
                    }
                }
            }
            val descriptor = fixture.descriptor(Descriptor.CHAR_EXT_PROP_UUID)

            assertContentEquals(byteArrayOf(0x01, 0x00), descriptor.read())
            val exception = assertFailsWith<OperationFailedException> {
                descriptor.write(byteArrayOf(0x00, 0x00))
            }
            assertEquals(OperationStatus.WriteNotPermitted, exception.reason)
        }
    }

    // ---------------------------------------------------------------- Write with response

    @Test
    fun `write with response delivers the value to the peripheral`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = setOf(Permission.WRITE),
            )

            fixture.characteristic().write(byteArrayOf(0x01, 0x02, 0x03), WriteType.WITH_RESPONSE)

            assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), handler.writeRequests.single())
            assertTrue(handler.writeCommands.isEmpty())
        }
    }

    @Test
    fun `write uses write with response by default when the characteristic supports it`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = CharacteristicProperty.WRITE and CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
                permissions = setOf(Permission.WRITE),
            )

            fixture.characteristic().write(byteArrayOf(0x01))

            assertEquals(1, handler.writeRequests.size)
            assertTrue(handler.writeCommands.isEmpty())
        }
    }

    @Test
    fun `write uses write without response by default when it is the only write property`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE),
                permissions = setOf(Permission.WRITE),
            )

            fixture.characteristic().write(byteArrayOf(0x01))

            assertTrue(handler.writeRequests.isEmpty())
            assertContentEquals(byteArrayOf(0x01), handler.writeCommands.single())
        }
    }

    @Test
    fun `write error returned by the peripheral is reported with the same status`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                writeHandler = { _, _ -> WriteResponse.Failure(OperationStatus.InvalidAttributeLength) }
            }
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = setOf(Permission.WRITE),
            )

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().write(byteArrayOf(0x01))
            }

            assertEquals(OperationStatus.InvalidAttributeLength, exception.reason)
        }
    }

    @Test
    fun `write to a characteristic without write properties fails with WriteNotPermitted`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = readPermission)

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().write(byteArrayOf(0x01), WriteType.WITH_RESPONSE)
            }

            assertEquals(OperationStatus.WriteNotPermitted, exception.reason)
            assertTrue(handler.writeRequests.isEmpty())
        }
    }

    @Test
    fun `write to an encrypted characteristic on a not bonded device bonds and succeeds`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = setOf(Permission.WRITE_ENCRYPTED),
            )

            fixture.characteristic().write(byteArrayOf(0x01))

            assertEquals(1, handler.writeRequests.size)
            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
        }
    }

    @Test
    fun `write to an encrypted characteristic fails with InsufficientAuthentication when bonding is rejected`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { bondResult = BondResult.Reject() }
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = setOf(Permission.WRITE_ENCRYPTED),
            )

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().write(byteArrayOf(0x01))
            }

            assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
            assertIs<BondState.None>(fixture.peripheral.bondState.value)
            assertTrue(handler.writeRequests.isEmpty())
        }
    }

    @Test
    fun `write without write permission is rejected by the peripheral`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattSingleCharacteristic(
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = readPermission,
            )

            val exception = assertFailsWith<OperationFailedException> {
                fixture.characteristic().write(byteArrayOf(0x01))
            }

            assertEquals(OperationStatus.WriteNotPermitted, exception.reason)
        }
    }

    // ---------------------------------------------------------------- Write without response

    @Test
    fun `write without response is delivered as a write command`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE),
                permissions = setOf(Permission.WRITE),
            )

            fixture.characteristic().write(byteArrayOf(0x0A, 0x0B), WriteType.WITHOUT_RESPONSE)

            assertContentEquals(byteArrayOf(0x0A, 0x0B), handler.writeCommands.single())
            assertTrue(handler.writeRequests.isEmpty())
        }
    }

    @Test
    fun `write commands reach the peripheral in the order they were issued`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE),
                permissions = setOf(Permission.WRITE),
            )
            val characteristic = fixture.characteristic()

            repeat(10) { characteristic.write(byteArrayOf(it.toByte()), WriteType.WITHOUT_RESPONSE) }

            assertEquals((0..9).map { it.toByte() }, handler.writeCommands.map { it.single() })
        }
    }

    @Test
    fun `write command longer than MTU - 3 is trimmed`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE),
                permissions = setOf(Permission.WRITE),
            )
            val characteristic = fixture.characteristic()
            val maxLength = fixture.peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE)
            assertEquals(20, maxLength)

            val data = gattBytes(maxLength + 10)
            characteristic.write(data, WriteType.WITHOUT_RESPONSE)

            // "If data is longer than maximumWriteValueLength for given writeType, the value will be trimmed."
            assertContentEquals(data.copyOf(maxLength), handler.writeCommands.single())
        }
    }

    @Test
    fun `write command of exactly MTU - 3 bytes is not trimmed`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE),
                permissions = setOf(Permission.WRITE),
            )

            val data = gattBytes(20)
            fixture.characteristic().write(data, WriteType.WITHOUT_RESPONSE)

            assertContentEquals(data, handler.writeCommands.single())
        }
    }

    @Test
    fun `write command limit follows the negotiated MTU`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                maxAttMtu = 100,
                properties = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE),
                permissions = setOf(Permission.WRITE),
            )
            val characteristic = fixture.characteristic()
            assertEquals(97, fixture.peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE))

            val data = gattBytes(97)
            characteristic.write(data, WriteType.WITHOUT_RESPONSE)

            assertContentEquals(data, handler.writeCommands.single())
        }
    }

    @Test
    fun `write without response to a characteristic that does not support it fails`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = setOf(Permission.WRITE),
            )

            // The characteristic declares only Write (with response).
            assertFailsWith<OperationFailedException> {
                fixture.characteristic().write(byteArrayOf(0x01), WriteType.WITHOUT_RESPONSE)
            }

            assertTrue(handler.writeCommands.isEmpty(), "Write Command must not be sent")
        }
    }

    @Test
    fun `signed write is not accepted when characteristic does not support it`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = setOf(Permission.WRITE),
            )

            assertFailsWith<OperationFailedException> {
                fixture.characteristic().write(byteArrayOf(0x01), WriteType.SIGNED)
            }

            assertTrue(handler.writeCommands.isEmpty())
        }
    }

    // ---------------------------------------------------------------- Concurrency

    @Test
    fun `concurrent writes are delivered one by one in the order they were started`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = setOf(Permission.WRITE),
            )
            val characteristic = fixture.characteristic()

            List(5) { index -> async { characteristic.write(byteArrayOf(index.toByte())) } }.awaitAll()

            assertEquals((0..4).map { it.toByte() }, handler.writeRequests.map { it.single() })
        }
    }

    @Test
    fun `concurrent operations on different characteristics do not overlap in time`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, readPermission)
                    Characteristic(GATT_CHAR_2_UUID, readable, readPermission)
                }
            }
            val (first, second) = fixture.discover().single().characteristics
            val before = testScheduler.currentTime
            val _ = first.read()
            val single = testScheduler.currentTime - before

            val start = testScheduler.currentTime
            listOf(async { first.read() }, async { second.read() }, async { first.read() }).awaitAll()
            val elapsed = testScheduler.currentTime - start

            // GATT allows only one outstanding request at a time, so the operations are serialised.
            assertTrue(elapsed >= 3 * single, "3 reads took $elapsed ms, one takes $single ms")
        }
    }
}
