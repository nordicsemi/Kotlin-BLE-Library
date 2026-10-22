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
import no.nordicsemi.kotlin.ble.core.CharacteristicScope
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.ServerScope
import no.nordicsemi.kotlin.ble.core.ServiceScope
import kotlin.uuid.Uuid

/**
 * A generator for attribute handle numbers.
 *
 * Each call to [next] returns the next handle value.
 *
 * Handle numbers are unique within a GATT server. They are assigned to services,
 * characteristics, and descriptors to uniquely identify them.
 */
private class Handle {
    // ATT handle 0x0000 is reserved, so the first handle is 1.
    private var value: Int = 1

    /** Generates the next handle value. */
    fun next(): Int {
        return value++
    }
}

/**
 * Internal server scope implementation.
 */
open class ServerScopeImpl: ServerScope {
    private val services = mutableListOf<ServiceDefinition>()
    private var handle = Handle()

    /**
     * Builds the list of services from the scope.
     */
    fun build(): List<ServiceDefinition> = services.toList()

    override fun Service(uuid: Uuid, builder: ServiceScope.() -> Unit) {
        val serviceHandle = handle.next()
        ServiceScopeImpl(handle)
            .apply(builder)
            .let { scope ->
                services.add(
                    ServiceDefinition(
                        uuid = uuid,
                        instanceId = serviceHandle,
                        characteristics = scope.characteristics,
                        includedServices = scope.includedServices
                    )
                )
            }
    }
}

private open class ServiceScopeImpl(
    private val handle: Handle,
): ServiceScope {
    val characteristics = mutableListOf<CharacteristicDefinition>()
    val includedServices = mutableListOf<ServiceDefinition>()

    override fun Characteristic(
        uuid: Uuid,
        properties: Set<CharacteristicProperty>,
        permissions: Set<Permission>,
        builder: CharacteristicScope.() -> Unit
    ): Int {
        val characteristicsHandle = handle.next()
        CharacteristicScopeImpl(handle)
            .apply(builder)
            .let { scope ->
                // Add CCCD (Client Characteristic Configuration Descriptor) if NOTIFY or INDICATE is
                // supported, and it wasn't added by the user.
                if ((CharacteristicProperty.NOTIFY in properties || CharacteristicProperty.INDICATE in properties) &&
                    scope.descriptors.none { it.uuid == Descriptor.CLIENT_CHAR_CONF_UUID }
                ) {
                    val cccdHandle = handle.next()
                    scope.descriptors.add(CCCD(instanceId = cccdHandle))
                }

                // We may be adding EXTENDED_PROPERTIES property, so create a mutable copy.
                val properties = properties.toMutableSet()

                // If there's no Characteristic Extended Properties Descriptor (CEPD),
                // but the Characteristic User Description Descriptor (CUD) exists and is writable,
                // add CEPD automatically with writableAuxiliaries set to true.
                if (scope.descriptors.none { it.uuid == Descriptor.CHAR_EXT_PROP_UUID } &&
                    scope.descriptors.any { it is CUD && Permission.WRITE in it.permissions }) {
                    val extPropHandle = handle.next()
                    scope.descriptors.add(
                        CEPD(
                            // The Reliable Write property needs to be set by the user.
                            reliableWrite = false,
                            writableAuxiliaries = true,
                            instanceId = extPropHandle,
                        )
                    )
                }

                // The Extended Properties bit must be set if, and only if, the characteristic has
                // a Characteristic Extended Properties Descriptor, no matter who added it.
                if (scope.descriptors.any { it.uuid == Descriptor.CHAR_EXT_PROP_UUID }) {
                    properties += CharacteristicProperty.EXTENDED_PROPERTIES
                }

                characteristics.add(
                    CharacteristicDefinition(
                        uuid = uuid,
                        instanceId = characteristicsHandle,
                        properties = properties,
                        permissions = permissions,
                        descriptors = scope.descriptors
                    )
                )
            }
        return characteristicsHandle
    }

    override fun IncludedService(
        uuid: Uuid,
        builder: ServiceScope.() -> Unit
    ) {
        val includedServiceHandle = handle.next()
        ServiceScopeImpl(handle)
            .apply(builder)
            .let { scope ->
                includedServices.add(
                    ServiceDefinition(
                        uuid = uuid,
                        instanceId = includedServiceHandle,
                        characteristics = scope.characteristics,
                        includedServices = scope.includedServices,
                    )
                )
            }
    }
}

private class CharacteristicScopeImpl(
    private val handle: Handle,
): CharacteristicScope {
    val descriptors = mutableListOf<DescriptorDefinition>()

    override fun Descriptor(uuid: Uuid, permissions: Set<Permission>): Int {
        require(uuid != Descriptor.CHAR_USER_DESC_UUID) {
            "Use CharacteristicUserDescriptionDescriptor() to add Characteristic User Description Descriptor."
        }
        require(uuid != Descriptor.CLIENT_CHAR_CONF_UUID) {
            "Use ClientCharacteristicConfigurationDescriptor() to add Client Characteristic Configuration Descriptor."
        }
        val descriptorHandle = handle.next()
        descriptors.add(
            DescriptorDefinition(
                uuid = uuid,
                instanceId = descriptorHandle,
                permissions = permissions
            )
        )
        return descriptorHandle
    }

    override fun CharacteristicUserDescriptionDescriptor(description: String, writable: Boolean): Int {
        val descriptorHandle = handle.next()

        // There can be only one CUD per characteristic.
        require(descriptors.none { it.uuid == Descriptor.CHAR_USER_DESC_UUID }) {
            "Characteristic User Description Descriptor already added to the characteristic."
        }
        descriptors.add(
            CUD(
                description = description,
                instanceId = descriptorHandle,
                writable = writable
            )
        )
        return descriptorHandle
    }

    override fun ClientCharacteristicConfigurationDescriptor(
        enabled: Boolean,
        permissions: Set<Permission>,
    ): Int {
        val descriptorHandle = handle.next()

        // There can be only one CCCD per characteristic.
        require(descriptors.none { it.uuid == Descriptor.CLIENT_CHAR_CONF_UUID }) {
            "Client Characteristic Configuration Descriptor already added to the characteristic."
        }
        descriptors.add(
            CCCD(
                enabled = enabled,
                instanceId = descriptorHandle,
                permissions = permissions,
            )
        )
        return descriptorHandle
    }

    override fun CharacteristicExtendedPropertiesDescriptor(
        reliableWrite: Boolean,
        writableAuxiliaries: Boolean
    ): Int {
        val descriptorHandle = handle.next()

        // There can be only one CEPD per characteristic.
        require(descriptors.none { it.uuid == Descriptor.CHAR_EXT_PROP_UUID }) {
            "Characteristic Extended Properties Descriptor already added to the characteristic."
        }
        descriptors.add(
            CEPD(
                reliableWrite = reliableWrite,
                writableAuxiliaries = writableAuxiliaries,
                instanceId = descriptorHandle
            )
        )
        return descriptorHandle
    }
}

