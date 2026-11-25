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

import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.internal.CEPD
import no.nordicsemi.kotlin.ble.core.internal.CUD
import no.nordicsemi.kotlin.ble.core.internal.DescriptorDefinition
import no.nordicsemi.kotlin.ble.server.Client
import no.nordicsemi.kotlin.ble.server.LocalDescriptor
import no.nordicsemi.kotlin.ble.server.ReadRequest
import no.nordicsemi.kotlin.ble.server.ReadResponse
import no.nordicsemi.kotlin.ble.server.WriteRequest
import no.nordicsemi.kotlin.ble.server.WriteResponse
import kotlin.uuid.Uuid

/**
 * Base implementation of [LocalDescriptor].
 *
 * The Client Characteristic Configuration descriptor is handled by the [LocalServerImpl]:
 * its value is stored per client in the characteristic.
 *
 * @param C The type of the client.
 * @param definition The definition of the descriptor.
 * @property characteristic The characteristic the descriptor belongs to.
 */
internal class BaseLocalDescriptor<C: Client<*>>(
    definition: DescriptorDefinition,
    override val characteristic: BaseLocalCharacteristic<C>,
): LocalDescriptor, LocalAttribute<C> {
    override val uuid: Uuid = definition.uuid
    override val instanceId: Int = definition.instanceId
    override val state = AttributeState(initialValue(definition))

    /** `true` if the descriptor can be read by a client. */
    val isReadPermitted = definition.permissions.any {
        it == Permission.READ || it == Permission.READ_ENCRYPTED || it == Permission.READ_ENCRYPTED_MITM
    }

    /** `true` if the descriptor can be written by a client. */
    val isWritePermitted = definition.permissions.any {
        it == Permission.WRITE || it == Permission.WRITE_ENCRYPTED || it == Permission.WRITE_ENCRYPTED_MITM ||
        it == Permission.WRITE_SIGNED || it == Permission.WRITE_SIGNED_MITM
    }

    override val value get() = state.value
    override val writes get() = state.writes

    override fun onRead(handler: (suspend ReadRequest.() -> ReadResponse)?) {
        checkNotCccd()
        state.onRead(handler)
    }

    override fun onWrite(handler: (suspend WriteRequest.() -> WriteResponse)?) {
        checkNotCccd()
        state.onWrite(handler)
    }

    override fun setValue(value: ByteArray) = state.setValue(value)

    private fun checkNotCccd() {
        check(!isClientCharacteristicConfiguration) {
            "Client Characteristic Configuration descriptor is handled by the library"
        }
    }

    override fun toString() = "Descriptor(uuid=$uuid, instanceId=$instanceId)"

    private companion object {
        /** Returns the initial value as defined by the definition. */
        fun initialValue(definition: DescriptorDefinition): ByteArray = when {
            definition is CUD -> definition.description.encodeToByteArray()
            definition is CEPD -> {
                // Bit 0: Reliable Write, bit 1: Writable Auxiliaries.
                val flags = (if (definition.reliableWrite) 0x01 else 0x00) or
                            (if (definition.writableAuxiliaries) 0x02 else 0x00)
                byteArrayOf(flags.toByte(), 0x00)
            }
            definition.uuid == Descriptor.CLIENT_CHAR_CONF_UUID -> byteArrayOf(0x00, 0x00)
            else -> byteArrayOf()
        }
    }
}
