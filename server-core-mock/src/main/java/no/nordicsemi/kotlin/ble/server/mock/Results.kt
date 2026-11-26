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

import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.exception.GattException

/**
 * Thrown by [ReadResult.getOrThrow] and [WriteResult.getOrThrow] when the GATT server rejected
 * the request.
 *
 * @property status The status received from the server.
 */
class GattOperationException(val status: OperationStatus): GattException("GATT operation failed: $status")

/**
 * The result of a read request sent by a simulated client.
 */
sealed class ReadResult {

    /**
     * The read succeeded.
     *
     * @property value The value received from the server. A single read request returns at most
     * `mtu - 1` bytes, see [MockClient.read] and [MockClient.readLong].
     */
    class Success(val value: ByteArray): ReadResult() {
        override fun equals(other: Any?) = other is Success && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
        override fun toString() = "Success(${value.size} bytes)"
    }

    /**
     * The server responded with an error.
     *
     * @property status The status received from the server.
     */
    data class Failure(val status: OperationStatus): ReadResult()

    /** `true` if the read succeeded. */
    val isSuccess: Boolean
        get() = this is Success

    /**
     * Returns the value, or throws [GattOperationException] if the read failed.
     */
    fun getOrThrow(): ByteArray = when (this) {
        is Success -> value
        is Failure -> throw GattOperationException(status)
    }

    /** Returns the value, or `null` if the read failed. */
    fun getOrNull(): ByteArray? = (this as? Success)?.value

    /** Returns the status of a failed read, or `null` if the read succeeded. */
    fun statusOrNull(): OperationStatus? = (this as? Failure)?.status
}

/**
 * The result of a write request sent by a simulated client.
 */
sealed class WriteResult {

    /**
     * The write operation succeeded.
     *
     * For write commands (without response) this is always returned.
     */
    data object Success: WriteResult()

    /**
     * The server responded with an error.
     *
     * @property status The status received from the server.
     */
    data class Failure(val status: OperationStatus): WriteResult()

    /** `true` if the write operation succeeded. */
    val isSuccess: Boolean
        get() = this is Success

    /** Throws [GattOperationException] if the write operation failed. */
    fun getOrThrow() {
        if (this is Failure) throw GattOperationException(status)
    }

    /** Returns the status of a failed write operation, or `null` if it succeeded. */
    fun statusOrNull(): OperationStatus? = (this as? Failure)?.status
}

/**
 * A notification or an indication received by a simulated client.
 *
 * @property attribute The `instanceId` of the characteristic.
 * @property value The value.
 * @property isIndication `true` for an indication (which was confirmed automatically),
 * `false` for a notification.
 */
class MockUpdate(
    val attribute: Int,
    val value: ByteArray,
    val isIndication: Boolean,
) {
    override fun toString() = "MockUpdate(attribute=$attribute, ${value.size} bytes, isIndication=$isIndication)"
}
