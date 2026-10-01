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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.mock.ConnectionResult
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteDescriptor
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class NotificationsTest {
    private val serviceUuid = Uuid.random()
    private val characteristicUuid = Uuid.random()
    private val descriptorUuid = Uuid.random()
    private val includedServiceUuid = Uuid.random()
    private val includedCharacteristicUuid = Uuid.random()
    private val value = byteArrayOf(0x01, 0x02, 0x03)

    private lateinit var scope: CoroutineScope
    private lateinit var peripheralSpec: PeripheralSpec<String>
    private lateinit var peripheral: Peripheral
    private var handle: Int = -1

    /** Number of CCCD writes received by the peripheral. */
    private var cccdWrites = 0

    /** Value of [RemoteCharacteristic.isNotifying] when the peripheral received the CCCD write. */
    private var isNotifyingOnCccdWrite: Boolean? = null

    /** The response to CCCD writes. */
    private var cccdWriteResponse: WriteResponse = WriteResponse.Success

    private val eventHandler = object : PeripheralSpecEventHandler {
        override fun onConnectionRequest(phy: PrimaryPhy) = ConnectionResult.Accept

        override fun onWriteRequest(descriptor: MockRemoteDescriptor, value: ByteArray): WriteResponse {
            if (!descriptor.isClientCharacteristicConfiguration) return WriteResponse.Success

            cccdWrites++
            isNotifyingOnCccdWrite = descriptor.characteristic.isNotifying.value

            // Send a notification immediately after notifications were enabled.
            // It will be received by the client before the CCCD Write Response.
            val enabled = value.isNotEmpty() && value[0] != 0.toByte()
            if (enabled && cccdWriteResponse is WriteResponse.Success) {
                peripheralSpec.simulateValueUpdate(handle, this@NotificationsTest.value)
            }
            return cccdWriteResponse
        }
    }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        cccdWrites = 0
        isNotifyingOnCccdWrite = null
        cccdWriteResponse = WriteResponse.Success
        peripheralSpec = PeripheralSpec.simulatePeripheral(
            identifier = "AA:BB:CC:DD:EE:FF",
            // Android assumes PUBLIC address type for devices that weren't scanned.
            addressType = AddressType.PUBLIC,
        ) {
            advertising {
                CompleteLocalName("Test")
            }
            connectable(
                name = "Test",
                eventHandler = eventHandler,
            ) {
                Service(uuid = serviceUuid) {
                    handle = Characteristic(
                        uuid = characteristicUuid,
                        property = CharacteristicProperty.NOTIFY,
                        permission = Permission.READ,
                    ) {
                        // CCCD is added automatically
                        Descriptor(descriptorUuid, permission = Permission.WRITE)
                    }
                    IncludedService(uuid = includedServiceUuid) {
                        Characteristic(
                            uuid = includedCharacteristicUuid,
                            property = CharacteristicProperty.NOTIFY,
                            permission = Permission.READ,
                        )
                    }
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Connects to the mock peripheral and returns the discovered services.
     */
    private suspend fun connect(): List<RemoteService> {
        val centralManager = CentralManager.mock(scope = scope)
            .apply { simulatePeripherals(listOf(peripheralSpec)) }
        peripheral = centralManager.getPeripheralById(peripheralSpec.identifier)!!
        centralManager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
        return peripheral.services()
            .filterIsInstance<RemoteServices.Discovered>()
            .first()
            .services
    }

    @Test
    fun `notification sent immediately after enabling is received`() = runBlocking {
        withTimeout(10.seconds) {
            val characteristic = connect()
                .first { it.uuid == serviceUuid }
                .characteristics
                .first { it.uuid == characteristicUuid }

            val received = withTimeout(2.seconds) {
                characteristic.subscribe().first()
            }

            assertContentEquals(value, received)
            // isNotifying reports the confirmed state, i.e. is set after the write response.
            assertEquals(false, isNotifyingOnCccdWrite)
            assertTrue(characteristic.isNotifying.value)
        }
    }

    @Test
    fun `notification sent immediately after re-enabling reaches active subscriber`() = runBlocking {
        withTimeout(10.seconds) {
            val characteristic = connect()
                .first { it.uuid == serviceUuid }
                .characteristics
                .first { it.uuid == characteristicUuid }

            val received = Channel<ByteArray>(Channel.UNLIMITED)
            val subscription = characteristic.subscribe()
                .onEach { received.send(it) }
                .launchIn(this)
            assertContentEquals(value, withTimeout(2.seconds) { received.receive() })

            // Disable and enable notifications again, while the subscriber keeps collecting.
            // On contrary to the subscribe() above, the subscriber is not waiting for the
            // CCCD write to complete, so it processes the notification immediately.
            characteristic.setNotifying(false)
            characteristic.setNotifying(true)
            assertContentEquals(value, withTimeout(2.seconds) { received.receive() })

            subscription.cancel()
        }
    }

    @Test
    fun `active subscriber receives no values after disabling`() = runBlocking {
        withTimeout(10.seconds) {
            val characteristic = connect()
                .first { it.uuid == serviceUuid }
                .characteristics
                .first { it.uuid == characteristicUuid }

            val received = Channel<ByteArray>(Channel.UNLIMITED)
            val subscription = characteristic.subscribe()
                .onEach { received.send(it) }
                .launchIn(this)
            assertContentEquals(value, withTimeout(2.seconds) { received.receive() })

            // Disable notifications, while the subscriber keeps collecting.
            characteristic.setNotifying(false)
            assertFalse(characteristic.isNotifying.value)

            // A peripheral may send a notification even if it's disabled.
            // It should not be delivered to the subscriber.
            peripheralSpec.simulateValueUpdate(handle, byteArrayOf(0x04))
            assertNull(withTimeoutOrNull(500.milliseconds) { received.receive() })

            subscription.cancel()
        }
    }

    @Test
    fun `rejected CCCD write does not enable notifications`() = runBlocking {
        withTimeout(10.seconds) {
            val characteristic = connect()
                .first { it.uuid == serviceUuid }
                .characteristics
                .first { it.uuid == characteristicUuid }
            cccdWriteResponse = WriteResponse.Failure(OperationStatus.InsufficientAuthentication)

            val exception = assertFailsWith<OperationFailedException> {
                characteristic.setNotifying(true)
            }
            assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
            assertFalse(characteristic.isNotifying.value)

            // Notifications sent anyway are not delivered to subscribers.
            val received = Channel<ByteArray>(Channel.UNLIMITED)
            val collector = characteristic.subscribe()
                .onEach { received.send(it) }
                .catch { /* Subscription fails with the same error. */ }
                .launchIn(this)
            peripheralSpec.simulateValueUpdate(handle, value)
            assertNull(withTimeoutOrNull(500.milliseconds) { received.receive() })
            collector.cancel()

            // Accepting the write enables notifications.
            cccdWriteResponse = WriteResponse.Success
            characteristic.setNotifying(true)
            assertTrue(characteristic.isNotifying.value)
        }
    }

    @Test
    fun `setNotifying during Reliable Write enables notifications immediately`() = runBlocking {
        withTimeout(10.seconds) {
            val characteristic = connect()
                .first { it.uuid == serviceUuid }
                .characteristics
                .first { it.uuid == characteristicUuid }

            // Reliable Write applies only to characteristic values. The CCCD is written
            // using a Write Request, and not queued until Execute Write.
            peripheral.beginReliableWrite()
            val received = withTimeout(2.seconds) {
                characteristic.subscribe().first()
            }
            assertContentEquals(value, received)
            assertEquals(1, cccdWrites)
            assertTrue(characteristic.isNotifying.value)

            peripheral.abortReliableWrite()
        }
    }

    @Test
    fun `Long Write to a descriptor completes`() = runBlocking {
        withTimeout(10.seconds) {
            val descriptor = connect()
                .first { it.uuid == serviceUuid }
                .characteristics
                .first { it.uuid == characteristicUuid }
                .descriptors
                .first { it.uuid == descriptorUuid }

            // The value is longer than MTU - 3, so Long Write is used.
            withTimeout(2.seconds) {
                descriptor.write(ByteArray(40) { it.toByte() })
            }
        }
    }

    @Test
    fun `notifications in included service are reset on disconnection`() = runBlocking {
        withTimeout(10.seconds) {
            val characteristic = connect()
                .first { it.uuid == serviceUuid }
                .includedServices
                .first { it.uuid == includedServiceUuid }
                .characteristics
                .first { it.uuid == includedCharacteristicUuid }

            characteristic.setNotifying(true)
            assertTrue(characteristic.isNotifying.value)

            peripheral.disconnect()

            // Invalidating services should reset characteristics of included services as well.
            withTimeout(2.seconds) {
                characteristic.isNotifying.first { !it }
            }
            assertFalse(characteristic.isNotifying.value)
        }
    }

    @Test
    fun `concurrent setNotifying writes CCCD once`() = runBlocking {
        withTimeout(10.seconds) {
            val characteristic = connect()
                .first { it.uuid == serviceUuid }
                .characteristics
                .first { it.uuid == characteristicUuid }

            List(3) { async { characteristic.setNotifying(true) } }.awaitAll()

            assertEquals(1, cccdWrites)
            assertTrue(characteristic.isNotifying.value)

            List(3) { async { characteristic.setNotifying(false) } }.awaitAll()

            assertEquals(2, cccdWrites)
            assertFalse(characteristic.isNotifying.value)
        }
    }
}
