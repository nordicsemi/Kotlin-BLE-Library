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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.ServerScope
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import no.nordicsemi.kotlin.ble.core.internal.ServerScopeImpl
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.server.AnyLocalService
import no.nordicsemi.kotlin.ble.server.Central
import no.nordicsemi.kotlin.ble.server.LocalService
import no.nordicsemi.kotlin.ble.server.LocalServer
import no.nordicsemi.kotlin.ble.server.ReadRequest
import no.nordicsemi.kotlin.ble.server.ReadResponse
import no.nordicsemi.kotlin.ble.server.WriteRequest
import no.nordicsemi.kotlin.ble.server.WriteResponse
import no.nordicsemi.kotlin.log.Log

/**
 * Base implementation of [LocalServer], shared by native and mock implementations.
 *
 * The platform layer, [GattServer], only translates platform callbacks to [GattServerEvent]s.
 * They are processed here, one by one, in the order they were received. This includes handling
 * Client Characteristic Configuration descriptors, long and prepared writes, handlers, values
 * and subscriptions.
 *
 * Handlers set using `onRead` and `onWrite` are called from the event loop, so a handler that
 * suspends for a long time delays processing of requests from other centrals.
 *
 * @param ID The type of the central identifier.
 * @param C The type of the central.
 * @property scope The coroutine scope.
 * @property server The platform layer.
 */
open class LocalServerImpl<ID: Any, C: Central<ID>>(
    protected val scope: CoroutineScope,
    protected val server: GattServer<ID, C>,
): LocalServer<ID, C> {
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
    private val _services = MutableStateFlow<List<LocalService<C>>>(emptyList())

    /** The characteristics and descriptors of the current services, by their instance ID. */
    private val attributes = MutableStateFlow<Map<Int, LocalAttribute<C>>>(emptyMap())

    /**
     * Prepared writes of each central. Accessed only by the event loop.
     */
    private val preparedWrites = mutableMapOf<C, MutableList<PreparedWrite<C>>>()

    private val host = object: LocalServerHost<C> {
        override fun trace(message: () -> String) {
            logger?.trace(Layer.GATT) { message() }
        }

        override suspend fun notify(central: C, characteristic: Int, value: ByteArray, confirm: Boolean) =
            server.notify(central, characteristic, value, confirm)
    }

    override val state get() = server.state
    override val services: StateFlow<List<LocalService<C>>> get() = _services

    /** The views of the services for each central. */
    private val centralServices = MutableStateFlow<Map<ID, StateFlow<List<LocalService<C>>>>>(emptyMap())

    override fun services(identifier: ID): StateFlow<List<LocalService<C>>> =
        centralServices.value[identifier] ?: centralServices
            .updateAndGet { map -> if (identifier in map) map else map + (identifier to CentralServices(_services, identifier)) }
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

    override suspend fun setup(builder: ServerScope.() -> Unit): List<LocalService<C>> {
        if (closed.value) throw ManagerClosedException()
        val definitions = ServerScopeImpl().apply(builder).build()

        return setupMutex.withLock {
            if (closed.value) throw ManagerClosedException()

            logger?.info(Layer.GATT) { "Setting up ${definitions.size} service(s)" }
            server.setServices(definitions)

            // The manager may have been closed while the services were being added.
            if (closed.value) throw ManagerClosedException()

            val newServices = definitions.map { BaseLocalService(it, this, host) }
            val newAttributes = mutableMapOf<Int, LocalAttribute<C>>()
            fun addAttributes(service: AnyLocalService<C>) {
                service.characteristics.forEach { characteristic ->
                    characteristic as BaseLocalCharacteristic<C>
                    newAttributes[characteristic.instanceId] = characteristic
                    characteristic.localDescriptors.forEach { newAttributes[it.instanceId] = it }
                }
                service.includedServices.forEach { addAttributes(it) }
            }
            newServices.forEach { addAttributes(it) }

            val oldServices = _services.value
            attributes.value = newAttributes
            _services.value = newServices
            oldServices.forEach { (it as BaseLocalService<C>).invalidate() }
            newServices
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
        _services.value.forEach { (it as BaseLocalService<C>).invalidate() }
        _services.value = emptyList()
        attributes.value = emptyMap()
        oldAttributes.values.forEach { (it as? BaseLocalCharacteristic<C>)?.removeAllCentrals() }
    }

    // ----- Event loop -----

    private suspend fun process(event: GattServerEvent<C>) {
        when (event) {
            is GattServerEvent.ReadRequested -> onReadRequested(event)
            is GattServerEvent.WriteRequested -> onWriteRequested(event)
            is GattServerEvent.ExecuteWrite -> onExecuteWrite(event)
            is GattServerEvent.SubscriptionChanged -> onSubscriptionChanged(event)
            is GattServerEvent.CentralDisconnected -> onCentralDisconnected(event)
        }
    }

    private suspend fun onReadRequested(event: GattServerEvent.ReadRequested<C>) {
        val central = event.central
        val attribute = attributes.value[event.attribute]
        if (attribute == null) {
            logger?.warn(Layer.GATT) { "Read request from $central for unknown attribute ${event.attribute}" }
            server.respond(central, event.requestId, OperationStatus.InvalidHandle, event.offset, EMPTY)
            return
        }
        logger?.trace(Layer.GATT) { "Read request from $central: $attribute, offset ${event.offset}" }

        val status = checkReadable(attribute)
        if (status != null) {
            server.respond(central, event.requestId, status, event.offset, EMPTY)
            return
        }

        val value: ByteArray
        if (attribute is BaseLocalDescriptor<C> && attribute.isClientCharacteristicConfiguration) {
            val cccd = attribute.characteristic.cccdValue(central)
            value = byteArrayOf((cccd and 0xFF).toByte(), ((cccd shr 8) and 0xFF).toByte())
        } else {
            val response = try {
                attribute.state.read(ReadRequest(central))
            } catch (e: Exception) {
                // A CancellationException thrown by the handler fails just that request, unless
                // the manager itself has been cancelled.
                currentCoroutineContext().ensureActive()
                logger?.error(Layer.GATT) { "Read handler of $attribute failed: $e" }
                server.respond(central, event.requestId, OperationStatus.UnlikelyError, event.offset, EMPTY)
                return
            }
            when (response) {
                is ReadResponse.Failure -> {
                    server.respond(central, event.requestId, response.status, event.offset, EMPTY)
                    return
                }
                is ReadResponse.Success -> value = response.value
            }
        }

        if (event.offset > value.size) {
            server.respond(central, event.requestId, OperationStatus.InvalidOffset, event.offset, EMPTY)
            return
        }
        server.respond(
            central = central,
            requestId = event.requestId,
            status = OperationStatus.Success,
            offset = event.offset,
            value = if (event.offset == 0) value else value.copyOfRange(event.offset, value.size),
        )
    }

    private suspend fun onWriteRequested(event: GattServerEvent.WriteRequested<C>) {
        val central = event.central
        val attribute = attributes.value[event.attribute]
        if (attribute == null) {
            logger?.warn(Layer.GATT) { "Write request from $central for unknown attribute ${event.attribute}" }
            respondToWrite(event, OperationStatus.InvalidHandle)
            return
        }
        logger?.trace(Layer.GATT) {
            "Write request from $central: $attribute, ${event.value.size} bytes" +
            (if (event.prepared) " (prepared, offset ${event.offset})" else "")
        }

        if (event.prepared) {
            val status = checkWritable(attribute, withResponse = true)
                ?: queuePreparedWrite(central, attribute, event.offset, event.value)
            // The platform echoes the value of a successful prepared write.
            server.respond(central, event.requestId, status ?: OperationStatus.Success, event.offset, event.value)
            return
        }

        val status = write(central, attribute, event.value, event.responseNeeded)
        respondToWrite(event, status)
    }

    private fun respondToWrite(event: GattServerEvent.WriteRequested<C>, status: OperationStatus) {
        if (event.responseNeeded) {
            server.respond(event.central, event.requestId, status, event.offset, EMPTY)
        }
    }

    private suspend fun onExecuteWrite(event: GattServerEvent.ExecuteWrite<C>) {
        val central = event.central
        val queue = preparedWrites.remove(central).orEmpty()
        if (!event.execute) {
            logger?.trace(Layer.GATT) { "Prepared writes of $central cancelled (${queue.size} fragment(s))" }
            server.respond(central, event.requestId, OperationStatus.Success, 0, EMPTY)
            return
        }
        logger?.trace(Layer.GATT) { "Executing prepared writes of $central (${queue.size} fragment(s))" }

        // Assemble the fragments of each attribute.
        val assembled = mutableListOf<Pair<LocalAttribute<C>, ByteArray>>()
        val fragments = queue.groupBy { it.attribute }
        for ((attribute, list) in fragments) {
            var value = byteArrayOf()
            for (fragment in list) {
                if (fragment.offset != value.size) {
                    server.respond(central, event.requestId, OperationStatus.InvalidOffset, 0, EMPTY)
                    return
                }
                value += fragment.value
            }
            if (value.size > MAX_ATTRIBUTE_LENGTH) {
                server.respond(central, event.requestId, OperationStatus.InvalidAttributeLength, 0, EMPTY)
                return
            }
            assembled += attribute to value
        }

        for ((attribute, value) in assembled) {
            val status = write(central, attribute, value, withResponse = true)
            if (status != OperationStatus.Success) {
                server.respond(central, event.requestId, status, 0, EMPTY)
                return
            }
        }
        server.respond(central, event.requestId, OperationStatus.Success, 0, EMPTY)
    }

    private fun onSubscriptionChanged(event: GattServerEvent.SubscriptionChanged<C>) {
        val characteristic = attributes.value[event.attribute] as? BaseLocalCharacteristic<C>
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
        logger?.info(Layer.GATT) { "${event.central} ${if (value == 0) "unsubscribed from" else "subscribed to"} $characteristic" }
        characteristic.setCccdValue(event.central, value)
    }

    private fun onCentralDisconnected(event: GattServerEvent.CentralDisconnected<C>) {
        val central = event.central
        logger?.info(Layer.GATT) { "$central disconnected" }
        preparedWrites.remove(central)
        attributes.value.values.forEach { (it as? BaseLocalCharacteristic<C>)?.removeCentral(central) }
    }

    // ----- Helpers -----

    /** Returns an error status if the attribute can't be read, or `null` otherwise. */
    private fun checkReadable(attribute: LocalAttribute<C>): OperationStatus? = when {
        attribute is BaseLocalCharacteristic<C> && CharacteristicProperty.READ !in attribute.properties ->
            OperationStatus.ReadNotPermitted
        attribute is BaseLocalDescriptor<C> && !attribute.isClientCharacteristicConfiguration && !attribute.isReadPermitted ->
            OperationStatus.ReadNotPermitted
        else -> null
    }

    /** Returns an error status if the attribute can't be written, or `null` otherwise. */
    private fun checkWritable(attribute: LocalAttribute<C>, withResponse: Boolean): OperationStatus? = when {
        attribute is BaseLocalCharacteristic<C> -> {
            val required = if (withResponse) CharacteristicProperty.WRITE else CharacteristicProperty.WRITE_WITHOUT_RESPONSE
            if (required in attribute.properties) null else OperationStatus.WriteNotPermitted
        }
        attribute is BaseLocalDescriptor<C> -> {
            // Descriptors are always written with response.
            if (attribute.isWritePermitted && withResponse) null else OperationStatus.WriteNotPermitted
        }
        else -> OperationStatus.WriteNotPermitted
    }

    /** Adds a fragment to the prepared writes of the central. Returns an error or `null`. */
    private fun queuePreparedWrite(central: C, attribute: LocalAttribute<C>, offset: Int, value: ByteArray): OperationStatus? {
        val queue = preparedWrites.getOrPut(central) { mutableListOf() }
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
     * @return The status to be sent to the central.
     */
    private suspend fun write(central: C, attribute: LocalAttribute<C>, value: ByteArray, withResponse: Boolean): OperationStatus {
        // The attribute may have been removed, if the services were replaced in the meantime.
        if (attributes.value[attribute.instanceId] !== attribute) {
            return OperationStatus.InvalidHandle
        }
        checkWritable(attribute, withResponse)?.let { return it }
        if (value.size > MAX_ATTRIBUTE_LENGTH) {
            return OperationStatus.InvalidAttributeLength
        }
        if (attribute is BaseLocalDescriptor<C> && attribute.isClientCharacteristicConfiguration) {
            return writeCccd(central, attribute, value)
        }
        val response = try {
            attribute.state.write(WriteRequest(central, value, withResponse))
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
    private fun writeCccd(central: C, descriptor: BaseLocalDescriptor<C>, value: ByteArray): OperationStatus {
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
                0 -> "$central disabled updates of $characteristic"
                NOTIFICATIONS -> "$central enabled notifications of $characteristic"
                else -> "$central enabled indications of $characteristic"
            }
        }
        characteristic.setCccdValue(central, cccd)
        return OperationStatus.Success
    }

    /** Checks whether the CCCD value is valid and the characteristic supports it. */
    private fun isCccdValueSupported(characteristic: BaseLocalCharacteristic<C>, cccd: Int) = when (cccd) {
        0 -> true
        NOTIFICATIONS -> CharacteristicProperty.NOTIFY in characteristic.properties
        INDICATIONS -> CharacteristicProperty.INDICATE in characteristic.properties
        else -> false
    }

    /** A fragment of a prepared (long or reliable) write. */
    private class PreparedWrite<C: Central<*>>(
        val attribute: LocalAttribute<C>,
        val offset: Int,
        val value: ByteArray,
    )

    private companion object {
        val EMPTY = byteArrayOf()

        /** Bit 0 of the Client Characteristic Configuration. */
        const val NOTIFICATIONS = 0x0001

        /** Bit 1 of the Client Characteristic Configuration. */
        const val INDICATIONS = 0x0002

        /** The maximum number of fragments of prepared writes queued by a single central. */
        const val PREPARED_WRITE_QUEUE_LIMIT = 64
    }
}
