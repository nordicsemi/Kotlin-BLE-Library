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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.core.ServerScope
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import no.nordicsemi.kotlin.ble.core.internal.ServerScopeImpl
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.server.AnyLocalService
import no.nordicsemi.kotlin.ble.server.Client
import no.nordicsemi.kotlin.ble.server.LocalServer
import no.nordicsemi.kotlin.ble.server.LocalService
import no.nordicsemi.kotlin.ble.server.ReadRequest
import no.nordicsemi.kotlin.ble.server.ReadResponse
import no.nordicsemi.kotlin.ble.server.WriteRequest
import no.nordicsemi.kotlin.ble.server.WriteResponse
import no.nordicsemi.kotlin.ble.server.exception.ClientNotConnectedException
import no.nordicsemi.kotlin.ble.server.exception.OperationFailedException
import no.nordicsemi.kotlin.log.Log
import kotlin.time.Duration.Companion.seconds

/**
 * Base implementation of [LocalServer], shared by native and mock implementations.
 *
 * The platform layer, [GattServer], only translates platform callbacks to [GattServerEvent]s.
 * They are processed here, one by one, in the order they were received. This includes handling
 * Client Characteristic Configuration descriptors, long and prepared writes, handlers, values
 * and subscriptions.
 *
 * Handlers set using `onRead` and `onWrite` are called from the event loop, so a handler that
 * suspends for a long time delays processing of requests from other clients.
 *
 * @param ID The type of the client identifier.
 * @property scope The coroutine scope.
 * @property server The platform layer.
 */
open class LocalServerImpl<ID: Any>(
    protected val scope: CoroutineScope,
    protected val server: GattServer,
): LocalServer<ID> {
    override var logger: Log.Sink<Layer>? = null

    /**
     * The scope of the jobs of this manager, e.g. the event loop.
     *
     * It is a child of [scope], so that it is cancelled when [scope] is canceled, but it can be
     * cancelled on its own, without cancelling [scope], when the manager is closed.
     */
    protected val internalScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val closed = MutableStateFlow(false)
    private val setupMutex = Mutex()
    private val _services = MutableStateFlow<List<LocalService>>(emptyList())

    /** The characteristics and descriptors of the current services, by their instance ID. */
    private val attributes = MutableStateFlow<Map<Int, LocalAttribute>>(emptyMap())

    /**
     * Prepared writes of each client. Accessed only by the event loop.
     */
    private val preparedWrites = mutableMapOf<Client<*>, MutableList<PreparedWrite>>()

    private val host = object: LocalServerHost {
        override fun trace(message: () -> String) {
            logger?.trace(Layer.GATT) { message() }
        }

        override suspend fun notify(client: Client<*>, characteristic: Int, value: ByteArray, confirm: Boolean) =
            sendUpdate(client, characteristic, value, confirm)
    }

    /**
     * The locks of the clients. The Bluetooth stack allows only one notification or indication
     * to be in flight per client, so the next one is sent when the previous one was reported.
     */
    private val clientLocks = MutableStateFlow<Map<Client<*>, Mutex>>(emptyMap())

    private fun lockOf(client: Client<*>): Mutex =
        clientLocks.value[client] ?: clientLocks
            .updateAndGet { map -> if (client in map) map else map + (client to Mutex()) }
            .getValue(client)

    /**
     * Sends a notification or an indication and waits until it was sent, or confirmed.
     *
     * The call enters the lock of the client, starts sending using the platform layer and then
     * waits for the result, which is reported as an event.
     */
    private suspend fun sendUpdate(client: Client<*>, characteristic: Int, value: ByteArray, confirm: Boolean) {
        lockOf(client).withLock {
            coroutineScope {
                // Subscribe to the events before sending, so that the result is not missed.
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(NOTIFICATION_TIMEOUT) {
                        server.events.first {
                            (it is GattServerEvent.NotificationSent && it.client == client) ||
                            (it is GattServerEvent.ClientDisconnected && it.client == client)
                        }
                    }
                }
                server.notify(client, characteristic, value, confirm)
                when (val event = result.await()) {
                    null -> throw OperationFailedException(OperationStatus.ConnectionTimeout)
                    is GattServerEvent.NotificationSent -> {
                        if (event.status != OperationStatus.Success) throw OperationFailedException(event.status)
                    }
                    else -> throw ClientNotConnectedException()
                }
            }
        }
    }

    override val state get() = server.state
    override val services: StateFlow<List<LocalService>> get() = _services

    /** The views of the services for each client. */
    private val clientServices = MutableStateFlow<Map<ID, StateFlow<List<LocalService>>>>(emptyMap())

    override fun services(identifier: ID): StateFlow<List<LocalService>> =
        clientServices.value[identifier] ?: clientServices
            .updateAndGet { map -> if (identifier in map) map else map + (identifier to ClientServices(_services, identifier)) }
            .getValue(identifier)

    init {
        // Make sure the manager gets closed when its scope gets canceled.
        // This coroutine gets cancelled when close() is called or when the scope gets canceled.
        internalScope.launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { close() } }
        }
        // The event loop. It is started undispatched, so that the manager is subscribed to the
        // events before the constructor returns and no event is lost.
        internalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            server.events.collect { event ->
                try {
                    process(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger?.error(Layer.GATT) { "Processing $event failed: $e" }
                }
            }
        }
    }

    override suspend fun setup(builder: ServerScope.() -> Unit): List<LocalService> {
        if (closed.value) throw ManagerClosedException()
        val definitions = ServerScopeImpl().apply(builder).build()

        return setupMutex.withLock {
            if (closed.value) throw ManagerClosedException()

            logger?.info(Layer.GATT) { "Setting up ${definitions.size} service(s)" }
            server.clearServices()
            try {
                definitions.forEach { addService(it, primary = true) }
            } catch (e: Throwable) {
                // Do not leave the server with some of the services only.
                try {
                    server.clearServices()
                } catch (_: Exception) {
                    // The server is not available, so there is nothing to clean up.
                }
                throw e
            }

            // The manager may have been closed while the services were being added.
            if (closed.value) throw ManagerClosedException()

            val newServices = definitions.map { SharedLocalService(it, this, host) }
            val newAttributes = mutableMapOf<Int, LocalAttribute>()
            fun addAttributes(service: AnyLocalService) {
                service.characteristics.forEach { characteristic ->
                    characteristic as SharedLocalCharacteristic
                    newAttributes[characteristic.instanceId] = characteristic
                    characteristic.localDescriptors.forEach { newAttributes[it.instanceId] = it }
                }
                service.includedServices.forEach { addAttributes(it) }
            }
            newServices.forEach { addAttributes(it) }

            val oldServices = _services.value
            attributes.value = newAttributes
            _services.value = newServices
            oldServices.forEach { (it as SharedLocalService).invalidate() }
            newServices
        }
    }

    override suspend fun removeServices() {
        if (closed.value) throw ManagerClosedException()
        if (_services.value.isEmpty()) return
        val _ = setup { }
    }

    /**
     * Adds the service, and the services it includes, and waits for the platform to confirm it.
     */
    private suspend fun addService(definition: ServiceDefinition, primary: Boolean) {
        definition.includedServices.forEach { addService(it, primary = false) }
        coroutineScope {
            val result = CompletableDeferred<GattServerEvent.ServiceAdded>()
            try {
                // Subscribe to the events before adding, so that the result is not missed.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    result.complete(
                        server.events
                            .filterIsInstance<GattServerEvent.ServiceAdded>()
                            .first { it.service == definition.instanceId }
                    )
                }
                // The result will never come if the server was closed, or Bluetooth was turned off.
                launch {
                    closed.first { it }
                    result.completeExceptionally(ManagerClosedException())
                }
                launch {
                    server.state.first { it != Manager.State.POWERED_ON }
                    result.completeExceptionally(BluetoothUnavailableException())
                }

                server.addService(definition, primary)

                val event = withTimeoutOrNull(SERVICE_ADDED_TIMEOUT) { result.await() }
                    ?: throw OperationFailedException(OperationStatus.RequestFailed)
                if (event.status != OperationStatus.Success) throw OperationFailedException(event.status)
            } finally {
                coroutineContext.cancelChildren()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        logger?.info(Layer.GATT) { "Closing the local server" }
        internalScope.cancel()
        try {
            server.close()
        } catch (e: Exception) {
            logger?.warn(Layer.GATT) { "Closing the GATT server failed: $e" }
        }
        val oldAttributes = attributes.value
        _services.value.forEach { (it as SharedLocalService).invalidate() }
        _services.value = emptyList()
        attributes.value = emptyMap()
        oldAttributes.values.forEach { (it as? SharedLocalCharacteristic)?.removeAllClients() }
    }

    // ----- Event loop -----

    private suspend fun process(event: GattServerEvent) {
        when (event) {
            is GattServerEvent.ReadRequested -> onReadRequested(event)
            is GattServerEvent.WriteRequested -> onWriteRequested(event)
            is GattServerEvent.ExecuteWrite -> onExecuteWrite(event)
            is GattServerEvent.SubscriptionChanged -> onSubscriptionChanged(event)
            is GattServerEvent.ClientDisconnected -> onClientDisconnected(event)
            // Handled by the call that is waiting for it.
            is GattServerEvent.NotificationSent,
            is GattServerEvent.ServiceAdded -> {}
        }
    }

    private suspend fun onReadRequested(event: GattServerEvent.ReadRequested) {
        val client = event.client
        val attribute = attributes.value[event.attribute]
        if (attribute == null) {
            logger?.warn(Layer.GATT) { "Read request from $client for unknown attribute ${event.attribute}" }
            server.respond(client, event.requestId, OperationStatus.InvalidHandle, event.offset, EMPTY)
            return
        }
        logger?.trace(Layer.GATT) { "Read request from $client: $attribute, offset ${event.offset}" }

        val status = checkReadable(attribute)
        if (status != null) {
            server.respond(client, event.requestId, status, event.offset, EMPTY)
            return
        }

        val value: ByteArray
        if (attribute is SharedLocalDescriptor && attribute.isClientCharacteristicConfiguration) {
            val cccd = attribute.characteristic.cccdValue(client)
            value = byteArrayOf((cccd and 0xFF).toByte(), ((cccd shr 8) and 0xFF).toByte())
        } else {
            val response = try {
                attribute.state.read(ReadRequest(client))
            } catch (e: Exception) {
                // A CancellationException thrown by the handler fails just that request, unless
                // the manager itself has been cancelled.
                currentCoroutineContext().ensureActive()
                logger?.error(Layer.GATT) { "Read handler of $attribute failed: $e" }
                server.respond(client, event.requestId, OperationStatus.UnlikelyError, event.offset, EMPTY)
                return
            }
            when (response) {
                is ReadResponse.Failure -> {
                    server.respond(client, event.requestId, response.status, event.offset, EMPTY)
                    return
                }
                is ReadResponse.Success -> value = response.value
            }
        }

        if (event.offset > value.size) {
            server.respond(client, event.requestId, OperationStatus.InvalidOffset, event.offset, EMPTY)
            return
        }
        server.respond(
            client = client,
            requestId = event.requestId,
            status = OperationStatus.Success,
            offset = event.offset,
            value = if (event.offset == 0) value else value.copyOfRange(event.offset, value.size),
        )
    }

    private suspend fun onWriteRequested(event: GattServerEvent.WriteRequested) {
        val client = event.client
        val attribute = attributes.value[event.attribute]
        if (attribute == null) {
            logger?.warn(Layer.GATT) { "Write request from $client for unknown attribute ${event.attribute}" }
            respondToWrite(event, OperationStatus.InvalidHandle)
            return
        }
        logger?.trace(Layer.GATT) {
            "Write request from $client: $attribute, ${event.value.size} bytes" +
            (if (event.prepared) " (prepared, offset ${event.offset})" else "")
        }

        if (event.prepared) {
            val status = checkWritable(attribute, withResponse = true)
                ?: queuePreparedWrite(client, attribute, event.offset, event.value)
            // The platform echoes the value of a successful prepared write.
            server.respond(client, event.requestId, status ?: OperationStatus.Success, event.offset, event.value)
            return
        }

        val status = write(client, attribute, event.value, event.responseNeeded)
        respondToWrite(event, status)
    }

    private fun respondToWrite(event: GattServerEvent.WriteRequested, status: OperationStatus) {
        if (event.responseNeeded) {
            server.respond(event.client, event.requestId, status, event.offset, EMPTY)
        }
    }

    private suspend fun onExecuteWrite(event: GattServerEvent.ExecuteWrite) {
        val client = event.client
        val queue = preparedWrites.remove(client).orEmpty()
        if (!event.execute) {
            logger?.trace(Layer.GATT) { "Prepared writes of $client cancelled (${queue.size} fragment(s))" }
            server.respond(client, event.requestId, OperationStatus.Success, 0, EMPTY)
            return
        }
        logger?.trace(Layer.GATT) { "Executing prepared writes of $client (${queue.size} fragment(s))" }

        // Assemble the fragments of each attribute.
        val assembled = mutableListOf<Pair<LocalAttribute, ByteArray>>()
        val fragments = queue.groupBy { it.attribute }
        for ((attribute, list) in fragments) {
            var value = byteArrayOf()
            for (fragment in list) {
                if (fragment.offset != value.size) {
                    server.respond(client, event.requestId, OperationStatus.InvalidOffset, 0, EMPTY)
                    return
                }
                value += fragment.value
            }
            if (value.size > MAX_ATTRIBUTE_LENGTH) {
                server.respond(client, event.requestId, OperationStatus.InvalidAttributeLength, 0, EMPTY)
                return
            }
            assembled += attribute to value
        }

        for ((attribute, value) in assembled) {
            val status = write(client, attribute, value, withResponse = true)
            if (status != OperationStatus.Success) {
                server.respond(client, event.requestId, status, 0, EMPTY)
                return
            }
        }
        server.respond(client, event.requestId, OperationStatus.Success, 0, EMPTY)
    }

    private fun onSubscriptionChanged(event: GattServerEvent.SubscriptionChanged) {
        val characteristic = attributes.value[event.attribute] as? SharedLocalCharacteristic
        if (characteristic == null) {
            logger?.warn(Layer.GATT) { "Subscription change for unknown characteristic ${event.attribute}" }
            return
        }
        val value = (if (event.notifications) NOTIFICATIONS else 0) or
                    (if (event.indications) INDICATIONS else 0)
        if (!isCccdValueSupported(characteristic, value)) {
            logger?.warn(Layer.GATT) { "Subscription change of $characteristic not supported: $value" }
            return
        }
        logger?.info(Layer.GATT) { "${event.client} ${if (value == 0) "unsubscribed from" else "subscribed to"} $characteristic" }
        characteristic.setCccdValue(event.client, value)
    }

    private fun onClientDisconnected(event: GattServerEvent.ClientDisconnected) {
        val client = event.client
        logger?.info(Layer.GATT) { "$client disconnected" }
        preparedWrites.remove(client)
        clientLocks.update { it - client }
        attributes.value.values.forEach { (it as? SharedLocalCharacteristic)?.removeClient(client) }
    }

    // ----- Helpers -----

    /** Returns an error status if the attribute can't be read, or `null` otherwise. */
    private fun checkReadable(attribute: LocalAttribute): OperationStatus? = when {
        attribute is SharedLocalCharacteristic && CharacteristicProperty.READ !in attribute.properties ->
            OperationStatus.ReadNotPermitted
        attribute is SharedLocalDescriptor && !attribute.isClientCharacteristicConfiguration && !attribute.isReadPermitted ->
            OperationStatus.ReadNotPermitted
        else -> null
    }

    /** Returns an error status if the attribute can't be written, or `null` otherwise. */
    private fun checkWritable(attribute: LocalAttribute, withResponse: Boolean): OperationStatus? = when {
        attribute is SharedLocalCharacteristic -> {
            val required = if (withResponse) CharacteristicProperty.WRITE else CharacteristicProperty.WRITE_WITHOUT_RESPONSE
            if (required in attribute.properties) null else OperationStatus.WriteNotPermitted
        }
        attribute is SharedLocalDescriptor -> {
            // Descriptors are always written with response.
            if (attribute.isWritePermitted && withResponse) null else OperationStatus.WriteNotPermitted
        }
        else -> OperationStatus.WriteNotPermitted
    }

    /** Adds a fragment to the prepared writes of the client. Returns an error or `null`. */
    private fun queuePreparedWrite(client: Client<*>, attribute: LocalAttribute, offset: Int, value: ByteArray): OperationStatus? {
        val queue = preparedWrites.getOrPut(client) { mutableListOf() }
        if (queue.size >= PREPARED_WRITE_QUEUE_LIMIT) {
            return OperationStatus.PrepareQueueFull
        }
        queue += PreparedWrite(attribute, offset, value)
        return null
    }

    /**
     * Executes a write: validates the request and handles the CCCD, or passes the value to
     * the attribute.
     *
     * @return The status to be sent to the client.
     */
    private suspend fun write(client: Client<*>, attribute: LocalAttribute, value: ByteArray, withResponse: Boolean): OperationStatus {
        // The attribute may have been removed, if the services were replaced in the meantime.
        if (attributes.value[attribute.instanceId] !== attribute) {
            return OperationStatus.InvalidHandle
        }
        checkWritable(attribute, withResponse)?.let { return it }
        if (value.size > MAX_ATTRIBUTE_LENGTH) {
            return OperationStatus.InvalidAttributeLength
        }
        if (attribute is SharedLocalDescriptor && attribute.isClientCharacteristicConfiguration) {
            return writeCccd(client, attribute, value)
        }
        val response = try {
            attribute.state.write(WriteRequest(client, value, withResponse))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger?.error(Layer.GATT) { "Write handler of $attribute failed: $e" }
            return OperationStatus.UnlikelyError
        }
        return when (response) {
            is WriteResponse.Success -> OperationStatus.Success
            is WriteResponse.Failure -> response.status
        }
    }

    /** Handles a write to the Client Characteristic Configuration descriptor. */
    private fun writeCccd(client: Client<*>, descriptor: SharedLocalDescriptor, value: ByteArray): OperationStatus {
        if (value.size != 2) {
            return OperationStatus.InvalidAttributeLength
        }
        val cccd = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        val characteristic = descriptor.characteristic
        if (!isCccdValueSupported(characteristic, cccd)) {
            return OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured
        }
        logger?.info(Layer.GATT) {
            when (cccd) {
                0 -> "$client disabled updates of $characteristic"
                NOTIFICATIONS -> "$client enabled notifications of $characteristic"
                else -> "$client enabled indications of $characteristic"
            }
        }
        characteristic.setCccdValue(client, cccd)
        return OperationStatus.Success
    }

    /** Checks whether the CCCD value is valid and the characteristic supports it. */
    private fun isCccdValueSupported(characteristic: SharedLocalCharacteristic, cccd: Int) = when (cccd) {
        0 -> true
        NOTIFICATIONS -> CharacteristicProperty.NOTIFY in characteristic.properties
        INDICATIONS -> CharacteristicProperty.INDICATE in characteristic.properties
        else -> false
    }

    /** A fragment of a prepared (long or reliable) write. */
    private class PreparedWrite(
        val attribute: LocalAttribute,
        val offset: Int,
        val value: ByteArray,
    )

    private companion object {
        val EMPTY = byteArrayOf()

        /** Bit 0 of the Client Characteristic Configuration. */
        const val NOTIFICATIONS = 0x0001

        /** Bit 1 of the Client Characteristic Configuration. */
        const val INDICATIONS = 0x0002

        /** The maximum number of fragments of prepared writes queued by a single client. */
        const val PREPARED_WRITE_QUEUE_LIMIT = 64

        /**
         * The time to wait for a notification to be sent, or an indication confirmed.
         *
         * The ATT transaction timeout is 30 seconds, after which the link is terminated.
         */
        val NOTIFICATION_TIMEOUT = 35.seconds

        /** The time to wait for the platform to confirm that a service was added. */
        val SERVICE_ADDED_TIMEOUT = 10.seconds
    }
}
