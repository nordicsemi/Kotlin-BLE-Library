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

package no.nordicsemi.kotlin.ble.client.android.preview

import no.nordicsemi.kotlin.ble.client.AnyRemoteService
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteIncludedService
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.core.ServiceScope
import no.nordicsemi.kotlin.ble.core.internal.ServerScopeImpl
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.uuid.Uuid

/**
 * A remote service that can be used for compose previews.
 *
 * It has no-op implementation.
 */
class PreviewRemoteService: RemoteService {
    override val uuid: Uuid
    override val instanceId: Int
    private val definition: ServiceDefinition?
    override val characteristics: List<RemoteCharacteristic>
    override val includedServices: List<RemoteIncludedService>

    // This instance is not null if the service is created as a parent of a characteristic
    // or a descriptor.
    internal val characteristic: RemoteCharacteristic?

    /**
     * Creates a preview service as a parent of given characteristic.
     */
    internal constructor(
        uuid: Uuid,
        instanceId: Int = 0,
        characteristic: PreviewRemoteCharacteristic,
    ) {
        this.uuid = uuid
        this.instanceId = instanceId
        this.definition = null
        this.characteristic = characteristic
        this.characteristics = listOf(characteristic)
        this.includedServices = emptyList()
    }

    /**
     * Creates a preview service as a parent of given descriptor.
     */
    internal constructor(
        uuid: Uuid,
        instanceId: Int = 0,
        descriptor: PreviewRemoteDescriptor,
    ) {
        this.uuid = uuid
        this.instanceId = instanceId
        this.definition = null
        this.characteristic = PreviewRemoteCharacteristic(this, Uuid.random(), emptySet(), descriptor)
        this.characteristics = listOf(characteristic)
        this.includedServices = emptyList()
    }

    /**
     * Creates a preview of a remote service with the given service identifier.
     *
     * @param shortUuid A 16 or 32-bit service UUID assigned by Bluetooth SIG.
     * @param instanceId The instance ID of the service, defaults to 0.
     * @param builder The service builder.
     */
    constructor(
        shortUuid: Int,
        instanceId: Int = 0,
        builder: ServiceScope.() -> Unit = {},
    ): this(
        uuid = Uuid.fromShortUuid(shortUuid),
        instanceId = instanceId,
        builder = builder
    )

    /**
     * Creates a preview of a remote service with the given service identifier.
     *
     * @param uuid Service UUID.
     * @param instanceId The instance ID of the service, defaults to 0.
     * @param builder The service builder.
     */
    constructor(
        uuid: Uuid,
        instanceId: Int = 0,
        builder: ServiceScope.() -> Unit = {},
    ) {
        this.uuid = uuid
        this.instanceId = instanceId
        this.definition = ServerScopeImpl()
            .apply { Service(uuid, builder) }
            .build()
            .first()
        this.characteristic = null
        this.characteristics = definition.characteristics.map {
            PreviewRemoteCharacteristic(
                service = this,
                uuid = it.uuid,
                properties = it.properties,
                definition = it,
            )
        }
        this.includedServices = definition.includedServices.map {
            PreviewIncludedRemoteService(
                service = this,
                uuid = it.uuid,
                definition = it,
            )
        }
    }
}

class PreviewIncludedRemoteService internal constructor(
    override val service: AnyRemoteService,
    override val uuid: Uuid,
    override val instanceId: Int = 0,
    private val definition: ServiceDefinition,
): RemoteIncludedService {

    override val characteristics: List<RemoteCharacteristic> = definition.characteristics.map {
        PreviewRemoteCharacteristic(
            service = this,
            uuid = it.uuid,
            properties = it.properties,
            definition = it,
        )
    }
    override val includedServices: List<RemoteIncludedService> = definition.includedServices.map {
        PreviewIncludedRemoteService(
            service = this,
            uuid = it.uuid,
            definition = it,
        )
    }
}