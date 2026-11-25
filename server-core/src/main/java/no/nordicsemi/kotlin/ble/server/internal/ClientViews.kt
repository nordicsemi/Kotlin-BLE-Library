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
import no.nordicsemi.kotlin.ble.server.Client
import no.nordicsemi.kotlin.ble.server.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.LocalDescriptor
import no.nordicsemi.kotlin.ble.server.LocalIncludedService
import no.nordicsemi.kotlin.ble.server.LocalService
import no.nordicsemi.kotlin.ble.server.WriteRequest

// Views of the local services, as seen by one client. They share the values, handlers and
// subscriptions with the services of the server, but the requests and the subscribers are limited
// to the client, and updates are sent to the client only.

/** The views of the services of the server, as seen by one client. */
internal class ClientServices(
    private val source: StateFlow<List<LocalService>>,
    private val identifier: Any,
): StateFlow<List<LocalService>> {
    private var cached: Pair<List<LocalService>, List<LocalService>>? = null

    override val value: List<LocalService> get() = viewsOf(source.value)
    override val replayCache: List<List<LocalService>> get() = listOf(value)

    /** The views are created once for each list of services, so they are the same when asked again. */
    private fun viewsOf(services: List<LocalService>): List<LocalService> {
        cached?.takeIf { it.first === services }?.let { return it.second }
        val views = services.map { ClientLocalService(it, identifier) }
        cached = services to views
        return views
    }

    override suspend fun collect(collector: FlowCollector<List<LocalService>>): Nothing {
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

/** A [SharedFlow] of the requests sent by one client. */
private class ClientWrites(
    private val source: SharedFlow<WriteRequest>,
    private val identifier: Any,
): SharedFlow<WriteRequest> {
    override val replayCache: List<WriteRequest>
        get() = source.replayCache.filter { it.client.identifier == identifier }

    override suspend fun collect(collector: FlowCollector<WriteRequest>): Nothing {
        source.collect { request ->
            if (request.client.identifier == identifier) collector.emit(request)
        }
    }
}

internal class ClientLocalService(
    private val service: LocalService,
    identifier: Any,
): LocalService by service {
    override val characteristics: List<LocalCharacteristic> =
        service.characteristics.map { ClientLocalCharacteristic(it, this, identifier) }
    override val includedServices: List<LocalIncludedService> =
        service.includedServices.map { ClientIncludedService(it, this, identifier) }

    override fun toString() = "ClientService($service)"
}

internal class ClientIncludedService(
    included: LocalIncludedService,
    override val service: AnyLocalService,
    identifier: Any,
): LocalIncludedService by included {
    override val characteristics: List<LocalCharacteristic> =
        included.characteristics.map { ClientLocalCharacteristic(it, this, identifier) }
    override val includedServices: List<LocalIncludedService> =
        included.includedServices.map { ClientIncludedService(it, this, identifier) }
}

internal class ClientLocalCharacteristic(
    private val characteristic: LocalCharacteristic,
    override val service: AnyLocalService,
    private val identifier: Any,
): LocalCharacteristic by characteristic {
    override val descriptors: List<LocalDescriptor> =
        characteristic.descriptors.map { ClientLocalDescriptor(it, this, identifier) }

    override val subscribers: StateFlow<Set<Client<*>>> =
        DerivedStateFlow(characteristic.subscribers) { set -> set.filterTo(mutableSetOf()) { it.identifier == identifier } }

    override val writes: SharedFlow<WriteRequest> = ClientWrites(characteristic.writes, identifier)

    override suspend fun update(value: ByteArray) {
        // Updates are sent to the client of the view only.
        val client = characteristic.subscribers.value.firstOrNull { it.identifier == identifier }
        checkNotNull(client) { "The client has not subscribed to this characteristic" }
        (characteristic as TargetedUpdate).update(value, client)
    }

    override fun toString() = "Client$characteristic"
}

internal class ClientLocalDescriptor(
    descriptor: LocalDescriptor,
    override val characteristic: LocalCharacteristic,
    identifier: Any,
): LocalDescriptor by descriptor {
    override val writes: SharedFlow<WriteRequest> = ClientWrites(descriptor.writes, identifier)
}
