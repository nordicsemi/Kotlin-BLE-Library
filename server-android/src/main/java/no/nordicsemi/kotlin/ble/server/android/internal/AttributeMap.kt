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

package no.nordicsemi.kotlin.ble.server.android.internal

import java.util.IdentityHashMap

/**
 * A two-way map between native attributes (services, characteristics and descriptors) and
 * the `instanceId`s of their definitions.
 *
 * The instance IDs assigned by Android to the attributes are not the same as the IDs of the
 * definitions, and may not even be unique across services on older Android versions. The native
 * objects are therefore compared by identity, like in [BluetoothGattService][android.bluetooth.BluetoothGattService]
 * and its siblings, which do not override `equals`.
 *
 * The map is thread safe, as it is used from the Binder threads of the callbacks.
 *
 * @param T The type of the native attribute.
 */
internal class AttributeMap<T: Any> {
    private val ids = IdentityHashMap<T, Int>()
    private val attributes = HashMap<Int, T>()

    /** The number of registered attributes. */
    val size: Int
        get() = synchronized(this) { ids.size }

    /**
     * Registers the attribute.
     *
     * @throws IllegalArgumentException if the ID is already used by another attribute.
     */
    fun register(attribute: T, id: Int) = synchronized(this) {
        require(attributes[id].let { it == null || it === attribute }) {
            "Attribute ID $id is not unique"
        }
        ids[attribute] = id
        attributes[id] = attribute
    }

    /** Returns the ID of the definition of the given native attribute, or `null` if unknown. */
    fun idOf(attribute: T): Int? = synchronized(this) { ids[attribute] }

    /** Returns the native attribute for the given ID, or `null` if unknown. */
    fun attributeOf(id: Int): T? = synchronized(this) { attributes[id] }

    /** Forgets all attributes. */
    fun clear() = synchronized(this) {
        ids.clear()
        attributes.clear()
    }
}
