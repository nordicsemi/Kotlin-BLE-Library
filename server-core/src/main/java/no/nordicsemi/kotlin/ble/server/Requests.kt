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

package no.nordicsemi.kotlin.ble.server

import no.nordicsemi.kotlin.ble.core.OperationStatus

/**
 * A request from a client to read the value of a characteristic or a descriptor.
 *
 * Long reads are handled by the library: the handler provides the whole value.
 *
 * @property client The client that sent the request.
 */
class ReadRequest(val client: Client<*>)

/**
 * A request from a client to write the value of a characteristic or a descriptor.
 *
 * Long write and reliable write operations are handled by the library: the whole value is delivered
 * once, after the write operation has been executed.
 *
 * @property client The client that sent the request.
 * @property value The written value.
 * @property withResponse `true` if the client expects a response, `false` for a write command
 * (write without response). A response from the handler is ignored for commands.
 */
class WriteRequest(
    val client: Client<*>,
    val value: ByteArray,
    val withResponse: Boolean,
) {
    override fun toString(): String = "WriteRequest(client=$client, value=${value.size} bytes, withResponse=$withResponse)"
}

/**
 * A response to a [ReadRequest].
 */
sealed class ReadResponse {

    /**
     * The read succeeded.
     *
     * @property value The whole value of the characteristic or descriptor.
     */
    class Success(val value: ByteArray): ReadResponse() {
        override fun equals(other: Any?) = other is Success && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
        override fun toString() = "Success(${value.size} bytes)"
    }

    /**
     * The read failed, and the client will receive the given status.
     *
     * @property status The status to be sent to the client. Should not be
     * [OperationStatus.Success].
     */
    data class Failure(val status: OperationStatus): ReadResponse()
}

/**
 * A response to a [WriteRequest].
 */
sealed class WriteResponse {

    /** The write operation succeeded. The value will be stored and the request emitted in `writes`. */
    data object Success: WriteResponse()

    /**
     * The write request was rejected, and the client will receive the given status. The value is not stored.
     *
     * @property status The status to be sent to the client. Should not be
     * [OperationStatus.Success].
     */
    data class Failure(val status: OperationStatus): WriteResponse()
}
