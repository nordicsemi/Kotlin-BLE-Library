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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.exception.ValueDoesNotMatchException
import no.nordicsemi.kotlin.ble.client.internal.BaseRemoteDescriptor
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicWrite
import no.nordicsemi.kotlin.ble.client.internal.DescriptorRead
import no.nordicsemi.kotlin.ble.client.internal.DescriptorWrite
import no.nordicsemi.kotlin.ble.client.internal.OperationEvent
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PrepareWriteResponse
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.internal.CCCD
import no.nordicsemi.kotlin.ble.core.internal.CEPD
import no.nordicsemi.kotlin.ble.core.internal.CUD
import no.nordicsemi.kotlin.ble.core.internal.DescriptorDefinition
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class MockRemoteDescriptor(
    parent: RemoteCharacteristic,
    private val peripheralSpec: PeripheralSpec<*>,
    private val descriptor: DescriptorDefinition,
    events: SharedFlow<GattEvent>,
): BaseRemoteDescriptor(parent, events) {
    override val uuid: Uuid = descriptor.uuid
    override val instanceId: Int = descriptor.instanceId

    override suspend fun FlowCollector<GattEvent>.executeRead() {
        val eventHandler = checkNotNull(peripheralSpec.eventHandler)
        val connectionInterval = checkNotNull(peripheralSpec.connectionParameters)
            .connectionIntervalMillis.milliseconds

        // Ensure that the services are valid.
        check(peripheralSpec.isServiceCacheValid) {
            // The read response is delivered in the next connection interval.
            delay(connectionInterval)
            emit(DescriptorRead(
                descriptor = this@MockRemoteDescriptor,
                value = byteArrayOf(),
                // TODO Verify if this is the correct status to use.
                status = OperationStatus.InvalidHandle,
            ))
            return
        }

        // Check read permissions.
        val insecure = Permission.READ in descriptor.permissions
        val authenticationRequired = descriptor.permissions.any {
            it == Permission.READ_ENCRYPTED ||
            it == Permission.READ_ENCRYPTED_MITM
        }
        val status = when {
            insecure ||
                    authenticationRequired && peripheralSpec.isBonded -> null
            authenticationRequired -> OperationStatus.InsufficientAuthentication
            else -> OperationStatus.ReadNotPermitted
        }
        status?.let { status ->
            // The response is delivered in the next connection interval.
            delay(connectionInterval)
            emit(DescriptorRead(
                descriptor = this@MockRemoteDescriptor,
                value = byteArrayOf(),
                status = status,
            ))
            return
        }

        val result = when (descriptor) {
            is CCCD -> ReadResponse.Success(descriptor.value)
            is CUD -> ReadResponse.Success(descriptor.value)
            is CEPD -> ReadResponse.Success(descriptor.value)
            else -> eventHandler.onReadRequest(this@MockRemoteDescriptor)
        }
        when (result) {
            is ReadResponse.Success -> {
                // Bluetooth Core Specification 6.2, Vol 3 (Host), Part F (ATT), 3.2.9. Long attribute values:
                // "The maximum length of an attribute value shall be 512 octets."
                val truncatedData = result.value.take(512).toByteArray()
                // Reading descriptor value takes time depending on the size of the value
                // and connection parameters.
                val duration =
                    peripheralSpec.estimateTransferDuration(truncatedData, isWrite = false)
                delay(duration)
                emit(DescriptorRead(
                        descriptor = this@MockRemoteDescriptor,
                        value = truncatedData,
                        status = OperationStatus.Success,
                    )
                )
            }
            is ReadResponse.Failure -> {
                // The read response is delivered in the next connection interval.
                delay(connectionInterval)
                emit(DescriptorRead(
                    descriptor = this@MockRemoteDescriptor,
                    value = byteArrayOf(),
                    status = result.status,
                ))
            }
        }
    }

    override suspend fun FlowCollector<GattEvent>.executeWrite(data: ByteArray) {
        val eventHandler = checkNotNull(peripheralSpec.eventHandler)
        val connectionInterval = checkNotNull(peripheralSpec.connectionParameters)
            .connectionIntervalMillis.milliseconds

        // Ensure that the services are valid.
        check(peripheralSpec.isServiceCacheValid) {
            // The write response is delivered in the next connection interval.
            delay(connectionInterval)
            emit(DescriptorWrite(
                descriptor = this@MockRemoteDescriptor,
                // TODO Verify if this is the correct status to use.
                status = OperationStatus.InvalidHandle,
            ))
            return
        }

        // Check write permissions.
        val insecure = Permission.WRITE in descriptor.permissions
        val authenticationRequired = descriptor.permissions.any {
            it == Permission.WRITE_ENCRYPTED ||
            it == Permission.WRITE_ENCRYPTED_MITM
        }
        val status = when {
            insecure ||
            authenticationRequired && peripheralSpec.isBonded -> null
            authenticationRequired -> OperationStatus.InsufficientAuthentication
            else -> OperationStatus.WriteNotPermitted
        }
        status?.let { status ->
            // The response is delivered in the next connection interval.
            delay(connectionInterval)
            emit(DescriptorWrite(
                descriptor = this@MockRemoteDescriptor,
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
                val result = eventHandler.onPrepareWriteRequest(this@MockRemoteDescriptor, truncatedData)
                when (result) {
                    is PrepareWriteResponse.Success -> {
                        // Writing characteristic value takes time depending on the size of the value
                        // and connection parameters.
                        val duration =
                            peripheralSpec.estimateTransferDuration(data, isWrite = true, withResponse = true)
                        delay(duration)
                        // Validate received data. In case of an incorrect data, throw an exception.
                        val match = truncatedData.contentEquals(result.value)
                        // When not in Reliable Write, Long Write automatically executes or
                        // aborts all prepared writes.
                        var result: WriteResponse = WriteResponse.Success
                        if (!useReliableWrite) {
                            result = eventHandler.onExecuteWriteRequest(match)
                            delay(connectionInterval)
                        }
                        if (!match) {
                            throw ValueDoesNotMatchException()
                        }
                        when (result) {
                            is WriteResponse.Success -> {
                                emit(CharacteristicWrite(
                                    characteristic = this@MockRemoteDescriptor,
                                    status = OperationStatus.Success,
                                ))
                            }
                            is WriteResponse.Failure -> {
                                emit(CharacteristicWrite(
                                    characteristic = this@MockRemoteDescriptor,
                                    status = result.status,
                                ))
                            }
                        }
                    }

                    is PrepareWriteResponse.Failure -> {
                        // The write response is delivered in the next connection interval.
                        delay(connectionInterval)
                        emit(CharacteristicWrite(
                            characteristic = this@MockRemoteDescriptor,
                            status = result.status,
                        ))
                    }
                }
            }
            false -> {
                val result = when (descriptor) {
                    is CCCD -> {
                        // Values 0x01-00 and 0x02-00 are used to enable notifications and indications, respectively.
                        // Value 0x00-00 is used to disable both.
                        // Any other value is RFU and ignored.
                        if (data.size == 2 && data[0] in 0..2 && data[1] == 0.toByte()) {
                            descriptor.enabled = data[0] > 0
                        }
                        WriteResponse.Success
                    }
                    else -> eventHandler.onWriteRequest(this@MockRemoteDescriptor, truncatedData)
                }
                when (result) {
                    is WriteResponse.Success -> {
                        // Writing descriptor value takes time depending on the size of the value
                        // and connection parameters.
                        val duration =
                            peripheralSpec.estimateTransferDuration(truncatedData, isWrite = true, withResponse = true)
                        delay(duration)
                        emit(DescriptorWrite(
                            descriptor = this@MockRemoteDescriptor,
                            status = OperationStatus.Success,
                        ))
                    }
                    is WriteResponse.Failure -> {
                        when (result.status) {
                            OperationStatus.Busy -> throw OperationFailedException(OperationStatus.Busy)
                            else -> { /* continue */ }
                        }
                        // The write response is delivered in the next connection interval.
                        delay(connectionInterval)
                        emit(DescriptorWrite(
                            descriptor = this@MockRemoteDescriptor,
                            status = result.status,
                        ))
                    }
                }
            }
        }
    }

    override fun OperationEvent.matches(): Boolean = this.subject == this@MockRemoteDescriptor

    /** The value of the CCCD in bytes. */
    val CCCD.value: ByteArray
        get() = when {
            enabled && CharacteristicProperty.NOTIFY in characteristic.properties -> ENABLE_NOTIFICATIONS_VALUE
            enabled && CharacteristicProperty.INDICATE in characteristic.properties -> ENABLE_INDICATIONS_VALUE
            else -> DISABLE_NOTIFICATIONS_VALUE
        }

    /** The value of the CUD in bytes. */
    val CUD.value: ByteArray
        get() = this.description.encodeToByteArray()

    /** The value of the CEPD in bytes. */
    val CEPD.value: ByteArray
        get() {
            var flags = 0
            if (reliableWrite) flags = flags or 0x0001
            if (writableAuxiliaries) flags = flags or 0x0002
            // Other bits are reserved and set to 0.
            return byteArrayOf(flags.toByte(), 0.toByte())
        }
}