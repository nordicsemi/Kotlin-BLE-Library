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

package no.nordicsemi.kotlin.ble.server.mock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.internal.CharacteristicDefinition
import no.nordicsemi.kotlin.ble.core.internal.DescriptorDefinition
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.server.Central
import no.nordicsemi.kotlin.ble.server.internal.GattServer
import no.nordicsemi.kotlin.ble.server.internal.GattServerEvent
import kotlin.uuid.Uuid

/**
 * A simulated platform layer of the local GATT server.
 *
 * It is used by the shared `LocalServerImpl` instead of a native GATT server. It does not
 * contain any logic of the GATT server itself: it only stores the definitions of the services,
 * turns actions of simulated centrals ([MockCentral]) into [GattServerEvent]s and delivers
 * the responses and notifications back to them.
 *
 * Requests of simulated centrals suspend until the server calls [respond]. Notifications and
 * indications are delivered immediately, and indications are confirmed automatically.
 *
 * @param ID The type of the central identifier.
 * @param C The type of the central, as seen by the GATT server.
 * @param state The state of Bluetooth on the simulated device. Defaults to always powered on.
 */
open class MockGattServer<ID: Any, C: Central<ID>>(
    override val state: StateFlow<Manager.State> = MutableStateFlow(Manager.State.POWERED_ON),
): GattServer<ID, C> {

    private class Pending<ID: Any, C: Central<ID>>(
        val central: MockCentral<ID, C>,
        val response: CompletableDeferred<Response>,
    )

    internal class Response(val status: OperationStatus, val value: ByteArray)

    private val _events = MutableSharedFlow<GattServerEvent<C>>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<GattServerEvent<C>> = _events.asSharedFlow()

    private val _services = MutableStateFlow<List<ServiceDefinition>>(emptyList())

    /**
     * The definitions of the services given in the last call to [setServices], for inspection.
     */
    val services: StateFlow<List<ServiceDefinition>> = _services.asStateFlow()

    private val _centrals = MutableStateFlow<List<MockCentral<ID, C>>>(emptyList())

    /** The simulated centrals that are currently connected. */
    val connectedMockCentrals: StateFlow<List<MockCentral<ID, C>>> = _centrals.asStateFlow()

    private val _connected = MutableStateFlow<List<C>>(emptyList())

    /** The same as [connectedMockCentrals], but as seen by the GATT server. */
    val connectedCentrals: StateFlow<List<C>> = _connected.asStateFlow()

    private val nextRequestId = MutableStateFlow(1)
    private val pending = MutableStateFlow<Map<Int, Pending<ID, C>>>(emptyMap())

    /**
     * Whether the server has been closed.
     */
    var isClosed: Boolean = false
        private set

    /**
     * Throws if the server cannot be used, e.g. when Bluetooth is off. Called by [setServices].
     *
     * Can be overridden to simulate other conditions, like a missing permission.
     *
     * @throws BluetoothUnavailableException if Bluetooth is not powered on.
     */
    protected open fun checkAvailable() {
        if (state.value != Manager.State.POWERED_ON) {
            throw BluetoothUnavailableException()
        }
    }

    override suspend fun setServices(services: List<ServiceDefinition>) {
        checkAvailable()
        _services.value = services.toList()
    }

    override fun respond(central: C, requestId: Int, status: OperationStatus, offset: Int, value: ByteArray) {
        val request = pending.value[requestId] ?: return
        if (request.central.identifier != central.identifier) return
        request.response.complete(Response(status, value.copyOf()))
    }

    override suspend fun notify(central: C, attribute: Int, value: ByteArray, confirm: Boolean) {
        val target = _centrals.value.firstOrNull { it.identifier == central.identifier }
            ?: throw GattOperationException(OperationStatus.RequestFailed)
        target.deliver(MockUpdate(attribute, value.copyOf(), isIndication = confirm))
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        _centrals.value.forEach { it.terminate(ConnectionState.Disconnected.Reason.TerminateLocalHost) }
        pending.value.values.forEach { it.response.complete(Response(OperationStatus.RequestFailed, ByteArray(0))) }
    }

    /**
     * Disconnects all simulated centrals.
     *
     * @param reason The reason reported to the centrals.
     */
    suspend fun disconnectAll(
        reason: ConnectionState.Disconnected.Reason = ConnectionState.Disconnected.Reason.TerminateLocalHost,
    ) {
        _centrals.value.forEach { it.disconnect(reason) }
    }

    // ---- Inspection helpers ----

    /**
     * Finds a service by UUID, including included services.
     *
     * @param uuid The UUID of the service.
     * @param index The index, if there are more services with the same UUID.
     * @return The `instanceId` of the service, or `null` if not found.
     */
    fun findService(uuid: Uuid, index: Int = 0): Int? =
        allServices().filter { it.uuid == uuid }.getOrNull(index)?.instanceId

    /**
     * Finds a characteristic by UUID.
     *
     * @param serviceUuid The UUID of the service containing the characteristic.
     * @param uuid The UUID of the characteristic.
     * @return The `instanceId` of the characteristic, or `null` if not found.
     */
    fun findCharacteristic(serviceUuid: Uuid, uuid: Uuid): Int? =
        allServices()
            .filter { it.uuid == serviceUuid }
            .firstNotNullOfOrNull { service -> service.characteristics.firstOrNull { it.uuid == uuid } }
            ?.instanceId

    /**
     * Finds a descriptor by UUID.
     *
     * @param serviceUuid The UUID of the service.
     * @param characteristicUuid The UUID of the characteristic.
     * @param uuid The UUID of the descriptor.
     * @return The `instanceId` of the descriptor, or `null` if not found.
     */
    fun findDescriptor(serviceUuid: Uuid, characteristicUuid: Uuid, uuid: Uuid): Int? =
        allServices()
            .filter { it.uuid == serviceUuid }
            .flatMap { it.characteristics }
            .filter { it.uuid == characteristicUuid }
            .firstNotNullOfOrNull { characteristic -> characteristic.descriptors.firstOrNull { it.uuid == uuid } }
            ?.instanceId

    /**
     * Finds the Client Characteristic Configuration descriptor of a characteristic.
     *
     * @param characteristic The `instanceId` of the characteristic.
     * @return The `instanceId` of the descriptor, or `null` if the characteristic does not have one.
     */
    fun findCccd(characteristic: Int): Int? =
        findCharacteristicDefinition(characteristic)
            ?.descriptors
            ?.firstOrNull { it.uuid == Descriptor.CLIENT_CHAR_CONF_UUID }
            ?.instanceId

    /**
     * Returns the definition of the characteristic with the given `instanceId`, or `null`.
     */
    fun findCharacteristicDefinition(characteristic: Int): CharacteristicDefinition? =
        allServices().flatMap { it.characteristics }.firstOrNull { it.instanceId == characteristic }

    /**
     * Returns the definition of the descriptor with the given `instanceId`, or `null`.
     */
    fun findDescriptorDefinition(descriptor: Int): DescriptorDefinition? =
        allServices()
            .flatMap { it.characteristics }
            .flatMap { it.descriptors }
            .firstOrNull { it.instanceId == descriptor }

    private fun allServices(): List<ServiceDefinition> {
        fun ServiceDefinition.flatten(): List<ServiceDefinition> =
            listOf(this) + includedServices.flatMap { it.flatten() }
        return _services.value.flatMap { it.flatten() }
    }

    // ---- API for simulated centrals ----

    internal fun attach(central: MockCentral<ID, C>) {
        check(!isClosed) { "The GATT server is closed" }
        _centrals.update { it + central }
        _connected.update { it + central.asCentral }
    }

    internal fun detach(central: MockCentral<ID, C>) {
        _connected.update { list -> list.filterNot { it.identifier == central.identifier } }
        _centrals.update { list -> list.filterNot { it === central } }
        pending.value.values
            .filter { it.central === central }
            .forEach { it.response.complete(Response(OperationStatus.RequestFailed, ByteArray(0))) }
    }

    internal suspend fun emit(event: GattServerEvent<C>) {
        _events.emit(event)
    }

    internal fun tryEmit(event: GattServerEvent<C>) {
        _events.tryEmit(event)
    }

    internal fun newRequestId(): Int = nextRequestId.getAndUpdate { it + 1 }

    /**
     * Emits the event created for a new request ID, and suspends until [respond] is called.
     */
    internal suspend fun request(
        central: MockCentral<ID, C>,
        event: (requestId: Int) -> GattServerEvent<C>,
    ): Response {
        val requestId = newRequestId()
        val response = CompletableDeferred<Response>()
        pending.update { it + (requestId to Pending(central, response)) }
        try {
            _events.emit(event(requestId))
            return response.await()
        } finally {
            pending.update { it - requestId }
        }
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 1024
    }
}
