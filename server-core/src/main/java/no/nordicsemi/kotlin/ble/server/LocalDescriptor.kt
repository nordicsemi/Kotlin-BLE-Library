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

@file:Suppress("unused")

package no.nordicsemi.kotlin.ble.server

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.Peer

/**
 * A descriptor hosted by the local GATT server.
 *
 * It behaves like [LocalCharacteristic]: reads return [value], and writes are stored and emitted
 * in [writes], unless handlers are set. Descriptors are always written with response.
 *
 * The Client Characteristic Configuration descriptor is handled by the library. Setting
 * a handler on it throws [IllegalStateException].
 *
 */
interface LocalDescriptor: Descriptor {
    override val characteristic: LocalCharacteristic

    /** Local attributes are not owned by a remote peer, so this is always `null`. */
    override val owner: Peer<*>?
        get() = null

    /** The current value of the descriptor. Empty initially, unless defined by the service definition. */
    val value: StateFlow<ByteArray>

    /** Writes accepted by the descriptor. Rejected writes are not emitted. */
    val writes: SharedFlow<WriteRequest>

    /** Sets a handler for read requests, or removes it when `null`. See [LocalCharacteristic.onRead]. */
    fun onRead(handler: (suspend ReadRequest.() -> ReadResponse)?)

    /**
     * Sets a provider of the value returned for each read request, or removes it when `null`.
     *
     * It is a shorthand for [onRead] which always succeeds.
     */
    fun provideValue(provider: (suspend ReadRequest.() -> ByteArray)?) =
        onRead(provider?.let { p -> { ReadResponse.Success(p()) } })

    /** Sets a handler for write requests, or removes it when `null`. See [LocalCharacteristic.onWrite]. */
    fun onWrite(handler: (suspend WriteRequest.() -> WriteResponse)?)

    /**
     * Sets the value of the descriptor.
     *
     * @throws IllegalArgumentException if the value is longer than 512 bytes.
     */
    fun setValue(value: ByteArray)
}
