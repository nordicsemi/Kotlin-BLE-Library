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

package no.nordicsemi.kotlin.ble.server.internal

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import no.nordicsemi.kotlin.ble.server.AnyLocalService
import no.nordicsemi.kotlin.ble.server.Central
import no.nordicsemi.kotlin.ble.server.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.LocalDescriptor
import no.nordicsemi.kotlin.ble.server.LocalIncludedService
import no.nordicsemi.kotlin.ble.server.LocalService
import no.nordicsemi.kotlin.ble.server.WriteRequest

// Views of the local services, as seen by one central. They share the values, handlers and
// subscriptions with the services of the server, but the requests and the subscribers are limited
// to the central, and updates are sent to the central only.

/** The views of the services of the server, as seen by one central. */
internal class CentralServices<C: Central<*>>(
    private val source: StateFlow<List<LocalService<C>>>,
    private val identifier: Any,
): StateFlow<List<LocalService<C>>> {
    private var cached: Pair<List<LocalService<C>>, List<LocalService<C>>>? = null

    override val value: List<LocalService<C>> get() = viewsOf(source.value)
    override val replayCache: List<List<LocalService<C>>> get() = listOf(value)

    /** The views are created once for each list of services, so they are the same when asked again. */
    private fun viewsOf(services: List<LocalService<C>>): List<LocalService<C>> {
        cached?.takeIf { it.first === services }?.let { return it.second }
        val views = services.map { CentralLocalService(it, identifier) }
        cached = services to views
        return views
    }

    override suspend fun collect(collector: FlowCollector<List<LocalService<C>>>): Nothing {
        source.map { viewsOf(it) }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

/** A read-only [StateFlow] computed from another one. */
private class DerivedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
): StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

/** A [SharedFlow] of the requests sent by one central. */
private class CentralWrites<C: Central<*>>(
    private val source: SharedFlow<WriteRequest<C>>,
    private val identifier: Any,
): SharedFlow<WriteRequest<C>> {
    override val replayCache: List<WriteRequest<C>>
        get() = source.replayCache.filter { it.central.identifier == identifier }

    override suspend fun collect(collector: FlowCollector<WriteRequest<C>>): Nothing {
        source.collect { request ->
            if (request.central.identifier == identifier) collector.emit(request)
        }
    }
}

internal class CentralLocalService<C: Central<*>>(
    private val service: LocalService<C>,
    identifier: Any,
): LocalService<C> by service {
    override val characteristics: List<LocalCharacteristic<C>> =
        service.characteristics.map { CentralLocalCharacteristic(it, this, identifier) }
    override val includedServices: List<LocalIncludedService<C>> =
        service.includedServices.map { CentralIncludedService(it, this, identifier) }

    override fun toString() = "CentralService($service)"
}

internal class CentralIncludedService<C: Central<*>>(
    included: LocalIncludedService<C>,
    override val service: AnyLocalService<C>,
    identifier: Any,
): LocalIncludedService<C> by included {
    override val characteristics: List<LocalCharacteristic<C>> =
        included.characteristics.map { CentralLocalCharacteristic(it, this, identifier) }
    override val includedServices: List<LocalIncludedService<C>> =
        included.includedServices.map { CentralIncludedService(it, this, identifier) }
}

internal class CentralLocalCharacteristic<C: Central<*>>(
    private val characteristic: LocalCharacteristic<C>,
    override val service: AnyLocalService<C>,
    private val identifier: Any,
): LocalCharacteristic<C> by characteristic {
    override val descriptors: List<LocalDescriptor<C>> =
        characteristic.descriptors.map { CentralLocalDescriptor(it, this, identifier) }

    override val subscribers: StateFlow<Set<C>> =
        DerivedStateFlow(characteristic.subscribers) { set -> set.filterTo(mutableSetOf()) { it.identifier == identifier } }

    override val writes: SharedFlow<WriteRequest<C>> = CentralWrites(characteristic.writes, identifier)

    override suspend fun update(value: ByteArray, to: C?) {
        require(to == null || to.identifier == identifier) {
            "The characteristic belongs to the view of another central"
        }
        // Updates are sent to the central of the view only.
        val central = to ?: characteristic.subscribers.value.firstOrNull { it.identifier == identifier }
        if (central != null) {
            characteristic.update(value, central)
        }
    }

    override fun toString() = "Central$characteristic"
}

internal class CentralLocalDescriptor<C: Central<*>>(
    descriptor: LocalDescriptor<C>,
    override val characteristic: LocalCharacteristic<C>,
    identifier: Any,
): LocalDescriptor<C> by descriptor {
    override val writes: SharedFlow<WriteRequest<C>> = CentralWrites(descriptor.writes, identifier)
}
