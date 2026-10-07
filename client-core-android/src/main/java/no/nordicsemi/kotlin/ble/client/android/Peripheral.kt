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

@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package no.nordicsemi.kotlin.ble.client.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.ConnectionParametersChanged
import no.nordicsemi.kotlin.ble.client.ConnectionStateChanged
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.MtuChanged
import no.nordicsemi.kotlin.ble.client.Peripheral
import no.nordicsemi.kotlin.ble.client.PhyChanged
import no.nordicsemi.kotlin.ble.client.ReliableWriteCompleted
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.ServicesChanged
import no.nordicsemi.kotlin.ble.client.android.Peripheral.Executor
import no.nordicsemi.kotlin.ble.client.android.exception.BondingFailedException
import no.nordicsemi.kotlin.ble.client.android.exception.PeripheralClosedException
import no.nordicsemi.kotlin.ble.client.exception.ConnectionFailedException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.exception.PeripheralNotConnectedException
import no.nordicsemi.kotlin.ble.client.exception.ValueDoesNotMatchException
import no.nordicsemi.kotlin.ble.client.internal.OperationMutex
import no.nordicsemi.kotlin.ble.core.ATT_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.ATT_MTU_MAX
import no.nordicsemi.kotlin.ble.core.BondState
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.PeripheralType
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.core.internal.withCallSite
import no.nordicsemi.kotlin.ble.core.log.Layer
import org.jetbrains.annotations.Range
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Android-specific implementation of a peripheral.
 *
 * This class extends [Peripheral] and adds Android-specific methods.
 *
 * @param scope scope The coroutine scope.
 * @param impl The executor that provides methods to interact with the peripheral.
 */
open class Peripheral(
    scope: CoroutineScope,
    impl: Executor,
): Peripheral<String, Executor>(scope, impl) {

    /**
     * An interface that provides methods to interact with the peripheral.
     *
     * The implementation should initiate requests and report events using [events] flow.
     */
    interface Executor: Peripheral.Executor<String> {
        override val environment: AndroidEnvironment

        /** MAC address of the device. */
        val address: String
            get() = identifier

        /** The Bluetooth device type of the remote device. */
        val type: PeripheralType

        /**
         * Bonding state as a state flow.
         *
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         */
        val bondState: StateFlow<BondState>

        /**
         * Requests the connection priority to be changed.
         *
         * The result should be reported by emitting [ConnectionParametersChanged] event
         * to [events] flow.
         *
         * @param priority The new connection priority.
         * @return True if connection priority was requested successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         * @throws OperationFailedException If connection priority request failed.
         */
        suspend fun requestConnectionPriority(priority: ConnectionPriority): Boolean

        /**
         * Requests the MTU (Maximum Transmission Unit) to be set to the given value.
         *
         * The result should be reported by emitting [MtuChanged] event to [events] flow.
         *
         * @param mtu Requested MTU value.
         * @return True if MTU was requested successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         * @throws OperationFailedException If MTU request failed.
         */
        suspend fun requestMtu(mtu: @Range(from = 23, to = 517) Int): Boolean

        /**
         * Requests the PHY to be changed.
         *
         * The result should be reported by emitting [ConnectionParametersChanged] event
         * to [events] flow.
         *
         * @param txPhy The preferred transmitter PHY.
         * @param rxPhy The preferred receiver PHY.
         * @param phyOptions The preferred coding to use when transmitting on the LE Coded PHY.
         * @return True if PHY was requested successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         */
        suspend fun requestPhy(txPhy: Phy, rxPhy: Phy, phyOptions: PhyOption): Boolean

        /**
         * This method should initiate reading the current PHY parameters.
         *
         * The result should be reported by emitting [PhyChanged] event to [events] flow.
         *
         * @return True if reading PHY was requested successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         */
        suspend fun readPhy(): Boolean

        /**
         * This method should initiate a reliable write transaction.
         *
         * No event is expected to be emitted to [events] flow.
         *
         * @return True if the operation was successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         * @throws OperationFailedException If reliable write could not be started.
         */
        fun beginReliableWrite(): Boolean

        /**
         * This method should execute all queued reliable write operations.
         *
         * The result should be reported by emitting [ReliableWriteCompleted] event to [events] flow.
         *
         * @return True if the operation was successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         * @throws OperationFailedException If reliable write could not be executed.
         */
        suspend fun executeReliableWrite(): Boolean

        /**
         * This method should abort a reliable write transaction.
         *
         * The result should be reported by emitting [ReliableWriteCompleted] event to [events] flow.
         *
         * @return True if the operation was successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         */
        suspend fun abortReliableWrite(): Boolean

        /**
         * Refreshes services cache.
         *
         * @return True if the operation was successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         * @throws OperationFailedException If refreshing cache failed.
         * @throws PeripheralClosedException If the peripheral was closed.
         */
        suspend fun refreshCache(): Boolean

        /**
         * This method should initiate bonding with the peripheral.
         *
         * This method is guaranteed to be called only when the bond information does not exist.
         *
         * The result should be reported by emitting state [BondState.BONDED] (in case of a success)
         * or [BondState.NONE] (in case of a failure) to [bondState] flow.
         *
         * @return True if bond was requested successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         * @throws OperationFailedException If creating bond failed.
         */
        suspend fun createBond(): Boolean

        /**
         * This method should initiate removing bond information associated with the peripheral.
         *
         * It is expected, that removing bond information will terminate existing connection.
         *
         * This method is guaranteed to be called only when the bond information exists.
         *
         * The result should be reported by emitting state [BondState.NONE] to [bondState] flow.
         *
         * @return True if removing bond information has been initiated successfully; false otherwise.
         * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
         * @throws OperationFailedException If removing bond information failed.
         */
        suspend fun removeBond(): Boolean
    }

    override val identifier: String = impl.address

    /** MAC address of the peripheral as String, alias for [Peripheral.identifier]. */
    val address: String = impl.address

    /** The Bluetooth device type of the remote device. */
    val type: PeripheralType = impl.type

    /** Connection parameters of the peripheral as state flow. */
    private val _connectionParameters = MutableStateFlow<ConnectionParameters?>(null)
    /**
     * The current connection parameters as [StateFlow].
     *
     * The value is `null` if the peripheral is not connected. When connected, it is
     * [ConnectionParameters.Unknown] until the parameters change (Android 8+).
     */
    val connectionParameters: StateFlow<ConnectionParameters?> = _connectionParameters.whenConnected()

    /** The current PHY as state flow. */
    private val _phy = MutableStateFlow<PhyInUse?>(null)
    /**
     * The current PHY in use for transmitting and receiving data.
     *
     * The value is `null` if the peripheral is not connected. After connecting the PHY is read
     * (the initial PHY may be LE 1M or LE Coded), and then it is updated each time it changes,
     * either by [setPreferredPhy] or by the remote device or the system.
     */
    val phy: StateFlow<PhyInUse?> = _phy.whenConnected()

    /** Current MTU (Maximum Transmission Unit) value. */
    private var _mtu: Int = ATT_MTU_DEFAULT

    override fun currentMtu(): Int = _mtu

    /**
     * A flag set in [prepareConnection].
     *
     * This is to indicate, that the peripheral is already connected, but the [state]
     * doesn't yet reflect that.
     */
    private var isPreparingConnection = false

    /** Whether the highest MTU should be requested for each connection, including reconnections. */
    private var automaticallyRequestHighestValueLength = false

    /**
     * Returns a flow that emits the value of this flow when the [state] is
     * [ConnectionState.Connected], and `null` otherwise.
     *
     * Some values are set while the connection is being prepared, before the state changes.
     */
    private fun <T: Any> StateFlow<T?>.whenConnected(): StateFlow<T?> =
        combine(state, this) { state, value -> value.takeIf { state.isConnected } }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    // Common implementation

    /**
     * Initiates a connection to the peripheral.
     *
     * This is a no-op if the peripheral is already connected.
     *
     * @param options The connection options.
     * @throws ConnectionFailedException If connection failed. See [ConnectionFailedException.reason]
     * for a reason.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @throws CancellationException If the coroutine was canceled.
     * @throws TimeoutCancellationException If the connection attempt timed out.
     */
    internal suspend fun connect(options: CentralManager.ConnectionOptions) {
        // Check if the peripheral isn't already connected or has a pending connection.
        if (state.value is ConnectionState.Connected) {
            return
        }

        // Start connection attempt, based on the connection options.
        logger?.trace(Layer.GAP) { "Connecting to $this using $options" }
        automaticallyRequestHighestValueLength = options.automaticallyRequestHighestValueLength
        _state.update { ConnectionState.Connecting }
        when (options) {
            // In case of auto connect, the connection attempt does not time out.
            // Cancel the coroutine to abort.
            is CentralManager.ConnectionOptions.AutoConnect -> {
                try {
                    val state = await(
                        action = {
                            impl.connect(
                                autoConnect = true,
                                autoMtu = options.automaticallyRequestHighestValueLength,
                                opportunistic = options.opportunistic
                            )
                        },
                        condition = { it.isConnected || it.isDisconnected },
                    )
                    when (state) {
                        is ConnectionState.Connected -> {
                            logger?.info(Layer.GAP) { "Connected to $this" }
                            // Request MTU, read PHY, etc. before reporting connected state.
                            if (!prepareConnection()) {
                                logger?.warn(Layer.GAP) { "Connection attempt failed (reason: ${Reason.LinkLoss})" }
                                _state.update { ConnectionState.Disconnected(Reason.LinkLoss) }
                                throw ConnectionFailedException(Reason.LinkLoss)
                            }
                            _state.update { ConnectionState.Connected }
                            // Since we're connected, let's start collecting GATT events, including
                            // connection state changes. The device may disconnect and reconnect at
                            // any time. To stop collecting the events one needs to call disconnect().
                            startCollectingGattEvents(closeWhenDisconnected = false)
                            initiateConnection()
                        }
                        is ConnectionState.Disconnected -> {
                            // RPA (Resolvable Private Address) can rotate, causing address to "expire" in the
                            // background connection list. RPA is allowed for direct connect, as such request
                            // times out after 30 seconds.
                            //
                            // Android returns status 133 or 135 when the address is not supported
                            // in background connection. It is possible to connect to such device using
                            // direct connection.
                            //
                            // See: https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/Bluetooth/system/stack/gatt/gatt_api.cc;l=1450
                            val reason = state.reason!!
                            if (reason is Reason.Unknown && (reason.status == 133 || reason.status == 135)) {
                                logger?.warn(Layer.GAP) { "Connection attempt failed (reason: ${Reason.UnsupportedAddress})" }
                                _state.update { ConnectionState.Disconnected(Reason.UnsupportedAddress) }
                                throw ConnectionFailedException(Reason.UnsupportedAddress)
                            }
                            logger?.warn(Layer.GAP) { "Connection attempt failed (reason: $reason)" }
                            _state.update { state }
                            throw ConnectionFailedException(reason)
                        }
                        else -> {}
                    }
                } catch (e: TimeoutCancellationException) {
                    // Although the connection using AutoConnect does not time out on its own,
                    // it's still possible to wrap it in withTimeout. Report this as a timeout, not cancellation.
                    logger?.warn(Layer.GAP) { "Connection attempt timed out" }
                    _state.update { ConnectionState.Disconnected(Reason.Timeout(e.timeout ?: Duration.ZERO)) }
                    close()
                    throw e
                } catch (e: CancellationException) {
                    logger?.warn(Layer.GAP) { "Connection attempt cancelled" }
                    _state.update { ConnectionState.Disconnected(Reason.Cancelled) }
                    close()
                    throw e
                }
            }

            // Direct connection gives more options to configure the connection.
            is CentralManager.ConnectionOptions.Direct -> {
                val now = System.currentTimeMillis()
                try {
                    val state = await(
                        action = {
                            impl.connect(
                                autoConnect = false,
                                autoMtu = options.automaticallyRequestHighestValueLength,
                                opportunistic = options.opportunistic,
                            )
                        },
                        condition = { it.isConnected || it.isDisconnected },
                        timeout = options.timeout,
                    )
                    when (state) {
                        is ConnectionState.Connected -> {
                            logger?.info(Layer.GAP) { "Connected to $this" }
                            // Request MTU, read PHY, etc. before reporting connected state.
                            if (!prepareConnection()) {
                                logger?.warn(Layer.GAP) { "Connection attempt failed (reason: ${Reason.LinkLoss})" }
                                _state.update { ConnectionState.Disconnected(Reason.LinkLoss) }
                                throw ConnectionFailedException(Reason.LinkLoss)
                            }
                            _state.update { state }
                            // Since we're connected, let's start collecting GATT events.
                            // In case of a direct connection, a disconnection will cancel
                            // event collection and close the peripheral.
                            startCollectingGattEvents()
                            initiateConnection()
                        }
                        is ConnectionState.Disconnected -> {
                            val reason = state.reason!!
                            // A connection may time out for 3 reasons: Direct(timeout), withTimeout,
                            // or internal timeout (error 133/147 after ~30s). The library should
                            // report all 3 cases the same way: as a TimeoutCancellationException.
                            // Error 147 was added in API 35: https://developer.android.com/reference/android/bluetooth/BluetoothGatt#GATT_CONNECTION_TIMEOUT
                            // Before, a connection timeout was reported as error 133.
                            if (reason is Reason.Unknown && (reason.status == 133 || reason.status == 147)) {
                                val elapsed = System.currentTimeMillis() - now
                                // Default timeout for direct connection is 30 seconds. Let's use 25.
                                if (elapsed >= 25000) {
                                    // The connection timeout should behave just as a timeout defined
                                    // by the user with withTimeout or Direct(timeout=...).
                                    withTimeout(Duration.ZERO) {}
                                    // ^ throws TimeoutCancellationException("Timed out immediately")!!!
                                }
                            }
                            check(options.retry > 0) {
                                logger?.warn(Layer.GAP) { "Connection attempt failed (reason: $reason)" }
                                _state.update { state }
                                throw ConnectionFailedException(reason)
                            }
                            logger?.warn(Layer.GAP) { "Connection attempt failed (reason: ${state.reason}), retrying in ${options.retryDelay}..." }
                            delay(options.retryDelay)
                            connect(options.copy(retry = options.retry - 1))
                        }
                        else -> {}
                    }
                } catch (e: TimeoutCancellationException) {
                    val elapsed = System.currentTimeMillis() - now
                    logger?.warn(Layer.GAP) { "Connection attempt timed out after ${e.timeout ?: elapsed.milliseconds}" }
                    _state.update { ConnectionState.Disconnected(Reason.Timeout(e.timeout ?: elapsed.milliseconds)) }
                    close()
                    throw e
                } catch (e: CancellationException) {
                    logger?.warn(Layer.GAP) { "Connection attempt cancelled" }
                    _state.update { ConnectionState.Disconnected(Reason.Cancelled) }
                    close()
                    throw e
                }
            }
        }
    }

    override suspend fun handle(event: GattEvent) = when (event) {
        is ReliableWriteCompleted -> impl.isReliableWriteEnabled = false
        is MtuChanged -> _mtu = event.mtu
        is PhyChanged -> _phy.update { event.phy }
        is ConnectionParametersChanged -> _connectionParameters.update { event.newParameters }
        is ConnectionStateChanged -> {
            if (event.isPhyRequestError) {
                super.handle(ConnectionStateChanged(ConnectionState.Disconnected(reason = Reason.UnsupportedConfiguration)))
            } else {
                super.handle(event)
            }
        }
        else -> super.handle(event)
    }

    final override suspend fun prepareConnection(): Boolean {
        isPreparingConnection = true
        try {
            // Initial connection parameters are not know.
            // An event will be emitted when they change.
            _connectionParameters.update { ConnectionParameters.Unknown }
            // Request MTU if set in ConnectionOptions.
            if (automaticallyRequestHighestValueLength && !runCatchingOnConnection { requestHighestValueLength() }) {
                return false
            }
            // Read initial PHY to feed the `phy` flow.
            return runCatchingOnConnection { _phy.update { readPhy() } }
        } finally {
            isPreparingConnection = false
        }
    }

    override fun handleDisconnection() {
        super.handleDisconnection()
        _mtu = ATT_MTU_DEFAULT
        _phy.update { null }
        _connectionParameters.update { null }
    }

    /**
     * Reads the current transmitter PHY and receiver PHY of the connection.
     *
     * The result is also reported to [phy] state flow.
     *
     * PHY LE 2M or PHY Coded is supported since Android 8.0 (API level 26) or later.
     *
     * @param timeout The timeout. Defaults to 3 seconds.
     * @return The PHY in use for transmitting and receiving data.
     * @throws PeripheralNotConnectedException If the device is not connected.
     * @throws OperationFailedException If PHY could not be read.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @throws TimeoutCancellationException If the response has not been received within the specified timeout.
     */
    private suspend fun readPhy(timeout: Duration = 3.seconds): PhyInUse = withCallSite("readPhy") {
        OperationMutex.withLock {
            impl.events
                .onSubscription {
                    logger?.trace(Layer.PHY) { "Reading PHY" }
                    try {
                        if ((!isPreparingConnection && !isConnected) || !impl.readPhy()) {
                            throw PeripheralNotConnectedException()
                        }
                    } catch (e: Exception) {
                        logger?.error(Layer.PHY) { "Requesting PHY read failed: ${e.message}" }
                        throw e
                    }
                }
                .takeWhile { !it.isDisconnectionEvent }
                .filterIsInstance<PhyChanged>()
                .timeout(timeout)
                .catch { t ->
                    if (t is TimeoutCancellationException) {
                        logger?.warn(Layer.PHY) { "PHY read timed out" }
                    }
                    throw t
                }
                .firstOrNull()?.phy
                ?.also { logger?.info(Layer.PHY) { "PHY read: $it" } }
                ?: throw PeripheralNotConnectedException()
        }
    }

    /**
     * Set the preferred connection PHY.
     *
     * PHY LE 2M or PHY Coded is supported since Android 8.0 (API level 26) or later.
     * Other devices will continue to use the only PHY they support, that is [Phy.PHY_LE_1M].
     *
     * Please note that this is just a recommendation, whether the PHY change will happen depends
     * on other applications preferences, local and remote controller capabilities.
     * Controller can override these settings.
     *
     * @param txPhy The preferred transmitter PHY.
     * @param rxPhy The preferred receiver PHY. By default, it is the same as [txPhy].
     * @param phyOptions The preferred coding to use when transmitting on the LE Coded PHY.
     * @param timeout The timeout. Defaults to 3 seconds.
     * @return The PHYs in use after the change.
     * @throws PeripheralNotConnectedException If the device is not connected.
     * @throws OperationFailedException If PHY change could not be requested.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @throws TimeoutCancellationException If the response has not been received within the
     * specified timeout.
     */
    suspend fun setPreferredPhy(
        txPhy: Phy,
        rxPhy: Phy = txPhy,
        phyOptions: PhyOption = PhyOption.NO_PREFERRED,
        timeout: Duration = 3.seconds,
    ): PhyInUse = withCallSite("setPreferredPhy") {
        OperationMutex.withLock {
            impl.events
                .onSubscription {
                    logger?.trace(Layer.PHY) { "Setting preferred PHY: tx=$txPhy, rx=$rxPhy, options=$phyOptions" }
                    try {
                        if (!isConnected || !impl.requestPhy(txPhy, rxPhy, phyOptions)) {
                            throw PeripheralNotConnectedException()
                        }
                    } catch (e: Exception) {
                        logger?.error(Layer.PHY) { "Requesting PHY change failed: ${e.message}" }
                        throw e
                    }
                }
                .takeWhile { !it.isDisconnectionEvent }
                .filterIsInstance<PhyChanged>()
                .timeout(timeout)
                .catch { t ->
                    if (t is TimeoutCancellationException) {
                        logger?.warn(Layer.PHY) { "PHY request timed out" }
                    }
                    throw t
                }
                .firstOrNull()?.phy
                ?.also { logger?.info(Layer.PHY) { "PHY changed to: $it" } }
                ?: throw PeripheralNotConnectedException()
        }
    }

    /**
     * The maximum amount of data, in bytes, that can be sent to a characteristic in a single write
     * operation.
     *
     * Maximum value length depends on [WriteType] and is calculated as:
     * * *512 bytes* for [WriteType.WITH_RESPONSE],
     * * *ATT MTU - 3 bytes* for [WriteType.WITHOUT_RESPONSE],
     * * *ATT MTU - 15 bytes* for [WriteType.SIGNED] (additional 12 bytes for the signature).
     *
     * Connect with [CentralManager.ConnectionOptions.automaticallyRequestHighestValueLength] to
     * request maximum supported value length on connection, or use [requestHighestValueLength] to
     * request it during connection.
     *
     * @throws PeripheralNotConnectedException If the device is not connected.
     * @see mtu
     */
    override fun maximumWriteValueLength(type: WriteType): Int {
        check(isConnected) {
            throw PeripheralNotConnectedException()
        }
        return when (type) {
            WriteType.WITH_RESPONSE -> 512
            WriteType.WITHOUT_RESPONSE -> min(_mtu - 3, 512)
            WriteType.SIGNED -> _mtu - 15
        }
    }

    /**
     * Requests the highest possible MTU ([517][ATT_MTU_MAX]).
     *
     * The highest MTU will be automatically requested when the peripheral is reconnected
     * when connected using [automaticallyRequestHighestValueLength][CentralManager.ConnectionOptions.automaticallyRequestHighestValueLength]
     * option.
     *
     * #### Note
     *
     * Although it used to be possible to request any value of MTU from 23 to 517, since Android 14
     * the system will always request value 517 ignoring the requested value. Hence, this method
     * does not allow to set custom MTU value.
     *
     * #### Important
     *
     * It is known that some Android devices (i.e. Samsung Galaxy Tab A8) fail to negotiate
     * L2CAP MTU (packet size on the Link Layer) using Data Length Extension (DLE). They mistakenly
     * claim supporting only 27 bytes of TX, but then try to send up to 251 bytes, causing the
     * connection to terminate. For such devices it is recommended not to request higher MTU
     * or never sending more than 20 bytes in a single write operation.
     *
     * @param timeout The timeout. Defaults to 3 seconds.
     * @throws PeripheralNotConnectedException If the device is not connected.
     * @throws OperationFailedException If MTU could not be requested.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @throws TimeoutCancellationException If the response has not been received within the
     * specified timeout.
     * @see maximumWriteValueLength
     * @see CentralManager.ConnectionOptions.automaticallyRequestHighestValueLength
     */
    suspend fun requestHighestValueLength(timeout: Duration = 3.seconds) {
        // If automatic higher value length is requested, this method is called before
        // the state changes to Connected. This is to make the MTU ready when user gets receives
        // Connected state. Don't check for isConnected here.
        check(_mtu == ATT_MTU_DEFAULT) {
            logger?.warn(Layer.GATT) { "MTU has been already requested" }
            return
        }
        _mtu = withCallSite("requestHighestValueLength") {
            OperationMutex.withLock {
                impl.events
                    .onSubscription {
                        logger?.trace(Layer.GATT) { "Requesting MTU: $ATT_MTU_MAX" }
                        try {
                            if ((!isPreparingConnection && !isConnected) || !impl.requestMtu(ATT_MTU_MAX)) {
                                throw PeripheralNotConnectedException()
                            }
                        } catch (e: Exception) {
                            logger?.error(Layer.GATT) { "Requesting MTU failed: ${e.message}" }
                            throw e
                        }
                    }
                    .takeWhile { !it.isDisconnectionEvent }
                    .filterIsInstance<MtuChanged>()
                    .timeout(timeout)
                    .catch { t ->
                        if (t is TimeoutCancellationException) {
                            logger?.warn(Layer.PHY) { "MTU request timed out" }
                        }
                        throw t
                    }
                    .firstOrNull()?.mtu
                    ?.also { logger?.info(Layer.GATT) { "MTU set to $it" } }
                    ?: throw PeripheralNotConnectedException()
            }
        }
    }

    /**
     * Requests new connection parameters.
     *
     * Android API does not allow to set custom connection parameters. Instead, predefined
     * connection priorities can be requested. Corresponding values for each priority are
     * may differ between Android versions and devices.
     *
     * On Android versions prior to Android 8 (Oreo) the updated connection parameters are not
     * returned to the app, therefore the returned value will be [ConnectionParameters.Unknown].
     *
     * @param priority The new connection priority.
     * @param timeout The timeout. Defaults to 3 seconds.
     * @return The new connection parameters.
     * @throws PeripheralNotConnectedException If the device is not connected.
     * @throws OperationFailedException If connection priority could not be requested.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @throws TimeoutCancellationException If the response has not been received within the
     * specified timeout.
     */
    @IgnorableReturnValue
    suspend fun requestConnectionPriority(
        priority: ConnectionPriority,
        timeout: Duration = 3.seconds,
    ): ConnectionParameters =
        withCallSite("requestConnectionPriority") {
            OperationMutex.withLock {
                impl.events
                    .onSubscription {
                        logger?.trace(Layer.LINK) { "Requesting connection priority: $priority" }
                        try {
                            if (!isConnected || !impl.requestConnectionPriority(priority)) {
                                throw PeripheralNotConnectedException()
                            }
                        } catch (e: Exception) {
                            logger?.error(Layer.LINK) { "Requesting connection priority failed: ${e.message}" }
                            throw e
                        }
                    }
                    .takeWhile { !it.isDisconnectionEvent }
                    .filterIsInstance<ConnectionParametersChanged>()
                    .timeout(timeout)
                    .catch { t ->
                        if (t is TimeoutCancellationException) {
                            logger?.warn(Layer.PHY) { "Connection parameters request timed out" }
                        }
                        throw t
                    }
                    .firstOrNull()?.newParameters
                    ?.also { logger?.info(Layer.LINK) { "Connection parameters updated: $it" } }
                    ?: throw PeripheralNotConnectedException()
            }
    }

    /**
     * Initiates a reliable write transaction.
     *
     * The purpose of *Reliable Write* is to queue up writes of values of one or multiple
     * attributes (characteristics or descriptors) in a first-in first-out queue and then execute
     * the write on all of them in a single atomic operation.
     *
     * After calling this method, all write with response operations performed on characteristics
     * and descriptors will use *Prepare Write Request* PDUs instead of *Write Request*.
     *
     * Call [executeReliableWrite] or [abortReliableWrite] to commit or cancel the transaction.
     *
     * If any of the write operations throws [ValueDoesNotMatchException], the whole transaction
     * will be aborted.
     *
     * @throws PeripheralNotConnectedException If the device is not connected.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @see executeReliableWrite
     * @see abortReliableWrite
     */
    // TODO IsValueDoesNotMatchException truly thrown?
    fun beginReliableWrite() {
        logger?.trace(Layer.GATT) { "Beginning reliable write" }
        try {
            if (!impl.beginReliableWrite()) {
                throw PeripheralNotConnectedException()
            }
        } catch (e: Exception) {
            logger?.error(Layer.GATT) {
                "Beginning reliable write failed: ${e.message}"
            }
            throw e
        }
    }

    /**
     * Executes all write with response operations queued since [beginReliableWrite] was called.
     *
     * The queued writes will be committed in a single atomic operation.
     *
     * @throws PeripheralNotConnectedException If the device is not connected.
     * @throws OperationFailedException If reliable write could not be executed.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @see beginReliableWrite
     * @see executeReliableWrite
     */
    suspend fun executeReliableWrite() {
        check(impl.isReliableWriteEnabled) {
            logger?.warn(Layer.GATT) { "Reliable write not in progress, nothing to execute" }
            return
        }
        val _ = withCallSite("executeReliableWrite") {
            OperationMutex.withLock {
                impl.events
                    .onSubscription {
                        logger?.trace(Layer.GATT) { "Executing reliable write" }
                        try {
                            if (!isConnected || !impl.executeReliableWrite()) {
                                throw PeripheralNotConnectedException()
                            }
                        } catch (e: Exception) {
                            logger?.error(Layer.GATT) { "Executing reliable write failed: ${e.message}" }
                            throw e
                        }
                    }
                    .takeWhile { !it.isDisconnectionEvent }
                    .filterIsInstance<ReliableWriteCompleted>()
                    // TODO add .timeout(...)?
                    // Note: Here we can't use `.let`. It returns the result of `when`, which is
                    //       `null` if `logger` is not set, causing the whole method to throw
                    //       `PeripheralNotConnectedException`.
                    .firstOrNull()?.also {
                        when (it.status) {
                            OperationStatus.Success -> logger?.info(Layer.GATT) { "Reliable write executed successfully" }
                            else -> {
                                logger?.warn(Layer.GATT) { "Reliable write failed: ${it.status}" }
                                throw OperationFailedException(it.status)
                            }
                        }
                    } ?: throw PeripheralNotConnectedException()
            }
        }
    }

    /**
     * Aborts the reliable write transaction started using [beginReliableWrite].
     *
     * All queued write operations will be discarded on the peripheral.
     *
     * @throws PeripheralNotConnectedException If the device is not connected.
     * @throws OperationFailedException If reliable write could not be aborted.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     * @see beginReliableWrite
     * @see executeReliableWrite
     */
    suspend fun abortReliableWrite() {
        check(impl.isReliableWriteEnabled) {
            logger?.warn(Layer.GATT) { "Reliable write not in progress, nothing to abort" }
            return
        }
        val _ = withCallSite("abortReliableWrite") {
            OperationMutex.withLock {
                impl.events
                    .onSubscription {
                        logger?.trace(Layer.GATT) { "Aborting reliable write" }
                        try {
                            if (!isConnected || !impl.abortReliableWrite()) {
                                throw PeripheralNotConnectedException()
                            }
                        } catch (e: Exception) {
                            logger?.error(Layer.GATT) { "Aborting reliable write failed: ${e.message}" }
                            throw e
                        }
                    }
                    .takeWhile { !it.isDisconnectionEvent }
                    .filterIsInstance<ReliableWriteCompleted>()
                    // TODO add .timeout(...)?
                    // Note: Here we can't use `.let`. It returns the result of `when`, which is
                    //       `null` if `logger` is not set, causing the whole method to throw
                    //       `PeripheralNotConnectedException`.
                    .firstOrNull()?.also {
                        when (it.status) {
                            OperationStatus.Success -> logger?.info(Layer.GATT) { "Reliable write aborted successfully" }
                            else -> {
                                logger?.warn(Layer.GATT) { "Aborting reliable write failed: ${it.status}" }
                                throw OperationFailedException(it.status)
                            }
                        }
                    } ?: throw PeripheralNotConnectedException()
            }
        }
    }

    /**
     * Refreshes the cached GATT database associated with the peripheral and starts new service
     * discovery automatically.
     *
     * All observers subscribed to invalidated attributes will be canceled. The flows returned
     * by [services] will emit an empty list of services following by updated list of services
     * when the new service discovery is complete.
     *
     * It is safe to call this method when the peripheral is connected, connecting, or disconnecting.
     * It may be called when the device is disconnected but only when the connection was made using
     * [AutoConnect][CentralManager.ConnectionOptions.AutoConnect] option in which case the system
     * is trying to reconnect.
     *
     * A connection made using [Direct][CentralManager.ConnectionOptions.Direct] option closes
     * automatically immediately after disconnection.
     *
     * When invoked on a closed connection the method throws [PeripheralClosedException].
     *
     * @throws PeripheralClosedException If the peripheral is closed.
     * @throws OperationFailedException If cache could not be refreshed.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     */
    suspend fun refreshCache() = withCallSite("refreshCache") {
        val _ = OperationMutex.withLock {
            impl.events
                .onSubscription {
                    logger?.trace(Layer.GATT) { "Refreshing cache" }
                    try {
                        if (impl.isClosed || !impl.refreshCache()) {
                            throw PeripheralClosedException()
                        }
                    } catch (e: Exception) {
                        // Reflection exception has a cause, which is more important.
                        val reason = e.cause?.message ?: e.message
                        logger?.error(Layer.GATT, e) { "Refreshing cache failed: $reason" }
                        throw e
                    }
                }
                // TODO add .timeout(...)?
                .first { it == ServicesChanged }
                .also { logger?.info(Layer.GATT) { "Cache refreshed" } }
        }
    }

    /**
     * Initiates bonding with the peripheral.
     *
     * @throws BondingFailedException If bonding failed.
     * @throws OperationFailedException If bonding could not be started.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     */
    suspend fun createBond() {
        if (hasBondInformation) {
            return
        }
        withCallSite("createBond") {
           val _ = OperationMutex.withLock {
                impl.bondState
                    .onSubscription {
                        logger?.trace(Layer.SMP) { "Creating bond" }
                        try {
                            if (!impl.createBond()) {
                                // The device doesn't need to be connected for the bond
                                // request to be successful, hence OperationFailedException, not
                                // PeripheralNotConnectedException.
                                throw OperationFailedException(OperationStatus.RequestFailed)
                            }
                        } catch (e: Exception) {
                            // Reflection exception has a cause, which is more important.
                            val reason = e.cause?.message ?: e.message
                            logger?.error(Layer.SMP) { "Bonding failed: $reason" }
                            throw e
                        }
                    }
                    // Skip the initial state. It should transition to BONDING quickly.
                    .dropWhile { it == BondState.NONE }
                    // Now, await for the next state after BONDING.
                    .first { it != BondState.BONDING }
                    // And process it.
                    .also {
                        when (it) {
                            BondState.BONDED -> logger?.info(Layer.SMP) { "Bond created" }
                            BondState.NONE -> {
                                logger?.warn(Layer.SMP) { "Bonding failed" }
                                throw BondingFailedException()
                            }
                            else -> { /* Not possible */ }
                        }
                    }
            }
        }
    }

    /**
     * Removes the bond information associated with the peripheral.
     *
     * This method will disconnect the peripheral if it was connected.
     *
     * @throws OperationFailedException If bond information could not be removed.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied, or if
     * BLUETOOTH_PRIVILEGED permission is denied on Android 17+.
     */
    suspend fun removeBond() {
        if (!hasBondInformation) {
            return
        }
        check(impl.environment.allowsBondRemoval) {
            throw SecurityException("BLUETOOTH_PRIVILEGED permission is required to remove bond information on Android 17+")
        }
        withCallSite("removeBond") {
            val _ = OperationMutex.withLock {
                impl.bondState
                    .onSubscription {
                        logger?.trace(Layer.SMP) { "Removing bond information" }
                        try {
                            if (!impl.removeBond()) {
                                // The device doesn't need to be connected for the bond
                                // request to be successful, hence OperationFailedException, not
                                // PeripheralNotConnectedException.
                                throw OperationFailedException(OperationStatus.RequestFailed)
                            }
                        } catch (e: Exception) {
                            // Reflection exception has a cause, which is more important.
                            val reason = e.cause?.message ?: e.message
                            logger?.error(Layer.SMP, e) { "Failed to remove bond information: $reason" }
                            throw e
                        }
                    }
                    .first { it == BondState.NONE }
                    .also { logger?.info(Layer.SMP) { "Bond information removed" } }
            }
        }
    }

    /**
     * Returns whether the system has bond information associated with this peripheral.
     *
     * #### Security Note
     * Having a bond information does not guarantee that the connection to the device is secure.
     * Android should terminate the connection to a device for which it has a bond information
     * if encryption cannot be resumed, but some devices don't do that. Instead, the bond state
     * is set to [BondState.BONDED] and the connection is kept open without any security.
     */
    val hasBondInformation: Boolean
        get() = bondState.value == BondState.BONDED

    /**
     * Returns the current bond state as [StateFlow].
     *
     * #### Security Note
     * State [BondState.BONDED] does not guarantee that the connection to the device is secure.
     * Some Android devices allow connecting to bonded devices without restoring encryption,
     * or they remove the bond information when it fails.
     */
    val bondState: StateFlow<BondState>
        get() = impl.bondState

    /**
     * A helper property to extract the timeout from the [TimeoutCancellationException] message.
     */
    private val TimeoutCancellationException.timeout: Duration?
        get() = message?.let { message ->
            val regex = Regex("""\d+""")
            val match = regex.find(message)
            return match?.value?.toLongOrNull()?.milliseconds
        }

    /**
     * Checks if the disconnection event is caused by a PHY request error.
     *
     * Samsung S8 with Android 9 fails to reconnect to a peripheral that requested PHY LE 2M
     * immediately after establishing the connection. It replies with PhyResponse with Instant
     * from the past, causing the peripheral to drop the connection.
     */
    private val ConnectionStateChanged.isPhyRequestError: Boolean
        get() = (newState as? ConnectionState.Disconnected)?.let {
            // Returned error is 0x08 (TIMEOUT).
            it.reason == Reason.LinkLoss &&
            // This happens before the services are discovered,
            _services.value !is RemoteServices.Discovered &&
            // ...but after the app is notified about change to PHY LE 2M.
            _phy.value?.txPhy == Phy.PHY_LE_2M
        } ?: false
}