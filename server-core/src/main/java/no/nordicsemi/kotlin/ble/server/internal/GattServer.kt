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

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.server.Central

/**
 * The platform layer of the local GATT server: a native one, or a mock.
 *
 * It only translates between the platform API and [GattServerEvent]s. All the logic, like
 * handling of Client Characteristic Configuration descriptors, long and prepared writes,
 * handlers, values and subscriptions, is in [LocalServerImpl].
 *
 * Attributes (services, characteristics and descriptors) are identified by the `instanceId`
 * of their [ServiceDefinition], which is unique within a GATT server.
 */
interface GattServer<ID: Any, C: Central<ID>> {

    /** The state of Bluetooth on the local device. */
    val state: StateFlow<Manager.State>

    /**
     * Requests and events from centrals. A hot flow, buffered so that no event is lost while
     * the collector is busy.
     */
    val events: SharedFlow<GattServerEvent<C>>

    /**
     * Makes the given services available, replacing the current ones.
     *
     * Suspends until the platform confirmed that all services were added.
     *
     * @throws no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException if Bluetooth is
     * not available.
     * @throws SecurityException if the required permission is denied.
     */
    suspend fun setServices(services: List<ServiceDefinition>)

    /**
     * Sends a response to a request received in [GattServerEvent.ReadRequested],
     * [GattServerEvent.WriteRequested] or [GattServerEvent.ExecuteWrite].
     *
     * @param central The central that sent the request.
     * @param requestId The request ID from the event.
     * @param status The status.
     * @param offset The offset from the event.
     * @param value The value of the attribute, starting from [offset], to be sent on success,
     * for read requests. Empty for write requests.
     */
    fun respond(central: C, requestId: Int, status: OperationStatus, offset: Int, value: ByteArray)

    /**
     * Sends a notification or an indication to a central.
     *
     * The platform serializes calls to the same central. Suspends until the notification has been
     * sent, or the indication confirmed.
     *
     * @param central The central.
     * @param attribute The `instanceId` of the characteristic.
     * @param value The value, no longer than [Central.maximumUpdateValueLength].
     * @param confirm `true` to send an indication, `false` for a notification.
     */
    suspend fun notify(central: C, attribute: Int, value: ByteArray, confirm: Boolean)

    /** Stops the server and releases its resources. Closes all connections. */
    fun close()
}

/**
 * An event reported by the platform layer, [GattServer].
 */
sealed class GattServerEvent<C: Central<*>> {

    /**
     * A central wants to read an attribute. Must be answered using [GattServer.respond].
     *
     * @property offset The offset for long reads, 0 for the first request.
     */
    class ReadRequested<C: Central<*>>(
        val central: C,
        val requestId: Int,
        val attribute: Int,
        val offset: Int,
    ): GattServerEvent<C>()

    /**
     * A central wants to write an attribute, including Client Characteristic Configuration
     * descriptors, which are handled by the [LocalServerImpl].
     *
     * If [responseNeeded], must be answered using [GattServer.respond]. If [prepared], the value
     * is a part of a long or reliable write, to be executed on [ExecuteWrite].
     */
    class WriteRequested<C: Central<*>>(
        val central: C,
        val requestId: Int,
        val attribute: Int,
        val offset: Int,
        val value: ByteArray,
        val prepared: Boolean,
        val responseNeeded: Boolean,
    ): GattServerEvent<C>()

    /**
     * A central wants to execute or cancel the queued prepared writes. Must be answered using
     * [GattServer.respond].
     */
    class ExecuteWrite<C: Central<*>>(
        val central: C,
        val requestId: Int,
        val execute: Boolean,
    ): GattServerEvent<C>()

    /**
     * A central enabled or disabled notifications or indications.
     *
     * For platforms that do not expose the Client Characteristic Configuration descriptor
     * (iOS). On Android and in the mock, the descriptor is written using [WriteRequested].
     */
    class SubscriptionChanged<C: Central<*>>(
        val central: C,
        val attribute: Int,
        val notifications: Boolean,
        val indications: Boolean,
    ): GattServerEvent<C>()

    /** A central disconnected, or is no longer reachable. Its state should be dropped. */
    class CentralDisconnected<C: Central<*>>(val central: C): GattServerEvent<C>()
}
