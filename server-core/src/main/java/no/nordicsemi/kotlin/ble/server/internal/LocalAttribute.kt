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

package no.nordicsemi.kotlin.ble.server.internal

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.server.Client
import no.nordicsemi.kotlin.ble.server.ReadRequest
import no.nordicsemi.kotlin.ble.server.ReadResponse
import no.nordicsemi.kotlin.ble.server.WriteRequest
import no.nordicsemi.kotlin.ble.server.WriteResponse
import kotlin.concurrent.Volatile

/**
 * The maximum length of an attribute value, in bytes, as defined by the Bluetooth Core
 * Specification.
 */
internal const val MAX_ATTRIBUTE_LENGTH = 512

/**
 * The services of the manager, as seen by the local attributes.
 *
 * It lets the attributes use the platform layer without a reference to the whole manager.
 */
internal interface LocalServerHost {

    /** Logs a message in the GATT layer, if the logger is set. */
    fun trace(message: () -> String)

    /** Sends a notification or indication, see [GattServer.notify]. */
    suspend fun notify(client: Client<*>, characteristic: Int, value: ByteArray, confirm: Boolean)
}

/**
 * An attribute (a characteristic or a descriptor) which can be a target of a read or write request.
 */
internal interface LocalAttribute {

    /** The `instanceId` of the attribute, unique within the GATT server. */
    val instanceId: Int

    /** The value, handlers and write requests of the attribute. */
    val state: AttributeState
}

/**
 * The value, the handlers and the accepted write requests of a local characteristic or descriptor.
 *
 * @param initialValue The initial value.
 */
internal class AttributeState(initialValue: ByteArray = byteArrayOf()) {
    private val _value = MutableStateFlow(initialValue)
    private val _writes = MutableSharedFlow<WriteRequest>(extraBufferCapacity = 64)

    @Volatile
    private var readHandler: (suspend ReadRequest.() -> ReadResponse)? = null

    @Volatile
    private var writeHandler: (suspend WriteRequest.() -> WriteResponse)? = null

    /** The current value. */
    val value: StateFlow<ByteArray> = _value

    /** The accepted write requests. */
    val writes: SharedFlow<WriteRequest> = _writes

    /** Sets the read handler, or removes it when `null`. */
    fun onRead(handler: (suspend ReadRequest.() -> ReadResponse)?) {
        readHandler = handler
    }

    /** Sets the write handler, or removes it when `null`. */
    fun onWrite(handler: (suspend WriteRequest.() -> WriteResponse)?) {
        writeHandler = handler
    }

    /**
     * Sets the value without notifying anyone.
     *
     * @throws IllegalArgumentException if the value is longer than [MAX_ATTRIBUTE_LENGTH].
     */
    fun setValue(value: ByteArray) {
        require(value.size <= MAX_ATTRIBUTE_LENGTH) {
            "Value too long: ${value.size} bytes, maximum is $MAX_ATTRIBUTE_LENGTH"
        }
        _value.value = value.copyOf()
    }

    /** Returns the response to a read request: from the handler, if set, or the current value. */
    suspend fun read(request: ReadRequest): ReadResponse {
        val handler = readHandler
        return if (handler != null) {
            handler(request)
        } else {
            ReadResponse.Success(_value.value)
        }
    }

    /**
     * Handles a write request: asks the handler, if set, and if the write is accepted, stores
     * the value and emits the request in [writes].
     *
     * The emit suspends when a collector of [writes] is slow and the buffer is full.
     */
    suspend fun write(request: WriteRequest): WriteResponse {
        val handler = writeHandler
        val response = if (handler != null) handler(request) else WriteResponse.Success
        if (response is WriteResponse.Success) {
            _value.value = request.value
            _writes.emit(request)
        }
        return response
    }
}
