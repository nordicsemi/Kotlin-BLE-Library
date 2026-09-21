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

import no.nordicsemi.kotlin.ble.core.ConnectionState

/**
 * The outcome of resolving a profile's services on a peripheral, passed to the `block` parameter
 * of [Peripheral.profile].
 *
 * Exactly one of [Found], [Unsupported] or [Failed] is reported for every service discovery
 * outcome, so `block` always has something to act on instead of, for an optional profile, simply
 * never being called.
 *
 * @param T The type of the resolved service(s): [RemoteService] for profiles based on a single
 * GATT service, or a [List] of [RemoteService] for profiles based on multiple GATT services.
 * @see Peripheral.profile
 */
sealed class ProfileServices<out T> {

    /**
     * All the profile's required services (and any optional ones found) are present on the
     * peripheral.
     *
     * @property services The resolved service(s).
     */
    data class Found<T>(val services: T) : ProfileServices<T>()

    /**
     * At least one of the profile's required services was not found on the peripheral.
     *
     * If the profile was registered as required, the peripheral is disconnected right after this
     * is reported, with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     * If the profile was registered as optional, the connection is left untouched: the peripheral
     * may still change its services later (e.g. after a Service Changed indication), in which case
     * [Found] may be reported on a later discovery.
     */
    data object Unsupported : ProfileServices<Nothing>()

    /**
     * Service discovery failed, so it could not be determined whether the profile's required
     * services are present on the peripheral.
     *
     * As with [Unsupported], a required profile is disconnected right after this is reported;
     * an optional profile is not.
     *
     * @property reason The reason of the service discovery failure, as reported by
     * [RemoteServices.Failed.reason].
     */
    data class Failed(val reason: RemoteServices.Failed.Reason) : ProfileServices<Nothing>()
}

/**
 * Returns a [ProfileServices] with [Found][ProfileServices.Found]'s services transformed by
 * [transform], or the same [Unsupported][ProfileServices.Unsupported]/[Failed][ProfileServices.Failed]
 * instance otherwise.
 */
internal fun <T, R> ProfileServices<T>.map(transform: (T) -> R): ProfileServices<R> = when (this) {
    is ProfileServices.Found -> ProfileServices.Found(transform(services))
    is ProfileServices.Unsupported -> this
    is ProfileServices.Failed -> this
}
