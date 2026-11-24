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

package no.nordicsemi.kotlin.ble.client.android.mock

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.mock.BondResult
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.GenericAccessService
import no.nordicsemi.kotlin.ble.client.mock.GenericAttributeService
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.SimulatedCentral
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.server.ReadResponse
import no.nordicsemi.kotlin.ble.server.WriteRequest
import no.nordicsemi.kotlin.ble.server.WriteResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests of the reads and writes of a peripheral with a hosted GATT server, which handles
 * the requests of the client using the handlers and values of the local attributes.
 *
 * With MTU 23, a single Read Response carries up to 22 bytes (MTU - 1) and a single Write Request
 * up to 20 bytes (MTU - 3).
 */
class HostedGattTest {

    private val readWrite = CharacteristicProperty.READ and CharacteristicProperty.WRITE
    private val readWritePermissions = Permission.READ and Permission.WRITE

    private suspend fun TestScope.readWriteFixture(maxAttMtu: Int = 23) = hostedFixture(maxAttMtu) {
        Service(HOSTED_SERVICE_UUID) {
            Characteristic(HOSTED_CHAR_UUID, readWrite, readWritePermissions) {
                CharacteristicUserDescriptionDescriptor("Description")
                Descriptor(HOSTED_DESCRIPTOR_UUID, readWritePermissions)
            }
            Characteristic(HOSTED_CHAR_2_UUID, readWrite, readWritePermissions)
        }
    }

    // ---------------------------------------------------------------- Discovery

    @Test
    fun `client discovers the services of the hosted server`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()

            val services = fixture.discover()

            val service = services.single()
            assertEquals(HOSTED_SERVICE_UUID, service.uuid)
            assertEquals(listOf(HOSTED_CHAR_UUID, HOSTED_CHAR_2_UUID), service.characteristics.map { it.uuid })
            assertEquals(fixture.services.single().instanceId, service.instanceId)
        }
    }

    @Test
    fun `discovered attributes have the handles of the local attributes`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()

            val characteristic = fixture.remote()

            assertEquals(fixture.local().instanceId, characteristic.instanceId)
            assertEquals(
                fixture.local().descriptors.map { it.instanceId to it.uuid },
                characteristic.descriptors.map { it.instanceId to it.uuid },
            )
            assertEquals(readWrite, characteristic.properties)
        }
    }

    @Test
    fun `Generic Access service returns the values of the peripheral spec`() = runTest {
        withTimeout(10.seconds) {
            val fixture = hostedFixture {
                GenericAccessService()
                GenericAttributeService()
                Service(HOSTED_SERVICE_UUID)
            }
            val gas = fixture.discover().first { it.uuid == no.nordicsemi.kotlin.ble.core.Service.GENERIC_ACCESS_UUID }

            val name = gas.characteristics.first { it.uuid == no.nordicsemi.kotlin.ble.core.Characteristic.DEVICE_NAME }

            assertEquals("Hosted", name.read().decodeToString())
        }
    }

    // ---------------------------------------------------------------- Read

    @Test
    fun `read of a characteristic without value returns an empty value`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()

            assertContentEquals(byteArrayOf(), fixture.remote().read())
        }
    }

    @Test
    fun `read returns the value of the local characteristic`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            fixture.local().setValue(byteArrayOf(0x01, 0x02, 0x03))

            assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), fixture.remote().read())

            fixture.local().setValue(byteArrayOf(0x04))
            assertContentEquals(byteArrayOf(0x04), fixture.remote().read())
        }
    }

    @Test
    fun `read handler provides the value and receives the central`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            var requester: no.nordicsemi.kotlin.ble.server.Central<*>? = null
            fixture.local().onRead {
                requester = central
                ReadResponse.Success(byteArrayOf(0x42))
            }

            assertContentEquals(byteArrayOf(0x42), fixture.remote().read())
            assertSame(fixture.server.central, requester)
        }
    }

    @Test
    fun `read handler is called for each read`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            var counter = 0
            fixture.local().onRead { ReadResponse.Success(byteArrayOf((++counter).toByte())) }
            val characteristic = fixture.remote()

            assertContentEquals(byteArrayOf(1), characteristic.read())
            assertContentEquals(byteArrayOf(2), characteristic.read())
            assertContentEquals(byteArrayOf(3), characteristic.read())
        }
    }

    @Test
    fun `read of a long value is returned in full`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val value = gattBytes(100)
            var requests = 0
            fixture.local().onRead {
                requests++
                ReadResponse.Success(value)
            }

            assertContentEquals(value, fixture.remote().read())
            // The first Read Request and 4 Read Blob Requests, each carrying up to 22 bytes.
            assertEquals(5, requests)
        }
    }

    @Test
    fun `read of a long value uses a larger mtu`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture(maxAttMtu = 247)
            val value = gattBytes(400)
            fixture.local().setValue(value)
            val characteristic = fixture.remote()
            hostedSettle()

            assertContentEquals(value, characteristic.read())
            assertEquals(247, fixture.server.central.mtu.value)
        }
    }

    @Test
    fun `read rejected by the handler fails with the given status`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            fixture.local().onRead { ReadResponse.Failure(OperationStatus.InsufficientAuthorization) }

            val exception = assertFailsWith<OperationFailedException> { fixture.remote().read() }

            assertEquals(OperationStatus.InsufficientAuthorization, exception.reason)
        }
    }

    @Test
    fun `read fails with an unlikely error when the handler throws`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            fixture.local().onRead { error("Failure in the handler") }

            val exception = assertFailsWith<OperationFailedException> { fixture.remote().read() }

            assertEquals(OperationStatus.UnlikelyError, exception.reason)
        }
    }

    @Test
    fun `read handler may suspend using virtual time`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.remote()
            fixture.local().onRead {
                delay(2.seconds)
                ReadResponse.Success(byteArrayOf(0x01))
            }

            val start = testScheduler.currentTime
            val _ = characteristic.read()

            assertTrue(testScheduler.currentTime - start >= 2.seconds.inWholeMilliseconds)
        }
    }

    @Test
    fun `read of a characteristic without READ property is not sent to the server`() = runTest {
        withTimeout(10.seconds) {
            val fixture = hostedFixture {
                Service(HOSTED_SERVICE_UUID) {
                    Characteristic(HOSTED_CHAR_UUID, CharacteristicProperty.WRITE, Permission.WRITE)
                }
            }
            var requests = 0
            fixture.local().onRead {
                requests++
                ReadResponse.Success(byteArrayOf())
            }

            val exception = assertFailsWith<OperationFailedException> { fixture.remote().read() }

            assertEquals(OperationStatus.ReadNotPermitted, exception.reason)
            assertEquals(0, requests)
        }
    }

    @Test
    fun `read of the user description descriptor returns its description`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val description = fixture.remote()
                .descriptors
                .first { it.uuid == no.nordicsemi.kotlin.ble.core.Descriptor.CHAR_USER_DESC_UUID }

            assertEquals("Description", description.read().decodeToString())
        }
    }

    @Test
    fun `read of a descriptor uses the handler of the local descriptor`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            fixture.localDescriptor().onRead { ReadResponse.Success(byteArrayOf(0x0D)) }

            assertContentEquals(byteArrayOf(0x0D), fixture.remoteDescriptor().read())
        }
    }

    // ---------------------------------------------------------------- Write

    @Test
    fun `write with response is stored and emitted`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.remote()
            val writes = mutableListOf<WriteRequest>()
            fixture.local().writes.onEach { writes += it }.launchIn(backgroundScope)
            hostedSettle()

            characteristic.write(byteArrayOf(0x01, 0x02))
            hostedSettle()

            assertContentEquals(byteArrayOf(0x01, 0x02), fixture.local().value.value)
            assertContentEquals(byteArrayOf(0x01, 0x02), writes.single().value)
            assertTrue(writes.single().withResponse)
            assertSame(fixture.server.central, writes.single().central)
        }
    }

    @Test
    fun `written value is returned by a read`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.remote()

            characteristic.write(byteArrayOf(0x0A, 0x0B))

            assertContentEquals(byteArrayOf(0x0A, 0x0B), characteristic.read())
        }
    }

    @Test
    fun `write handler receives the value`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val received = mutableListOf<ByteArray>()
            fixture.local().onWrite {
                received += value
                WriteResponse.Success
            }

            fixture.remote().write(byteArrayOf(0x05))

            assertContentEquals(byteArrayOf(0x05), received.single())
        }
    }

    @Test
    fun `write rejected by the handler fails with the given status and is not stored`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            fixture.local().setValue(byteArrayOf(0x01))
            fixture.local().onWrite { WriteResponse.Failure(OperationStatus.ValueNotAllowed) }
            val writes = mutableListOf<WriteRequest>()
            fixture.local().writes.onEach { writes += it }.launchIn(backgroundScope)
            hostedSettle()

            val exception = assertFailsWith<OperationFailedException> {
                fixture.remote().write(byteArrayOf(0x02))
            }
            hostedSettle()

            assertEquals(OperationStatus.ValueNotAllowed, exception.reason)
            assertContentEquals(byteArrayOf(0x01), fixture.local().value.value)
            assertTrue(writes.isEmpty())
        }
    }

    @Test
    fun `write fails with an unlikely error when the handler throws`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            fixture.local().onWrite { error("Failure in the handler") }

            val exception = assertFailsWith<OperationFailedException> {
                fixture.remote().write(byteArrayOf(0x02))
            }

            assertEquals(OperationStatus.UnlikelyError, exception.reason)
        }
    }

    @Test
    fun `write without response is delivered to the handler`() = runTest {
        withTimeout(10.seconds) {
            val fixture = hostedFixture {
                Service(HOSTED_SERVICE_UUID) {
                    Characteristic(
                        HOSTED_CHAR_UUID,
                        CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
                        Permission.WRITE,
                    )
                }
            }
            val requests = mutableListOf<WriteRequest>()
            fixture.local().onWrite {
                requests += this
                WriteResponse.Success
            }
            val characteristic = fixture.remote()

            characteristic.write(byteArrayOf(0x07), WriteType.WITHOUT_RESPONSE)
            hostedSettle()

            val request = requests.single()
            assertContentEquals(byteArrayOf(0x07), request.value)
            assertEquals(false, request.withResponse)
        }
    }

    @Test
    fun `write of a characteristic without WRITE property is not sent to the server`() = runTest {
        withTimeout(10.seconds) {
            val fixture = hostedFixture {
                Service(HOSTED_SERVICE_UUID) {
                    Characteristic(HOSTED_CHAR_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }
            var requests = 0
            fixture.local().onWrite {
                requests++
                WriteResponse.Success
            }

            val exception = assertFailsWith<OperationFailedException> { fixture.remote().write(byteArrayOf(0x01)) }

            assertEquals(OperationStatus.WriteNotPermitted, exception.reason)
            assertEquals(0, requests)
        }
    }

    @Test
    fun `write of a descriptor uses the handler of the local descriptor`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val received = mutableListOf<ByteArray>()
            fixture.localDescriptor().onWrite {
                received += value
                WriteResponse.Success
            }

            fixture.remoteDescriptor().write(byteArrayOf(0x09))

            assertContentEquals(byteArrayOf(0x09), received.single())
            assertContentEquals(byteArrayOf(0x09), fixture.remoteDescriptor().read())
        }
    }

    @Test
    fun `write of a descriptor rejected by the handler fails`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            fixture.localDescriptor().onWrite { WriteResponse.Failure(OperationStatus.InvalidAttributeLength) }

            val exception = assertFailsWith<OperationFailedException> {
                fixture.remoteDescriptor().write(byteArrayOf(0x09))
            }

            assertEquals(OperationStatus.InvalidAttributeLength, exception.reason)
        }
    }

    // ---------------------------------------------------------------- Long Write

    @Test
    fun `long write delivers the whole value to the handler once`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val value = gattBytes(50)
            val received = mutableListOf<ByteArray>()
            fixture.local().onWrite {
                received += this.value
                WriteResponse.Success
            }

            fixture.remote().write(value)

            assertContentEquals(value, received.single())
            assertContentEquals(value, fixture.local().value.value)
        }
    }

    @Test
    fun `long write is emitted in writes once`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val writes = mutableListOf<WriteRequest>()
            fixture.local().writes.onEach { writes += it }.launchIn(backgroundScope)
            hostedSettle()

            fixture.remote().write(gattBytes(60))
            hostedSettle()

            assertEquals(1, writes.size)
            assertEquals(60, writes.single().value.size)
        }
    }

    @Test
    fun `long write rejected by the handler fails and is not stored`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            fixture.local().onWrite { WriteResponse.Failure(OperationStatus.ValueNotAllowed) }

            val exception = assertFailsWith<OperationFailedException> {
                fixture.remote().write(gattBytes(50))
            }

            assertEquals(OperationStatus.ValueNotAllowed, exception.reason)
            assertContentEquals(byteArrayOf(), fixture.local().value.value)
        }
    }

    @Test
    fun `long write of a value longer than 512 bytes is truncated`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val value = gattBytes(600)

            fixture.remote().write(value)

            assertContentEquals(value.copyOf(512), fixture.local().value.value)
        }
    }

    @Test
    fun `long write of a descriptor delivers the whole value`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val value = gattBytes(40)

            fixture.remoteDescriptor().write(value)

            assertContentEquals(value, fixture.localDescriptor().value.value)
        }
    }

    @Test
    fun `long write after a rejected one is not affected by it`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            var accept = false
            fixture.local().onWrite {
                if (accept) WriteResponse.Success else WriteResponse.Failure(OperationStatus.ValueNotAllowed)
            }
            val characteristic = fixture.remote()
            assertFailsWith<OperationFailedException> { characteristic.write(gattBytes(50)) }

            accept = true
            characteristic.write(gattBytes(30, seed = 1))

            assertContentEquals(gattBytes(30, seed = 1), fixture.local().value.value)
        }
    }

    // ---------------------------------------------------------------- Reliable Write

    @Test
    fun `reliable write is applied when executed`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.remote()

            fixture.peripheral.beginReliableWrite()
            characteristic.write(byteArrayOf(0x01, 0x02))
            hostedSettle()
            assertContentEquals(byteArrayOf(), fixture.local().value.value)

            fixture.peripheral.executeReliableWrite()

            assertContentEquals(byteArrayOf(0x01, 0x02), fixture.local().value.value)
        }
    }

    @Test
    fun `aborted reliable write is not applied`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.remote()

            fixture.peripheral.beginReliableWrite()
            characteristic.write(byteArrayOf(0x01))
            fixture.peripheral.abortReliableWrite()

            assertContentEquals(byteArrayOf(), fixture.local().value.value)
        }
    }

    @Test
    fun `reliable write of several characteristics is executed together`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val first = fixture.remote(HOSTED_CHAR_UUID)
            val second = fixture.remote(HOSTED_CHAR_2_UUID)

            fixture.peripheral.beginReliableWrite()
            first.write(byteArrayOf(0x01))
            second.write(byteArrayOf(0x02))
            fixture.peripheral.executeReliableWrite()

            assertContentEquals(byteArrayOf(0x01), fixture.local(HOSTED_CHAR_UUID).value.value)
            assertContentEquals(byteArrayOf(0x02), fixture.local(HOSTED_CHAR_2_UUID).value.value)
        }
    }

    @Test
    fun `reliable write rejected by the handler fails on execute`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            fixture.local().onWrite { WriteResponse.Failure(OperationStatus.WriteRequestRejected) }
            val characteristic = fixture.remote()
            fixture.peripheral.beginReliableWrite()
            characteristic.write(byteArrayOf(0x01))

            val exception = assertFailsWith<OperationFailedException> {
                fixture.peripheral.executeReliableWrite()
            }

            assertEquals(OperationStatus.WriteRequestRejected, exception.reason)
            assertContentEquals(byteArrayOf(), fixture.local().value.value)
        }
    }

    @Test
    fun `reliable write of a long value delivers the whole value`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val value = gattBytes(70)
            val characteristic = fixture.remote()

            fixture.peripheral.beginReliableWrite()
            characteristic.write(value)
            fixture.peripheral.executeReliableWrite()

            assertContentEquals(value, fixture.local().value.value)
        }
    }

    @Test
    fun `write after a reliable write is a regular write`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.remote()
            fixture.peripheral.beginReliableWrite()
            characteristic.write(byteArrayOf(0x01))
            fixture.peripheral.executeReliableWrite()

            characteristic.write(byteArrayOf(0x02))

            assertContentEquals(byteArrayOf(0x02), fixture.local().value.value)
        }
    }

    // ---------------------------------------------------------------- Timing

    private suspend fun TestScope.measure(block: suspend () -> Unit): Long {
        val start = testScheduler.currentTime
        block()
        return testScheduler.currentTime - start
    }

    /**
     * Creates a peripheral using the event handler, with the same characteristic as the hosted one,
     * and returns the characteristic.
     */
    private suspend fun TestScope.handlerBasedCharacteristic(value: ByteArray) = gattSingleCharacteristic(
        GattHandler().apply { readHandler = { no.nordicsemi.kotlin.ble.client.mock.ReadResponse.Success(value) } },
        properties = readWrite,
        permissions = readWritePermissions,
    ).characteristic()

    @Test
    fun `read takes as long as read from a peripheral using an event handler`() = runTest {
        withTimeout(30.seconds) {
            val value = gattBytes(10)
            val hosted = readWriteFixture()
            hosted.local().setValue(value)
            val hostedCharacteristic = hosted.remote()
            val handlerBased = handlerBasedCharacteristic(value)

            assertEquals(measure { val _ = handlerBased.read() }, measure { val _ = hostedCharacteristic.read() })
        }
    }

    @Test
    fun `long read takes as long as long read from a peripheral using an event handler`() = runTest {
        withTimeout(30.seconds) {
            val value = gattBytes(100)
            val hosted = readWriteFixture()
            hosted.local().setValue(value)
            val hostedCharacteristic = hosted.remote()
            val handlerBased = handlerBasedCharacteristic(value)

            val expected = measure { val _ = handlerBased.read() }
            assertTrue(expected > 31, "A long read should take more than a connection interval")
            assertEquals(expected, measure { val _ = hostedCharacteristic.read() })
        }
    }

    @Test
    fun `write takes as long as write to a peripheral using an event handler`() = runTest {
        withTimeout(30.seconds) {
            val hosted = readWriteFixture()
            val hostedCharacteristic = hosted.remote()
            val handlerBased = handlerBasedCharacteristic(byteArrayOf())

            val expected = measure { handlerBased.write(byteArrayOf(0x01, 0x02)) }
            assertEquals(expected, measure { hostedCharacteristic.write(byteArrayOf(0x01, 0x02)) })
        }
    }

    @Test
    fun `long write takes as long as long write to a peripheral using an event handler`() = runTest {
        withTimeout(30.seconds) {
            val hosted = readWriteFixture()
            val hostedCharacteristic = hosted.remote()
            val handlerBased = handlerBasedCharacteristic(byteArrayOf())
            val value = gattBytes(80)

            val expected = measure { handlerBased.write(value) }
            assertTrue(expected > 31, "A long write should take more than a connection interval")
            assertEquals(expected, measure { hostedCharacteristic.write(value) })
        }
    }

    @Test
    fun `write rejected by the handler takes as long as a rejected write using an event handler`() = runTest {
        withTimeout(30.seconds) {
            val hosted = readWriteFixture()
            hosted.local().onWrite { WriteResponse.Failure(OperationStatus.ValueNotAllowed) }
            val hostedCharacteristic = hosted.remote()
            val handler = GattHandler().apply {
                writeHandler = { _, _ -> no.nordicsemi.kotlin.ble.client.mock.WriteResponse.Failure(OperationStatus.ValueNotAllowed) }
            }
            val handlerBased = gattSingleCharacteristic(
                handler,
                properties = readWrite,
                permissions = readWritePermissions,
            ).characteristic()

            val expected = measure { val _ = runCatching { handlerBased.write(byteArrayOf(0x01)) } }
            assertEquals(expected, measure { val _ = runCatching { hostedCharacteristic.write(byteArrayOf(0x01)) } })
            assertTrue(expected in 1..100)
        }
    }

    // ---------------------------------------------------------------- Bonding

    @Test
    fun `read of an encrypted characteristic bonds and succeeds`() = runTest {
        withTimeout(10.seconds) {
            val eventHandler = object : PeripheralSpecEventHandler {
                override fun onBondRequest(peripheral: MockPeripheral) = BondResult.Accept()
            }
            val fixture = hostedFixture(eventHandler = eventHandler) {
                Service(HOSTED_SERVICE_UUID) {
                    Characteristic(HOSTED_CHAR_UUID, CharacteristicProperty.READ, Permission.READ_ENCRYPTED)
                }
            }
            fixture.local().setValue(byteArrayOf(0x33))

            assertContentEquals(byteArrayOf(0x33), fixture.remote().read())
            assertTrue(fixture.spec.isBonded)
        }
    }

    @Test
    fun `read of an encrypted characteristic fails when the peripheral rejects bonding`() = runTest {
        withTimeout(10.seconds) {
            val fixture = hostedFixture {
                Service(HOSTED_SERVICE_UUID) {
                    Characteristic(HOSTED_CHAR_UUID, CharacteristicProperty.READ, Permission.READ_ENCRYPTED)
                }
            }
            var requests = 0
            fixture.local().onRead {
                requests++
                ReadResponse.Success(byteArrayOf())
            }

            val exception = assertFailsWith<OperationFailedException> { fixture.remote().read() }

            assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
            assertEquals(0, requests)
        }
    }

    @Test
    fun `peripheral is connected using a direct connection`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()

            fixture.manager.connect(
                fixture.manager.getPeripheralById(HOSTED_ADDRESS)!!,
                CentralManager.ConnectionOptions.Direct(),
            )

            assertTrue(fixture.spec.isConnected)
            assertTrue(fixture.server.central.state.value.isConnected)
        }
    }

    @Test
    fun `write completes within a connection interval for a single request`() = runTest {
        withTimeout(10.seconds) {
            val fixture = readWriteFixture()
            val characteristic = fixture.remote()

            val duration = measure { characteristic.write(byteArrayOf(0x01)) }

            // The default connection interval of a peripheral is 25 * 1.25 ms.
            assertTrue(duration in 1..100.milliseconds.inWholeMilliseconds, "Took $duration ms")
        }
    }
}
