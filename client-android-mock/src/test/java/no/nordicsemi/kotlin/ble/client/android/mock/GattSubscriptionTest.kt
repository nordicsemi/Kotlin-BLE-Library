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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.and
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GattSubscriptionTest {

    private var handle = -1
    private var otherHandle = -1

    private fun TestScope.notifyingFixture(
        properties: Set<CharacteristicProperty> = setOf(CharacteristicProperty.NOTIFY),
        maxAttMtu: Int = 23,
        handler: GattHandler = GattHandler(),
    ) = gattFixture(handler, maxAttMtu) {
        Service(GATT_SERVICE_UUID) {
            handle = Characteristic(GATT_CHAR_UUID, properties, setOf(Permission.READ))
            otherHandle = Characteristic(GATT_CHAR_2_UUID, properties, setOf(Permission.READ))
        }
    }

    /** Starts collecting [characteristic] and returns when notifications are enabled. */
    private suspend fun TestScope.collect(
        characteristic: RemoteCharacteristic,
        values: MutableList<ByteArray>,
    ) {
        val enabled = CompletableDeferred<Unit>()
        characteristic
            .subscribe { enabled.complete(Unit) }
            .onEach { values += it }
            .launchIn(backgroundScope)
        enabled.await()
    }

    private fun lastCccdWrite(handler: GattHandler) =
        handler.descriptorWrites.last { it.first == Descriptor.CLIENT_CHAR_CONF_UUID }.second

    // ---------------------------------------------------------------- Notifications

    @Test
    fun `notification values are received unchanged`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.characteristic(), values)

            fixture.spec.simulateValueUpdate(handle, byteArrayOf(0x0A, 0x0B, 0x0C))
            gattSettle()

            assertEquals(1, values.size)
            assertContentEquals(byteArrayOf(0x0A, 0x0B, 0x0C), values.single())
        }
    }

    @Test
    fun `notifications are received in the order they were sent`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.characteristic(), values)

            for (i in 0 until 5) {
                fixture.spec.simulateValueUpdate(handle, byteArrayOf(i.toByte()))
            }
            gattSettle()

            assertEquals((0..4).map { it.toByte() }, values.map { it.single() })
        }
    }

    @Test
    fun `none of a burst of notifications is lost`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.characteristic(), values)

            for (i in 0 until 50) {
                fixture.spec.simulateValueUpdate(handle, byteArrayOf(i.toByte(), 0x00))
            }
            gattSettle()

            assertEquals((0 until 50).map { it.toByte() }, values.map { it[0] })
        }
    }

    @Test
    fun `notification longer than MTU - 3 is truncated`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.characteristic(), values)

            // A peripheral cannot send more than MTU - 3 bytes in a single notification.
            fixture.spec.simulateValueUpdate(handle, gattBytes(30))
            gattSettle()

            assertContentEquals(gattBytes(20), values.single())
        }
    }

    @Test
    fun `notification of exactly MTU - 3 bytes is not truncated`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.characteristic(), values)

            fixture.spec.simulateValueUpdate(handle, gattBytes(20))
            gattSettle()

            assertContentEquals(gattBytes(20), values.single())
        }
    }

    @Test
    fun `notification limit follows the negotiated MTU`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture(maxAttMtu = 247)
            val values = mutableListOf<ByteArray>()
            collect(fixture.characteristic(), values)

            // The MTU is 247, so a notification has up to 244 bytes.
            fixture.spec.simulateValueUpdate(handle, gattBytes(244))
            fixture.spec.simulateValueUpdate(handle, gattBytes(250))
            gattSettle()

            assertEquals(listOf(244, 244), values.map { it.size })
        }
    }

    @Test
    fun `notification is never longer than the maximum attribute value of 512 bytes`() = runTest {
        withTimeout(10.seconds) {
            // With the MTU of 517 a notification could carry 514 bytes.
            val fixture = notifyingFixture(maxAttMtu = 517)
            val values = mutableListOf<ByteArray>()
            collect(fixture.characteristic(), values)

            fixture.spec.simulateValueUpdate(handle, gattBytes(600))
            gattSettle()

            assertEquals(512, values.single().size)
        }
    }

    @Test
    fun `truncating a notification does not change the value given by the caller`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val values = mutableListOf<ByteArray>()
            collect(fixture.characteristic(), values)

            val value = gattBytes(30)
            fixture.spec.simulateValueUpdate(handle, value)
            gattSettle()

            assertEquals(30, value.size)
            assertEquals(20, values.single().size)
        }
    }

    @Test
    fun `notifications are delivered only to the subscriber of that characteristic`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val (first, second) = fixture.discover().single().characteristics
            val firstValues = mutableListOf<ByteArray>()
            val secondValues = mutableListOf<ByteArray>()
            collect(first, firstValues)
            collect(second, secondValues)

            fixture.spec.simulateValueUpdate(otherHandle, byteArrayOf(0x02))
            gattSettle()

            assertTrue(firstValues.isEmpty())
            assertContentEquals(byteArrayOf(0x02), secondValues.single())
        }
    }

    @Test
    fun `two subscribers receive the same values`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val characteristic = fixture.characteristic()
            val first = mutableListOf<ByteArray>()
            val second = mutableListOf<ByteArray>()
            collect(characteristic, first)
            collect(characteristic, second)

            fixture.spec.simulateValueUpdate(handle, byteArrayOf(0x01))
            fixture.spec.simulateValueUpdate(handle, byteArrayOf(0x02))
            gattSettle()

            assertEquals(listOf<Byte>(1, 2), first.map { it.single() })
            assertEquals(listOf<Byte>(1, 2), second.map { it.single() })
        }
    }

    @Test
    fun `second subscriber does not write the CCCD again`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = notifyingFixture(handler = handler)
            val characteristic = fixture.characteristic()
            collect(characteristic, mutableListOf())
            collect(characteristic, mutableListOf())

            val cccdWrites = handler.descriptorWrites.count { it.first == Descriptor.CLIENT_CHAR_CONF_UUID }
            assertEquals(1, cccdWrites)
        }
    }

    @Test
    fun `subscribe enables notifications writing 0x0001 to the CCCD`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = notifyingFixture(handler = handler)
            collect(fixture.characteristic(), mutableListOf())

            assertContentEquals(byteArrayOf(0x01, 0x00), lastCccdWrite(handler))
        }
    }

    @Test
    fun `notifications are not enabled until the flow is collected`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = notifyingFixture(handler = handler)
            val characteristic = fixture.characteristic()

            val _ = characteristic.subscribe()
            gattSettle()

            assertFalse(characteristic.isNotifying.value)
            assertTrue(handler.descriptorWrites.isEmpty())
        }
    }

    @Test
    fun `waitForValueChange returns the value sent after the trigger`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattFixture(handler) {
                Service(GATT_SERVICE_UUID) {
                    handle = Characteristic(
                        GATT_CHAR_UUID,
                        CharacteristicProperty.NOTIFY and CharacteristicProperty.WRITE,
                        Permission.WRITE,
                    )
                }
            }
            handler.writeHandler = { _, _ ->
                fixture.spec.simulateValueUpdate(handle, byteArrayOf(0x42))
                WriteResponse.Success
            }

            val value = fixture.characteristic().waitForValueChange(trigger = { write(byteArrayOf(0x01)) })

            assertContentEquals(byteArrayOf(0x42), value)
        }
    }

    // ---------------------------------------------------------------- Indications

    @Test
    fun `subscribe to an indicate-only characteristic writes 0x0002 to the CCCD`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = notifyingFixture(setOf(CharacteristicProperty.INDICATE), handler = handler)
            val characteristic = fixture.characteristic()

            collect(characteristic, mutableListOf())

            assertContentEquals(byteArrayOf(0x02, 0x00), lastCccdWrite(handler))
            assertTrue(characteristic.isNotifying.value)
        }
    }

    @Test
    fun `indications are received like notifications`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture(setOf(CharacteristicProperty.INDICATE))
            val values = mutableListOf<ByteArray>()
            collect(fixture.characteristic(), values)

            fixture.spec.simulateValueUpdate(handle, byteArrayOf(0x09, 0x08))
            gattSettle()

            assertContentEquals(byteArrayOf(0x09, 0x08), values.single())
        }
    }

    @Test
    fun `characteristic with both notify and indicate uses notifications`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = notifyingFixture(
                CharacteristicProperty.NOTIFY and CharacteristicProperty.INDICATE,
                handler = handler,
            )

            collect(fixture.characteristic(), mutableListOf())

            // Notify has priority over Indicate if both are supported.
            assertContentEquals(byteArrayOf(0x01, 0x00), lastCccdWrite(handler))
        }
    }

    // ---------------------------------------------------------------- Not subscribable

    @Test
    fun `subscribe to a characteristic without notify or indicate fails`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattSingleCharacteristic(
                properties = setOf(CharacteristicProperty.READ),
                permissions = setOf(Permission.READ),
            )
            val characteristic = fixture.characteristic()

            val exception = assertFailsWith<OperationFailedException> { characteristic.subscribe() }

            assertEquals(OperationStatus.SubscribeNotPermitted, exception.reason)
        }
    }

    @Test
    fun `setNotifying on a characteristic without notify or indicate fails`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattSingleCharacteristic(
                properties = setOf(CharacteristicProperty.READ),
                permissions = setOf(Permission.READ),
            )
            val characteristic = fixture.characteristic()

            val exception = assertFailsWith<OperationFailedException> { characteristic.setNotifying(true) }

            assertEquals(OperationStatus.SubscribeNotPermitted, exception.reason)
            assertFalse(characteristic.isNotifying.value)
        }
    }

    @Test
    fun `subscribe fails when the peripheral rejects the CCCD write`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                descriptorWriteHandler = { _, _ -> WriteResponse.Failure(OperationStatus.InsufficientEncryption) }
            }
            val fixture = notifyingFixture(handler = handler)
            val characteristic = fixture.characteristic()

            val result = runCatching { characteristic.subscribe().first() }

            val exception = result.exceptionOrNull()
            assertTrue(exception is OperationFailedException, "Was: $exception")
            assertEquals(OperationStatus.InsufficientEncryption, exception.reason)
            assertFalse(characteristic.isNotifying.value)
        }
    }

    // ---------------------------------------------------------------- isNotifying

    @Test
    fun `isNotifying follows setNotifying`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = notifyingFixture(handler = handler)
            val characteristic = fixture.characteristic()
            assertFalse(characteristic.isNotifying.value)

            characteristic.setNotifying(true)
            assertTrue(characteristic.isNotifying.value)
            assertContentEquals(byteArrayOf(0x01, 0x00), lastCccdWrite(handler))

            characteristic.setNotifying(false)
            assertFalse(characteristic.isNotifying.value)
            assertContentEquals(byteArrayOf(0x00, 0x00), lastCccdWrite(handler))
        }
    }

    @Test
    fun `setNotifying with the current state does not write the CCCD`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = notifyingFixture(handler = handler)
            val characteristic = fixture.characteristic()

            characteristic.setNotifying(false)

            assertTrue(handler.descriptorWrites.isEmpty())
        }
    }

    @Test
    fun `CCCD descriptor reads back the enabled notifications`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val characteristic = fixture.characteristic()
            val cccd = characteristic.descriptors.single()
            assertContentEquals(byteArrayOf(0x00, 0x00), cccd.read())

            characteristic.setNotifying(true)

            assertContentEquals(byteArrayOf(0x01, 0x00), cccd.read())
        }
    }

    // ---------------------------------------------------------------- Disconnection

    @Test
    fun `subscription flow completes when the peripheral disconnects`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val characteristic = fixture.characteristic()
            val enabled = CompletableDeferred<Unit>()
            val collected = async {
                characteristic.subscribe { enabled.complete(Unit) }.toList()
            }
            enabled.await()
            fixture.spec.simulateValueUpdate(handle, byteArrayOf(0x01))
            gattSettle()

            fixture.peripheral.disconnect()

            val values = withTimeoutOrNull(2.seconds) { collected.await() }
            assertEquals(listOf<Byte>(1), values?.map { it.single() })
        }
    }

    @Test
    fun `isNotifying is false after disconnection`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val characteristic = fixture.characteristic()
            characteristic.setNotifying(true)

            fixture.peripheral.disconnect()

            withTimeout(2.seconds) { characteristic.isNotifying.first { !it } }
        }
    }

    @Test
    fun `notifications are not enabled again after reconnection until requested`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = notifyingFixture(handler = handler)
            fixture.characteristic().setNotifying(true)
            fixture.peripheral.disconnect()
            gattSettle()
            val cccdWritesBefore = handler.descriptorWrites.size

            val characteristic = fixture.characteristic()
            gattSettle()

            assertFalse(characteristic.isNotifying.value)
            assertEquals(cccdWritesBefore, handler.descriptorWrites.size)
        }
    }

    @Test
    fun `new subscription after reconnection receives notifications`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            fixture.characteristic().setNotifying(true)
            fixture.peripheral.disconnect()
            gattSettle()

            val characteristic = fixture.characteristic()
            val values = mutableListOf<ByteArray>()
            collect(characteristic, values)
            fixture.spec.simulateValueUpdate(handle, byteArrayOf(0x33))
            gattSettle()

            assertContentEquals(byteArrayOf(0x33), values.single())
        }
    }

    @Test
    fun `notifications sent while not subscribed are not delivered later`() = runTest {
        withTimeout(10.seconds) {
            val fixture = notifyingFixture()
            val characteristic = fixture.characteristic()

            fixture.spec.simulateValueUpdate(handle, byteArrayOf(0x01))
            gattSettle()
            val values = mutableListOf<ByteArray>()
            collect(characteristic, values)
            gattSettle()

            assertNull(values.firstOrNull())
            assertNull(withTimeoutOrNull(500.milliseconds) { characteristic.subscribe().first() })
        }
    }
}
