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

package no.nordicsemi.kotlin.ble.core

import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

/**
 * A Bluetooth LE profile, which separates the GATT services from the application logic.
 *
 * A profile can use services of both sides of the connection: the services of the other device,
 * which are discovered after connecting ([R]), and the services of this device, which were defined
 * by the application ([L]). For example, the Proximity Profile of a phone uses the Link Loss
 * and Immediate Alert services of the remote peripheral, and offers its own Immediate Alert service.
 * Profiles using the services of one side only use [Nothing] as the type of the other one.
 *
 * The profile is registered on a manager, which calls [prepare] when all the services were found,
 * and then [initialize] to start it.
 *
 * Local services are not added by the profile. They are defined by the application when it sets
 * up the server. The profile only receives them, when they are available.
 *
 * @param P The type of the object the profile is attached to, for example a peripheral.
 * @param R The type of the services of the other device.
 * @param L The type of the local services.
 * @property requiredServiceUuids UUIDs of the services of the other device that have to be found.
 * @property optionalServiceUuids UUIDs of the services of the other device that are used when
 * they are found.
 * @property localServiceUuids UUIDs of the local services the profile needs.
 * @property name An optional name of the profile, used in the logs.
 */
abstract class Profile<P: Any, R: PrimaryService<*>, L: PrimaryService<*>>(
    val requiredServiceUuids: List<Uuid> = emptyList(),
    val optionalServiceUuids: List<Uuid> = emptyList(),
    val localServiceUuids: List<Uuid> = emptyList(),
    val name: String? = null,
) {

    /**
     * Gets the services and validates them.
     *
     * Throw [IllegalArgumentException], or [NoSuchElementException], if the services cannot be
     * used, for example when a required characteristic is missing. The profile is then treated as
     * not supported.
     *
     * @param target The object the profile is attached to.
     * @param remoteServices The services of the other device, found by the required and optional
     * UUIDs.
     * @param localServices The local services, found by [localServiceUuids].
     */
    protected abstract fun prepare(target: P, remoteServices: List<R>, localServices: List<L>)

    /**
     * Starts the profile.
     *
     * It is called after [prepare], in a scope that is cancelled when the services are no
     * longer valid, for example when the peripheral disconnects.
     *
     * @param target The object the profile is attached to.
     */
    protected abstract suspend fun CoroutineScope.initialize(target: P)

    /**
     * Called when the services of the profile were not found, or were rejected by [prepare].
     *
     * @param target The object the profile is attached to.
     */
    protected open suspend fun unsupported(target: P) {
        // Empty default implementation.
    }

    /**
     * Called when the services could not be discovered.
     *
     * @param target The object the profile is attached to.
     * @param reason The reason, which depends on the kind of the manager.
     */
    protected open suspend fun failed(target: P, reason: Any) {
        // Empty default implementation.
    }

    /**
     * Calls [prepare], reporting a missing element as [IllegalArgumentException].
     *
     * It is called by the library. It should not be called by the application.
     */
    fun validate(target: P, remoteServices: List<R>, localServices: List<L>) {
        try {
            prepare(target, remoteServices, localServices)
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException(e)
        }
    }

    /**
     * Starts the profile, after the services were validated with [validate].
     *
     * It is called by the library. It should not be called by the application.
     */
    suspend fun start(target: P, scope: CoroutineScope) {
        with(scope) { initialize(target) }
    }

    /**
     * Reports that the services were not found.
     *
     * It is called by the library. It should not be called by the application.
     */
    suspend fun reportUnsupported(target: P) = unsupported(target)

    /**
     * Reports that the services could not be discovered.
     *
     * It is called by the library. It should not be called by the application.
     */
    suspend fun reportFailed(target: P, reason: Any) = failed(target, reason)
}
