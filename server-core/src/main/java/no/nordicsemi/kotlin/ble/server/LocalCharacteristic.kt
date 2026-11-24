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
import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Peer

/**
 * A characteristic hosted by the local GATT server, which remote centrals can read, write to and
 * subscribe to.
 *
 * By default, read requests return [value], and write requests are stored and emitted in
 * [writes]. Use [onRead]
 * and [onWrite] to handle requests dynamically, for example to validate data or to return
 * a different value for each central.
 *
 */
interface LocalCharacteristic: Characteristic<LocalDescriptor> {
    override val service: AnyLocalService

    /** Local attributes are not owned by a remote peer, so this is always `null`. */
    override val owner: Peer<*>?
        get() = null

    /**
     * The current value of the characteristic, which is sent in response to read requests
     * unless a handler set with [onRead] provides one. Empty initially.
     *
     * It changes when a write request is accepted, and when [update] or [setValue] is called.
     */
    val value: StateFlow<ByteArray>

    /**
     * Centrals that have enabled notifications or indications of this characteristic.
     *
     * Always empty if the characteristic does not have the [CharacteristicProperty.NOTIFY] or
     * [CharacteristicProperty.INDICATE] property. The Client Characteristic Configuration
     * descriptor is handled by the library.
     */
    val subscribers: StateFlow<Set<Central<*>>>

    /**
     * Write requests accepted by the characteristic, that is requests for which the handler set with
     * [onWrite], or the default one, returned [WriteResponse.Success].
     *
     * It is a hot flow with no replay. Rejected write requests are not emitted.
     */
    val writes: SharedFlow<WriteRequest>

    /**
     * Sets a handler for read requests, or removes it when `null`.
     *
     * The handler must return the whole value of the characteristic. If the value is longer than
     * what fits in a single response, the library serves it using long read procedure.
     * Only one handler can be set; a new one replaces the previous.
     */
    fun onRead(handler: (suspend ReadRequest.() -> ReadResponse)?)

    /**
     * Sets a provider of the value returned for each read request, or removes it when `null`.
     *
     * The provider is called for every read request, so the value does not have to be set before
     * each read. It is a shorthand for [onRead] which always succeeds. Only one handler can be
     * set, so it replaces the one set with [onRead].
     *
     * ```kotlin
     * characteristic.provideValue { batteryLevel().toByteArray() }
     * ```
     */
    fun provideValue(provider: (suspend ReadRequest.() -> ByteArray)?) =
        onRead(provider?.let { p -> { ReadResponse.Success(p()) } })

    /**
     * Sets a handler for write requests, or removes it when `null`.
     *
     * The handler decides whether the write request is accepted. If it returns [WriteResponse.Success],
     * the value is stored and emitted in [writes]. Only one handler can be set; a new one
     * replaces the previous.
     */
    fun onWrite(handler: (suspend WriteRequest.() -> WriteResponse)?)

    /**
     * Sets the value of the characteristic without notifying anyone.
     *
     * @param value The new value. Maximum length is 512 bytes.
     * @throws IllegalArgumentException if the value is too long.
     */
    fun setValue(value: ByteArray)

    /**
     * Sets the value of the characteristic and sends a notification, or an indication, to
     * subscribed centrals.
     *
     * A notification is used if the characteristic has the [CharacteristicProperty.NOTIFY]
     * property, otherwise an indication. The method suspends until the value has been sent
     * to all targets, or in case of an indication, confirmed by them.
     *
     * @param value The new value.
     * @param to The central to notify, or `null` to notify all [subscribers].
     * @throws IllegalArgumentException if the value is longer than the maximum update value length
     * of any of the targets, see [Central.maximumUpdateValueLength].
     * @throws IllegalStateException if the characteristic does not support notifications or
     * indications, or [to] is not subscribed.
     */
    suspend fun update(value: ByteArray, to: Central<*>? = null)
}
