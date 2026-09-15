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

package no.nordicsemi.kotlin.ble.client.mock

import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteDescriptor
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.exception.BluetoothException
import kotlin.uuid.Uuid

/**
 * The result of a connection request.
 *
 * A connection request may either succeed or be denied (ignored by the peripheral). It cannot fail
 * with an error. Issues may arise during an open connection which will cause the link to be terminated,
 * but the connection itself is successful.
 *
 * Read more about connection process on [DevAcademy](https://academy.nordicsemi.com/courses/bluetooth-low-energy-fundamentals/lessons/lesson-3-bluetooth-le-connections/topic/connection-process/).
 */
sealed class ConnectionResult {
    /**
     * The connection request succeeded.
     *
     * This is the default behavior.
     */
    object Accept : ConnectionResult()
    /**
     * The connection request was denied.
     *
     * This may mock a situation where the peripheral is not accepting connections
     * or the peripheral's ID is not in the accept list.
     */
    object Deny : ConnectionResult()

    // TODO Android sometimes returns error on connection request. Consider adding it here?
}

/**
 * The result of a service discovery request.
 */
sealed class ServiceDiscoveryResult {
    /**
     * The service discovery operation succeeded.
     *
     * The client will receive a list of requested services (which may be empty if no matching
     * services were found).
     */
    object Success : ServiceDiscoveryResult()
    /**
     * The service discovery operation failed.
     *
     * Reporting [Failure] will return an empty service list to the client.
     *
     * Note: This library does not provide a way to report an exact error during service discovery.
     * Instead, it simply reports that no services were found.
     */
    object Failure : ServiceDiscoveryResult()
}

/**
 * The result of a data read operation.
 */
sealed class ReadResponse {
    /**
     * The read operation succeeded.
     *
     * @param value The value read from the characteristic or descriptor.
     */
    data class Success(val value: ByteArray) : ReadResponse() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Success

            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int {
            return value.contentHashCode()
        }
    }

    /**
     * The read operation failed.
     *
     * @param status The status of the failed operation.
     */
    data class Failure(val status: OperationStatus) : ReadResponse() {
        override fun toString(): String = status.toString()
    }
}

/**
 * The result of a Write Request operation.
 */
sealed class WriteResponse {
    /**
     * The write operation succeeded.
     */
    object Success : WriteResponse()
    /**
     * The write operation failed.
     *
     * @param status The status of the failed operation.
     */
    data class Failure(val status: OperationStatus) : WriteResponse() {
        override fun toString(): String = status.toString()
    }
}

/**
 * The response of a Prepare Write request.
 *
 * Prepare Write is used in *Long Write* procedure and in *Reliable Write* procedure. A response
 * to this request replies back the data that was sent in the request to confirm that it was received
 * correctly. Returning different data indicates a transmission error, and the client will cancel
 * the write operation using Execute Write request with `execute` flag set to `false`.
 *
 * On contrary to how Bluetooth LE works, this mock implementation sends all prepared writes
 * in a single request. This is done to simplify the implementation and avoid the need for
 * queuing multiple requests.
 */
sealed class PrepareWriteResponse {
    /**
     * The Prepare Write operation succeeded.
     *
     * @param value Confirmation of the value to written to the characteristic. This should be
     * the same as the value sent in the [PeripheralSpecEventHandler.onPrepareWriteRequest] if
     * the value was received correctly, or different to indicate a transmission error.
     */
    data class Success(val value: ByteArray) : PrepareWriteResponse() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Success

            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int {
            return value.contentHashCode()
        }
    }
    /**
     * The Prepare Write operation failed.
     *
     * This response indicates an error on the peripheral side.
     *
     * @param status The status of the failed operation.
     */
    data class Failure(val status: OperationStatus) : PrepareWriteResponse() {
        override fun toString(): String = status.toString()
    }
}

/**
 * This is the interface of a peripheral event handler.
 *
 * The implementation should respond to various events occurring on the mock peripheral,
 * such as connection requests, read/write requests, etc.
 *
 * For example, a mock Blinky device can handle requests to turn on an LED or send notifications
 * when a button was clicked.
 */
interface PeripheralSpecEventHandler {

    /**
     * Called when a connection request is made to the mock peripheral.
     *
     * By default, the connection request is accepted.
     *
     * @param phy The PHY used for connection.
     * @return The result of the connection request, by default [ConnectionResult.Accept].
     */
    // Note: This cannot be suspended! It can request MTU, bonding, PHY update, etc., but in a coroutine.
    fun onConnectionRequest(phy: PrimaryPhy): ConnectionResult {
        return ConnectionResult.Accept
    }

    /**
     * Called when the connection is lost.
     *
     * @param reason The reason for the disconnection.
     */
    fun onConnectionLost(reason: DisconnectionReason) {
        // no-op
    }

    /**
     * Called when the peripheral is reset.
     *
     * This method is called when user calls [PeripheralSpec.simulateReset].
     *
     * All existing connections will time out. [onConnectionLost] will NOT be called.
     */
    fun onReset() {
        // no-op
    }

    /**
     * Called when a service discovery request is made to the mock peripheral.
     *
     * Note, that this method does not return the list of services. Instead, it only indicates
     * whether the discovery should succeed or fail. The actual list of services is determined
     * by the [PeripheralSpec] definition.
     *
     * @param uuids The list of service UUIDs requested by the client.
     * @return The result of the service discovery request, by default [ServiceDiscoveryResult.Success].
     */
    fun onServiceDiscoveryRequest(uuids: List<Uuid>): ServiceDiscoveryResult {
        return ServiceDiscoveryResult.Success
    }

    /**
     * A callback called when the client sends Read request to a characteristic.
     *
     * This method should return the value to be sent to the client. The value will be truncated
     * to **512 bytes**, which is the maximum length of a GATT attribute value.
     *
     * The returned response will be delayed by one or more connection interval to emulate the time
     * needed to send the response back to the client.
     *
     * Note, that this callback is invoked for both *Read Characteristic Value* procedure and
     * *Read Long Characteristic Value* procedure. Instead of returning each blob separately,
     * the implementation should return the full value.
     *
     * See Bluetooth Code Specification 6.2, Vol 3 (Host), Part G (GATT), 4.8.1 Read Characteristic Value:
     * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/generic-attribute-profile--gatt-.html#UUID-90b3e212-b224-6a3f-8f2e-e6fb63641e31).
     *
     * ### Exceptions
     *
     * Exceptions thrown by this method are rethrown immediately, without the simulated transfer time,
     * emulating a failure on the client side (e.g. [OperationFailedException], or [SecurityException]).
     *
     * Simulated error on the peripheral side should be reported by returning [ReadResponse.Failure],
     * which is delayed by one connection interval to emulate the time needed to send the error response.
     *
     * Any exception other than [OperationFailedException] will be wrapped into [BluetoothException].
     *
     * @param characteristic The characteristic that was read.
     * @return The response of the read operation. This emulates a response received from the
     * peripheral, and it will be delayed to the client by one or more connection interval.
     * @throws OperationFailedException in case of a client error (reported without a delay).
     */
    fun onReadRequest(characteristic: MockRemoteCharacteristic): ReadResponse {
        return ReadResponse.Success(byteArrayOf())
    }

    /**
     * A callback called when the client sends a Write Command (write without response) to a characteristic.
     *
     * The maximum length of the [value] is guaranteed to be at most [PeripheralSpec.mtu]` - 3`
     * bytes. 1 byte is consumed by the OpCode and 2 by the handle number. In case of a *Signed Write
     * Without Response* the maximum length is further reduced by 12 bytes, which are used for the
     * signature. This mock implementation ignores the signature and assumes it to be always valid.
     *
     * After this method returns, an artificial delay is added to emulate the time needed to send
     * the data to the peripheral. The length of the delay depends on the length of the value,
     * active PHY and L2CAP MTU.
     *
     * Read more in Bluetooth Code Specification 6.2, Vol 3 (Host), Part G (GATT), 4.9.1 Write Without Response:
     * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/generic-attribute-profile--gatt-.html#UUID-9f1c2e38-8fbe-f60c-d885-076707c88a43).
     *
     * ### Exceptions
     *
     * Exceptions thrown by this method are rethrown immediately, without the simulated transfer time,
     * emulating a failure on the client side (e.g. [OperationFailedException], or [SecurityException]).
     *
     * Any exception other than [OperationFailedException] will be wrapped into [BluetoothException].
     *
     * @param characteristic The characteristic that was written.
     * @param value The value written to the characteristic.
     * @throws OperationFailedException in case of a client error (reported without a delay).
     */
    fun onWriteCommand(characteristic: MockRemoteCharacteristic, value: ByteArray) {
        // no-op
    }

    /**
     * A callback called when the client sends a Write Request (write with response) to a characteristic.
     *
     * The maximum length of the [value] is guaranteed to be at most [PeripheralSpec.mtu]` - 3`
     * byte. 1 byte is consumed by the OpCode and 2 by the handle number.
     *
     * For Long Write procedure and Reliable Write procedure, see [onPrepareWriteRequest], which
     * replies back the written value.
     *
     * The response returned by this method will be delayed by one connection interval to emulate
     * the time needed to send the response back to the client.
     *
     * Read more in Bluetooth Code Specification 6.2, Vol 3 (Host), Part G (GATT), 4.9.3 Write Characteristic Value:
     * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/generic-attribute-profile--gatt-.html#UUID-ba4b856a-6994-01e4-97f6-357f9be40990).
     *
     * ### Exceptions
     *
     * Exceptions thrown by this method are rethrown immediately, without the simulated transfer time,
     * emulating a failure on the client side (e.g. [OperationFailedException], or [SecurityException]).
     *
     * Simulated error on the peripheral side should be reported by returning [WriteResponse.Failure],
     * which is delayed by one connection interval to emulate the time needed to send the error response.
     *
     * Any exception other than [OperationFailedException] will be wrapped into [BluetoothException].
     *
     * @param characteristic The characteristic to write to.
     * @param value The value written to the characteristic.
     * @return The response of the write operation. This emulates a response received from the peripheral,
     * and it will be delayed to the client by one connection interval.
     * @throws OperationFailedException in case of a client error (reported without a delay).
     * @see onPrepareWriteRequest
     */
    fun onWriteRequest(characteristic: MockRemoteCharacteristic, value: ByteArray): WriteResponse {
        return WriteResponse.Success
    }

    /**
     * Emulates a prepared write to a characteristic.
     *
     * The maximum length of the [value] is guaranteed to be at most 512 bytes.
     *
     * The response returned by this method will be delayed by one or more connection interval to
     * emulate the time needed to send the response back to the client.
     *
     * If not overridden, this method calls [onWriteRequest] and replies back the same data to
     * simplify implementations.
     *
     * ### Prepare Write
     *
     * Prepare Write request is used in *Long Write* procedure and in *Reliable Write* procedure.
     * On contrary to how Bluetooth LE works, this mock implementation sends all prepared writes
     * in a single request. This is done to simplify the implementation and avoid the need for
     * queuing multiple requests.
     *
     * The event handler should reply with the same data to confirm that it was received correctly,
     * or different data to indicate a transmission error, in which case the cancellation will happen
     * automatically.
     *
     * The client confirms or cancels the procedure by sending an Execute Write request.
     * All prepared writes should be queued until the Execute Write request is received.
     *
     * ### Long Write
     *
     * Android and iOS automatically use *Long Write* procedure when the value to be written
     * exceeds the maximum write size (MTU - 3 bytes). In this case, multiple Prepare Write requests
     * are sent, each containing a part of the value, followed by an Execute Write request to commit
     * the changes if the data returned in the responses matched the data sent in the requests,
     * or cancel the operation otherwise.
     *
     * This mock implementation simplifies the procedure by sending all parts in a single
     * Prepare Write request instead of multiple requests.
     *
     * Read more in Bluetooth Code Specification 6.2, Vol 3 (Host), Part G (GATT), 4.9.4 Write Long Characteristic Value:
     * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/generic-attribute-profile--gatt-.html#UUID-6ab738ad-6d26-1da1-7417-e83da50e90c5).
     *
     * ### Reliable Write
     *
     * *Reliable Write* procedure is used to ensure that the data is transmitted correctly
     * to the peripheral before committing the changes. The client sends multiple Prepare Write requests,
     * each containing a part of the value, and the peripheral responds with the same data to
     * confirm that it was received correctly. If any part of the data is incorrect, the
     * operation will be canceled automatically using Execute Write request with `execute` flag
     * set to `false`.
     *
     * This mock implementation simplifies the procedure by sending all parts in a single
     * Prepare Write request instead of multiple requests.
     *
     * A single *Reliable Write* procedure may be applied to multiple characteristics or descriptors.
     *
     * Read more in Bluetooth Code Specification 6.2, Vol 3 (Host), Part G (GATT), 4.9.5 Characteristic Value Reliable Writes:
     * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/generic-attribute-profile--gatt-.html#UUID-7fc3dafa-7199-3f9c-a137-1575597b2a8c).
     *
     * ### Exceptions
     *
     * Exceptions thrown by this method are rethrown immediately, without the simulated transfer time,
     * emulating a failure on the client side (e.g. [OperationFailedException], or [SecurityException]).
     *
     * Simulated error on the peripheral side should be reported by returning [PrepareWriteResponse.Failure],
     * which is delayed by one connection interval to emulate the time needed to send the error response.
     *
     * Any exception other than [OperationFailedException] will be wrapped into [BluetoothException].
     *
     * @param characteristic The characteristic to write to.
     * @param value The part of the value to be written to the characteristic.
     * @return The response of the prepare write operation. This emulates a response received from
     * the peripheral, and it will be delayed to the client by one or more connection interval.
     * @throws OperationFailedException in case of a client error (reported without a delay).
     * @see onExecuteWriteRequest
     */
    fun onPrepareWriteRequest(characteristic: MockRemoteCharacteristic, value: ByteArray): PrepareWriteResponse =
        // By default, call the regular write request handler to simplify implementations.
        when (val response = onWriteRequest(characteristic, value)) {
            is WriteResponse.Success -> PrepareWriteResponse.Success(value)
            is WriteResponse.Failure -> PrepareWriteResponse.Failure(response.status)
        }

    /**
     * A callback called when the client sends Read request to a characteristic descriptor.
     *
     * This method should return the value to be sent to the client. The value will be truncated
     * to **512 bytes**, which is the maximum length of a GATT attribute value.
     *
     * The returned response will be delayed by one or more connection interval to emulate the time
     * needed to send the response back to the client.
     *
     * Note, that this callback is invoked for both *Read Characteristic Descriptor* procedure and
     * *Read Long Characteristic Descriptor* procedure. Instead of returning each blob separately,
     * the implementation should return the full value.
     *
     * See Bluetooth Code Specification 6.2, Vol 3 (Host), Part G (GATT), 4.12.1 Read Characteristic Descriptor:
     * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/generic-attribute-profile--gatt-.html#UUID-5a1a9293-614a-f4ed-7771-fd8b4143d076).
     *
     * ### Exceptions
     *
     * Exceptions thrown by this method are rethrown immediately, without the simulated transfer time,
     * emulating a failure on the client side (e.g. [OperationFailedException], or [SecurityException]).
     *
     * Simulated error on the peripheral side should be reported by returning [ReadResponse.Failure],
     * which is delayed by one connection interval to emulate the time needed to send the error response.
     *
     * Any exception other than [OperationFailedException] will be wrapped into [BluetoothException].
     *
     * @param descriptor The characteristic descriptor that was read.
     * @return The response of the read operation. This emulates a response received from the
     * peripheral, and it will be delayed to the client by one or more connection interval.
     * @throws OperationFailedException in case of a client error (reported without a delay).
     */
    fun onReadRequest(descriptor: MockRemoteDescriptor): ReadResponse {
        return ReadResponse.Success(byteArrayOf())
    }

    /**
     * A callback called when the client sends a Write Request (write with response) to a characteristic
     * descriptor.
     *
     * The maximum length of the [value] is guaranteed to be at most [PeripheralSpec.mtu]` - 3`
     * byte. 1 byte is consumed by the OpCode and 2 by the handle number.
     *
     * For Long Write procedure and Reliable Write procedure, see [onPrepareWriteRequest], which
     * replies back the written value.
     *
     * The response returned by this method will be delayed by one connection interval to emulate
     * the time needed to send the response back to the client.
     *
     * Read more in Bluetooth Code Specification 6.2, Vol 3 (Host), Part G (GATT), 4.9.3 Write Characteristic Value:
     * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/generic-attribute-profile--gatt-.html#UUID-ba4b856a-6994-01e4-97f6-357f9be40990).
     *
     * ### Exceptions
     *
     * Exceptions thrown by this method are rethrown immediately, without the simulated transfer time,
     * emulating a failure on the client side (e.g. [OperationFailedException], or [SecurityException]).
     *
     * Simulated error on the peripheral side should be reported by returning [WriteResponse.Failure],
     * which is delayed by one connection interval to emulate the time needed to send the error response.
     *
     * Any exception other than [OperationFailedException] will be wrapped into [BluetoothException].
     *
     * @param descriptor The characteristic descriptor to write to.
     * @param value The value written to the characteristic descriptor.
     * @return The response of the write operation. This emulates a response received from the peripheral,
     * and it will be delayed to the client by one connection interval.
     * @throws OperationFailedException in case of a client error (reported without a delay).
     */
    fun onWriteRequest(descriptor: MockRemoteDescriptor, value: ByteArray): WriteResponse {
        return WriteResponse.Success
    }

    /**
     * Emulates a prepared write to a characteristic descriptor.
     *
     * The maximum length of the [value] is guaranteed to be at most 512 bytes.
     *
     * The response returned by this method will be delayed by one or more connection interval to
     * emulate the time needed to send the response back to the client.
     *
     * If not overridden, this method calls [onWriteRequest] and replies back the same data to
     * simplify implementations.
     *
     * ### Prepare Write
     *
     * Prepare Write request is used in *Long Write* procedure and in *Reliable Write* procedure.
     * On contrary to how Bluetooth LE works, this mock implementation sends all prepared writes
     * in a single request. This is done to simplify the implementation and avoid the need for
     * queuing multiple requests.
     *
     * The event handler should reply with the same data to confirm that it was received correctly,
     * or different data to indicate a transmission error, in which case the cancellation will happen
     * automatically.
     *
     * The client confirms or cancels the procedure by sending an Execute Write request.
     * All prepared writes should be queued until the Execute Write request is received.
     *
     * ### Long Write
     *
     * Android and iOS automatically use *Long Write* procedure when the value to be written
     * exceeds the maximum write size (MTU - 3 bytes). In this case, multiple Prepare Write requests
     * are sent, each containing a part of the value, followed by an Execute Write request to commit
     * the changes if the data returned in the responses matched the data sent in the requests,
     * or cancel the operation otherwise.
     *
     * This mock implementation simplifies the procedure by sending all parts in a single
     * Prepare Write request instead of multiple requests.
     *
     * Read more in Bluetooth Code Specification 6.2, Vol 3 (Host), Part G (GATT), 4.12.2 Write Long Characteristic Descriptor:
     * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/generic-attribute-profile--gatt-.html#UUID-84794b9f-7220-7665-0f14-b7a462da94b7.
     *
     * ### Exceptions
     *
     * Exceptions thrown by this method are rethrown immediately, without the simulated transfer time,
     * emulating a failure on the client side (e.g. [OperationFailedException], or [SecurityException]).
     *
     * Simulated error on the peripheral side should be reported by returning [PrepareWriteResponse.Failure],
     * which is delayed by one connection interval to emulate the time needed to send the error response.
     *
     * Any exception other than [OperationFailedException] will be wrapped into [BluetoothException].
     *
     * @param descriptor The characteristic descriptor to write to.
     * @param value The part of the value to be written to the characteristic descriptor.
     * @return The response of the prepare write operation. This emulates a response received from
     * the peripheral, and it will be delayed to the client by one or more connection interval.
     * @throws OperationFailedException in case of a client error (reported without a delay).
     * @see onExecuteWriteRequest
     */
    fun onPrepareWriteRequest(descriptor: MockRemoteDescriptor, value: ByteArray): PrepareWriteResponse =
        // By default, call the regular write request handler to simplify implementations.
        when (val response = onWriteRequest(descriptor, value)) {
            is WriteResponse.Success -> PrepareWriteResponse.Success(value)
            is WriteResponse.Failure -> PrepareWriteResponse.Failure(response.status)
        }

    /**
     * Emulates an Execute Write request to the characteristic.
     *
     * This method after at least one [onPrepareWriteRequest] call. When `execute` is `true`,
     * all previously prepared writes should be committed and applied atomically in the order they
     * were received. When `execute` is `false`, all previously prepared writes should be discarded.
     *
     * @param execute `true` to commit all previously prepared writes, `false` to discard them.
     * @return The response of the execute write operation. This emulates a response received from
     * the peripheral, and it will be delayed to the client by one connection interval.
     * @throws OperationFailedException in case of a client error (reported without a delay).
     * @see onPrepareWriteRequest
     */
    fun onExecuteWriteRequest(execute: Boolean): WriteResponse {
        return WriteResponse.Success
    }

}