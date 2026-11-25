/*
 * Copyright (c) 2025, Nordic Semiconductor
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

package no.nordicsemi.kotlin.ble.client.mock

import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.ServerScope
import no.nordicsemi.kotlin.ble.core.Service
import no.nordicsemi.kotlin.ble.core.ServiceScope
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.core.internal.ServerScopeImpl

/**
 * Scope for building a mock GATT server.
 *
 * This interface extends [ServerScope] and allows to declare common services used in BLE:
 * - Generic Access Service (GAS)
 * - Generic Attribute Service (GATT).
 *
 * Note: The attributes added by these services will be handled automatically, that is no call to
 * [PeripheralSpecEventHandler] will be made for them.
 */
interface MockServerScope: ServerScope {

    /**
     * Declares the Generic Access Service (GAS) with common characteristics:
     * - Device Name
     * - Appearance
     * - Peripheral Preferred Connection Parameters
     *
     * and allows to add more with the [builder].
     *
     * Note: The 3 characteristics mentioned above will be handled automatically.
     */
    @Suppress("FunctionName")
    fun GenericAccessService(builder: ServiceScope.() -> Unit = {}) = addGenericAccessService(builder)

    /**
     * Declares the Generic Attribute Service (GATT) with the Service Changed characteristic.
     *
     * The Service Changed characteristic will be handled automatically.
     * Use [PeripheralSpec.simulateServiceChange] to simulate service change.
     *
     * @param initiallyEnabled If true, the Client Characteristic Configuration Descriptor
     * for the Service Changed characteristic will be initially enabled.
     */
    @Suppress("FunctionName")
    fun GenericAttributeService(initiallyEnabled: Boolean = true) = addGenericAttributeService(initiallyEnabled)
}

/**
 * Declares the Generic Access Service (GAS) with common characteristics, like
 * [MockServerScope.GenericAccessService], in any [ServerScope].
 *
 * It is meant for services of a [MockPeripheralServer], which are defined using
 * [no.nordicsemi.kotlin.ble.server.LocalServer.setup], where the receiver is a plain
 * [ServerScope].
 *
 * The Device Name, Appearance and Peripheral Preferred Connection Parameters characteristics are
 * handled by the [PeripheralSpec] using the values given in the builder. Handlers set on them are
 * not used for reading.
 *
 * @param builder Allows to add more characteristics to the service.
 */
@Suppress("FunctionName")
fun ServerScope.GenericAccessService(builder: ServiceScope.() -> Unit = {}) = addGenericAccessService(builder)

/**
 * Declares the Generic Attribute Service (GATT) with the Service Changed characteristic, like
 * [MockServerScope.GenericAttributeService], in any [ServerScope].
 *
 * The Service Changed characteristic is handled automatically. Use
 * [PeripheralSpec.simulateServiceChange] to simulate a service change.
 *
 * @param initiallyEnabled If true, the client is subscribed to indications of the Service Changed
 * characteristic whenever it connects.
 */
@Suppress("FunctionName")
fun ServerScope.GenericAttributeService(initiallyEnabled: Boolean = true) =
    addGenericAttributeService(initiallyEnabled)

private fun ServerScope.addGenericAccessService(builder: ServiceScope.() -> Unit) {
    Service(
        uuid = Service.GENERIC_ACCESS_UUID,
    ) {
        Characteristic(
            uuid = Characteristic.DEVICE_NAME,
            properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
            permissions = Permission.READ and Permission.WRITE
        )
        Characteristic(
            uuid = Characteristic.APPEARANCE,
            property = CharacteristicProperty.READ,
            permission = Permission.READ
        )
        Characteristic(
            uuid = Characteristic.PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS,
            property = CharacteristicProperty.READ,
            permission = Permission.READ
        )
        builder()
    }
}

private fun ServerScope.addGenericAttributeService(initiallyEnabled: Boolean) {
    Service(
        uuid = Service.GENERIC_ATTRIBUTE_UUID,
    ) {
        Characteristic(
            uuid = Characteristic.SERVICE_CHANGED,
            property = CharacteristicProperty.INDICATE,
        ) {
            ClientCharacteristicConfigurationDescriptor(enabled = initiallyEnabled)
        }
    }
}

/**
 * Mock server scope implementation.
 */
internal class MockServerScopeImpl: ServerScopeImpl(), MockServerScope