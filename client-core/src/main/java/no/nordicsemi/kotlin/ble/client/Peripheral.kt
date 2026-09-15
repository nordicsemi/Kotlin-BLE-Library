/*
 * Copyright (c) 2024, Nordic Semiconductor
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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.exception.PeripheralNotConnectedException
import no.nordicsemi.kotlin.ble.client.internal.OperationMutex
import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Environment
import no.nordicsemi.kotlin.ble.core.Peer
import no.nordicsemi.kotlin.ble.core.Service
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.internal.withCallSite
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.log.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Class representing a Bluetooth LE peripheral.
 *
 * @property scope The coroutine scope.
 * @property impl The executor that provides methods to interact with the peripheral.
 * @property name The friendly Bluetooth name of the remote device.
 * The local adapter will automatically retrieve remote names when performing a device scan,
 * and will cache them. This method just returns the name for this device from the cache
 * @property state The connection state of the peripheral, as [StateFlow]. The flow emits a new
 * value whenever the state of the peripheral changes.
 */
abstract class Peripheral<ID: Any, EX: Peripheral.Executor<ID>>(
    protected val scope: CoroutineScope,
    protected val impl: EX,
): Peer<ID> {
    override var logger: Log.Sink<Layer>?
        get() = impl.logger
        set(logger) {
            impl.logger = logger
        }

    val name: String?
        get() = impl.name

    override val identifier: ID
        get() = impl.identifier

    internal val executor = this.impl

    /**
     * A job that collects GATT events from the peripheral.
     *
     * This is not-null when the device is connected or was connected using auto connect,
     * that is when any GATT event for the device, including connection state change, is expected.
     *
     * It's set to `null` when the peripheral is closed.
     */
    private var gattEventCollector: Job? = null

    /** The current state of the peripheral as a state flow. */
    protected var _state: MutableStateFlow<ConnectionState> = MutableStateFlow(impl.initialState)
    val state = _state.asStateFlow()

    /** Current list of GATT services. */
    protected var _services: MutableStateFlow<RemoteServices> = MutableStateFlow(
        value = impl.takeIf { it.initialState == ConnectionState.Connected }?.initialServices
            ?.let { RemoteServices.Discovered(it) }
            ?: RemoteServices.Unknown
    )

    /**
     * A flag indicating that the services have been discovered.
     *
     * This flag is reset on disconnection and when services are invalidated.
     */
    private var servicesDiscovered = false

    /**
     * A flag indicating that the service discovery was requested.
     */
    private var serviceDiscoveryRequested = false

    /**
     * A coroutine job that is used to start service discovery.
     */
    private var serviceDiscoveryJob: Job? = null

    /**
     * The list of service UUIDs requested for discovery.
     *
     * @see discoverServices
     * TODO Should and when this list be cleared?
     */
    private var requestedServiceUuids: List<Uuid> = emptyList()

    /**
     * An interface that provides methods to interact with the peripheral.
     *
     * The implementation should initiate requests and report events using [events] flow.
     *
     * @property environment The environment to use for the connection.
     */
    interface Executor<ID: Any>: Log.IdentifiableEmitter<ID> {
        val environment: Environment

        /** An object that will receive log events generated by the executor. */
        var logger: Log.Sink<Layer>?

        /**
         * The name of the device, if available.
         *
         * The name may change during the lifetime of the peripheral.
         */
        val name: String?

        /** The initial state of the peripheral. */
        val initialState: ConnectionState

        /** The initial services of the peripheral. */
        val initialServices: List<RemoteService>

        /** A flow of GATT events from the peripheral. */
        val events: SharedFlow<GattEvent>

        /** Returns true if the connection is closed. */
        val isClosed: Boolean

        /** Returns true it reliable write is in progress. */
        var isReliableWriteEnabled: Boolean

        /**
         * Makes a connection to the peripheral.
         *
         * This method may be called multiple times in case of a retry (when [autoConnect] is `false`).
         *
         * @param autoConnect True to use auto connect feature, false to use direct connection.
         * @param autoMtu True to enable automatic MTU negotiation.
         * @param opportunistic True to enable opportunistic connection.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         */
        suspend fun connect(
            autoConnect: Boolean,
            autoMtu: Boolean,
            opportunistic: Boolean = false,
        )

        /**
         * Initiates GATT services discovery.
         *
         * The result should be reported by emitting [ServicesDiscovered] event to [events] flow.
         * @param uuids An optional list of service UUIDs to filter the results.
         * @return True if service discovery was requested successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         * @throws OperationFailedException If the operation failed due to a platform error.
         */
        suspend fun discoverServices(uuids: List<Uuid>): Boolean

        /**
         * Initiates a read of the RSSI value from the peripheral.
         *
         * The result should be reported by emitting [RssiRead] event to [events] flow.
         *
         * @return True if RSSI was requested successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         * @throws OperationFailedException If the operation failed due to a platform error.
         */
        suspend fun readRssi(): Boolean

        /**
         * Disconnects from the peripheral.
         *
         * The result should be reported by emitting [ConnectionStateChanged] event
         * to [events] flow.
         *
         * @param reason The reason for disconnection, returned by [ConnectionState.Disconnected.reason].
         * @return True if disconnection was requested successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         */
        suspend fun disconnect(reason: ConnectionState.Disconnected.Reason): Boolean

        /**
         * Closes the connection to the peripheral.
         *
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         */
        fun close()
    }

    /**
     * Returns `true` if the peripheral is currently connected.
     */
    val isConnected: Boolean
        get() = state.value.isConnected

    /**
     * Returns `true` if the peripheral is disconnected of getting disconnected.
     */
    val isDisconnected: Boolean
        get() = state.value.isDisconnected

    /**
     * Waits until the given condition is met.
     *
     * @param timeout The timeout, or [Duration.INFINITE] (default) to wait indefinitely.
     * @param condition The condition to meet, which takes the current state as an argument.
     * @throws TimeoutCancellationException If the timeout is set and the condition is not met.
     */
    @IgnorableReturnValue
    protected suspend fun await(
        action: suspend () -> Unit,
        condition: suspend (ConnectionState) -> Boolean,
        timeout: Duration = Duration.INFINITE,
    ): ConnectionState = withTimeout(timeout) {
        impl.events
            .onSubscription { action() }
            .filterIsInstance(ConnectionStateChanged::class)
            .map { it.newState }
            .first { condition(it) }
    }

    /**
     * Starts collecting GATT events from the peripheral.
     *
     * The implementation may want to continue collecting events after the peripheral disconnects.
     * This may be the case when the connection may automatically get reestablished when the
     * peripheral is again in range. In such cases, the [closeWhenDisconnected] parameter should
     * be set to `false`.
     *
     * @param closeWhenDisconnected True to close the connection when the peripheral disconnects;
     *        false to keep collecting events.
     */
    protected fun startCollectingGattEvents(closeWhenDisconnected: Boolean = true) {
        check(gattEventCollector == null) {
            "Previous GATT event collector wasn't nullified before creating a new one"
        }

        gattEventCollector = impl.events
            // Handle each GATT event.
            .onEach { event ->
                when {
                    // In case of a disconnection event...
                    event is ConnectionStateChanged && event.newState is ConnectionState.Disconnected -> {
                        // ...when the connection was terminated using disconnect() or canceled,
                        // or the closeWhenDisconnected flag was set (no automatic reconnection),
                        // or connection failed due to insufficient authentication (bond info removed from peer),
                        // process the event and cancel the collector.
                        if (closeWhenDisconnected ||
                            event.newState.isUserInitiated ||
                            event.newState.reason is ConnectionState.Disconnected.Reason.InsufficientAuthentication) {
                            handle(event)
                            // This will call the onCompletion method below.
                            gattEventCollector?.cancel()
                        } else {
                            // If the connection will be retried, switch immediately to Connecting state.
                            // Note: This changes state Connected -> Connecting, without going through
                            //       Disconnected state.
                            handle(ConnectionStateChanged(ConnectionState.Connecting))
                        }
                    }
                    // Handle other events.
                    else -> handle(event)
                }
            }
            .onCompletion {
                gattEventCollector = null
                close()
            }
            // Peripheral will also be closed if the scope gets canceled.
            .launchIn(scope)
    }

    /**
     * Cancels collection of GATT events and closes the connection.
     */
    protected fun close() {
        // Cancel the event collector.
        // If the collector was already canceled or wasn't started, close the executor.
        gattEventCollector?.cancel() ?: run {
            handleDisconnection()
            handleClose()
            serviceDiscoveryRequested = false
            impl.close()
        }
        gattEventCollector = null
    }

    /**
     * This method is called when the connection to the peripheral was terminated.
     *
     * It should reset data associated with the connection.
     */
    protected open fun handleDisconnection() {
        invalidateServices()
    }

    /**
     * This method is called when the peripheral is closed.
     *
     * In this state the peripheral is disconnected and will not try to reconnect.
     */
    protected open fun handleClose() {
        // Empty default implementation.
    }

    /**
     * Invalidates current GATT services.
     */
    private fun invalidateServices() {
        _services.value.invalidate()
        _services.update { RemoteServices.Unknown }
        servicesDiscovered = false
        // Note!
        // Don't clear the serviceDiscoveryRequested flag here.
        // It will be cleared when the peripheral is closed.
        // This flag is used to start service discovery when the peripheral reconnects,
        // so that existing observers will get the services.
    }

    /**
     * Handles GATT events.
     *
     * @param event The GATT event to process.
     */
    protected open suspend fun handle(event: GattEvent) {
        when (event) {
            is ConnectionStateChanged -> {
                // If the state didn't change, ignore the event.
                if (_state.value == event.newState) {
                    return
                }
                if (event.disconnected) {
                    logger?.info(Layer.GAP) { "Disconnected from $this" }
                }
                _state.update { event.newState }
                when (event.newState) {
                    is ConnectionState.Connected -> {
                        initiateConnection()
                    }
                    // When the link is lost, a peripheral state may transition from Connected
                    // to Connecting or Disconnected.
                    is ConnectionState.Connecting,
                    is ConnectionState.Disconnected -> {
                        handleDisconnection()
                    }
                    else -> { /* Ignore */ }
                }
            }

            ServicesChanged -> {
                logger?.warn(Layer.GATT) { "Services invalidated" }
                invalidateServices()
                discoverServices(requestedServiceUuids)
            }

            is ServicesDiscovered -> {
                servicesDiscovered = true
                logger?.info(Layer.GATT) { "Services discovered" }
                // Assign the owner to each service, making them valid.
                val services = event.services.onEach { it.owner = this }
                // Search for Service Changed characteristic and enable CCCD.
                // This characteristic notifies about service changes.
                try {
                    services
                        .firstOrNull { it.uuid == Service.GENERIC_ATTRIBUTE_UUID }
                        ?.characteristics
                        ?.firstOrNull { it.uuid == Characteristic.SERVICE_CHANGED }
                        ?.takeIf { it.isSubscribable() }
                        ?.also { logger?.trace(Layer.GATT) { "Enabling Service Changed indications" } }
                        ?.subscribe { logger?.info(Layer.GATT) { "Service Changed indications enabled" } }
                        ?.onEach {
                            // The Service Changed indication is consumed by the system.
                            //
                            // * Android versions since Android 12 will notify the app using
                            //   `onServiceChanged` callback.
                            // * Android versions 8-11 refresh the cache, but do not notify the app.
                            //   However, during service discovery they switch to connection
                            //   interval 7.5 ms (6 units), which only happens during discovery.
                            //   This library detects it, and reports `ServicesChanged` event
                            //   (see NativeGattCallback).
                            // * Android versions prior to 8 also refresh services, but
                            //   they don't have a hidden `onConnectionUpdated` method, so it is
                            //   not possible to detect when the services get invalidated.
                            //
                            // None of the phones we tested (Android 6 - 16) indicated anything
                            // on Service Change characteristic.
                            // However, perhaps older Android versions do not handle SC indication
                            // internally, and will report it to the app, so let's use it to
                            // indicate service change.
                            handle(ServicesChanged)
                        }
                        ?.catch {
                            // This catches an error if isNotifying(true) inside subscribe() fails.
                            // For example, if a peripheral tries to bond, but PIN is incorrect.
                            logger?.warn(Layer.GATT) { "Enabling Service Changed indications failed" }
                        }
                        ?.launchIn(scope)
                } catch (t: Throwable) {
                    // This catches an error if SD is not subscribable or invalidated.
                    // These are thrown before the Flow is created.
                    logger?.warn(Layer.GATT) { "Enabling Service Changed indications failed" }
                }
                _services.update { RemoteServices.Discovered(services) }
            }

            is ServiceDiscoveryFailed -> {
                logger?.warn(Layer.GATT) { "Service discovery failed: ${event.reason}" }
                invalidateServices()
                _services.update { RemoteServices.Failed(event.reason) }
            }

            else -> { /* Ignore */ }
        }
    }

    /**
     * This method is called when the peripheral is connected.
     */
    protected open suspend fun initiateConnection() {
        // If services are observed, start service discovery.
        // This may happen when services() was called before the peripheral connected.
        if (serviceDiscoveryRequested) {
            discoverServices(requestedServiceUuids)
        }
    }

    /**
     * Initiates service discovery on the peripheral.
     *
     * This method does nothing if [servicesDiscovered] is `true`.
     */
    private fun discoverServices(uuids: List<Uuid>) {
        if (servicesDiscovered || serviceDiscoveryJob?.isActive == true) {
            return
        }
        serviceDiscoveryJob = scope.launch {
            OperationMutex.withLock {
                try {
                    impl.events
                        .onSubscription {
                            logger?.trace(Layer.GATT) { "Discovering services" }
                            _services.update { RemoteServices.Discovering }
                            if (!isConnected || !impl.discoverServices(uuids)) {
                                throw PeripheralNotConnectedException()
                            }
                        }
                        .takeWhile { !it.isDisconnectionEvent }
                        .filterIsInstance<ServiceDiscoveryCompleted>()
                        .firstOrNull()
                        ?: throw PeripheralNotConnectedException()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PeripheralNotConnectedException) {
                    _services.update { RemoteServices.Unknown }
                } catch (e: Exception) {
                    _services.update { RemoteServices.Failed(RemoteServices.Failed.Reason.InternalError) }
                    logger?.error(Layer.GATT) { "Discovering services failed: ${e.message}" }
                }
            }
        }
    }

    /**
     * Forces closing the connection.
     *
     * This method should be called when the manager was closed or Bluetooth was disabled.
     */
    internal fun forceClose() {
        if (impl.isClosed) {
            return
        }

        if (state.value !is ConnectionState.Disconnected) {
            _state.update {
                ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.TerminateLocalHost)
            }
        }
        close()
    }

    // Public API implementation

    /**
     * Returns a flow emitting [RemoteServices] events.
     *
     * ## Overview
     *
     * This method is the main entry point to discover GATT services on the peripheral and observe
     * changes in the services. It returns a flow that emits the current state of service discovery
     * process, which can be observed to get the list of services when they are discovered, or to
     * handle errors when service discovery fails.
     *
     * Note, that the flow may emit [RemoteServices.Discovered] state multiple times, for example
     * when the peripheral reconnects or when the services get invalidated and rediscovered.
     * This is not a one-time operation, but a continuous observation of the services on the peripheral.
     *
     * ## State machine
     *
     * Initially, the state is [Unknown][RemoteServices.Unknown]. Shortly after calling [services]
     * the flow will emit [Discovering][RemoteServices.Discovering] state, followed by
     * [Discovered][RemoteServices.Discovered] state, or (unlikely) [Failed][RemoteServices.Failed]
     * state, where the reason of the failure is given as a parameter
     * [Failed.reason][RemoteServices.Failed.reason].
     *
     * When in [Discovered][RemoteServices.Discovered] or [Failed][RemoteServices.Failed], and the
     * service will get invalidated, either due to a disconnection or Service Change indication
     * sent from the remote peripheral, the state will transition to [Unknown][RemoteServices.Unknown].
     *
     * Service change will automatically request service discovery, which, when complete, will emit
     * [Discovered][RemoteServices.Discovered] state with new set of services.
     *
     * ```
     *                           (Initial state)
     *                                  │
     *        ┌───────────────>      Unknown     <────────────────┐
     *        │                         │                         │
     * (disconnection)     (service discovery started)    (disconnection)
     *        │                         ↓                         │
     *     Failed <── (failure) ─── Discovering ─── (done) ──> Discovered
     *
     * ```
     *
     * @param uuids An optional list of service UUID to filter the results. If empty, all services
     *        will be returned. Some platforms may do partial service discovery and return only
     *        services with given UUIDs.
     * @return A state flow with the current state of service discovery process. If the method
     * was called with a [uuids] filter, the [RemoteServices.Discovered] state will contain only
     * services with given UUIDs, otherwise it will contain all services returned by the
     * system. If the returned list is empty, the service was not found on the peripheral.
     */
    fun services(uuids: List<Uuid> = emptyList()): StateFlow<RemoteServices> {
        // Mark that service discovery was requested. This is useful when the peripheral
        // reconnects but the services observer was already set.
        serviceDiscoveryRequested = true
        requestedServiceUuids = (requestedServiceUuids + uuids).distinct()

        // First call to this method triggers service discovery.
        if (isConnected) {
            discoverServices(uuids)
        }

        // If there is no filter, return the original flow.
        if (uuids.isEmpty()) {
            return _services.asStateFlow()
        }

        // If there is a filter, create a new flow that will emit filtered services only.
        val filteredState = _services.value.filteredBy(uuids)
        return _services
            .map { it.filteredBy(uuids) }
            .stateIn(scope, SharingStarted.Lazily, filteredState)
    }

    /**
     * Registers a profile implementation that runs when the specified GATT service is discovered.
     *
     * ## Overview
     *
     * Decouples the Bluetooth LE profile interface from application logic, allowing each
     * device feature (profile) to run in its own coroutine.
     *
     * Multiple profiles can be added by calling this method once for each service,
     * i.e. Battery Profile and Heart Rate Profile.
     *
     * This method suspends only to get the current coroutine scope using [currentCoroutineContext].
     * The [block] is called in a child coroutine.
     *
     * ## Services
     *
     * As `profile` is using [services] under the hood, it is safe and recommended to call this method
     * before connecting the peripheral.
     *
     * The [block] will be called every time the services are discovered,
     * which may happen multiple times (e.g. when the peripheral reconnects, or when the service
     * gets invalidated and rediscovered). To stop observing services cancel the job in which this
     * method is called, or use the `profile` method with custom scope.
     *
     * If multiple services share the same [serviceUuid], only the first one is passed to `block`.
     *
     * ## Validation
     *
     * If the profile was marked as [required] and the service is not found,
     * or the `block` throws [IllegalArgumentException] during service validation, the connection
     * will be terminated with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     *
     * ## Block completion
     *
     * The device will NOT be disconnected when the [block] ends, unless the situation described
     * in the Validation section.
     *
     * ## Example
     *
     * ```kotlin
     * override suspend fun connect(
     *     block: suspend CoroutineScope.(HeartRateProfile.State) -> Unit,
     * ): Unit = withContext(Dispatchers.IO) {
     *     // First, register profile.
     *     peripheral.profile(
     *         serviceUuid = HeartRateProfile.heartRateServiceUuid,
     *         required = true,
     *         name = "Heart Rate Profile",
     *     ) { remoteService ->
     *         val state = HeartRateServiceImpl(remoteService, this)
     *
     *         // Call the block with the Heart Rare service state, separating Bluetooth LE from the logic.
     *         block(state)
     *     }
     *     // Connect.
     *     centralManager.connect(peripheral)
     *
     *     // Await disconnection.
     *     peripheral.awaitDisconnection()
     * }
     * ```
     *
     * See [profile] for more information.
     *
     * @param serviceUuid The UUID of the profile service.
     * @param required Whether the service is required. In example, a Heart Rate app may require
     * a Heart Rate Service, but also support an optional Battery Service to indicate the battery
     * level.
     * @param name An optional name of the profile, used only in log messages. This is useful when
     * an app registers multiple profiles, to easily distinguish them in logs.
     * @param block The profile implementation.
     */
    suspend fun profile(
        serviceUuid: Uuid,
        required: Boolean = true,
        name: String? = null,
        block: suspend CoroutineScope.(RemoteService) -> Unit,
    ) {
        // Get the current context. This will allow creating a scope, that will get closed
        // together with the outer scope.
        val context = currentCoroutineContext()
        val userScope = CoroutineScope(context)

        profile(userScope, serviceUuid, required, name, block)
    }

    /**
     * Registers a profile implementation that runs when the specified GATT service is discovered.
     *
     * ## Overview
     *
     * Decouples the Bluetooth LE profile interface from application logic, allowing each
     * device feature (profile) to run in its own coroutine.
     *
     * Multiple profiles can be added by calling this method once for each service,
     * i.e. Battery Profile and Heart Rate Profile.
     *
     * The provided [block] is launched on a child coroutine in the given [scope] when a matching
     * [RemoteService] is emitted by the [services] flow. The launched job is canceled when
     * the peripheral disconnects (the cancellation cause is [PeripheralNotConnectedException])
     * or when the job completes. When the `block` finishes (normally or exceptionally) the
     * peripheral will be disconnected (with the reason [Success][ConnectionState.Disconnected.Reason.Success]).
     *
     * ## Services
     *
     * As `profile` is using [services] under the hood, it is safe and recommended to call this method
     * before connecting the peripheral.
     *
     * The [block] will be called every time the services are discovered,
     * which may happen multiple times (e.g. when the peripheral reconnects, or when the service
     * gets invalidated and rediscovered). To stop observing services cancel the [scope].
     *
     * If multiple services share the same [serviceUuid], only the first one is passed to `block`.
     *
     * ## Validation
     *
     * If the profile was marked as [required] and the service is not found,
     * or the `block` throws [IllegalArgumentException] during service validation, the connection
     * will be terminated with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     *
     * ## Block completion
     *
     * The device will NOT be disconnected when the [block] ends, unless the situation described
     * in the Validation section.
     *
     * ## Example
     *
     * In this example, the app is connecting to a Heart Rate device with an optional Sensor Location
     * and HR Control Point characteristics. It updates the UI using `locationFlow` and
     * receives Reset button events using `resetButtonEvents`.
     *
     * ```kotlin
     * // Helper methods.
     * val RemoteService.heartRateMeasurement: RemoteCharacteristic? = characteristics
     *    .firstOrNull { it.uuid = HeartRateProfile.heartRateMeasurementUuid }
     * val RemoteService.heartRateControlPoint: RemoteCharacteristic? = characteristics
     *    .firstOrNull { it.uuid = HeartRateProfile.heartRateControlPointUuid }
     * val RemoteService.bodySensorLocation: RemoteCharacteristic? = characteristics
     *    .firstOrNull { it.uuid = HeartRateProfile.bodySensorLocationUuid }
     *
     * // LBS profile implementation.
     * peripheral.profile(
     *    scope = scope,
     *    serviceUuid = HeartRateProfile.heartRateServiceUuid,
     *    required = true,
     *    name = "Heart Rate Profile",
     * ) { hrmService ->
     *    // 1. Validate the service.
     *
     *    // HRM characteristic is required.
     *    val hrMeasurement = requireNotNull(hrmService.heartRateMeasurement) {
     *       "HRM characteristic not found"
     *    }
     *    require(hrMeasurement.isSubscribable()) {
     *       "HRM characteristic must have the NOTIFY property"
     *    }
     *    // Other characteristics are optional.
     *    val hrControlPoint = hrmService.heartRateControlPoint
     *    val bodySensorLocation = hrmService.bodySensorLocation
     *
     *    // 2. Initialize the profile.
     *
     *    // Read the sensor location characteristic.
     *    val location = bodySensorLocation?.read()
     *       .map { it.toBodySensorLocation() }
     *       ?: BodySensorLocation.NOT_SUPPORTED
     *    locationFlow.update { location }
     *
     *    // Subscribe to the Heart Rate Measurement characteristic.
     *    hrMeasurement
     *       .subscribe {
     *          // Set up the (optional) Control Point when HRM subscription is complete:
     *          hrControlPoint?.let { cp ->
     *              resetButtonEvents
     *                  .onEach {
     *                     cp.write(HeartRateControlPoint.RESET)
     *                  }
     *                  // Note, that the collection is launched in the profile scope,
     *                  // not the outer scope.
     *                  .launchIn(this)
     *          }
     *       }
     *       .onEach {
     *          // Update UI or something.
     *       }
     *       .launchIn(this)
     * }
     * ```
     *
     * @param scope The coroutine scope to launch the user block in.
     * @param serviceUuid The UUID of the profile service.
     * @param required Whether the service is required by the app. In example, a Heart Rate app
     * may require a Heart Rate Service, but also support an optional Battery Service to indicate
     * the battery level.
     * @param name An optional name of the profile, used only in log messages. This is useful when
     * an app registers multiple profiles, to easily distinguish them in logs.
     * @param block The profile implementation.
     */
    fun profile(
        scope: CoroutineScope,
        serviceUuid: Uuid,
        required: Boolean = true,
        name: String? = null,
        block: suspend CoroutineScope.(RemoteService) -> Unit,
    ) = profile(
        scope = scope,
        requiredServiceUuids = listOf(serviceUuid),
        required = required,
        name = name,
    ) { services ->
        block(services.first())
    }

    /**
     * Registers a profile implementation that runs when the specified GATT services are discovered.
     *
     * ## Overview
     *
     * Decouples the Bluetooth LE profile interface from application logic, allowing each
     * device feature (profile) to run in its own coroutine.
     *
     * Multiple profiles can be added by calling this method once for each service,
     * i.e. Battery Profile and Heart Rate Profile.
     *
     * Note, that this overload of the `profile` method returns all [RemoteService]s matching
     * any of the [requiredServiceUuids] or [optionalServiceUuids], even if multiple instances
     * of the same service were discovered.
     *
     * This method suspends only to get the current coroutine scope using [currentCoroutineContext].
     * The [block] is called in a child coroutine.
     *
     * ## Services
     *
     * As `profile` is using [services] under the hood, it is safe and recommended to call this method
     * before connecting the peripheral.
     *
     * The [block] will be called every time the services are discovered,
     * which may happen multiple times (e.g. when the peripheral reconnects, or when the service
     * gets invalidated and rediscovered). To stop observing services cancel the job in this
     * method is called, or use `profile` method with custom scope.
     *
     * ## Validation
     *
     * If the profile was marked as [required] and at least one of the required services is not found,
     * or the `block` throws [IllegalArgumentException] during service validation, the connection
     * will be terminated with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     *
     * ## Block completion
     *
     * The device will NOT be disconnected when the [block] ends, unless the situation described
     * in the Validation section.
     *
     * ## Example
     *
     * ```kotlin
     * override suspend fun connect(
     *     block: suspend CoroutineScope.(Proximity.State) -> Unit,
     * ): Unit = withContext(Dispatchers.IO) {
     *     // First, register profile. Do this only once for a peripheral.
     *     // The profile block will get called each time the peripheral is connected.
     *     peripheral.profile(
     *         requiredServiceUuids = listOf(
     *            Proximity.linkLossServiceUuid
     *         ),
     *         optionalServiceUuids = listOf(
     *            Proximity.immediateAlertServiceUuid,
     *            Proximity.txPowerServiceUuid,
     *         ),
     *         required = true,
     *         name = "Proximity",
     *     ) { remoteServices ->
     *         val state = ProximityImpl(remoteServices, this)
     *
     *         // Call the block with the Proximity profile state, separating Bluetooth LE from the logic.
     *         block(state)
     *     }
     *     // Connect.
     *     centralManager.connect(peripheral)
     *
     *     // Await disconnection.
     *     try {
     *        peripheral.awaitDisconnection()
     *     } catch (e: CancellationException) {
     *        // The scope may get canceled when user leaves the screen.
     *        // In that case, make sure to disconnect.
     *        // Don't disconnect when services were invalidated, as the profile will be re-launched.
     *        if (e.cause !is InvalidAttributeException) {
     *            peripheral.disconnect()
     *        }
     *        // Rethrow.
     *        throw e
     *     }
     * }
     * ```
     *
     * See [profile] for more information.
     *
     * @param requiredServiceUuids The list of UUIDs of the GATT services required by the profile.
     * @param optionalServiceUuids The list of UUIDs of the optional GATT services.
     * @param required Whether support for this profile is required by the app. In example,
     * a Heart Rate app may require a Heart Rate Profile, but also support an optional
     * Battery Profile to indicate the battery level. If `true` (default), and at least one of the
     * required services is not found on the peripheral, the connection will be terminated with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     * If `false`, the [block] won't be called, but the connection won't be terminated.
     * @param name An optional name of the profile, used only in log messages. This is useful when
     * an app registers multiple profiles, to easily distinguish them in logs.
     * @param block The profile implementation.
     */
    suspend fun profile(
        requiredServiceUuids: List<Uuid>,
        optionalServiceUuids: List<Uuid> = emptyList(),
        required: Boolean = true,
        name: String? = null,
        block: suspend CoroutineScope.(List<RemoteService>) -> Unit,
    ) {
        // Get the current context. This will allow creating a scope, that will get closed
        // together with the outer scope.
        val context = currentCoroutineContext()
        val userScope = CoroutineScope(context)

        profile(userScope, requiredServiceUuids, optionalServiceUuids, required, name, block)
    }

    /**
     * Registers a profile implementation that runs when the specified GATT services are discovered.
     *
     * ## Overview
     *
     * Decouples the Bluetooth LE profile interface from application logic, allowing each
     * device feature (profile) to run in its own coroutine.
     *
     * Multiple profiles can be added by calling this method once for each group of services,
     * i.e. Battery Profile and Heart Rate Profile.
     *
     * Note, that this overload of the `profile` method returns all [RemoteService]s matching
     * any of the [requiredServiceUuids] or [optionalServiceUuids], even if multiple instances
     * of the same service were discovered.
     *
     * The provided [block] is launched on a child coroutine in the given [scope] when all matching
     * [RemoteService]s are emitted by the [services] flow. The coroutine is canceled when
     * the peripheral disconnects (the cancellation cause is [PeripheralNotConnectedException])
     * or service are invalidated (cause is [InvalidAttributeException]).
     *
     * ## Services
     *
     * As `profile` is using [services] under the hood, it is safe and recommended to call this method
     * before connecting the peripheral.
     *
     * The [block] will be called every time the services are discovered,
     * which may happen multiple times (e.g. when the peripheral reconnects, or when the service
     * gets invalidated and rediscovered). To stop observing services cancel the [scope].
     *
     * ## Validation
     *
     * If the profile was marked as [required] and at least one of the required services is not found,
     * or the `block` throws [IllegalArgumentException] during service validation, the connection
     * will be terminated with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     *
     * ## Block completion
     *
     * The device will NOT be disconnected when the [block] ends, unless the situation described
     * in the Validation section.
     *
     * ## Example
     *
     * In this example, the app is connecting to a Heart Rate device with an optional Sensor Location
     * and HR Control Point characteristics. It updates the UI using `locationFlow` and
     * receives Reset button events using `resetButtonEvents`.
     *
     * ```kotlin
     * // Helper methods.
     * val List<RemoteService>.linkLossService: RemoteService? = services
     *    .firstOrNull { it.uuid = ProximityProfile.linkLossServiceUuid }
     * val List<RemoteService>.immediateAlertService: RemoteService? = services
     *    .firstOrNull { it.uuid = ProximityProfile.immediateAlertServiceUuid }
     * val List<RemoteService>.txPowerService: RemoteService? = services
     *    .firstOrNull { it.uuid = ProximityProfile.txPowerServiceUuid }
     *
     * val RemoteService.alertLevel: RemoteCharacteristic? = characteristics
     *    .firstOrNull { it.uuid = ProximityProfile.alertLevelUuid }
     * val RemoteService.txPowerLevel: RemoteCharacteristic? = characteristics
     *    .firstOrNull { it.uuid = ProximityProfile.txPowerLevelUuid }
     *
     * // Proximity profile implementation.
     * peripheral.profile(
     *    scope = scope,
     *    requiredServiceUuids = listOf(
     *       ProximityProfile.linkLossServiceUuid
     *    ),
     *    optionalServiceUuids = listOf(
     *       ProximityProfile.immediateAlertServiceUuid,
     *       ProximityProfile.txPowerServiceUuid,
     *    ),
     *    required = true,
     *    name = "Proximity",
     * ) { services ->
     *    // 1. Validate the services.
     *
     *    // Link Loss Service is required.
     *    val linkLossAlertLevel = requireNotNull(services.linkLossService?.alertLevel) {
     *       "Link Loss Alert Level characteristic not found"
     *    }
     *    require(linkLossAlertLevel.isWritable()) {
     *       "Link Loss Alert Level characteristic must have the WRITE property"
     *    }
     *
     *    // Other services are optional, but can only be used when both are found.
     *    val immediateAlertService = services.immediateAlertService
     *    val txPowerService = services.txPowerService
     *    val optionalServicesSupported = immediateAlertService != null && txPowerService != null
     *
     *    // [...]
     *
     *    // 2. Initialize the profile.
     *
     *    // Write Link Loss Alert Level.
     *    linkLossAlertLevel?.write(ProximityProfile.ALERT_HIGH)
     *
     *    // Set up immediate alert.
     *    // Note, that the collection is launched in the profile scope, not the outer scope.
     *    if (optionalServicesSupported) {
     *       buttonState
     *          .onEach {
     *             immediateAlertService?.alertLevel?.let { level ->
     *                level.write(ProximityProfile.ALERT_HIGH)
     *             }
     *          }
     *          .launchIn(this)
     *    }
     * }
     * ```
     *
     * @param scope The coroutine scope to launch the user block in.
     * @param requiredServiceUuids The list of UUIDs of the GATT services required by the profile.
     * @param optionalServiceUuids The list of UUIDs of the optional GATT services.
     * @param required Whether support for this profile is required by the app. In example,
     * a Heart Rate app may require a Heart Rate Profile, but also support an optional
     * Battery Profile to indicate the battery level. If `true` (default), and at least one of the
     * required services is not found on the peripheral, the connection will be terminated with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     * If `false`, the [block] won't be called, but the connection won't be terminated.
     * @param name An optional name of the profile, used only in log messages. This is useful when
     * an app registers multiple profiles, to easily distinguish them in logs.
     * @param block The profile implementation.
     */
    fun profile(
        scope: CoroutineScope,
        requiredServiceUuids: List<Uuid>,
        optionalServiceUuids: List<Uuid> = emptyList(),
        required: Boolean = true,
        name: String? = null,
        // TODO Should we add a callback for "service not found", which would disconnect by default?
        block: suspend CoroutineScope.(List<RemoteService>) -> Unit,
    ) {
        val name = name ?: "Profile"
        require(requiredServiceUuids.isNotEmpty()) { "$name: Service UUIDs list cannot be empty" }

        /**
         * The user job will be started to execute the user block.
         * It will be canceled when the outer scope is canceled, or when the services get
         * invalidated (i.e. on disconnect).
         */
        var userJob: Job? = null

        services(requiredServiceUuids + optionalServiceUuids)
            // The services flow will initially emit "Unknown" (as the services are not discovered yet).
            // Upon successful connection the flow will emit "Discovering" followed by:
            // 1. Discovered - when service discovery was successful.
            // 2. Unknown - when the device disconnected before service discovery finished.
            // 3. Failed - when service discovery failed, giving the reason as a parameter.
            //
            // Note, that disconnection during service discovery will transition the state to Unknown,
            // not to Failed. This is to ensure, that whenever the device is disconnected, the
            // services state is the same.
            .onEach { state ->
                when (state) {
                    is RemoteServices.Unknown -> {
                        // When the services get invalidated, of the device gets disconnected,
                        // cancel the user job (if running).
                        val cause = if (isConnected) InvalidAttributeException() else PeripheralNotConnectedException()
                        userJob?.cancel(CancellationException(cause.toString(), cause))
                        // Do not cancel the user scope here. The device may get reconnected.
                        // User scope is continuing observing the services.
                    }
                    is RemoteServices.Discovered -> {
                        // When services are discovered, check if all required services were discovered.
                        val missingServices = requiredServiceUuids.filter { serviceUuid ->
                            state.services.none { service -> service.uuid == serviceUuid }
                        }
                        if (missingServices.isEmpty()) {
                            // If the GATT service was found, start the user block in a new job.
                            // This job will be canceled with the outer scope, or when the peripheral
                            // disconnects (throwing InvalidAttributeException).
                            userJob = scope.launch {
                                try {
                                    block(state.services)
                                    awaitCancellation()
                                } catch (e: Exception) {
                                    when (e) {
                                        // Rethrow cancellation exceptions, without any action.
                                        is CancellationException -> throw e
                                        // The implementation may use require(...) methods
                                        // to verify the service.
                                        // Catch them and report as if the service was not found.
                                        is IllegalArgumentException -> {
                                            logger?.warn(Layer.GATT, e) { "$name: Validation failed" }
                                            if (required) {
                                                disconnect(ConnectionState.Disconnected.Reason.RequiredServiceNotFound)
                                            }
                                        }
                                        else -> {
                                            logger?.error(Layer.GATT, e) { "$name: Block failed with exception" }
                                            throw e
                                        }
                                    }
                                } finally {
                                    userJob = null
                                }
                            }
                        } else {
                            // If any required service was not found disconnect, disconnect.
                            if (required) {
                                logger?.warn(Layer.GATT) { "$name: Required services not supported (missing: $missingServices)" }
                                disconnect(ConnectionState.Disconnected.Reason.RequiredServiceNotFound)
                            } else {
                                // Do not disconnect or cancel the user scope.
                                // The device may change its services and the Discovered state
                                // may be emitted again.
                            }
                        }
                    }
                    is RemoteServices.Failed -> {
                        logger?.error(Layer.GATT) { "$name: Service discovery failed (reason: ${state.reason})" }
                        // In case of a service discovery failure, act as if the service was not found.
                        // TODO Is this expected behavior?
                        disconnect(ConnectionState.Disconnected.Reason.RequiredServiceNotFound)
                    }
                    else -> { /* Ignore */ }
                }
            }
            .launchIn(scope)
    }

    /**
     * Registers a profile implementation that runs when the specified GATT services are discovered.
     *
     * ## Overview
     *
     * Decouples the Bluetooth LE profile interface from application logic, allowing each
     * device feature (profile) to run in its own coroutine.
     *
     * Multiple profiles can be added by calling this method once for each service,
     * i.e. Battery Profile and Heart Rate Profile.
     *
     * This method suspends only to get the current coroutine scope using [currentCoroutineContext].
     * The profile is executed in a child coroutine.
     *
     * ## Services
     *
     * As `profile` is using [services] under the hood, it is safe and recommended to call this method
     * before connecting the peripheral.
     *
     * The profile will be executed every time the services are discovered,
     * which may happen multiple times (e.g. when the peripheral reconnects, or when the service
     * gets invalidated and rediscovered). To stop observing services cancel the [scope].
     *
     * ## Validation
     *
     * If the profile was marked as [required] and at least one of the required services is not found,
     * or the `block` throws [IllegalArgumentException] during service validation, the connection
     * will be terminated with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     *
     * #### Example
     *
     * ##### Profile definition
     * ```kotlin
     * /**
     *  * API of the profile.
     *  */
     * interface LedButton {
     *     /** The current button state on the DK. */
     *     val buttonState: StateFlow<Boolean>
     *     /** The LED state. */
     *     val ledState: MutableStateFlow<Boolean>
     * }
     *
     * class LedButtonProfile: Profile.Simple(
     *     serviceUuid = SERVICE_UUID,
     *     name = "LBS",
     * ), LedButton {
     *     companion object {
     *         val SERVICE_UUID = Uuid.parse("00001523-1212-efde-1523-785feabcd123")
     *         val BUTTON_CHARACTERISTIC_UUID = Uuid.parse("00001524-1212-efde-1523-785feabcd123")
     *         val LED_CHARACTERISTIC_UUID = Uuid.parse("00001525-1212-efde-1523-785feabcd123")
     *     }
     *
     *     // GATT characteristics.
     *     private lateinit var buttonCharacteristic: RemoteCharacteristic
     *     private lateinit var ledCharacteristic: RemoteCharacteristic
     *
     *     // Public API.
     *     private val _buttonState = MutableStateFlow(false)
     *     override val buttonState: StateFlow<Boolean> = _buttonState.asStateFlow()
     *     override val ledState: MutableStateFlow<Boolean> = MutableStateFlow(false)
     *
     *     // Implementation.
     *     override fun prepare(service: RemoteService) {
     *         // This should always pass.
     *         require(service.uuid == SERVICE_UUID)
     *
     *         // Obtain characteristics from the service.
     *         buttonCharacteristic = service.characteristics.first { it.uuid == BUTTON_CHARACTERISTIC_UUID }
     *         ledCharacteristic = service.characteristics.first { it.uuid == LED_CHARACTERISTIC_UUID }
     *
     *         // Validate properties.
     *         require(buttonCharacteristic.isSubscribable()) { "Button characteristic must be subscribable." }
     *         require(ledCharacteristic.isWritable()) { "LED characteristic must be writable." }
     *     }
     *
     *     override suspend fun CoroutineScope.initialize() {
     *         // Subscribe to button characteristic.
     *         buttonCharacteristic
     *             .subscribe()
     *             .map { value -> value.singleOrNull() == 1.toByte() }
     *             .onEach { isPressed -> _buttonState.update { isPressed } }
     *             .launchIn(this)
     *
     *         // Read current Button state.
     *         try {
     *             val currentState = buttonCharacteristic.read()
     *             _buttonState.update { currentState.singleOrNull() == 1.toByte() }
     *         } catch (e: OperationFailedException) {
     *             println("Reading button characteristic failed: ${e.message}")
     *         }
     *
     *         // Handle LED state updates.
     *         ledState
     *             .map { isOn -> byteArrayOf(if (isOn) 1 else 0) }
     *             .onEach { value ->
     *                 try {
     *                     ledCharacteristic.write(value)
     *                 } catch (e: OperationFailedException) {
     *                     println("Writing LED characteristic failed: ${e.message}")
     *                 }
     *             }
     *             .launchIn(this)
     *     }
     * }
     * ```
     * ##### Usage
     * ```kotlin
     * val api: LedButton = LedButtonProfile()
     *    .also { peripheral.profile(it) }
     * ```
     *
     * @param profile The profile implementation.
     * @param required Whether support for this profile is required by the app.
     */
    suspend fun profile(profile: Profile, required: Boolean = true) = profile(
        requiredServiceUuids = profile.requiredServiceUuids,
        optionalServiceUuids = profile.optionalServiceUuids,
        required = required,
        name = profile.name,
        block = {services ->
            profile.execute(services, this)
        }
    )

    /**
     * Registers a profile implementation that runs when the specified GATT services are discovered.
     *
     * ## Overview
     *
     * Decouples the Bluetooth LE profile interface from application logic, allowing each
     * device feature (profile) to run in its own coroutine.
     *
     * Multiple profiles can be added by calling this method once for each service,
     * i.e. Battery Profile and Heart Rate Profile.
     *
     * This method suspends only to get the current coroutine scope using [currentCoroutineContext].
     * The profile is executed in a child coroutine.
     *
     * ## Services
     *
     * As `profile` is using [services] under the hood, it is safe and recommended to call this method
     * before connecting the peripheral.
     *
     * The profile will be executed every time the services are discovered,
     * which may happen multiple times (e.g. when the peripheral reconnects, or when the service
     * gets invalidated and rediscovered). To stop observing services cancel the [scope].
     *
     * ## Validation
     *
     * If the profile was marked as [required] and at least one of the required services is not found,
     * or the `block` throws [IllegalArgumentException] during service validation, the connection
     * will be terminated with reason
     * [RequiredServiceNotFound][ConnectionState.Disconnected.Reason.RequiredServiceNotFound].
     *
     * #### Example
     *
     * ##### Profile definition
     * ```kotlin
     * /**
     *  * API of the profile.
     *  */
     * interface LedButton {
     *     /** The current button state on the DK. */
     *     val buttonState: StateFlow<Boolean>
     *     /** The LED state. */
     *     val ledState: MutableStateFlow<Boolean>
     * }
     *
     * class LedButtonProfile: Profile.Simple(
     *     serviceUuid = SERVICE_UUID,
     *     name = "LBS",
     * ), LedButton {
     *     companion object {
     *         val SERVICE_UUID = Uuid.parse("00001523-1212-efde-1523-785feabcd123")
     *         val BUTTON_CHARACTERISTIC_UUID = Uuid.parse("00001524-1212-efde-1523-785feabcd123")
     *         val LED_CHARACTERISTIC_UUID = Uuid.parse("00001525-1212-efde-1523-785feabcd123")
     *     }
     *
     *     // GATT characteristics.
     *     private lateinit var buttonCharacteristic: RemoteCharacteristic
     *     private lateinit var ledCharacteristic: RemoteCharacteristic
     *
     *     // Public API.
     *     private val _buttonState = MutableStateFlow(false)
     *     override val buttonState: StateFlow<Boolean> = _buttonState.asStateFlow()
     *     override val ledState: MutableStateFlow<Boolean> = MutableStateFlow(false)
     *
     *     // Implementation.
     *     override fun prepare(service: RemoteService) {
     *         // This should always pass.
     *         require(service.uuid == SERVICE_UUID)
     *
     *         // Obtain characteristics from the service.
     *         buttonCharacteristic = service.characteristics.first { it.uuid == BUTTON_CHARACTERISTIC_UUID }
     *         ledCharacteristic = service.characteristics.first { it.uuid == LED_CHARACTERISTIC_UUID }
     *
     *         // Validate properties.
     *         require(buttonCharacteristic.isSubscribable()) { "Button characteristic must be subscribable." }
     *         require(ledCharacteristic.isWritable()) { "LED characteristic must be writable." }
     *     }
     *
     *     override suspend fun CoroutineScope.initialize() {
     *         // Subscribe to button characteristic.
     *         buttonCharacteristic
     *             .subscribe()
     *             .map { value -> value.singleOrNull() == 1.toByte() }
     *             .onEach { isPressed -> _buttonState.update { isPressed } }
     *             .launchIn(this)
     *
     *         // Read current Button state.
     *         try {
     *             val currentState = buttonCharacteristic.read()
     *             _buttonState.update { currentState.singleOrNull() == 1.toByte() }
     *         } catch (e: OperationFailedException) {
     *             println("Reading button characteristic failed: ${e.message}")
     *         }
     *
     *         // Handle LED state updates.
     *         ledState
     *             .map { isOn -> byteArrayOf(if (isOn) 1 else 0) }
     *             .onEach { value ->
     *                 try {
     *                     ledCharacteristic.write(value)
     *                 } catch (e: OperationFailedException) {
     *                     println("Writing LED characteristic failed: ${e.message}")
     *                 }
     *             }
     *             .launchIn(this)
     *     }
     * }
     * ```
     * ##### Usage
     * ```kotlin
     * val api: LedButton = LedButtonProfile()
     *    .also { peripheral.profile(it) }
     * ```
     *
     * @param scope The coroutine scope to launch the user block in.
     * @param profile The profile implementation.
     * @param required Whether support for this profile is required by the app.
     */
    fun profile(scope: CoroutineScope, profile: Profile, required: Boolean = true) = profile(
        scope = scope,
        requiredServiceUuids = profile.requiredServiceUuids,
        optionalServiceUuids = profile.optionalServiceUuids,
        required = required,
        name = profile.name,
        block = {services ->
            profile.execute(services, this)
        }
    )

    /**
     * Suspends until the peripheral is disconnected.
     *
     * @throws CancellationException if the current coroutine is canceled.
     */
    @IgnorableReturnValue
    suspend fun awaitDisconnection(): ConnectionState.Disconnected.Reason? = state
        .filterIsInstance<ConnectionState.Disconnected>()
        .first().reason

    /**
     * The maximum amount of data, in bytes, that can be sent to a characteristic in a single write
     * operation.
     *
     * Maximum value length depends on [WriteType] and is calculated as:
     * * *512 bytes* for [WriteType.WITH_RESPONSE],
     * * *ATT MTU - 3 bytes* for [WriteType.WITHOUT_RESPONSE],
     * * *ATT MTU - 15 bytes* for [WriteType.SIGNED] (additional 12 bytes for the signature).
     *
     * @throws PeripheralNotConnectedException if the peripheral is not connected.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     */
    abstract fun maximumWriteValueLength(type: WriteType): Int

    /**
     * Reads the received signal strength indicator (RSSI) of the peripheral.
     *
     * Usually, the RSSI value is between -120 dBm (vary far) and -30 dBm (very close),
     * but the exact value depends on the TX power, antenna, environment, and other factors.
     *
     * @return The RSSI value in dBm.
     * @throws PeripheralNotConnectedException if the peripheral is not connected.
     * @throws OperationFailedException If reading RSSI could not be initiated.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     */
    suspend fun readRssi(): Int = withCallSite("readRssi") {
        OperationMutex.withLock {
            impl.events
                .onSubscription {
                    logger?.trace(Layer.LINK) { "Reading RSSI" }
                    try {
                        if (!isConnected || !impl.readRssi()) {
                            throw PeripheralNotConnectedException()
                        }
                    } catch (e: Exception) {
                        logger?.error(Layer.LINK) { "Reading RSSI failed: ${e.message}" }
                        throw e
                    }
                }
                .takeWhile { !it.isDisconnectionEvent }
                .filterIsInstance<RssiRead>()
                // TODO add .timeout(...)?
                .firstOrNull()?.rssi
                ?.also { logger?.info(Layer.LINK) { "RSSI read: $it dBm" } }
                ?: throw PeripheralNotConnectedException()
        }
    }

    /**
     * Disconnects the client from the peripheral.
     *
     * Note, that calling this method does not guarantee that the peripheral will disconnect;
     * other clients, also in other applications, may still be connected to the peripheral.
     *
     * This method does nothing if the peripheral is already disconnected.
     *
     * Runs in [NonCancellable] coroutine context.
     *
     * Hint: Use [CentralManager.connect] to connect to the peripheral.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     */
    suspend fun disconnect() = disconnect(ConnectionState.Disconnected.Reason.Success)

    /**
     * Disconnects the client from the peripheral returning the given parameter as disconnection
     * reason.
     *
     * This method does nothing if the peripheral is already disconnected.
     *
     * Runs in [NonCancellable] coroutine context.
     *
     * @param reason The reason for disconnection. Use [Success][ConnectionState.Disconnected.Reason.Success]
     * when disconnection was initiated by the user.
     */
    internal suspend fun disconnect(reason: ConnectionState.Disconnected.Reason) = withCallSite("disconnect") {
        withContext(NonCancellable) {
            // Depending on the state...
            when (state.value) {
                is ConnectionState.Disconnected -> {
                    // Make sure auto-connection is closed.
                    close()
                    return@withContext
                }

                is ConnectionState.Disconnecting -> {
                    // Skip..
                }

                is ConnectionState.Connecting -> {
                    // Cancel the connection attempt.
                    logger?.trace(Layer.GAP) { "Cancelling connection to ${this@Peripheral}" }
                    _state.update { ConnectionState.Disconnecting }
                }

                is ConnectionState.Connected -> {
                    // Disconnect from the peripheral.
                    logger?.trace(Layer.GAP) { "Disconnecting from ${this@Peripheral}" }
                    _state.update { ConnectionState.Disconnecting }
                }
            }

            // Disconnect and wait until it is disconnected, then close.
            try {
                if (!impl.isClosed) {
                    await(
                        action = { impl.disconnect(reason) },
                        condition = { it.isDisconnected },
                        timeout = 500.milliseconds
                    )
                }
            } catch (e: TimeoutCancellationException) {
                if (!isDisconnected) {
                    logger?.warn(Layer.GAP) { "Disconnection takes longer than expected, closing" }
                }
            } finally {
                close()
                // If before calling disconnect() the state was not Connected (i.e. Connecting),
                // the state at this point will be Disconnecting. Change it to Disconnected manually.
                _state.compareAndSet(
                    expect = ConnectionState.Disconnecting,
                    update = ConnectionState.Disconnected(reason)
                )
            }
        }
    }

    // Other

    override fun toString(): String {
        return name ?: identifier.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Peripheral<*, *>) return false

        return identifier == other.identifier
    }

    override fun hashCode(): Int {
        return identifier.hashCode()
    }
}