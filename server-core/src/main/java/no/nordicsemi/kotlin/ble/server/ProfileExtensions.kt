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

package no.nordicsemi.kotlin.ble.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.core.Profile

/**
 * Registers a profile that uses the local services of this manager.
 *
 * The profile is started when all the local services it needs, [Profile.localServiceUuids], are
 * available in [LocalServer.services]. It is cancelled when the services are replaced, and
 * started again with the new ones. If the services are not set up yet, the profile waits for them.
 *
 * The services are not added by the profile. The application defines them using
 * [LocalServer.setup].
 *
 * A profile that also uses the services of a remote device is registered on that device, see
 * the `profile` method of the peripheral.
 *
 * The profile runs on a child coroutine of the given [scope], until the scope is cancelled.
 *
 * @param ID The type of the identifier of the manager.
 * @param C The type of the central.
 * @param scope The scope in which the profile runs.
 * @param profile The profile, which uses the local services.
 * @return The job observing the services, which can be cancelled to unregister the profile.
 */
@IgnorableReturnValue
fun <ID: Any, C: Central<ID>> LocalServer<ID, C>.profile(
    scope: CoroutineScope,
    profile: Profile<LocalServer<ID, C>, Nothing, LocalService>,
): Job {
    val manager = this
    return scope.launch {
        services.collectLatest { services ->
            val local = services.filter { it.uuid in profile.localServiceUuids }
            if (!profile.localServiceUuids.all { uuid -> local.any { it.uuid == uuid } }) {
                // Wait until the services are available.
                return@collectLatest
            }
            try {
                profile.validate(manager, emptyList(), local)
            } catch (e: IllegalArgumentException) {
                profile.reportUnsupported(manager)
                return@collectLatest
            }
            // The profile runs until the services change, or the scope is cancelled.
            profile.start(manager, this)
            awaitCancellation()
        }
    }
}

/**
 * Registers a profile that uses the local services of this manager.
 *
 * The profile runs in the scope of the caller. See the overload with a scope for details.
 *
 * @param ID The type of the identifier of the manager.
 * @param C The type of the central.
 * @param profile The profile, which uses the local services.
 * @return The job observing the services, which can be cancelled to unregister the profile.
 */
@IgnorableReturnValue
suspend fun <ID: Any, C: Central<ID>> LocalServer<ID, C>.profile(
    profile: Profile<LocalServer<ID, C>, Nothing, LocalService>,
): Job = profile(CoroutineScope(currentCoroutineContext()), profile)
