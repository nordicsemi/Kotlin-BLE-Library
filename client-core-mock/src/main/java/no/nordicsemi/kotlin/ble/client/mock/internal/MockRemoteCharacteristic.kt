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

package no.nordicsemi.kotlin.ble.client.mock.internal

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import no.nordicsemi.kotlin.ble.client.AnyRemoteService
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.exception.ValueDoesNotMatchException
import no.nordicsemi.kotlin.ble.client.internal.BaseRemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicRead
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicWrite
import no.nordicsemi.kotlin.ble.client.internal.OperationEvent
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec.Authentication
import no.nordicsemi.kotlin.ble.client.mock.PrepareWriteResponse
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.Service
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.internal.CharacteristicDefinition
import kotlin.uuid.Uuid

class MockRemoteCharacteristic(
    parent: AnyRemoteService,
    private val peripheralSpec: PeripheralSpec<*>,
    private val characteristic: CharacteristicDefinition,
    events: SharedFlow<GattEvent>,
): BaseRemoteCharacteristic(parent, events) {
    override val uuid: Uuid = characteristic.uuid
    override val instanceId: Int = characteristic.instanceId
    override val properties: Set<CharacteristicProperty> = characteristic.properties
    override val descriptors: List<RemoteDescriptor> = characteristic.descriptors.map {
        MockRemoteDescriptor(this, peripheralSpec, it, events)
    }

    override fun setCharacteristicNotification(enabled: Boolean) {
        // no-op
    }

    override suspend fun FlowCollector<GattEvent>.executeRead() {
        val eventHandler = checkNotNull(peripheralSpec.eventHandler)
        check(peripheralSpec.isConnected) { "Peripheral not connected" }

        // Ensure that the services are valid.
        check(peripheralSpec.isServiceCacheValid) {
            // The response is delivered in the next connection interval.
            peripheralSpec.emulateDelay()
            emit(CharacteristicRead(
                characteristic = this@MockRemoteCharacteristic,
                value = byteArrayOf(),
                // TODO Verify if this is the correct status to use.
                status = OperationStatus.InvalidHandle,
            ))
            return
        }

        // Check read permissions.
        val insecure = Permission.READ in characteristic.permissions
        val authenticationRequired = characteristic.permissions.any {
            it == Permission.READ_ENCRYPTED ||
            it == Permission.READ_ENCRYPTED_MITM
        }
        val status = when {
            insecure -> null
            authenticationRequired -> when (peripheralSpec.requestAuthentication()) {
                Authentication.Granted -> null
                Authentication.Denied -> OperationStatus.InsufficientAuthentication
                // The system started bonding and dropped the request. There is no response.
                Authentication.Dropped -> return
            }
            else -> OperationStatus.ReadNotPermitted
        }
        status?.let { status ->
            // The response is delivered in the next connection interval.
            peripheralSpec.emulateDelay()
            emit(CharacteristicRead(
                characteristic = this@MockRemoteCharacteristic,
                value = byteArrayOf(),
                status = status,
            ))
            return
        }

        // Handle read request. Some characteristics use Peripheral Spec data directly.
        val result = when (service.uuid) {
            Service.GENERIC_ACCESS_UUID -> when (characteristic.uuid) {
                Characteristic.DEVICE_NAME -> ReadResponse.Success(peripheralSpec.name!!.encodeToByteArray())
                Characteristic.APPEARANCE -> ReadResponse.Success(peripheralSpec.appearance!!.toByteArray())
                Characteristic.PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS -> {
                    val preferredConnectionInterval = peripheralSpec.preferredConnectionInterval!!
                    val minInterval = preferredConnectionInterval.first
                    val maxInterval = preferredConnectionInterval.last
                    val slaveLatency = peripheralSpec.preferredSlaveLatency!!
                    val supervisionTimeout = peripheralSpec.preferredSupervisionTimeout!!
                    ReadResponse.Success(
                        minInterval.toByteArray() +
                        maxInterval.toByteArray() +
                        slaveLatency.toByteArray() +
                        supervisionTimeout.toByteArray()
                    )
                }
                else -> eventHandler.onReadRequest(this@MockRemoteCharacteristic)
            }
            else -> eventHandler.onReadRequest(this@MockRemoteCharacteristic)
        }
        when (result) {
            is ReadResponse.Success -> {
                // Bluetooth Core Specification 6.2, Vol 3 (Host), Part F (ATT), 3.2.9. Long attribute values:
                // "The maximum length of an attribute value shall be 512 octets."
                val truncatedData = result.value.take(512).toByteArray()
                // Reading descriptor value takes time depending on the size of the value
                // and connection parameters.
                peripheralSpec.emulateTransferDuration(truncatedData, isWrite = false)
                emit(CharacteristicRead(
                    characteristic = this@MockRemoteCharacteristic,
                    value = truncatedData,
                    status = OperationStatus.Success,
                ))
            }
            is ReadResponse.Failure -> {
                // The read response is delivered in the next connection interval.
                peripheralSpec.emulateDelay()
                emit(CharacteristicRead(
                    characteristic = this@MockRemoteCharacteristic,
                    value = byteArrayOf(),
                    status = result.status,
                ))
            }
        }
    }

    override suspend fun FlowCollector<GattEvent>.executeWrite(data: ByteArray, writeType: WriteType) {
        val eventHandler = checkNotNull(peripheralSpec.eventHandler)
        check(peripheralSpec.isConnected) { "Peripheral not connected" }

        when (writeType == WriteType.WITH_RESPONSE) {
            true -> {
                // Ensure that the services are valid.
                check(peripheralSpec.isServiceCacheValid) {
                    // The response is delivered in the next connection interval.
                    peripheralSpec.emulateDelay()
                    emit(CharacteristicWrite(
                        characteristic = this@MockRemoteCharacteristic,
                        // TODO Verify if this is the correct status to use.
                        status = OperationStatus.InvalidHandle,
                    ))
                    return
                }

                // Check write permissions.
                val insecure = Permission.WRITE in characteristic.permissions
                val authenticationRequired = characteristic.permissions.any {
                    it == Permission.WRITE_ENCRYPTED ||
                    it == Permission.WRITE_ENCRYPTED_MITM
                }
                val status = when {
                    insecure -> null
                    authenticationRequired -> when (peripheralSpec.requestAuthentication()) {
                        Authentication.Granted -> null
                        Authentication.Denied -> OperationStatus.InsufficientAuthentication
                        // The system started bonding and dropped the request. There is no response.
                        Authentication.Dropped -> return
                    }
                    else -> OperationStatus.WriteNotPermitted
                }
                status?.let { status ->
                    // The response is delivered in the next connection interval.
                    peripheralSpec.emulateDelay()
                    emit(CharacteristicWrite(
                        characteristic = this@MockRemoteCharacteristic,
                        status = status,
                    ))
                    return
                }

                // Bluetooth Core Specification 6.2, Vol 3 (Host), Part F (ATT), 3.2.9. Long attribute values:
                // "The maximum length of an attribute value shall be 512 octets."
                val truncatedData = data.take(512).toByteArray()

                val mtu = checkNotNull(peripheralSpec.mtu)
                // Reliable Write is enabled manually by the user.
                val useReliableWrite = isReliableWriteEnabled
                // Long Write is used automatically when the data size exceeds (MTU - 3) bytes.
                val useLongWrite = truncatedData.size > (mtu - 3)

                // Notify the event handler about the write request.
                // Note, that this emulates a Write Request (with response), or a Long Write Request,
                // which uses number of Prepare Write Requests followed by an Execute Write Request,
                // but this is only emulated by calculating longer transfer time.
                // Notifying the event handler is done before the simulated delay,
                // as we need to know whether the write operation was successful or not.
                // We assume, that possible error was sent after first Prepare Write Request,
                // so only one connection interval delay is added below in case of failure.
                when (useLongWrite || useReliableWrite) {
                    true -> {
                        val response = eventHandler.onPrepareWriteRequest(
                            this@MockRemoteCharacteristic,
                            truncatedData
                        )
                        when (response) {
                            is PrepareWriteResponse.Success -> {
                                // Writing characteristic value takes time depending on the size of the value
                                // and connection parameters.
                                peripheralSpec.emulateTransferDuration(data, isWrite = true, withResponse = true)
                                // Validate received data. In case of an incorrect data, throw an exception.
                                val match = truncatedData.contentEquals(response.value)
                                // When not in Reliable Write, Long Write automatically executes or
                                // aborts all prepared writes.
                                var status: OperationStatus = OperationStatus.Success
                                if (!useReliableWrite) {
                                    when (val response = eventHandler.onExecuteWriteRequest(match)) {
                                        is WriteResponse.Success -> { /* no-op */ }
                                        is WriteResponse.Failure -> status = response.status
                                    }
                                    peripheralSpec.emulateDelay()
                                }
                                if (!match) {
                                    throw ValueDoesNotMatchException()
                                }
                                emit(CharacteristicWrite(
                                    characteristic = this@MockRemoteCharacteristic,
                                    status = status,
                                ))
                            }

                            is PrepareWriteResponse.Failure -> {
                                // The read response is delivered in the next connection interval.
                                peripheralSpec.emulateDelay()
                                emit(CharacteristicWrite(
                                    characteristic = this@MockRemoteCharacteristic,
                                    status = response.status,
                                ))
                            }
                        }
                    }

                    false -> {
                        val result = eventHandler.onWriteRequest(
                            this@MockRemoteCharacteristic,
                            truncatedData
                        )
                        when (result) {
                            is WriteResponse.Success -> {
                                // Writing characteristic value takes time depending on the size of the value
                                // and connection parameters.
                                peripheralSpec.emulateTransferDuration(data, isWrite = true, withResponse = true)
                                emit(CharacteristicWrite(
                                    characteristic = this@MockRemoteCharacteristic,
                                    status = OperationStatus.Success,
                                ))
                            }

                            is WriteResponse.Failure -> {
                                // The read response is delivered in the next connection interval.
                                peripheralSpec.emulateDelay()
                                emit(CharacteristicWrite(
                                    characteristic = this@MockRemoteCharacteristic,
                                    status = result.status,
                                ))
                            }
                        }
                    }
                }
            }
            // There is no response for Write Without Response or Signed Write.
            false -> {
                // Note: There is no check for service cache validity or write permissions,
                //       as the operation does not expect a response.
                //       It will always succeed.

                val mtu = checkNotNull(peripheralSpec.mtu)
                // Truncate the data to MTU - 3 bytes, or MTU - 12 bytes, depending on the write type.
                val extra = if (writeType == WriteType.SIGNED) 12 else 3
                val truncatedData = data.take(mtu - extra).toByteArray()
                // Notify the event handler about the write command.
                // There is no response for this operation.
                eventHandler.onWriteCommand(
                    this@MockRemoteCharacteristic,
                    truncatedData
                )
                // Writing characteristic value takes time depending on the size of the value.
                // Multiple packets may be sent in one connection interval.
                peripheralSpec.emulateTransferDuration(truncatedData, isWrite = true, withResponse = false)

                // The flow is suspended and awaits for CharacteristicWrite event.
                // Even though there is no response for this operation, we need to
                // release it by emitting the event.
                emit(CharacteristicWrite(
                    characteristic = this@MockRemoteCharacteristic,
                    status = OperationStatus.Success,
                ))
            }
        }
    }

    override fun OperationEvent.matches(): Boolean = when (subject) {
        // When handling read or write events, the subject is the characteristic itself.
        is MockRemoteCharacteristic -> subject == this@MockRemoteCharacteristic
        // When simulating notifications, the subject is the handle number of the characteristic.
        is Int -> subject == this@MockRemoteCharacteristic.instanceId
        // This should be never reached.
        else -> error("Unknown subject type: ${subject::class}")
    }

    private fun Int.toByteArray(): ByteArray = ByteArray(2)
        .also {
            it[0] = (this and 0xFF).toByte()
            it[1] = ((this shr 8) and 0xFF).toByte()
        }
}