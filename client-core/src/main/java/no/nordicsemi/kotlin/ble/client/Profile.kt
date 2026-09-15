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

package no.nordicsemi.kotlin.ble.client

import kotlinx.coroutines.CoroutineScope
import no.nordicsemi.kotlin.ble.core.ConnectionState
import kotlin.uuid.Uuid

/**
 * A class representing a Bluetooth LE profile.
 *
 * The profiles are intended to separate the underlying GATT services and characteristics and the
 * application logic and device API. For example, the profile can expose methods to turn a light
 * ON or OFF, while internally it would use Bluetooth LE connection.
 *
 * @property requiredServiceUuids A list of UUIDs of required profile GATT services.
 * @property optionalServiceUuids A list of UUIDs of optional profile GATT services.
 * @property name The name of the profile. This is for convenience, used only in logging.
 * @see Peripheral.profile
 */
sealed class Profile(
    internal val requiredServiceUuids: List<Uuid>,
    internal val optionalServiceUuids: List<Uuid> = emptyList(),
    val name: String? = null,
) {
    /**
     * This method should validate if the services contain the required characteristics,
     * validate if the characteristics have expected properties, and store the references
     * to the characteristics in the class properties for later use.
     *
     * This method is called before [initialize]. It should use [require] and [first] to validate
     * the service and throw [IllegalArgumentException] or [NoSuchElementException] if validation fails.
     * In that case, a required profile will cause a disconnection with
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     *
     * #### Example
     * ```kotlin
     * override fun prepare(peripheral: Peripheral<*, *>, services: List<RemoteService>) {
     *     // Link Loss service is required.
     *     services.first { it.uuid == LINK_LOSS_SERVICE_UUID }.also { service ->
     *        // Alert Characteristic is required.
     *        linkLossCharacteristic = service.characteristics.first { it.uuid == ALERT_LEVEL_UUID }
     *
     *        require(linkLossCharacteristic.isWritable()) { "Alert level characteristic in Link Loss Service must be writable" }
     *        require(linkLossCharacteristic.isReadable()) { "Alert level characteristic in Link Loss Service must be readable" }
     *     }
     *
     *     // Immediate Alert service is optional.
     *     services.firstOrNull { it.uuid == IMMEDIATE_ALERT_SERVICE_UUID }?.also { service ->
     *        // If this service is found, the Alert Level characteristic is required.
     *        immediateAlertCharacteristic = service.characteristics.first { it.uuid == ALERT_LEVEL_UUID }
     *        require(immediateAlertCharacteristic.isWritable()) { "Alert level characteristic must be writable" }
     *     }
     *
     *     // TX Power service is optional.
     *     services.firstOrNull { it.uuid == TX_POWER_SERVICE_UUID }?.also { service ->
     *        // If this service is found, the TX Power Level characteristic is required.
     *        txPowerCharacteristic = service.characteristics.first { it.uuid == TX_POWER_UUID }
     *        require(txPowerCharacteristic.isReadable()) { "TX power characteristic must be readable" }
     *     }
     * }
     * ```
     *
     * @param peripheral The peripheral the profile is being resolved on.
     * @param services The discovered services.
     */
    protected abstract fun prepare(peripheral: Peripheral<*, *>, services: List<RemoteService>)

    /**
     * This method should initialize the profile.
     *
     * The context of this method is the profile coroutine scope, which will automatically be
     * canceled when the device gets disconnected or the services will get invalidated.
     *
     * #### Example
     * ```kotlin
     * override suspend fun CoroutineScope.initialize(peripheral: Peripheral<*, *>) {
     *     // Subscribe to button characteristic.
     *     txCharacteristic
     *         .subscribe()
     *         .map { value -> String(value) }
     *         .onEach {
     *             println("Received: $it")
     *         }
     *         .launchIn(this)
     * }
     * ```
     *
     * @param peripheral The peripheral the profile is being resolved on.
     */
    protected abstract suspend fun CoroutineScope.initialize(peripheral: Peripheral<*, *>)

    /**
     * This method is called instead of [prepare] and [initialize] when [requiredServiceUuids]
     * could not be found on the peripheral.
     *
     * This is purely a notification, useful for tracking whether the profile is supported by the
     * connected peripheral (for example, to update application state for an optional profile).
     * It does not affect the connection lifecycle: if the profile was registered as required,
     * the peripheral will still be disconnected with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound]
     * regardless of what this method does.
     *
     * The default implementation does nothing.
     *
     * @param peripheral The peripheral the profile is being resolved on.
     * @see Peripheral.profile
     */
    protected open suspend fun CoroutineScope.unsupported(peripheral: Peripheral<*, *>) {
        // Empty default implementation.
    }

    /**
     * This method is called instead of [prepare] and [initialize] when service discovery failed,
     * so it could not be determined whether [requiredServiceUuids] are present on the peripheral.
     *
     * As with [unsupported], this is purely a notification and does not affect the connection
     * lifecycle: if the profile was registered as required, the peripheral will still be
     * disconnected with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound]
     * regardless of what this method does.
     *
     * The default implementation does nothing.
     *
     * @param peripheral The peripheral the profile is being resolved on.
     * @param reason The reason of the service discovery failure.
     * @see Peripheral.profile
     */
    protected open suspend fun CoroutineScope.failed(peripheral: Peripheral<*, *>, reason: RemoteServices.Failed.Reason) {
        // Empty default implementation.
    }

    /**
     * Executes the profile for the given [state]: [prepare] and [initialize] on
     * [Found][ProfileServices.Found], or [unsupported]/[failed] otherwise.
     *
     * @param peripheral The peripheral the profile is being resolved on.
     * @param state The outcome of resolving the profile's services on the peripheral.
     * @param profileScope The coroutine scope of the profile. This scope gets canceled when the
     * services get invalidated or the device gets disconnected.
     */
    internal suspend fun execute(
        peripheral: Peripheral<*, *>,
        state: ProfileServices<List<RemoteService>>,
        profileScope: CoroutineScope,
    ) {
        when (state) {
            is ProfileServices.Found -> {
                try {
                    prepare(peripheral, state.services)
                } catch (e: NoSuchElementException) {
                    throw IllegalArgumentException(e)
                }
                with(profileScope) {
                    initialize(peripheral)
                }
            }
            is ProfileServices.Unsupported -> with(profileScope) { unsupported(peripheral) }
            is ProfileServices.Failed -> with(profileScope) { failed(peripheral, state.reason) }
        }
    }

    /**
     * A class representing a multiservice GATT profile.
     *
     * This is intended for profiles that have multiple GATT services, i.e. Proximity Profile.
     *
     * Note, the [prepare] method may be called with a list of services containing multiple
     * instances of the same service if returned by the service discovery. For example, if a device
     * has multiple batteries, it may expose their level with a *Battery Service* for each of them.
     *
     * @param requiredServiceUuids A list of UUIDs of required profile GATT services.
     * @param optionalServiceUuids A list of UUIDs of optional profile GATT services.
     * @param name The name of the profile. This is for convenience, used only in logging.
     */
    abstract class MultiService(
        requiredServiceUuids: List<Uuid>,
        optionalServiceUuids: List<Uuid> = emptyList(),
        name: String? = null,
    ): Profile(
        requiredServiceUuids = requiredServiceUuids,
        optionalServiceUuids = optionalServiceUuids,
        name = name,
    )

    /**
     * A class representing a simple GATT profile, based on a single GATT service.
     *
     * This is intended for profiles that have a single GATT service, i.e. Battery Profile.
     *
     * ## Multiple instances of the same service
     *
     * In rare cases, a peripheral may have multiple instances of the same service, e.g. multiple
     * batteries reporting their level, each with a different instance of a *Battery Service*.
     *
     * Override [instance] method to pick a desired instance of a remote service.
     *
     * Note, that the list of services contains only GATT services with specified service UUID.
     *
     * @param serviceUuid The UUID of the GATT service.
     * @param name The name of the profile. This is for convenience, used only in logging.
     */
    abstract class Simple(
        serviceUuid: Uuid,
        name: String? = null,
    ) : Profile(
        requiredServiceUuids = listOf(serviceUuid),
        name = name,
    ) {
        final override fun prepare(peripheral: Peripheral<*, *>, services: List<RemoteService>) =
            prepare(peripheral, instance(services))

        /**
         * This method should select a single service instance from the list of identical services
         * returned by the service discovery.
         *
         * The [services] will contain only instances of GATT services with the given UUID.
         * Use [RemoteService.instanceId] to distinguish between instances.
         *
         * ## Multiple instances of the same service
         *
         * By default, this method should return the first service instance. However, in some cases,
         * a peripheral may have multiple instances of the same service, e.g. multiple batteries
         * reporting their level, each with a different instance of a *Battery Service*.
         */
        protected open fun instance(services: List<RemoteService>): RemoteService = services.first()

        /**
         * This method should validate if the services contain the required characteristics,
         * validate if the characteristics have expected properties, and store the references
         * to the characteristics in the class properties for later use.
         *
         * This method is called before [initialize]. It should use [require] and [first] to validate
         * the service and throw [IllegalArgumentException] or [NoSuchElementException] if validation fails.
         * In that case, a required profile will cause a disconnection with
         * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
         *
         * ## Example
         * ```kotlin
         * override fun prepare(peripheral: Peripheral<*, *>, service: RemoteService) {
         *     buttonCharacteristic = service.characteristics.first { it.uuid == BUTTON_CHARACTERISTIC_UUID }
         *     ledCharacteristic = service.characteristics.first { it.uuid == LED_CHARACTERISTIC_UUID }
         *
         *     // Validate properties.
         *     require(buttonCharacteristic.isSubscribable()) { "Button characteristic must be subscribable." }
         *     require(ledCharacteristic.isWritable()) { "LED characteristic must be writable." }
         * }
         * ```
         *
         * @param peripheral The peripheral the profile is being resolved on.
         * @param service The discovered service.
         */
        protected abstract fun prepare(peripheral: Peripheral<*, *>, service: RemoteService)
    }
}