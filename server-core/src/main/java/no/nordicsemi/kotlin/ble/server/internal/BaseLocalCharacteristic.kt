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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.internal.CharacteristicDefinition
import no.nordicsemi.kotlin.ble.server.AnyLocalService
import no.nordicsemi.kotlin.ble.server.Central
import no.nordicsemi.kotlin.ble.server.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.ReadRequest
import no.nordicsemi.kotlin.ble.server.ReadResponse
import no.nordicsemi.kotlin.ble.server.WriteRequest
import no.nordicsemi.kotlin.ble.server.WriteResponse
import kotlin.uuid.Uuid

/**
 * Base implementation of [LocalCharacteristic].
 *
 * The state of Client Characteristic Configuration descriptors of all centrals is kept here.
 * It is modified only by the event loop of the [LocalServerImpl].
 *
 * @param C The type of the central.
 * @param definition The definition of the characteristic.
 * @property service The service the characteristic belongs to.
 * @property host The services of the manager.
 */
internal class BaseLocalCharacteristic<C: Central<*>>(
    definition: CharacteristicDefinition,
    override val service: AnyLocalService,
    private val host: LocalServerHost<C>,
): LocalCharacteristic, LocalAttribute<C> {
    override val uuid: Uuid = definition.uuid
    override val instanceId: Int = definition.instanceId
    override val properties: Set<CharacteristicProperty> = definition.properties
    override val state = AttributeState()

    /** The descriptors, including the auto-added Client Characteristic Configuration. */
    val localDescriptors: List<BaseLocalDescriptor<C>> =
        definition.descriptors.map { BaseLocalDescriptor(it, this) }
    override val descriptors: List<BaseLocalDescriptor<C>> get() = localDescriptors

    /** The values of Client Characteristic Configuration descriptors: only non-zero ones. */
    private val cccdValues = MutableStateFlow<Map<C, Int>>(emptyMap())
    private val _subscribers = MutableStateFlow<Set<C>>(emptySet())

    /** `true` if the characteristic can notify or indicate. */
    private val canUpdate = CharacteristicProperty.NOTIFY in properties ||
                            CharacteristicProperty.INDICATE in properties

    override val value get() = state.value
    override val writes get() = state.writes
    override val subscribers: StateFlow<Set<C>> get() = _subscribers

    override fun onRead(handler: (suspend ReadRequest.() -> ReadResponse)?) = state.onRead(handler)
    override fun onWrite(handler: (suspend WriteRequest.() -> WriteResponse)?) = state.onWrite(handler)
    override fun setValue(value: ByteArray) = state.setValue(value)

    override suspend fun update(value: ByteArray) = update(value, to = null)

    /**
     * Sets the value and notifies the given central, or all subscribers when `null`.
     *
     * @throws IllegalStateException if [to] is not subscribed.
     */
    suspend fun update(value: ByteArray, to: Central<*>?) {
        check(canUpdate) { "Characteristic does not support notifications nor indications" }
        check(service.manager != null) { "The service has been removed" }
        require(value.size <= MAX_ATTRIBUTE_LENGTH) {
            "Value too long: ${value.size} bytes, maximum is $MAX_ATTRIBUTE_LENGTH"
        }
        val targets = if (to != null) {
            val subscriber = _subscribers.value.firstOrNull { it == to }
            checkNotNull(subscriber) { "$to has not subscribed to this characteristic" }
            listOf(subscriber)
        } else {
            _subscribers.value.toList()
        }
        targets.forEach {
            require(value.size <= it.maximumUpdateValueLength) {
                "Value too long for $it: ${value.size} bytes, maximum is ${it.maximumUpdateValueLength}"
            }
        }
        state.setValue(value)
        if (targets.isEmpty()) return

        val confirm = CharacteristicProperty.NOTIFY !in properties
        val failures = coroutineScope {
            targets
                .map { central ->
                    async {
                        try {
                            host.trace {
                                "Sending ${if (confirm) "indication" else "notification"} to $central: ${value.size} bytes"
                            }
                            host.notify(central, instanceId, value, confirm)
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            e
                        }
                    }
                }
                .awaitAll()
        }
        failures.firstOrNull { it != null }?.let { throw it }
    }

    /** Returns the value of the Client Characteristic Configuration descriptor of the central. */
    fun cccdValue(central: C): Int = cccdValues.value[central] ?: 0

    /**
     * Sets the value of the Client Characteristic Configuration descriptor of the central.
     *
     * @param value Bit 0 for notifications, bit 1 for indications.
     */
    fun setCccdValue(central: C, value: Int) {
        cccdValues.update { if (value == 0) it - central else it + (central to value) }
        _subscribers.value = cccdValues.value.keys
    }

    /** Forgets the central. */
    fun removeCentral(central: C) = setCccdValue(central, 0)

    /** Forgets all centrals. */
    fun removeAllCentrals() {
        cccdValues.value = emptyMap()
        _subscribers.value = emptySet()
    }

    override fun toString() = "Characteristic(uuid=$uuid, instanceId=$instanceId)"
}
