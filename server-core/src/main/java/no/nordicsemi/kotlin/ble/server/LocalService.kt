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

import no.nordicsemi.kotlin.ble.core.AnyService
import no.nordicsemi.kotlin.ble.core.IncludedService
import no.nordicsemi.kotlin.ble.core.Peer
import no.nordicsemi.kotlin.ble.core.PrimaryService

/**
 * A GATT service hosted by the local GATT server.
 *
 * There is a single instance of each service, shared by all centrals. Requests carry the central
 * that sent them.
 *
 * @param C The type of the central.
 */
sealed interface AnyLocalService<C: Central<*>>: AnyService<LocalCharacteristic<C>> {
    override val includedServices: List<LocalIncludedService<C>>

    /**
     * Local services are not owned by a remote peer, so this is always `null`.
     *
     * Use [manager] to get the manager hosting the service.
     */
    override val owner: Peer<*>?
        get() = null

    /**
     * The manager hosting this service, or `null` if the service was removed, because the services
     * were replaced using [LocalServer.setup] or the manager was closed.
     */
    val manager: LocalServer<*, C>?
}

/**
 * A primary service of the local GATT server.
 */
interface LocalService<C: Central<*>>: PrimaryService<LocalCharacteristic<C>>, AnyLocalService<C>

/**
 * A service that is included in another service of the local GATT server.
 *
 * There are no limits to the number of include definitions or the depth of nested includes.
 */
interface LocalIncludedService<C: Central<*>>: IncludedService<LocalCharacteristic<C>>, AnyLocalService<C> {
    override val service: AnyLocalService<C>
    override val owner: Peer<*>?
        get() = null
    override val manager: LocalServer<*, C>?
        get() = service.manager
}
