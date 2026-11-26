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

@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

package no.nordicsemi.kotlin.ble.server

import kotlinx.coroutines.CoroutineScope
import no.nordicsemi.kotlin.ble.core.Profile
import kotlin.uuid.Uuid

/**
 * A profile that uses the local services of a [LocalServer] only.
 *
 * The profile is registered with [LocalServer.profile]. The services are defined by the
 * application, using [LocalServer.setup]. The profile starts when all of its services exist,
 * and it is cancelled and started again when the services are replaced.
 *
 * A profile based on a single service extends [Simple]. A profile based on more services extends
 * [MultiService].
 *
 * #### Example
 * ```kotlin
 * class LedButtonServerProfile : LocalProfile.Simple(
 *     serviceUuid = LBS_UUID,
 *     name = "LBS",
 * ) {
 *     private lateinit var ledCharacteristic: LocalCharacteristic
 *     private lateinit var buttonCharacteristic: LocalCharacteristic
 *
 *     /** The LED is controlled by the client, which writes the LED characteristic. */
 *     val ledState: StateFlow<Boolean>
 *         field = MutableStateFlow(false)
 *
 *     /** The Button is controlled by the server. A change notifies the subscribed clients. */
 *     val buttonState = MutableStateFlow(false)
 *
 *     // Throw NoSuchElementException or IllegalArgumentException to reject the service.
 *     override fun prepare(server: LocalServer<*>, service: LocalService) {
 *         ledCharacteristic = service.characteristics.first { it.uuid == LED_UUID }
 *         buttonCharacteristic = service.characteristics.first { it.uuid == BUTTON_UUID }
 *     }
 *
 *     // Cancelled when the services are replaced or the profile is unregistered.
 *     override suspend fun CoroutineScope.initialize(server: LocalServer<*>) {
 *         ledCharacteristic.setValue(byteArrayOf(0))
 *         buttonCharacteristic.setValue(byteArrayOf(0))
 *         // The state follows the value of the characteristic, which can also be set by the server.
 *         ledCharacteristic.value
 *             .onEach { value -> ledState.value = value.singleOrNull() == 1.toByte() }
 *             .launchIn(this)
 *         buttonState
 *             .onEach { pressed -> buttonCharacteristic.update(byteArrayOf(if (pressed) 1 else 0)) }
 *             .launchIn(this)
 *     }
 * }
 *
 * localServer.profile(scope, LedButtonServerProfile())
 * ```
 *
 * A profile that also uses the services of a remote device extends the `Profile.WithLocalServices`
 * of the client instead.
 *
 * @param localServiceUuids The UUIDs of the local services the profile needs.
 * @param name The name of the profile. This is for convenience, used only in logging.
 * @see LocalServer.profile
 */
sealed class LocalProfile(
    localServiceUuids: List<Uuid>,
    name: String? = null,
): Profile<LocalServer<*>, Nothing, LocalService>(
    localServiceUuids = localServiceUuids,
    name = name,
) {

    /**
     * Initializes the profile.
     *
     * It is called after `prepare` if that method completed successfully. The scope is cancelled
     * when the services are replaced, or when the profile is unregistered. Use a `finally` block
     * to release whatever the profile acquired. When the work is done in the coroutines
     * launched in the scope, the profile keeps running until the scope is cancelled.
     *
     * @param server The server the profile is registered on.
     */
    abstract override suspend fun CoroutineScope.initialize(server: LocalServer<*>)

    /**
     * Called when the local services were found, but rejected by `prepare`.
     *
     * Throw [IllegalArgumentException] or [NoSuchElementException] from `prepare` to reject
     * the services, for example when a required characteristic is missing.
     */
    override suspend fun unsupported(server: LocalServer<*>) {
        // Empty default implementation.
    }

    /**
     * A profile based on a single local service.
     *
     * @param serviceUuid The UUID of the service.
     * @param name The name of the profile, used in logging.
     */
    abstract class Simple(
        serviceUuid: Uuid,
        name: String? = null,
    ): LocalProfile(
        localServiceUuids = listOf(serviceUuid),
        name = name,
    ) {
        final override fun prepare(
            server: LocalServer<*>,
            remoteServices: List<Nothing>,
            localServices: List<LocalService>,
        ) = prepare(server, localServices.first())

        /**
         * Gets the characteristics from the service and validates them.
         *
         * Throw [IllegalArgumentException] or [NoSuchElementException] if the service cannot be
         * used. The profile is then treated as not supported, and [unsupported] is called.
         *
         * @param server The server the profile is registered on.
         * @param service The local service with the UUID given to the profile.
         */
        protected abstract fun prepare(server: LocalServer<*>, service: LocalService)
    }

    /**
     * A profile based on multiple local services, all of which are required.
     *
     * @param serviceUuids The UUIDs of the services.
     * @param name The name of the profile, used in logging.
     */
    abstract class MultiService(
        serviceUuids: List<Uuid>,
        name: String? = null,
    ): LocalProfile(
        localServiceUuids = serviceUuids,
        name = name,
    ) {
        final override fun prepare(
            server: LocalServer<*>,
            remoteServices: List<Nothing>,
            localServices: List<LocalService>,
        ) = prepare(server, localServices)

        /**
         * Gets the characteristics from the services and validates them.
         *
         * Throw [IllegalArgumentException] or [NoSuchElementException] if the services cannot be
         * used. The profile is then treated as not supported, and [unsupported] is called.
         *
         * @param server The server the profile is registered on.
         * @param services The local services with the UUIDs given to the profile.
         */
        protected abstract fun prepare(server: LocalServer<*>, services: List<LocalService>)
    }
}
