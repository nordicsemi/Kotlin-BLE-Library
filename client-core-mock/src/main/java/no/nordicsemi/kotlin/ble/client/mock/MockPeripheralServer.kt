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

package no.nordicsemi.kotlin.ble.client.mock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.client.mock.internal.SimulatedCentralImpl
import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.Service
import no.nordicsemi.kotlin.ble.core.ServerScope
import no.nordicsemi.kotlin.ble.core.internal.CCCD
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.server.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.internal.LocalServerImpl
import no.nordicsemi.kotlin.ble.server.mock.MockCentral
import no.nordicsemi.kotlin.ble.server.mock.MockGattServer
import no.nordicsemi.kotlin.ble.server.mock.ReadResult
import no.nordicsemi.kotlin.ble.server.mock.WriteResult

/**
 * A GATT server hosted by a mock peripheral, [PeripheralSpec].
 *
 * It is a [no.nordicsemi.kotlin.ble.server.LocalServer], the same one that apps use to
 * implement the peripheral role, running on top of a simulated GATT server. Every connectable
 * [PeripheralSpec] hosts one, also when its services are defined with the DSL of the builder
 * (see [PeripheralSpec.server]). It answers the requests of the mock clients: read and write requests, long and reliable writes, and subscriptions are
 * handled by the handlers set with `onRead` and `onWrite` of the local characteristics and
 * descriptors, and by their values, and notifications and indications are sent with `update()`.
 *
 * The requests of the clients go through the same simulation as the requests to peripherals using
 * other peripherals: they take as much time as the transfer of the value, using
 * the connection interval, the MTU and the PHY of the connection, and are subject to the same
 * checks of permissions, bonding and the validity of cached services.
 *
 * Sample code:
 * ```kotlin
 * val server = MockPeripheralServer(scope = backgroundScope)
 * val services = server.setup {
 *     GenericAccessService()
 *     GenericAttributeService()
 *     Service(BLINKY_SERVICE_UUID) {
 *         Characteristic(
 *             uuid = LED_UUID,
 *             properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
 *             permissions = Permission.READ and Permission.WRITE,
 *         )
 *         Characteristic(
 *             uuid = BUTTON_UUID,
 *             properties = CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
 *             permission = Permission.READ,
 *         )
 *     }
 * }
 * val led = services.single { it.uuid == BLINKY_SERVICE_UUID }.characteristics.first { it.uuid == LED_UUID }
 * val button = services.single { it.uuid == BLINKY_SERVICE_UUID }.characteristics.first { it.uuid == BUTTON_UUID }
 * led.onWrite { if (value.size == 1) WriteResponse.Success else WriteResponse.Failure(OperationStatus.InvalidAttributeLength) }
 * led.writes.onEach { println("LED: ${it.value.first()}") }.launchIn(backgroundScope)
 *
 * val peripheral = PeripheralSpec.simulatePeripheral(identifier = "01:02:03:04:05:06") {
 *     advertising { CompleteLocalName("Blinky") }
 *     connectable(name = "Blinky", server = server)
 * }
 * // ... later, when a client has subscribed to the button:
 * button.update(byteArrayOf(1))
 * ```
 *
 * ### Services
 *
 * Define the services, like on a real device, using [setup], **before** a client discovers them
 * (the services are discovered from the server when the client asks for them). The services of
 * the Generic Access and Generic Attribute, if needed, are declared with
 * [GenericAccessService][ServerScope.GenericAccessService] and
 * [GenericAttributeService][ServerScope.GenericAttributeService]. As with
 * [PeripheralSpec.Builder.connectable] using [PeripheralSpecEventHandler], their Device Name,
 * Appearance and Peripheral Preferred Connection Parameters characteristics return the values from
 * the [PeripheralSpec], and Service Changed is handled automatically.
 * Use [PeripheralSpec.simulateServiceChange] to replace the services of a peripheral that is
 * in use: calling [setup] again does not notify the clients.
 *
 * ### Threading and time
 *
 * Requests are processed by the event loop of the manager, which runs in the given scope. The
 * simulated transfers use the dispatcher and the clock of the mock central manager, so in tests
 * using `runTest`, the scope should use the same test scheduler, for example `backgroundScope`.
 * Handlers may suspend, for example to simulate a slow peripheral, using virtual time.
 *
 * ### Limitations
 *
 * - One server can be used by one [PeripheralSpec] only.
 * - The only central is [central], the phone. It is the same for all the clients of the peripheral.
 * - [PeripheralSpec.simulateValueUpdate] sends a notification to the clients, but it bypasses the
 *   server. Use `update()` of the local characteristic instead.
 *
 * ### Simulating faults
 *
 * The server handles Prepare Write, Execute Write and Client Characteristic Configuration requests
 * itself. To simulate a fault at the level of ATT requests, set an [interceptor][GattInterceptor].
 *
 * @param scope The scope of the event loop of the manager, which also runs the handlers.
 */
class MockPeripheralServer private constructor(
    scope: CoroutineScope,
    private val gatt: SimulatedGattServer,
): LocalServerImpl<String, SimulatedCentral>(scope, gatt) {

    /**
     * Creates a server that is not used by any peripheral yet.
     *
     * @param scope The scope of the event loop of the manager, which also runs the handlers.
     * In tests using `runTest`, use `backgroundScope`, so that the server uses virtual time.
     */
    constructor(scope: CoroutineScope): this(scope, SimulatedGattServer())

    /** The central, which is also a [MockCentral] sending the requests of the clients. */
    internal val simulatedCentral = SimulatedCentralImpl(gatt)

    /**
     * Defines the services without suspending.
     *
     * Used for the services defined with the DSL of the peripheral builder, which are hosted by
     * an internal server.
     */
    internal fun setupBlocking(block: MockServerScope.() -> Unit) {
        runImmediately { val _ = setup { MockServerScopeView(this).block() } }
    }

    /**
     * An optional interceptor of the requests of the clients.
     *
     * It simulates faults at the level of ATT requests. See [GattInterceptor].
     */
    var interceptor: GattInterceptor? = null

    /** The peripheral using this server. */
    private var peripheral: PeripheralSpec<*>? = null

    /** The reliable write, or a long write, which has been started, but not executed yet. */
    private var reliableWrite: MockCentral<String, SimulatedCentral>.ReliableWrite? = null

    /**
     * The central, that is the phone the mock peripheral is connected to.
     *
     * It is connected when the [PeripheralSpec] using this server is connected.
     */
    val central: SimulatedCentral
        get() = simulatedCentral

    /** The centrals that are connected: the [central] when the [PeripheralSpec] is connected. */
    val connectedCentrals: StateFlow<List<SimulatedCentral>>
        get() = gatt.connectedCentrals

    /** The definitions of the current services, which are discovered by the clients. */
    internal val definitions: List<ServiceDefinition>
        get() = gatt.services.value

    // ---- Binding to the peripheral ----

    /**
     * Makes the given peripheral use this server.
     *
     * @throws IllegalArgumentException if the server is already used by another peripheral.
     */
    internal fun bind(peripheral: PeripheralSpec<*>) {
        require(this.peripheral == null) { "The server is already used by another peripheral." }
        this.peripheral = peripheral
        gatt.updateSink = { attribute, value, indication ->
            peripheral.deliverUpdate(attribute, value, indication)
        }
    }

    // ---- Requests of the clients ----

    /**
     * Reads the whole value of the attribute, using the Read, and Read Blob requests when needed.
     */
    internal suspend fun read(attribute: Int): ReadResponse =
        interceptor?.onRead(attribute) ?: when (val result = simulatedCentral.readLong(attribute)) {
            is ReadResult.Success -> ReadResponse.Success(result.value)
            is ReadResult.Failure -> ReadResponse.Failure(result.status)
        }

    /** Sends a single Write Request. The value must fit in a single request. */
    internal suspend fun write(attribute: Int, value: ByteArray): WriteResponse =
        interceptor?.onWrite(attribute, value) ?: simulatedCentral.write(attribute, value, withResponse = true).toResponse()

    /** Sends a Write Command. The value must fit in a single command. */
    internal suspend fun writeCommand(attribute: Int, value: ByteArray) {
        interceptor?.onWriteCommand(attribute, value)?.let { return }
        val _ = simulatedCentral.write(attribute, value, withResponse = false)
    }

    /**
     * Queues a value using Prepare Write Requests, which are executed with [executeWrite].
     *
     * @param reliable `true` if it is a part of a Reliable Write, which keeps the queue after
     * a failure, until it is executed or cancelled. A failed Long Write cancels the queue.
     */
    internal suspend fun prepareWrite(attribute: Int, value: ByteArray, reliable: Boolean): PrepareWriteResponse {
        interceptor?.onPrepareWrite(attribute, value)?.let { return it }
        val write = reliableWrite
            ?: simulatedCentral.beginReliableWrite().also { reliableWrite = it }
        return when (val result = write.write(attribute, value)) {
            is WriteResult.Success -> PrepareWriteResponse.Success(value)
            is WriteResult.Failure -> {
                if (!reliable) {
                    reliableWrite = null
                    write.cancel()
                }
                PrepareWriteResponse.Failure(result.status)
            }
        }
    }

    /**
     * Executes or cancels the values queued with [prepareWrite]. Does nothing if nothing was queued.
     */
    internal suspend fun executeWrite(execute: Boolean): WriteResponse {
        interceptor?.onExecuteWrite(execute)?.let { return it }
        val write = reliableWrite ?: return WriteResponse.Success
        reliableWrite = null
        return (if (execute) write.execute() else write.cancel()).toResponse()
    }

    private fun WriteResult.toResponse(): WriteResponse = when (this) {
        is WriteResult.Success -> WriteResponse.Success
        is WriteResult.Failure -> WriteResponse.Failure(status)
    }

    // ---- State of the connection ----

    /**
     * Called when the peripheral gets connected, or all its connections end.
     *
     * @param connected Whether the peripheral is connected.
     * @param mtu The ATT MTU of the connection, if connected.
     */
    internal fun onConnectionChanged(connected: Boolean, mtu: Int?) {
        if (connected) {
            if (simulatedCentral.isConnected || gatt.isClosed) return
            simulatedCentral.connect()
            mtu?.let { simulatedCentral.exchangeMtu(it) }
            if (isServiceChangedEnabledByDefault()) {
                subscribeToServiceChanged()
            }
        } else if (simulatedCentral.isConnected) {
            reliableWrite = null
            // The central is disconnected immediately, so that the peripheral may connect again.
            runImmediately {
                simulatedCentral.disconnect(ConnectionState.Disconnected.Reason.TerminatePeerUser)
            }
        }
    }

    /** Called when the ATT MTU of the connection changed. */
    internal fun onMtuChanged(mtu: Int) {
        if (simulatedCentral.isConnected) {
            val _ = simulatedCentral.exchangeMtu(mtu)
        }
    }

    // ---- Service Changed ----

    private fun serviceChangedCharacteristic(): LocalCharacteristic? = services.value
        .firstOrNull { it.uuid == Service.GENERIC_ATTRIBUTE_UUID }
        ?.characteristics
        ?.firstOrNull { it.uuid == Characteristic.SERVICE_CHANGED }

    /** Whether the central has enabled indications of the Service Changed characteristic. */
    private fun isServiceChangedEnabled(): Boolean =
        serviceChangedCharacteristic()?.subscribers?.value?.contains(simulatedCentral) == true

    /**
     * Whether the Service Changed characteristic has been declared with its CCCD enabled, which
     * means that the phone subscribes to indications when it connects.
     */
    private fun isServiceChangedEnabledByDefault(): Boolean = definitions
        .firstOrNull { it.uuid == Service.GENERIC_ATTRIBUTE_UUID }
        ?.characteristics
        ?.firstOrNull { it.uuid == Characteristic.SERVICE_CHANGED }
        ?.descriptors
        ?.firstOrNull { it.uuid == Descriptor.CLIENT_CHAR_CONF_UUID }
        .let { it is CCCD && it.enabled }

    /** Enables indications of the Service Changed characteristic, as the phone does. */
    private fun subscribeToServiceChanged() {
        val characteristic = serviceChangedCharacteristic()?.instanceId ?: return
        internalScope.launch {
            if (simulatedCentral.isConnected) {
                val _ = simulatedCentral.subscribe(characteristic, indications = true)
            }
        }
    }

    /**
     * Replaces the services, without suspending, and returns whether the central was subscribed to
     * indications of the Service Changed characteristic.
     *
     * The subscription is kept, if the new services have the characteristic, as it is for
     * the phone with the real peripheral.
     */
    internal fun replaceServices(newServices: MockServerScope.() -> Unit): Boolean {
        val enabled = isServiceChangedEnabled()
        runImmediately {
            val _ = setup { MockServerScopeView(this).newServices() }
        }
        if (enabled) {
            subscribeToServiceChanged()
        }
        return enabled
    }

    /**
     * A [MockServerScope] adding services to any [ServerScope].
     */
    private class MockServerScopeView(scope: ServerScope): MockServerScope, ServerScope by scope
}

/**
 * Runs the block that is not supposed to suspend, in the calling thread, and returns when it
 * completes. If the block fails, the exception is thrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun runImmediately(block: suspend () -> Unit) {
    val result = CoroutineScope(Dispatchers.Unconfined).async(start = CoroutineStart.UNDISPATCHED) { block() }
    if (result.isCompleted) {
        result.getCompleted()
    }
}

/**
 * The simulated GATT server, which sends the notifications and indications to the client with
 * the delay of the transfer.
 */
internal class SimulatedGattServer: MockGattServer<String, SimulatedCentral>() {

    /**
     * Delivers the update to the clients of the peripheral, and suspends until it is sent
     * (notification) or confirmed (indication).
     */
    var updateSink: (suspend (attribute: Int, value: ByteArray, indication: Boolean) -> Unit)? = null

    override suspend fun notify(central: SimulatedCentral, attribute: Int, value: ByteArray, confirm: Boolean) {
        updateSink?.invoke(attribute, value, confirm)
        super.notify(central, attribute, value, confirm)
    }
}

/**
 * Intercepts the ATT requests sent by the clients to a [MockPeripheralServer].
 *
 * The requests are intercepted before they reach the services of the server. Use it to simulate faults that the GATT API of the server cannot
 * express, as the server handles Prepare Write, Execute Write and Client Characteristic
 * Configuration writes itself.
 *
 * Each method returns `null` to let the request go to the server as usual, or a response to
 * answer the request without the server seeing it. The attributes are identified by their handle,
 * the `instanceId`.
 */
interface GattInterceptor {

    /** A Read request, or a series of Read Blob requests, of the attribute. */
    suspend fun onRead(attribute: Int): ReadResponse? = null

    /** A Write Request of the attribute, including write requests to the CCCD. */
    suspend fun onWrite(attribute: Int, value: ByteArray): WriteResponse? = null

    /** A Write Command of the attribute. A non-null result means the command was consumed. */
    suspend fun onWriteCommand(attribute: Int, value: ByteArray): Unit? = null

    /**
     * A Prepare Write request, a part of a Long Write or a Reliable Write. Returning
     * [PrepareWriteResponse.Success] with a value different from the sent one simulates
     * a transmission error.
     */
    suspend fun onPrepareWrite(attribute: Int, value: ByteArray): PrepareWriteResponse? = null

    /** An Execute Write request, which commits or cancels the prepared write requests. */
    suspend fun onExecuteWrite(execute: Boolean): WriteResponse? = null
}
