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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import no.nordicsemi.kotlin.ble.client.AnyRemoteService
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.CharacteristicScope
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.internal.CharacteristicDefinition
import no.nordicsemi.kotlin.ble.core.internal.ServerScopeImpl
import no.nordicsemi.kotlin.ble.core.util.MergeResult
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.uuid.Uuid

/**
 * A remote characteristic that can be used for compose previews.
 *
 * It has no-op implementation.
 */
class PreviewRemoteCharacteristic : RemoteCharacteristic {
    override val service: AnyRemoteService
    override val uuid: Uuid
    override val instanceId: Int
    private val _isNotifying = MutableStateFlow(false)
    override val isNotifying: StateFlow<Boolean>
        get() = _isNotifying.asStateFlow()
    override val properties: Set<CharacteristicProperty>

    override val descriptors: List<RemoteDescriptor>
        get() = definition?.descriptors?.map {
            PreviewRemoteDescriptor(this, it.uuid)
        } ?: listOfNotNull(descriptor)

    /**
     * The characteristic definition, when defined by a user.
     */
    private val definition: CharacteristicDefinition?

    /**
     * The child descriptor in case this characteristic is created as a parent of a preview
     * descriptor.
     */
    private val descriptor: RemoteDescriptor?

    internal constructor(
        service: AnyRemoteService,
        uuid: Uuid,
        properties: Set<CharacteristicProperty>,
        definition: CharacteristicDefinition,
    ) {
        this.service = service
        this.uuid = uuid
        this.properties = properties
        this.definition = definition
        this.descriptor = null
        this.instanceId = 0
    }

    internal constructor(
        service: AnyRemoteService,
        uuid: Uuid,
        properties: Set<CharacteristicProperty>,
        descriptor: PreviewRemoteDescriptor,
    ) {
        // The point of this constructor is to create a characteristic with a parent service
        // and child descriptor, that point to this instance as their child and parent.
        this.service = service
        this.uuid = uuid
        this.properties = properties
        this.definition = null
        this.descriptor = descriptor
        this.instanceId = 0
    }

    /**
     * Creates a preview characteristic from a Characteristic Scope.
     *
     * @param shortUuid A 16 or 32 bit short UUID assigned by Bluetooth SIG.
     * @param instanceId The instance ID of the characteristic, defaults to 0.
     * @param properties The properties of the characteristic, defaults to an empty set.
     * @param isNotifying Whether the characteristic is notifying, defaults to false.
     * @param builder The characteristic builder.
     */
    constructor(
        shortUuid: Int,
        instanceId: Int = 0,
        properties: Set<CharacteristicProperty> = emptySet(),
        isNotifying: Boolean = false,
        builder: CharacteristicScope.() -> Unit = {},
    ): this(Uuid.fromShortUuid(shortUuid), instanceId, properties, isNotifying, builder)

    /**
     * Creates a preview characteristic from a Characteristic Scope.
     *
     * @param uuid The service UUID.
     * @param instanceId The instance ID of the characteristic, defaults to 0.
     * @param properties The properties of the characteristic, defaults to an empty set.
     * @param isNotifying Whether the characteristic is notifying, defaults to false.
     * @param builder The characteristic builder.
     */
    constructor(
        uuid: Uuid,
        instanceId: Int = 0,
        properties: Set<CharacteristicProperty> = emptySet(),
        isNotifying: Boolean = false,
        builder: CharacteristicScope.() -> Unit = {},
    ) {
        val serviceDefinition = ServerScopeImpl()
            .apply {
                Service(Uuid.random()) {
                    Characteristic(uuid, properties, emptySet(), builder)
                }
            }
            .build()
            .first()
        this.service = PreviewRemoteService(serviceDefinition.uuid, 0, this)
        this.uuid = uuid
        this.instanceId = instanceId
        this._isNotifying.update { isNotifying }
        this.properties = properties
        this.definition = serviceDefinition.characteristics.first()
        this.descriptor = null
    }

    override suspend fun setNotifying(enabled: Boolean) {
        this._isNotifying.update { enabled }
    }

    override suspend fun read(): ByteArray = byteArrayOf()

    override suspend fun write(data: ByteArray, writeType: WriteType) {}

    override fun subscribe(
        onSubscription: suspend RemoteCharacteristic.() -> Unit
    ): Flow<ByteArray> = emptyFlow()

    override suspend fun waitForValueChange(
        rawDataFilter: (ByteArray) -> Boolean,
        merge: suspend (ByteArray, ByteArray, Int) -> MergeResult,
        filter: (ByteArray) -> Boolean,
        trigger: suspend RemoteCharacteristic.() -> Unit,
    ): ByteArray = byteArrayOf()
        .also { trigger() }
}