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

package no.nordicsemi.kotlin.ble.client

import no.nordicsemi.kotlin.ble.client.internal.BaseRemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.AnyService
import no.nordicsemi.kotlin.ble.core.IncludedService
import no.nordicsemi.kotlin.ble.core.PrimaryService

/**
 * A GATT service on a remote connected peripheral device.
 */
sealed interface AnyRemoteService: AnyService<RemoteCharacteristic> {
    override val includedServices: List<RemoteIncludedService>
    override val owner: Peripheral<*, *>?
}

/**
 * A GATT service on a remote connected peripheral device.
 */
abstract class RemoteService: PrimaryService<RemoteCharacteristic>, AnyRemoteService {
    override var owner: Peripheral<*, *>? = null
        internal set(newValue) {
            field = newValue
            // Setting owner to null means, that the characteristic has been invalidated.
            if (newValue == null) {
                characteristics.forEach {
                    (it as? BaseRemoteCharacteristic)?.reset()
                }
            }
        }
}

/**
 * A GATT service that is included in another service on a remote connected peripheral device.
 *
 * There are no limits to the number of include definitions or
 * the depth of nested includes in a service definition.
 */
interface RemoteIncludedService: IncludedService<RemoteCharacteristic>, AnyRemoteService {
    override val service: AnyRemoteService
    override val owner: Peripheral<*, *>?
        get() = service.owner
}