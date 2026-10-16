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

package no.nordicsemi.kotlin.ble.core.internal

import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.and
import kotlin.uuid.Uuid

class ServiceDefinition(
    val uuid: Uuid,
    val instanceId: Int,
    val characteristics: List<CharacteristicDefinition>,
    val includedServices: List<ServiceDefinition>,
)

class CharacteristicDefinition(
    val uuid: Uuid,
    val instanceId: Int,
    val properties: Set<CharacteristicProperty>,
    val permissions: Set<Permission>,
    val descriptors: List<DescriptorDefinition>,
)

open class DescriptorDefinition(
    val uuid: Uuid,
    val instanceId: Int,
    val permissions: Set<Permission>,
)

/**
 * Client Characteristic Configuration Descriptor (CCCD) definition.
 *
 * @param enabled True if notifications/indications are enabled initially. Bonded devices may
 * store this information and restore it when reconnecting.
 * @param instanceId The instance ID of the descriptor.
 * @param permissions The permissions of the descriptor.
 */
class CCCD(
    var enabled: Boolean = false,
    instanceId: Int,
    permissions: Set<Permission> = Permission.READ and Permission.WRITE,
): DescriptorDefinition(
    uuid = Descriptor.CLIENT_CHAR_CONF_UUID,
    instanceId = instanceId,
    permissions = permissions,
)

/**
 * Characteristic User Description (CUD) definition.
 *
 * @param description The description string.
 * @param writable True if the description is writable.
 * @param instanceId The instance ID of the descriptor.
 */
class CUD(
    val description: String,
    writable: Boolean,
    instanceId: Int,
): DescriptorDefinition(
    uuid = Descriptor.CHAR_USER_DESC_UUID,
    instanceId = instanceId,
    permissions = if (writable) {
        Permission.READ and Permission.WRITE
    } else {
        setOf(Permission.READ)
    }
)


/**
 * Characteristic Extended Properties Descriptor (CEPD) definition.
 *
 * @param reliableWrite True if reliable writes are supported.
 * @param writableAuxiliaries True if the Characteristic User Description descriptor is writable.
 * @param instanceId The instance ID of the descriptor.
 */
class CEPD(
    val reliableWrite: Boolean,
    val writableAuxiliaries: Boolean,
    instanceId: Int,
): DescriptorDefinition(
    uuid = Descriptor.CHAR_EXT_PROP_UUID,
    instanceId = instanceId,
    permissions = setOf(Permission.READ)
)