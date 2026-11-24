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
import no.nordicsemi.kotlin.ble.server.Central
import no.nordicsemi.kotlin.ble.server.LocalIncludedService
import no.nordicsemi.kotlin.ble.server.LocalService
import no.nordicsemi.kotlin.ble.server.LocalServer
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * Base implementation of [LocalService].
 *
 * The service, with all its characteristics, descriptors and included services, is created from
 * the definition. It stays valid until the services are replaced or the manager is closed.
 *
 * @param C The type of the central.
 * @param definition The definition of the service.
 * @param host The services of the manager.
 */
internal class BaseLocalService<C: Central<*>>(
    definition: ServiceDefinition,
    manager: LocalServer<*, C>,
    host: LocalServerHost<C>,
): LocalService {
    @Volatile
    private var _manager: LocalServer<*, C>? = manager

    override val uuid: Uuid = definition.uuid
    override val instanceId: Int = definition.instanceId
    override val manager: LocalServer<*, C>? get() = _manager
    override val characteristics: List<BaseLocalCharacteristic<C>> =
        definition.characteristics.map { BaseLocalCharacteristic(it, this, host) }
    override val includedServices: List<BaseLocalIncludedService<C>> =
        definition.includedServices.map { BaseLocalIncludedService(it, this, host) }

    /** Marks the service as removed: [manager] becomes `null`. */
    fun invalidate() {
        _manager = null
    }

    override fun toString() = "Service(uuid=$uuid, instanceId=$instanceId)"
}

/**
 * Base implementation of [LocalIncludedService].
 *
 * @param C The type of the central.
 * @param definition The definition of the service.
 * @property service The service that includes this one.
 * @param host The services of the manager.
 */
internal class BaseLocalIncludedService<C: Central<*>>(
    definition: ServiceDefinition,
    override val service: AnyLocalService,
    host: LocalServerHost<C>,
): LocalIncludedService {
    override val uuid: Uuid = definition.uuid
    override val instanceId: Int = definition.instanceId
    override val characteristics: List<BaseLocalCharacteristic<C>> =
        definition.characteristics.map { BaseLocalCharacteristic(it, this, host) }
    override val includedServices: List<BaseLocalIncludedService<C>> =
        definition.includedServices.map { BaseLocalIncludedService(it, this, host) }

    override fun toString() = "IncludedService(uuid=$uuid, instanceId=$instanceId)"
}
