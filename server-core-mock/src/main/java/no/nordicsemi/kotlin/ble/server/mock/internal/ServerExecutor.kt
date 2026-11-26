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


package no.nordicsemi.kotlin.ble.server.mock.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.client.ConnectionStateChanged
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.Peripheral
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.ServicesChanged
import no.nordicsemi.kotlin.ble.client.ServicesDiscovered
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicChanged
import no.nordicsemi.kotlin.ble.client.internal.OperationMutex
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Environment
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.server.mock.MockClient
import no.nordicsemi.kotlin.log.Log
import kotlin.uuid.Uuid

/**
 * The identifier of the GATT server of the other device, which is a local server of the phone.
 */
internal const val SERVER_IDENTIFIER = "server"

/**
 * A GATT server of the other device, as seen by a simulated client.
 *
 * The services are the ones set up in the local server, and the requests are sent using
 * the [MockClient], which must be connected. The executor is connected from the beginning, and
 * it reports the disconnection when the client disconnects.
 *
 * @param client The simulated client.
 * @param environment The environment.
 * @param scope The scope in which the events are forwarded. The executor uses a child of it.
 */
internal class ServerExecutor(
    private val client: MockClient<*>,
    override val environment: Environment,
    scope: CoroutineScope,
): Peripheral.Executor<String> {
    // Collects the events of the client, until the executor is closed.
    private val collectorScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    override val identifier: String = SERVER_IDENTIFIER
    override val name: String? = null
    override var logger: Log.Sink<Layer>? = Log.Sink.Null
    override val operationMutex = OperationMutex()
    override val initialState: ConnectionState = ConnectionState.Connected
    override val initialServices: List<RemoteService> = emptyList()
    override var isReliableWriteEnabled: Boolean = false

    /** The current ATT MTU. */
    val attMtu: Int
        get() = client.attMtu.value

    override val events: SharedFlow<GattEvent>
        field = MutableSharedFlow<GattEvent>(extraBufferCapacity = 64)

    override var isClosed: Boolean = false
        private set

    private var servicesDiscovered = false

    init {
        // Unconfined and undispatched, so that the events are forwarded as they are emitted.
        collectorScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            client.updates.collect { update ->
                events.emit(CharacteristicChanged(update.attribute, update.value))
            }
        }
        collectorScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            client.connectionState.collect { state ->
                if (state is ConnectionState.Disconnected) {
                    events.emit(ConnectionStateChanged(state))
                }
            }
        }
        collectorScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            var previous = client.services.value
            client.services.collect { services ->
                // The services were set up again, which is what a Service Changed indication tells.
                if (services !== previous && servicesDiscovered) {
                    servicesDiscovered = false
                    events.emit(ServicesChanged)
                }
                previous = services
            }
        }
    }

    override suspend fun connect(autoConnect: Boolean, autoMtu: Boolean, opportunistic: Boolean) {
        // The client is connected as long as the connection to the other device.
    }

    override suspend fun discoverServices(uuids: List<Uuid>): Boolean {
        if (isClosed || !client.isConnected) return false
        val services = client.services.value
            .filter { uuids.isEmpty() || it.uuid in uuids }
            .map { SimulatedRemoteService(client, it, events) }
        servicesDiscovered = true
        events.emit(ServicesDiscovered(services))
        return true
    }

    override suspend fun readRssi(): Boolean = false

    override suspend fun disconnect(reason: ConnectionState.Disconnected.Reason): Boolean {
        if (isClosed) return false
        client.disconnect(reason)
        return true
    }

    override fun close() {
        isClosed = true
        collectorScope.cancel()
    }
}

/** The GATT server of the phone, as seen by a simulated client. */
internal class ServerPeripheral private constructor(
    scope: CoroutineScope,
    impl: ServerExecutor,
): Peripheral<String, ServerExecutor>(scope, impl) {

    /** Closes the peripheral, which stops forwarding the events of the client. */
    fun shutdown() = impl.close()

    override fun maximumWriteValueLength(type: WriteType): Int = when (type) {
        // Longer values are sent using Long Write.
        WriteType.WITH_RESPONSE -> 512
        WriteType.WITHOUT_RESPONSE -> impl.attMtu - 3
        WriteType.SIGNED -> impl.attMtu - 15
    }

    companion object {
        /**
         * Creates the GATT server of the phone for a connected client.
         *
         * @param client The simulated client, which is connected.
         * @param environment The environment.
         * @param scope The scope in which the events are forwarded. The peripheral uses a child
         * of it.
         */
        fun create(client: MockClient<*>, environment: Environment, scope: CoroutineScope): ServerPeripheral {
            val executor = ServerExecutor(client, environment, scope)
            return ServerPeripheral(CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])), executor)
        }
    }
}
