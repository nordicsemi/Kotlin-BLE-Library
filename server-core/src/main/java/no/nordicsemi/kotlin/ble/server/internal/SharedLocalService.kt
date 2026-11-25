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

import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.server.AnyLocalService
import no.nordicsemi.kotlin.ble.server.Client
import no.nordicsemi.kotlin.ble.server.LocalIncludedService
import no.nordicsemi.kotlin.ble.server.LocalService
import no.nordicsemi.kotlin.ble.server.LocalServer
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * The implementation of [LocalService], one instance per attribute shared by all clients.
 *
 * The per-client views in `ClientViews.kt` delegate to it.
 *
 * The service, with all its characteristics, descriptors and included services, is created from
 * the definition. It stays valid until the services are replaced or the manager is closed.
 *
 * @param C The type of the client.
 * @param definition The definition of the service.
 * @param host The services of the manager.
 */
internal class SharedLocalService<C: Client<*>>(
    definition: ServiceDefinition,
    manager: LocalServer<*>,
    host: LocalServerHost<C>,
): LocalService {
    @Volatile
    private var _manager: LocalServer<*>? = manager

    override val uuid: Uuid = definition.uuid
    override val instanceId: Int = definition.instanceId
    override val manager: LocalServer<*>? get() = _manager
    override val characteristics: List<SharedLocalCharacteristic<C>> =
        definition.characteristics.map { SharedLocalCharacteristic(it, this, host) }
    override val includedServices: List<SharedLocalIncludedService<C>> =
        definition.includedServices.map { SharedLocalIncludedService(it, this, host) }

    /** Marks the service as removed: [manager] becomes `null`. */
    fun invalidate() {
        _manager = null
    }

    override fun toString() = "Service(uuid=$uuid, instanceId=$instanceId)"
}

/**
 * The implementation of [LocalIncludedService], one instance per attribute shared by all clients.
 *
 * The per-client views in `ClientViews.kt` delegate to it.
 *
 * @param C The type of the client.
 * @param definition The definition of the service.
 * @property service The service that includes this one.
 * @param host The services of the manager.
 */
internal class SharedLocalIncludedService<C: Client<*>>(
    definition: ServiceDefinition,
    override val service: AnyLocalService,
    host: LocalServerHost<C>,
): LocalIncludedService {
    override val uuid: Uuid = definition.uuid
    override val instanceId: Int = definition.instanceId
    override val characteristics: List<SharedLocalCharacteristic<C>> =
        definition.characteristics.map { SharedLocalCharacteristic(it, this, host) }
    override val includedServices: List<SharedLocalIncludedService<C>> =
        definition.includedServices.map { SharedLocalIncludedService(it, this, host) }

    override fun toString() = "IncludedService(uuid=$uuid, instanceId=$instanceId)"
}
