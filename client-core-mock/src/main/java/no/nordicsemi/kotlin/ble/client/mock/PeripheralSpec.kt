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

package no.nordicsemi.kotlin.ble.client.mock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.client.CentralManager
import no.nordicsemi.kotlin.ble.client.ConnectionParametersChanged
import no.nordicsemi.kotlin.ble.client.ConnectionStateChanged
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.MtuChanged
import no.nordicsemi.kotlin.ble.client.PhyChanged
import no.nordicsemi.kotlin.ble.client.ReliableWriteCompleted
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.RssiRead
import no.nordicsemi.kotlin.ble.client.ServicesChanged
import no.nordicsemi.kotlin.ble.client.ServicesDiscovered
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicChanged
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteService
import no.nordicsemi.kotlin.ble.client.mock.internal.MockScanResult
import no.nordicsemi.kotlin.ble.core.ATT_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.Bluetooth5AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.LL_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.PeripheralType
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.Service
import no.nordicsemi.kotlin.ble.core.ServiceScope
import no.nordicsemi.kotlin.ble.core.internal.CCCD
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.core.mock.AdvertisingDataScope
import no.nordicsemi.kotlin.ble.core.mock.MockEnvironment
import no.nordicsemi.kotlin.ble.core.mock.internal.AdvertisingDataScopeImpl
import org.jetbrains.annotations.Range
import kotlin.math.ceil
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * A specification of a peripheral that can be simulated.
 *
 * Use [PeripheralSpec.simulatePeripheral] to create a new peripheral specification.
 *
 * @param identifier The peripheral identifier.
 * @param addressType The address type of the peripheral.
 * @property type The peripheral type.
 * @param initialProximity The initial proximity of the peripheral.
 * @property name The name of the peripheral, exposed with Device Name characteristic.
 * @property appearance The appearance of the peripheral, exposed with Appearance characteristic.
 * See Bluetooth Assigned Numbers:
 * [link](https://www.bluetooth.com/specifications/assigned-numbers/), 2.6 Appearance Values.
 * @property preferredConnectionInterval The preferred connection interval range.
 * @property preferredSlaveLatency The preferred slave latency.
 * @property preferredSupervisionTimeout The preferred supervision timeout.
 * @property maxAttMtu The maximum supported ATT MTU (ATT layer Maximum Transfer Unit).
 * @property maxLlMtu The maximum Link Layer MTU (Maximum Transfer Unit used on Link Layer using
 * Data Length Extension (DLE)).
 * @property supportedPhy The set of supported PHYs.
 * @param isInitiallyConnected Whether the peripheral is initially connected.
 * @param isKnown Whether the peripheral has been scanned before and its address type is cached.
 * It is not possible to connect to an unknown device without scanning.
 * @param isBonded Whether the peripheral is bonded.
 * @param advertisingSets List of advertisement configurations.
 * @param eventHandler The event handler that will be called for every event emulating
 * a real peripheral.
 * @param services The services available after service discovery.
 * @param cachedServices The services cached on the client side.
 * @param isServiceCacheValid Whether the cached services are valid.
 */
class PeripheralSpec<ID: Any> private constructor(
    identifier: ID,
    addressType: AddressType,
    val type: PeripheralType,
    initialProximity: Proximity,
    val name: String?,
    val appearance: Int?,
    val preferredConnectionInterval: IntRange?,
    val preferredSlaveLatency: Int?,
    val preferredSupervisionTimeout: Int?,
    val maxAttMtu: Int?,
    val maxLlMtu: Int?,
    val supportedPhy: Set<Phy>,
    isInitiallyConnected: Boolean,
    isKnown: Boolean,
    isBonded: Boolean,
    internal val advertisingSets: List<MockAdvertisingSet>?,
    internal val eventHandler: PeripheralSpecEventHandler?,
    private var services: List<ServiceDefinition>?,
    private val cachedServices: List<ServiceDefinition>?,
    isServiceCacheValid: Boolean,
) {
    /** A flow of GATT events emitted by the peripheral. */
    private val _events: MutableSharedFlow<GattEvent> = MutableSharedFlow(extraBufferCapacity = 64)
    val events: SharedFlow<GattEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

    /** The peripheral identifier. */
    var identifier: ID = identifier
        private set

    /** The address type of the peripheral. */
    var addressType: AddressType = addressType
        private set

    /** The proximity of the peripheral. */
    var proximity: Proximity = initialProximity
        private set(value) {
            field = value

            if (field == Proximity.OUT_OF_RANGE && isConnected) {
                val supervisionTimeout = connectionParameters!!.supervisionTimeoutMillis.milliseconds

                scope.launch {
                    // Simulate supervision timeout delay before notifying disconnection.
                    delay(supervisionTimeout)

                    // Check if still out of range.
                    if (field == Proximity.OUT_OF_RANGE && isConnected) {
                        // TODO Should the connection be dropped immediately, or after supervision timeout? See simulateReset().
                        connectionsCount = 0
                        eventHandler!!.onConnectionLost(DisconnectionReason.Timeout)
                        _events.emit(ConnectionStateChanged(ConnectionState.Disconnected(Reason.LinkLoss)))
                    }
                }
            }
        }

    /**
     * Whether the peripheral has been scanned before and its address type is cached.
     *
     * Unknown devices have assumed [AddressType.PUBLIC] address type.
     *
     * It is not possible to connect to an unknown device with address type other than
     * [AddressType.PUBLIC] without scanning it first (which sets this flag).
     *
     * @see simulateCaching
     */
    var isKnown: Boolean = isKnown
        private set

    /** Whether the peripheral is bonded. */
    var isBonded: Boolean = isBonded
        private set

    /** Whether the peripheral is connected. */
    val isConnected: Boolean
        get() = connectionsCount > 0

    /**
     * Number of active virtual connections to the peripheral.
     *
     * This is used to simulate multiple clients connecting to the same peripheral.
     *
     * Calling [simulateReset] sets this value to 0.
     */
    private var connectionsCount = if (isInitiallyConnected) 1 else 0
        set(value) {
            field = value.coerceAtLeast(0)

            // If no more connections, reset connection parameters.
            if (field == 0) {
                phy = null
                mtu = null
                llMtu = null
                connectionParameters = null
            } else if (mtu == null) {
                // If first connection, set default parameters.
                phy = Phy.PHY_LE_1M
                mtu = ATT_MTU_DEFAULT
                llMtu = LL_MTU_DEFAULT
                connectionParameters = ConnectionParameters.Specified(
                    connectionInterval = preferredConnectionInterval!!.first,
                    latency = preferredSlaveLatency!!,
                    supervisionTimeout = preferredSupervisionTimeout!!
                )
            }
        }

    /** Current connection parameters. */
    var connectionParameters: ConnectionParameters.Specified? = null
        private set

    /** Current ATT MTU. */
    var mtu: Int? = null
        private set

    /** Current Link Layer MTU. */
    var llMtu: Int? = null
        private set

    /** The currently used PHY. */
    var phy: Phy? = null
        private set

    /**
     * A flag indicating that the cached services are valid.
     *
     * If this is `false`, the client should perform service discovery again.
     */
    internal var isServiceCacheValid: Boolean = isServiceCacheValid
        private set

    /**
     * Estimates the time it takes to transfer a single Write or Read packet in Bluetooth LE.
     *
     * This applies to single Write Command, Write Request or Long Write procedures, as well as
     * Read and Long Read procedures. It does NOT calculate the total time for transferring a
     * long packet using multiple writes.
     *
     * @param value the attribute value to be written or read.
     * @param isWrite whether this is a Read (Read Request / Long Read via Read Blob) as opposed to
     * a Write (Write Request / Long Write via Prepare Write). Ignored when [withResponse] is
     * `false`, since only writes can be sent without a response.
     * @param withResponse whether the operation requires a response (Write Request / Long Write,
     * or any Read operation). Must be `false` only for Write Command (fire-and-forget writes have
     * no response; there is no such thing as a "read without response").
     * @return The estimated duration of the transfer.
     * @throws IllegalStateException if the peripheral is not connected.
     */
    internal fun estimateTransferDuration(
        value: ByteArray,
        isWrite: Boolean,
        withResponse: Boolean = true,
    ): Duration {
        val mtu = checkNotNull(mtu) { "Peripheral not connected" }
        val llMtu = checkNotNull(this@PeripheralSpec.llMtu)
        val phy = checkNotNull(phy)
        val connectionInterval = checkNotNull(connectionParameters).connectionIntervalMillis

        return when (withResponse) {
            true -> when (isWrite) {
                // Read Request / Read Blob Request (Long Read). Both Read Response and Read Blob
                // Response carry only a 1-byte opcode as overhead, so the payload cap is mtu - 1.
                // Since the caller already knows value.size, no extra "confirmation" read is
                // needed once all bytes have been retrieved.
                false -> {
                    val payloadPerPdu = min(mtu - 1, llMtu - 4)
                    val pdusNeeded = ceil(value.size.toDouble() / payloadPerPdu)
                    connectionInterval.milliseconds * pdusNeeded
                }
                // Write Request, Indications or Prepare Write + Execute Write (Long Write).
                true -> {
                    // Write Request overhead: opcode (1) + handle (2) = 3 bytes.
                    val singlePduCap = min(mtu - 3, llMtu - 4)
                    if (value.size <= singlePduCap) {
                        // Fits into a single Write Request / Write Response.
                        connectionInterval.milliseconds
                    } else {
                        // Long Write: Prepare Write overhead is opcode (1) + handle (2) + offset (2)
                        // = 5 bytes per chunk, followed by one mandatory Execute Write Request/Response.
                        val chunkCap = min(mtu - 5, llMtu - 4)
                        val chunksNeeded = ceil(value.size.toDouble() / chunkCap)
                        connectionInterval.milliseconds * (chunksNeeded + 1) // +1 for Execute Write
                    }
                }
            }
            // Write Command and Notifications fit into a single ATT PDU
            // (opcode + handle = 3 bytes overhead), but may be split into several link-layer PDUs.
            false -> {
                // Max payload of a single link-layer PDU (bytes).
                val payloadPerPdu = min(mtu - 3, llMtu - 4)
                // Time to send a single PDU, including a gap afterward (seconds).
                // +14 = preamble(1) + access address(4) + LL header(2) + CRC(3) + MIC(4), assuming
                // an encrypted 1M/2M PHY link; use +10 instead if the link is not encrypted.
                // Note: this linear model does not hold for LE Coded PHY (S=2/S=8), which has a
                // different packet structure (FEC block, CI, TERM1/TERM2).
                val timePerPdu = (payloadPerPdu + 14) * 8 / phy.rate() + 0.00015 // seconds
                // Approximate time needed to send all PDUs.
                val pdusNeeded = value.size / payloadPerPdu.toDouble()
                timePerPdu.seconds * pdusNeeded
            }
        }
    }

    companion object {

        /**
         * Simulates a peripheral with given ID and proximity.
         *
         * Note, that if the identifier is a Bluetooth Address, the 2 most significant bits
         * should match the [addressType]:
         * - `0b00` - [AddressType.RANDOM_PRIVATE_NON_RESOLVABLE]
         * - `0b01` - [AddressType.RANDOM_PRIVATE_RESOLVABLE]
         * - `0b10` is reserved for future use and should not be used.
         * - `0b11` - [AddressType.RANDOM_STATIC]
         * - any value for [AddressType.PUBLIC]
         *
         * See Bluetooth Core Specification v6.2, Vol 6, Part B, Section 1.3 Device address:
         * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/low-energy-controller/link-layer-specification.html#UUID-3815b05a-b69c-4e3c-5897-c8d3baa4fc30).
         *
         * ### Example
         *
         * ```kotlin
         * val peripheralSpec = PeripheralSpec.simulatePeripheral(
         *   identifier = "12:34:56:78:9A:BC",
         *   addressType = AddressType.RANDOM_STATIC,
         *   proximity = Proximity.NEAR
         * ) {
         *     advertising(
         *         parameters = LegacyAdvertisingSetParameters(
         *             connectable = true,
         *             interval = 500.milliseconds,
         *         ),
         *         isAdvertisingWhenConnected = false,
         *         delay = 1.seconds,
         *         // timeout = 10.seconds,
         *         // maxAdvertisingEvents = 30,
         *     ) {
         *         CompleteLocalName("Nordic_LBS")
         *         ServiceUuid(Uuid.parse("00001523-1212-EFDE-1523-785FEABCD123"))
         *         IncludeTxPowerLevel()
         *     }
         *     advertising(
         *         parameters = Bluetooth5AdvertisingSetParameters(
         *             connectable = true,
         *             interval = 1.seconds,
         *             primaryPhy = PrimaryPhy.PHY_LE_CODED,
         *             secondaryPhy = Phy.PHY_LE_CODED,
         *             txPowerLevel = TxPowerLevel.HIGH,
         *             includeTxPowerLevel = true,
         *         ),
         *         delay = 4.seconds,
         *         timeout = 10.seconds,
         *     ) {
         *         Flags(
         *            AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE,
         *            AdvertisingDataFlag.BR_EDR_NOT_SUPPORTED
         *         )
         *         CompleteLocalName("HR Sensor")
         *         ServiceUuid(Uuid.fromShortUuid(0x1809))
         *         ServiceUuid(Uuid.fromShortUuid(0x180A))
         *     }
         *     connectable(
         *         name = "Nordic_Blinky",
         *         maxAttMtu = 247,
         *         maxLlMtu = 251,
         *         // Uncommenting this line switches to a different "connectable" method, which
         *         // makes the peripheral "cached" (there's additional param "cachedServices" to provide).
         *         // In that case, the mock impl assumes, that the peripheral was connected before
         *         // and services were cached, i.e. the Device Name was read. Hence, the scanner
         *         // will switch from "Nordic_LBS" to "Nordic_Blinky".
         *         //
         *         // isBonded = false,
         *         eventHandler = blinkyImpl,
         *     ) {
         *         GenericAccessService()
         *         GenericAttributeService()
         *         // Add LED Button Service (Blinky)
         *         Service(
         *             uuid = Uuid.parse("00001523-1212-EFDE-1523-785FEABCD123")
         *         ) {
         *             Characteristic(
         *                 uuid = Uuid.parse("00001524-1212-EFDE-1523-785FEABCD123"),
         *                 properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
         *                 permissions = Permission.READ and Permission.WRITE,
         *             ) {
         *                 // CCCD is added automatically
         *                 CharacteristicUserDescriptionDescriptor("Button 1")
         *             }
         *             Characteristic(
         *                 uuid = Uuid.parse("00001525-1212-EFDE-1523-785FEABCD123"),
         *                 properties = CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
         *                 permission = Permission.READ,
         *             ) {
         *                 // CCCD is added automatically
         *                 CharacteristicUserDescriptionDescriptor("LED 1")
         *             }
         *         }
         *     }
         * }
         *
         * @param identifier The peripheral identifier.
         * @param addressType The address type of the peripheral, default is [AddressType.RANDOM_STATIC].
         * @param type The peripheral type. By default set to [PeripheralType.LE].
         * @param proximity Approximate distance to the peripheral.
         * By default set to [Proximity.IMMEDIATE].
         */
        fun <ID: Any> simulatePeripheral(
            identifier: ID,
            addressType: AddressType = AddressType.RANDOM_STATIC,
            type: PeripheralType = PeripheralType.LE,
            proximity: Proximity = Proximity.IMMEDIATE,
            builder: Builder<ID>.() -> Unit = {}
        ): PeripheralSpec<ID> {
            return Builder(identifier, addressType, type, proximity).apply(builder).build()
        }
    }

    /**
     * Simulates the situation when the device is scanned and advertisement data is received.
     *
     * The phone is adding the device address to its cache, remembering the address type.
     * This will allow retrieving the device using [CentralManager.getPeripheralById] or connecting
     * to it without scanning first.
     *
     * @see PeripheralSpec.isKnown
     */
    fun simulateCaching() {
        isKnown = true
    }

    /**
     * Simulates a change in the proximity of the peripheral.
     *
     * When the peripheral is moved out of range, the connected clients will get a disconnection
     * event with reason [ConnectionState.Disconnected.Reason.LinkLoss] after the supervision timeout.
     *
     * @param proximity The new proximity. Use [Proximity.OUT_OF_RANGE] to simulate leaving the range.
     * @see ConnectionParameters.Specified.supervisionTimeout
     */
    fun simulateProximityChange(proximity: Proximity) {
        this.proximity = proximity
    }

    /**
     * Simulates the situation when another application on the device tries to connect to the device.
     *
     * If the device is already connected, the connections count will be increased. Otherwise,
     * the [PeripheralSpecEventHandler.onConnectionRequest] will be called, and if the connection
     * is accepted, the connections count will be increased.
     *
     * If the device advertises with [MockAdvertisingSet.isAdvertisingWhenConnected] flag set
     * to `false` the advertisement will stop.
     *
     * A manager registered for connection event will be notified.
     *
     * @param phy The PHY used to connect to the peripheral.
     * @param mtu The ATT MTU negotiated for the connection, defaults to the max ATT MTU
     * supported by the [PeripheralSpec].
     * @throws IllegalStateException if the peripheral is not connectable.
     */
    fun simulateConnection(
        phy: PrimaryPhy = PrimaryPhy.PHY_LE_1M,
        mtu: Int = maxAttMtu ?: ATT_MTU_DEFAULT,
    ) {
        // Make sure the device is connectable.
        val maxAttMtu = this.maxAttMtu ?: return
        val maxLlMtu = this.maxLlMtu ?: return

        // If another client is already connected, just increase the connections count.
        if (isConnected) {
            connectionsCount += 1
            return
        }
        // Otherwise, notify the event handler about the connection request.
        val eventHandler = checkNotNull(eventHandler) { "Cannot connect to not connectable device" }
        when (eventHandler.onConnectionRequest(phy)) {
            is ConnectionResult.Accept -> {
                connectionsCount += 1
                this.mtu = mtu.coerceIn(23, maxAttMtu)
                this.llMtu = maxLlMtu
                if (supportedPhy.contains(phy.toPhy())) {
                    this.phy = phy.toPhy()
                } // else defaults to PHY LE 1M
            }
            else -> {
                // Do nothing. Assume that the connection request times out.
            }
        }
    }

    /**
     * Simulates a situation when the peripheral is gracefully disconnecting from the device.
     *
     * The [PeripheralSpecEventHandler.onConnectionLost] will receive a call with reason
     * [DisconnectionReason.TerminateLocalHost].
     *
     * Connected clients will immediately be notified about the disconnection with reason
     * [ConnectionState.Disconnected.Reason.TerminatePeerUser].
     *
     */
    // TODO Add Error to be returned to the clients?
    fun simulateDisconnection() {
        if (isConnected) {
            // TODO Should we delay this to simulate connection interval?
            connectionsCount = 0
            eventHandler!!.onConnectionLost(DisconnectionReason.TerminateLocalHost)
            _events.tryEmit(ConnectionStateChanged(ConnectionState.Disconnected(Reason.TerminatePeerUser)))
        }
    }

    /**
     * Simulates peripheral reset.
     *
     * Connected clients will receive a disconnection event with reason
     * [ConnectionState.Disconnected.Reason.LinkLoss] after the supervision timeout.
     *
     * @see ConnectionParameters.Specified.supervisionTimeout
     */
    fun simulateReset() {
        val supervisionTimeout = connectionParameters?.supervisionTimeoutMillis?.milliseconds
        connectionsCount = 0
        eventHandler?.onReset()

        // If the device was connected, notify about disconnection due to link loss.
        if (supervisionTimeout != null) {
            scope.launch {
                // Simulate supervision timeout delay before notifying clients.
                delay(supervisionTimeout)
                _events.emit(ConnectionStateChanged(ConnectionState.Disconnected(Reason.LinkLoss)))
            }
        }
    }

    /**
     * Simulates a characteristic value update on the peripheral.
     *
     * This will notify all connected clients that the value has changed.
     *
     * The *handle number* is returned when creating the characteristic using
     * [PeripheralSpec.Builder.connectable] from [ServiceScope.Characteristic].
     *
     * @param handle The handle of the characteristic.
     * @param value The new value of the characteristic.
     */
    fun simulateValueUpdate(handle: Int, value: ByteArray) {
        if (!isConnected) return

        // Notify clients that the value has changed.
        scope.launch {
            val transferDuration = estimateTransferDuration(value, isWrite = true, withResponse = false)
            delay(transferDuration)

            // If any client is still connected, emit the event.
            if (isConnected) {
                _events.emit(CharacteristicChanged(handle, value))
            }
        }
    }

    /**
     * Simulates a change in the services offered by the peripheral.
     *
     * This will replace the existing services with new ones defined in [newServices].
     *
     * @param newServices A builder for the set of services.
     */
    fun simulateServiceChange(newServices: MockServerScope.() -> Unit) {
        val oldServices = services

        // Replace the services with new ones.
        services = MockServerScopeImpl().apply(newServices).build()
        isServiceCacheValid = false

        val connectionInterval = connectionParameters?.connectionIntervalMillis?.milliseconds ?: return

        // If the indications on Service Changed characteristic are enabled, notify clients about the change.
        val serviceChangedCharacteristicCccd = oldServices
            ?.firstOrNull { it.uuid == Service.GENERIC_ATTRIBUTE_UUID }
            ?.characteristics
            ?.firstOrNull { it.uuid == Characteristic.SERVICE_CHANGED }
            ?.descriptors
            ?.firstOrNull { it.uuid == Descriptor.CLIENT_CHAR_CONF_UUID } as? CCCD
        if (serviceChangedCharacteristicCccd?.enabled == true) {
            scope.launch {
                // Simulate delay for sending indication.
                delay(connectionInterval)

                // If any client is still connected, emit the event.
                if (isConnected) {
                    // TODO Pre-Oreo Android versions do not report Service Changed events.
                    // TODO Android 6+ (until 15?) report 7.5ms interval during service discovery.
                    // On Android 16 the 7.5 ms interval is not reported? No longer a thing?
                    _events.emit(ServicesChanged)
                }
            }
        }
    }

    /**
     * Simulates a change of the peripheral identifier (Device Address).
     *
     * This will be applied only if the peripheral is not connected.
     *
     * Note, that if the identifier is the Bluetooth Device Address, the 2 most significant bits
     * should match the [addressType]:
     * - `0b00` - [AddressType.RANDOM_PRIVATE_NON_RESOLVABLE]
     * - `0b01` - [AddressType.RANDOM_PRIVATE_RESOLVABLE]
     * - `0b10` is reserved for future use and should not be used.
     * - `0b11` - [AddressType.RANDOM_STATIC]
     * - any value for [AddressType.PUBLIC]
     *
     * See Bluetooth Core Specification v6.2, Vol 6, Part B, Section 1.3 Device address:
     * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/low-energy-controller/link-layer-specification.html#UUID-3815b05a-b69c-4e3c-5897-c8d3baa4fc30).
     *
     * @param newIdentifier The new peripheral identifier.
     * @param addressType The new address type.
     */
    fun simulateMacChange(newIdentifier: ID, addressType: AddressType) {
        if (!isConnected) {
            this.identifier = newIdentifier
            this.addressType = addressType
            this.isKnown = false
            this.isBonded = false
        }
        // TODO what with cached connections in central managers? PeripheralSpec instance is the same.
    }

    /**
     * Simulates a connection parameters update request from the peripheral.
     *
     * The change is applied [delay] connection intervals after the request.
     *
     * @param parameters The new connection parameters.
     * @param delay Number of old connection intervals to wait before applying new parameters.
     * Defaults to 5.
     */
    fun simulateConnectionParametersRequest(parameters: ConnectionParameters.Specified, delay: Int = 5) {
        // TODO Validate parameters against preferred / possible parameters?
        connectionParameters?.connectionIntervalMillis?.let { connectionInterval ->
            scope.launch {
                // Wait for few (old) connection intervals before applying new parameters.
                delay(connectionInterval.milliseconds * delay)

                connectionParameters = parameters
                _events.emit(ConnectionParametersChanged(parameters))
            }
        }
    }

    /**
     * Simulates an MTU request from the peripheral.
     *
     * MTU may be changed only once per connection.
     *
     * > This request shall only be sent once during a connection by the client.
     *
     * Read more in Core Specification v6.2, Vol 3, Part F, Section 3.4.2 MTU Exchange:
     * [link](https://w
     * ww.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/attribute-protocol--att-.html#UUID-a1f22a10-ac5b-9887-badf-54c142565593).
     *
     * @param mtu The requested MTU.
     */
    fun simulateMtuRequest(mtu: Int) {
        // MTU and max MTU are null if peripheral is not connected.
        val currentMtu = this.mtu ?: return
        val maxAttMtu = this.maxAttMtu ?: return
        val newMtu = mtu.coerceIn(23, maxAttMtu)

        // MTU may be changed only once per connection.
        // If MTU is grater than 23, it has already been changed.
        if (currentMtu != ATT_MTU_DEFAULT || newMtu == currentMtu) {
            return
        }

        // Wait for few connection intervals before applying new parameters.
        val connectionInterval = checkNotNull(connectionParameters).connectionIntervalMillis.milliseconds
        scope.launch {
            delay(connectionInterval * 2)

            this@PeripheralSpec.mtu = newMtu
            _events.emit(MtuChanged(newMtu))
        }
    }

    // TODO Add more simulation methods

    // Implementation

    /**
     * Initiates a mock GATT connection to the peripheral.
     *
     * This method awaits first connectable advertisement from the given flow of advertisements
     * and then simulates connection by calling [Api.connect].
     *
     * If a peripheral is already connected, it just emits the connection event immediately.
     *
     * @param environment The mock environment.
     * @param autoConnect Whether to use auto-connect mode.
     * @param advertisements A flow of advertisements emitted by the mock advertiser.
     * @return The mock GATT object.
     */
    internal suspend fun connectGatt(
        environment: MockEnvironment,
        autoConnect: Boolean,
        autoMtu: Boolean,
        opportunistic: Boolean,
        advertisements: Flow<MockScanResult<*>>,
    ): Api {
        return Api(environment).also { gatt ->
            // If the mock peripheral is already connected (to the phone) and another
            // (virtual) client (that is Peripheral instance) tries to connect to it,
            // just notify about the success.
            if (isConnected) {
                // This event will be ignored by already connected clients, but can re-connect
                // a disconnected client, if not closed.
                // TODO Is this re-connection intended behavior?
                _events.emit(ConnectionStateChanged(ConnectionState.Connected))
                return@also
            }

            // Android does not allow to create an auto-connection to a peripheral with RPA
            // if the device is not bonded.
            // Note: If a device is not known (has not been scanned before), Android
            //       assumes PUBLIC address type.
            // Note 2: On iOS it is not possible to get a peripheral by Device Address at all.
            //         Devices must be scanned so that the Identifier is known.
            if (autoConnect && !isBonded && isKnown && addressType == AddressType.RANDOM_PRIVATE_RESOLVABLE) {
                // Some Android devices return error 133, newer ones 135.
                // This event is later translated to UnsupportedAddress.
                _events.emit(ConnectionStateChanged(ConnectionState.Disconnected(Reason.Unknown(135))))
                return@also
            }

            // If a device was not scanned before, Android assumes it has PUBLIC address type.
            // Skip listening for advertisements if the spec has different address type.
            // TODO Does iOS behave the same way?
            val assumedPublicAddress = !isKnown
            if (assumedPublicAddress && addressType != AddressType.PUBLIC) {
                return@also
            }

            // Connection Request is sent as a response to a connectable advertisement.
            // Note: The advertisements flow already has results filtered by supported PHY.
            val advertisement = advertisements.first { scanResult ->
                scanResult.peripheralSpec.identifier == identifier && scanResult.isConnectable
            }

            // Note: Ignoring "preferred PHY" set by user. Choosing the primary PHY of scanned packet.
            gatt.connect(advertisement.primaryPhy, autoMtu)
        }
    }

    /**
     * @hide
     */
    inner class Api internal constructor(val environment: MockEnvironment) {
        /**
         * A job for observing [ServicesChanged] events, that invalidates the service cache.
         *
         * Each connected client may invalidate services, but the change applies to all of them.
         */
        private val cacheMonitoring = events
            .filterIsInstance(ServicesChanged::class)
            .onEach { cachedServices = null }
            .launchIn(scope)

        /** Closes the GATT connection. */
        internal fun close() {
            cachedServices = null
            cacheMonitoring.cancel()
        }

        /**
         * Currently cached services.
         *
         * This contains a copy of the services discovered by the peripheral. Each service
         * has [RemoteService.owner] which refers to this peripheral instance.
         *
         * Note, that these may differ from the actual services on the peripheral. For example,
         * when a peripheral which was connected at some point gets a new firmware, or changes
         * operation mode, it may change its services. The client is not aware of it until the
         * Service Changed indication is received or cache is invalidated and services are
         * discovered again.
         */
        internal var cachedServices: List<RemoteService>? = when (isServiceCacheValid) {
            true -> services
            false -> this@PeripheralSpec.cachedServices
        }?.map { MockRemoteService(this@PeripheralSpec, it, events) }
            private set

        /**
         * Simulates connecting to the peripheral.
         *
         * @param phy The PHY used for the connection. This is the primary PHY of the connectable
         * advertisement, which triggered the connection request.
         * @param autoMtu Whether the MTU should be automatically negotiated upon connection.
         * @throws IllegalStateException when the device is not connectable.
         */
        suspend fun connect(phy: PrimaryPhy, autoMtu: Boolean) {
            val maxAttMtu = maxAttMtu ?: return
            val maxLlMtu = this@PeripheralSpec.maxLlMtu ?: return
            val maxSupportedAttMtu = maxAttMtu.coerceAtMost(environment.maxAttMtu)
            val maxSupportedLlMtu = maxLlMtu.coerceAtMost(environment.maxLlMtu)

            // Event handler will only be null if a device is non-connectable.
            val eventHandler =
                checkNotNull(eventHandler) { "Cannot connect to not connectable device." }

            // Notify the event handler about the connection request.
            // TODO LE Coded PHY seems to be ignored, devices connect only with LE 1M. To be investigated.
            when (eventHandler.onConnectionRequest(phy)) {
                // TODO add option to fail connection with 133-ish error

                ConnectionResult.Accept -> {
                    connectionsCount += 1
                    this@PeripheralSpec.phy = phy.toPhy()
                    this@PeripheralSpec.llMtu = maxSupportedLlMtu
                    // TODO set L2CAP MTU to max supported value
                    // Note: onConnectionRequest callback can request MTU, bonding or PHY update.
                    // TODO Make sure these requests are handled after state is reported
                    // TODO This will re-connect all disconnected Peripheral instances.
                    _events.emit(ConnectionStateChanged(ConnectionState.Connected))

                    if (autoMtu) {
                        simulateMtuRequest(maxSupportedAttMtu)
                    }
                }

                ConnectionResult.Deny -> {
                    // Do nothing. The connection request should time out.
                }
            }
        }

        /**
         * Simulates disconnecting from the peripheral.
         *
         * @throws IllegalStateException when the device is not connected.
         */
        suspend fun disconnect() = disconnect(Reason.Success)

        internal suspend fun disconnect(reason: Reason) {
            val connectionParameters =
                checkNotNull(connectionParameters) { "Peripheral not connected." }
            val connectionInterval = connectionParameters.connectionIntervalMillis.milliseconds
            val eventHandler = checkNotNull(eventHandler)

            delay(connectionInterval)
            _events.emit(ConnectionStateChanged(ConnectionState.Disconnected(reason)))

            // One virtual client disconnected.
            connectionsCount -= 1

            // If no more connections, notify the event handler.
            if (connectionsCount == 0) {
                // Notify the event handler about the disconnection.
                eventHandler.onConnectionLost(DisconnectionReason.TerminatePeerUser)
            }
        }

        /**
         * Simulates service discovery on the peripheral.
         *
         * @param uuids The list of service UUIDs to discover.
         * @return `true` if the service discovery was started, `false` otherwise.
         */
        suspend fun discoverServices(uuids: List<Uuid>): Boolean {
            val connectionParameters = connectionParameters ?: return false
            val connectionInterval = connectionParameters.connectionIntervalMillis.milliseconds
            val eventHandler = eventHandler ?: return false

            // Return cached services if available. Note, that the cache may be invalid.
            cachedServices?.let {
                _events.emit(ServicesDiscovered(it))
                return true
            }

            // Notify the event handler about the service discovery request.
            when (eventHandler.onServiceDiscoveryRequest(uuids)) {
                is ServiceDiscoveryResult.Success -> {
                    // Simulate changing connection interval.
                    delay(connectionInterval)

                    // Android changes connection parameters during service discovery.
                    if (environment.reportsConnectionParameters) {
                        _events.emit(ConnectionParametersChanged(
                            ConnectionParameters.Specified(
                                connectionInterval = 6, // 7.5 ms
                                // TODO Are those kept the same?
                                latency = connectionParameters.supervisionTimeout,
                                supervisionTimeout = connectionParameters.supervisionTimeout,
                            )
                        ))
                    }
                    // Simulate service discovery delay.
                    // The duration is arbitrary, but depends on the number of services.
                    // Android switches to 7.5 ms connection interval during the service discovery.
                    // TODO iOS doesn't change interval, but only discovers requested services.
                    val serviceDiscoveryDuration = services!!
                        .fold(connectionInterval * 2) { acc, service ->
                            acc + 10.milliseconds * (service.characteristics.size + service.includedServices.size)
                        }
                    delay(serviceDiscoveryDuration)
                    // TODO iOS returns only requested services. Filtering is also done later, but should be here?
                    // TODO iOS does not return Generic Access and Generic Attribute services. Filter out, or leave for the PeripheralSpec?
                    isServiceCacheValid = true
                    cachedServices = services!!.map { MockRemoteService(this@PeripheralSpec, it, _events) }
                    _events.emit(ServicesDiscovered(cachedServices!!))

                    // Restore connection parameters.
                    if (environment.reportsConnectionParameters) {
                        scope.launch {
                            delay(connectionInterval * 2)
                            _events.emit(ConnectionParametersChanged(connectionParameters))
                        }
                    }
                }

                is ServiceDiscoveryResult.Failure -> {
                    // Let's say the failure happens after some delay.
                    delay(connectionInterval * 2)
                    _events.emit(ServicesDiscovered(emptyList()))
                }
            }
            return true
        }

        /**
         * Simulates refreshing the service cache on the peripheral.
         *
         * This will invalidate the service cache on all connected clients.
         *
         * @return `true` if the cache refresh was started, `false` otherwise.
         */
        suspend fun refreshCache(): Boolean {
            if (isConnected) {
                isServiceCacheValid = false
                // This will clear the cached services on all clients.
                // See `cacheMonitoring` in Api init.
                // TODO All clients will start service discovery again, independently.
                _events.emit(ServicesChanged)
                return true
            }
            return false
        }

        /**
         * Simulates reading RSSI from the peripheral.
         *
         * @return `true` if the RSSI read was started, `false` otherwise.
         */
        suspend fun readRssi(): Boolean {
            // Simulate a short delay for reading RSSI.
            val connectionParameters = connectionParameters ?: return false
            delay(connectionParameters.connectionIntervalMillis.milliseconds)

            _events.emit(RssiRead(proximity.randomRssi()))
            return true
        }

        /**
         * Simulates reading RSSI from the peripheral.
         *
         * @param mtu The requested MTU.
         * @return `true` if the MTU request was started, `false` otherwise.
         */
        suspend fun requestMtu(mtu: @Range(from = 23, to = 517) Int): Boolean {
            // MTU can be change just once per connection.
            val currentMtu = this@PeripheralSpec.mtu ?: return false
            if (currentMtu > ATT_MTU_DEFAULT || mtu == currentMtu) {
                _events.emit(MtuChanged(currentMtu))
                return true
            }

            // Simulate a delay for requesting MTU.
            val connectionParameters = checkNotNull(connectionParameters)
            delay(connectionParameters.connectionIntervalMillis.milliseconds)

            // Check if the peripheral is still connected.
            val maxAttMtu = maxAttMtu ?: return false
            val newMtu = mtu.coerceIn(23, maxAttMtu)

            this@PeripheralSpec.mtu = newMtu
            _events.emit(MtuChanged(newMtu))
            return true
        }

        /**
         * Simulates requesting connection parameters update.
         *
         * @param parameters The requested connection parameters.
         * @return `true` if the request was started, `false` otherwise.
         */
        suspend fun requestConnectionParameters(parameters: ConnectionParameters.Specified): Boolean {
            val connectionParameters = connectionParameters ?: return false

            // On environments that report connection parameters update, simulate the delay
            // and then emit the event with new parameters.
            if (environment.reportsConnectionParameters) {
                // Simulate a delay for requesting connection parameters update.
                delay(connectionParameters.connectionIntervalMillis.milliseconds * 5)

                // Check if the peripheral is still connected and emit the event.
                if (isConnected) {
                    this@PeripheralSpec.connectionParameters = parameters
                    _events.emit(ConnectionParametersChanged(parameters))
                    return true
                } else {
                    return false
                }
            } else {
                // Otherwise, apply the new parameters immediately. The client won't be notified.
                this@PeripheralSpec.connectionParameters = parameters
                return true
            }
        }

        /**
         * Notifies the client about connection parameters update,
         * but without giving the exact parameters.
         *
         * This method should be called after [requestConnectionParameters] on environments
         * that do not report connection parameters update automatically
         * (i.e. pre-Oreo Android, or iOS).
         */
        suspend fun onConnectionUpdated() {
            val newParameters = ConnectionParameters.Unknown
            _events.emit(ConnectionParametersChanged(newParameters))
        }

        /**
         * Simulates requesting PHY update.
         *
         * @param txPhy The requested TX PHY.
         * @param rxPhy The requested RX PHY.
         * @param phyOptions The PHY options.
         */
        // TODO PHY option is ignored. This could be use to improve estimating transfer duration.
        suspend fun setPreferredPhy(txPhy: Phy, rxPhy: Phy, phyOptions: PhyOption) {
            // If the requested PHY is already in use, just emit it.
            // The collector is waiting for the PhyChanged event.
            if (this@PeripheralSpec.phy == txPhy) {
                _events.emit(PhyChanged(PhyInUse(txPhy, rxPhy)))
                return
            }

            val connectionParameters = connectionParameters ?: return

            // Simulate a delay for requesting PHY update.
            delay(connectionParameters.connectionIntervalMillis.milliseconds * 2)

            // Check if the peripheral is still connected.
            if (isConnected) {
                this@PeripheralSpec.phy = txPhy
                _events.emit(PhyChanged(PhyInUse(txPhy, rxPhy)))
            }
        }

        /**
         * Simulates reading the currently used PHY.
         */
        suspend fun readPhy() {
            val phy = phy ?: return
            _events.emit(PhyChanged(PhyInUse(phy, phy)))
        }

        /**
         * Simulating ending reliable writes.
         *
         * @param execute Whether to execute or abort the reliable write procedure.
         */
        suspend fun endReliableWrites(execute: Boolean): Boolean {
            val connectionParameters = connectionParameters ?: return false
            // Simulate a delay for ending reliable writes.
            delay(connectionParameters.connectionIntervalMillis.milliseconds)

            val eventHandler = checkNotNull(eventHandler)
            when (val response = eventHandler.onExecuteWriteRequest(execute)) {
                is WriteResponse.Success -> {
                    _events.emit(ReliableWriteCompleted(OperationStatus.Success))
                }

                is WriteResponse.Failure -> {
                    _events.emit(ReliableWriteCompleted(response.status))
                }
            }
            return true
        }
    }

    /**
     * The builder for the [PeripheralSpec].
     *
     * @param ID The type of the peripheral identifier.
     * @param identifier The peripheral identifier.
     * @param proximity Approximate distance to the peripheral.
     */
    class Builder<ID: Any> internal constructor(
        private val identifier: ID,
        private val addressType: AddressType,
        private val type: PeripheralType,
        private val proximity: Proximity,
    ) {
        private var advertisingSets: List<MockAdvertisingSet>? = null
        private var name: String? = null
        private var appearance: Int? = null
        private var services: List<ServiceDefinition>? = null
        private var cachedServices: List<ServiceDefinition>? = null
        private var eventHandler: PeripheralSpecEventHandler? = null
        private var preferredConnectionInterval: @Range(from = 6L, to = 3200L) IntRange? = null
        private var preferredSlaveLatency: @Range(from = 0, to = 499) Int? = null
        private var preferredSupervisionTimeout: @Range(from = 10, to = 3200) Int? = null
        private var maxAttMtu: Int? = null
        private var maxL2capMtu: Int? = null
        private var supportedPhy: MutableSet<Phy> = mutableSetOf(Phy.PHY_LE_1M)
        private var isInitiallyConnected: Boolean = false
        private var isKnown: Boolean = false
        private var isBonded: Boolean = false
        private var isServiceCacheValid: Boolean = false

        /**
         * Adds an advertising set to the peripheral advertisement configuration.
         *
         * @param parameters The advertising parameters.
         * @param delay The delay before the advertising starts.
         * @param timeout The advertising timeout, since the start of advertising. By default, set to infinite.
         * @param isAdvertisingWhenConnected Whether the device should advertise when connected.
         * @param isBeacon Whether the device is a beacon which can reveal user's location, that is
         * an iBeacon or Eddystone beacon. On Android 12+ such advertisements require location
         * permission granted or are excluded from the scan results.
         * @param advertisingData The builder for the advertising data.
         */
        fun advertising(
            parameters: AdvertisingSetParameters = LegacyAdvertisingSetParameters(true),
            delay: Duration = Duration.ZERO,
            timeout: Duration = Duration.INFINITE,
            isAdvertisingWhenConnected: Boolean = false,
            isBeacon: Boolean = false,
            advertisingData: AdvertisingDataScope.() -> Unit,
        ) = addAdvertisingSet(
            parameters = parameters,
            delay = delay,
            timeout = timeout,
            maxAdvertisingEvents = Int.MAX_VALUE,
            isAdvertisingWhenConnected = isAdvertisingWhenConnected,
            isBeacon = isBeacon,
            builder = advertisingData,
        )

        /**
         * Adds an advertising set to the peripheral advertisement configuration.
         *
         * @param parameters The advertising parameters.
         * @param delay The delay before the advertising starts.
         * @param maxAdvertisingEvents The maximum number of advertising events, in range 1..255.
         * @param isAdvertisingWhenConnected Whether the device should advertise when connected.
         * @param isBeacon Whether the device is a beacon which can reveal user's location, that is
         * an iBeacon or Eddystone beacon. On Android 12+ such advertisements require location
         * permission granted or are excluded from the scan results.
         * @param advertisingData The builder for the advertising data.
         */
        fun advertising(
            parameters: AdvertisingSetParameters = LegacyAdvertisingSetParameters(true),
            delay: Duration = Duration.ZERO,
            maxAdvertisingEvents: @Range(from = 1L, to = 255L) Int,
            isAdvertisingWhenConnected: Boolean = false,
            isBeacon: Boolean = false,
            advertisingData: AdvertisingDataScope.() -> Unit,
        ) = addAdvertisingSet(
            parameters = parameters,
            delay = delay,
            timeout = Duration.INFINITE,
            maxAdvertisingEvents = maxAdvertisingEvents,
            isAdvertisingWhenConnected = isAdvertisingWhenConnected,
            isBeacon = isBeacon,
            builder = advertisingData,
        )

        /**
         * Makes the device connectable, but not connected at the moment of initialization.
         *
         * This method should be used to mock a peripheral seen for the first time, that is
         * one which wasn't scanned, bonded or connected before.
         *
         * No services will be cached on the client side.
         *
         * @param name The name of the peripheral, available from Device Name characteristic.
         * @param appearance The appearance of the peripheral, available from Appearance characteristic.
         * See Bluetooth Assigned Numbers:
         * [link](https://www.bluetooth.com/specifications/assigned-numbers/), 2.6 Appearance Values.
         * @param preferredConnectionInterval The min and max connection interval, in 1.25 ms units.
         * Valid range is from 6 (7.5ms) to 3200 (4000ms). Default is 25-40 (30-50 ms).
         * @param preferredSlaveLatency Slave latency. Valid range is from 0 to 499. Default is 0.
         * @param preferredSupervisionTimeout Supervision timeout in 10 ms units. Valid range
         * is from 10 (0.1s) to 3200 (32s). Default is 400 (4 s).
         * @param maxAttMtu The maximum supported ATT MTU (Maximum Transfer Unit).
         * This value must be in range 23..517.
         * @param maxL2capMtu The maximum supported L2CAP MTU (Maximum Transfer Unit used on L2CAP
         * Layer using Data Length Extension (DLE)). This value must be in range 27..251.
         * @param isPhyLe2MSupported Whether the device supports PHY LE 2M.
         * @param isPhyCodedSupported Whether the device supports PHY LE Coded.
         * @param eventHandler The event handler that will be called for every event emulating
         * a real peripheral.
         * @param services The services available after service discovery.
         */
        fun connectable(
            name: String,
            appearance: Int = 0x0000,
            preferredConnectionInterval: @Range(from = 6L, to = 3200L) IntRange = IntRange(25, 40), // 30-50 ms
            preferredSlaveLatency: @Range(from = 0, to = 499) Int = 0,
            preferredSupervisionTimeout: @Range(from = 10, to = 3200) Int = 400, // 4 s
            maxAttMtu: @Range(from = 23L, to = 517L) Int = 23,
            maxL2capMtu: @Range(from = 27L, to = 251L) Int = 27,
            isPhyLe2MSupported: Boolean = false,
            isPhyCodedSupported: Boolean = false,
            eventHandler: PeripheralSpecEventHandler,
            services: MockServerScope.() -> Unit,
        ) = setConnectionParameters(
            preferredConnectionInterval = preferredConnectionInterval,
            preferredSlaveLatency = preferredSlaveLatency,
            preferredSupervisionTimeout = preferredSupervisionTimeout,
            maxAttMtu = maxAttMtu,
            maxL2capMtu = maxL2capMtu,
            isPhyLe2MSupported = isPhyLe2MSupported,
            isPhyCodedSupported = isPhyCodedSupported,
        ).also {
            require(this.name == null) { "Device is already configured as connectable." }
            this.name = name
            this.appearance = appearance
            this.services = MockServerScopeImpl().apply(services).build()
            this.eventHandler = eventHandler
        }

        /**
         * Makes the device connectable, but not connected at the moment of initialization.
         *
         * This method should be used to mock a peripheral that was connected before,
         * so some services may be cached on the client side. It is also possible to mock
         * a situation in which the cached services are invalid and will return an error when
         * accessed, in which case they need to be invalidated (i.e. using Service Changed
         * characteristic) and discovered again.
         *
         * @param name The name of the peripheral, available from Device Name characteristic.
         * @param appearance The appearance of the peripheral, available from Appearance characteristic.
         * See Bluetooth Assigned Numbers:
         * [link](https://www.bluetooth.com/specifications/assigned-numbers/), 2.6 Appearance Values.
         * @param preferredConnectionInterval The min and max connection interval, in 1.25 ms units.
         * Valid range is from 6 (7.5ms) to 3200 (4000ms). Default is 25-40 (30-50 ms).
         * @param preferredSlaveLatency Slave latency. Valid range is from 0 to 499. Default is 0.
         * @param preferredSupervisionTimeout Supervision timeout in 10 ms units. Valid range
         * is from 10 (0.1s) to 3200 (32s). Default is 400 (4 s).
         * @param maxAttMtu The maximum supported ATT MTU (Maximum Transfer Unit).
         * This value must be in range 23..517.
         * @param maxL2capMtu The maximum supported L2CAP MTU (Maximum Transfer Unit used on L2CAP
         * Layer using Data Length Extension (DLE)). This value must be in range 27..251.
         * @param isPhyLe2MSupported Whether the device supports PHY LE 2M.
         * @param isPhyCodedSupported Whether the device supports PHY LE Coded.
         * @param isBonded Whether the peripheral is bonded.
         * @param eventHandler The event handler that will be called for every event emulating
         * a real peripheral.
         * @param actualServices The services available after service discovery. If `null` (default),
         * they are set to be equal to [cachedServices].
         * @param cachedServices The services that are cached and will be returned immediately
         * on service discovery without the discovery. They may differ from the actual services.
         * On some platforms a client may invalidate cache and trigger full service discovery.
         */
        fun connectable(
            name: String,
            appearance: Int = 0x0000,
            preferredConnectionInterval: @Range(from = 6L, to = 3200L) IntRange = IntRange(25, 40), // 30-50 ms
            preferredSlaveLatency: @Range(from = 0, to = 499) Int = 0,
            preferredSupervisionTimeout: @Range(from = 10, to = 3200) Int = 400, // 4 s
            maxAttMtu: @Range(from = 23L, to = 517L) Int = 23,
            maxL2capMtu: @Range(from = 27L, to = 251L) Int = 27,
            isPhyLe2MSupported: Boolean = false,
            isPhyCodedSupported: Boolean = false,
            isBonded: Boolean = false,
            eventHandler: PeripheralSpecEventHandler,
            actualServices: (MockServerScope.() -> Unit)? = null,
            cachedServices: (MockServerScope.() -> Unit),
        ) = setConnectionParameters(
            preferredConnectionInterval = preferredConnectionInterval,
            preferredSlaveLatency = preferredSlaveLatency,
            preferredSupervisionTimeout = preferredSupervisionTimeout,
            maxAttMtu = maxAttMtu,
            maxL2capMtu = maxL2capMtu,
            isPhyLe2MSupported = isPhyLe2MSupported,
            isPhyCodedSupported = isPhyCodedSupported,
        ).also {
            require(this.name == null) { "Device is already configured as connectable." }
            this.name = name
            this.appearance = appearance
            this.eventHandler = eventHandler
            this.cachedServices = MockServerScopeImpl().apply(cachedServices).build()
            this.services = actualServices?.let { MockServerScopeImpl().apply(it).build() } ?: this.cachedServices
            // If the actual services were are specified, mark cache as invalid.
            // Accessing cached services will result in INVALID_HANDLE error and a new service
            // discovery will have to be performed
            this.isServiceCacheValid = actualServices == null
            // As services were cached, this device for sure was scanned before.
            this.isKnown = true
            this.isBonded = isBonded
        }

        /**
         * Makes the device connectable and already connected to the device at the moment
         * of initialization (if it's in range).
         *
         * Connected devices are also allowed for retrieval using [CentralManager.getPeripheralById].
         *
         * @param name The name of the peripheral, available from Device Name characteristic.
         * @param appearance The appearance of the peripheral, available from Appearance characteristic.
         * See Bluetooth Assigned Numbers:
         * [link](https://www.bluetooth.com/specifications/assigned-numbers/), 2.6 Appearance Values.
         * @param preferredConnectionInterval The min and max connection interval, in 1.25 ms units.
         * Valid range is from 6 (7.5ms) to 3200 (4000ms). Default is 25-40 (30-50 ms).
         * @param preferredSlaveLatency Slave latency. Valid range is from 0 to 499. Default is 0.
         * @param preferredSupervisionTimeout Supervision timeout in 10 ms units. Valid range
         * is from 10 (0.1s) to 3200 (32s). Default is 400 (4 s).
         * @param maxAttMtu The maximum supported ATT MTU (Maximum Transfer Unit).
         * This value must be in range 23..517.
         * @param maxL2capMtu The maximum supported L2CAP MTU (Maximum Transfer Unit used on L2CAP
         * Layer using Data Length Extension (DLE)). This value must be in range 27..251.
         * @param isPhyLe2MSupported Whether the device supports PHY LE 2M.
         * @param isPhyCodedSupported Whether the device supports PHY LE Coded.
         * @param isBonded Whether the peripheral is bonded.
         * @param eventHandler The event handler that will be called for every event emulating
         * a real peripheral.
         * @param actualServices The services available after service discovery. If `null` (default),
         * they are set to be equal to [cachedServices].
         * @param cachedServices The services that are cached and will be returned immediately
         * on service discovery without the discovery. They may differ from the actual services.
         * On some platforms a client may invalidate cache and trigger full service discovery.
         */
        fun connected(
            name: String,
            appearance: Int = 0x0000,
            preferredConnectionInterval: @Range(from = 6L, to = 3200L) IntRange = IntRange(25, 40), // 30-50 ms
            preferredSlaveLatency: @Range(from = 0, to = 499) Int = 0,
            preferredSupervisionTimeout: @Range(from = 10, to = 3200) Int = 400, // 4 s
            maxAttMtu: @Range(from = 23L, to = 517L) Int = 23,
            maxL2capMtu: @Range(from = 27L, to = 251L) Int = 27,
            isPhyLe2MSupported: Boolean = false,
            isPhyCodedSupported: Boolean = false,
            isBonded: Boolean = false,
            eventHandler: PeripheralSpecEventHandler,
            actualServices: (MockServerScope.() -> Unit)? = null,
            cachedServices: (MockServerScope.() -> Unit),
        ) = setConnectionParameters(
            preferredConnectionInterval = preferredConnectionInterval,
            preferredSlaveLatency = preferredSlaveLatency,
            preferredSupervisionTimeout = preferredSupervisionTimeout,
            maxAttMtu = maxAttMtu,
            maxL2capMtu = maxL2capMtu,
            isPhyLe2MSupported = isPhyLe2MSupported,
            isPhyCodedSupported = isPhyCodedSupported,
        ).also {
            require(this.name == null) { "Device is already configured as connectable." }
            this.name = name
            this.appearance = appearance
            this.eventHandler = eventHandler
            this.cachedServices = MockServerScopeImpl().apply(cachedServices).build()
            this.services = actualServices?.let { MockServerScopeImpl().apply(it).build() } ?: this.cachedServices
            // If the actual services were are specified, mark cache as invalid.
            // Accessing cached services will result in INVALID_HANDLE error and a new service
            // discovery will have to be performed
            this.isServiceCacheValid = actualServices == null
            this.isKnown = true
            this.isBonded = isBonded
            this.isInitiallyConnected = proximity != Proximity.OUT_OF_RANGE
        }

        /**
         * Configures the device for high speed connection.
         *
         * Sets ATT MTU to 498 bytes and L2CAP MTU to 251 bytes and enables PHY LE 2M
         * together with fast connection parameters.
         *
         * [connectable] or [connected] method must be called in addition to this one, for example:
         * ```kotlin
         * val mock = PeripheralSpec.simulatePeripheral("01:02:03:04:05:06") {
         *     connectable(
         *         name = "High Speed Device",
         *         eventHandler = myEventHandler,
         *         services = {
         *             // Define services here
         *         }
         *     )
         *     highSpeed()
         * }
         * ```
         *
         * @param preferredConnectionInterval The min and max connection interval, in 1.25 ms units.
         * Valid range is from 6 (7.5ms) to 3200 (4000ms). Default is 15-30 (15-30 ms).
         * @param preferredSlaveLatency Slave latency. Valid range is from 0 to 499. Default is 0.
         * @param preferredSupervisionTimeout Supervision timeout in 10 ms units. Valid range
         * is from 10 (0.1s) to 3200 (32s). Default is 100 (1 s).
         * @param maxAttMtu The maximum supported ATT MTU (Maximum Transfer Unit).
         * This value must be in range 23..517. Default is 498, which allows to fit 2 full L2CAP
         * packets of 251 bytes each into a single ATT packet.
         * @param maxL2capMtu The maximum supported L2CAP MTU (Maximum Transfer Unit used on L2CAP
         * Layer using Data Length Extension (DLE)). This value must be in range 27..251.
         * Default is 251.
         * @param isPhyLe2MSupported Whether the device supports PHY LE 2M, default is `true`.
         */
        fun highSpeed(
            preferredConnectionInterval: @Range(from = 6L, to = 3200L) IntRange = IntRange(12, 24), // 15-30 ms
            preferredSlaveLatency: @Range(from = 0, to = 499) Int = 0,
            preferredSupervisionTimeout: @Range(from = 10, to = 3200) Int = 100, // 1 s
            maxAttMtu: @Range(from = 23L, to = 517L) Int = 498,
            maxL2capMtu: @Range(from = 27L, to = 251L) Int = 251,
            isPhyLe2MSupported: Boolean = true,
        ) = setConnectionParameters(
            preferredConnectionInterval = preferredConnectionInterval,
            preferredSlaveLatency = preferredSlaveLatency,
            preferredSupervisionTimeout = preferredSupervisionTimeout,
            maxAttMtu = maxAttMtu,
            maxL2capMtu = maxL2capMtu,
            isPhyLe2MSupported = isPhyLe2MSupported,
        )

        /**
         * Makes a connection to the device possible without scanning.
         *
         * If this method is not called, the device needs to be scanned before connection attempt.
         *
         * Bonded devices are automatically allowed for retrieval.
         */
        fun allowForRetrieval(): Builder<ID> = apply {
            isKnown = true
        }

        /**
         * Builds the [PeripheralSpec] object.
         */
        fun build(): PeripheralSpec<ID> = PeripheralSpec(
            identifier = identifier,
            addressType = addressType,
            type = type,
            initialProximity = proximity,
            name = name,
            appearance = appearance,
            preferredConnectionInterval = preferredConnectionInterval,
            preferredSlaveLatency = preferredSlaveLatency,
            preferredSupervisionTimeout = preferredSupervisionTimeout,
            maxAttMtu = maxAttMtu,
            maxLlMtu = maxL2capMtu,
            supportedPhy = supportedPhy,
            isInitiallyConnected = isInitiallyConnected,
            isKnown = isKnown,
            isBonded = isBonded,
            advertisingSets = advertisingSets,
            eventHandler = eventHandler,
            services = services,
            cachedServices = cachedServices,
            isServiceCacheValid = isServiceCacheValid,
        )

        // Implementation

        private fun addAdvertisingSet(
            parameters: AdvertisingSetParameters,
            delay: Duration,
            timeout: Duration,
            maxAdvertisingEvents: Int,
            isAdvertisingWhenConnected: Boolean,
            isBeacon: Boolean,
            builder: AdvertisingDataScope.() -> Unit,
        ) {
            val advertisingData =
                AdvertisingDataScopeImpl(parameters.txPowerLevel).apply(builder).build()
            val advertisement = MockAdvertisingSet(
                delay = delay,
                timeout = timeout,
                maxAdvertisingEvents = maxAdvertisingEvents,
                isAdvertisingWhenConnected = isAdvertisingWhenConnected,
                parameters = parameters,
                advertisingData = advertisingData,
                isBeacon = isBeacon,
            )
            advertisingSets = advertisingSets?.plus(advertisement) ?: listOf(advertisement)

            // Update the set of supported PHYs.
            if (parameters is Bluetooth5AdvertisingSetParameters) {
                if (parameters.primaryPhy == PrimaryPhy.PHY_LE_CODED) {
                    supportedPhy.add(Phy.PHY_LE_CODED)
                }
                supportedPhy.add(parameters.secondaryPhy)
            }
        }

        private fun setConnectionParameters(
            preferredConnectionInterval: IntRange,
            preferredSlaveLatency: Int,
            preferredSupervisionTimeout: Int,
            maxAttMtu: Int,
            maxL2capMtu: Int,
            isPhyLe2MSupported: Boolean,
            isPhyCodedSupported: Boolean = false,
        ) {
            require(preferredConnectionInterval.first in 6..3200) { "Min connection interval is out of range." }
            require(preferredConnectionInterval.last in 6..3200) { "Max connection interval is out of range." }
            require(preferredSlaveLatency in 0..499) { "Slave latency is out of range." }
            require(preferredSupervisionTimeout in 10..3200) { "Supervision timeout is out of range." }
            require(maxAttMtu in 23..517) { "Max ATT MTU is out of range." }
            require(maxL2capMtu in 27..251) { "Max L2CAP MTU is out of range." }
            this.preferredConnectionInterval = preferredConnectionInterval
            this.preferredSlaveLatency = preferredSlaveLatency
            this.preferredSupervisionTimeout = preferredSupervisionTimeout
            if (this.maxAttMtu == null || this.maxAttMtu!! < maxAttMtu) {
                this.maxAttMtu = maxAttMtu.coerceIn(23, 517)
            }
            if (this.maxL2capMtu == null || this.maxL2capMtu!! < maxL2capMtu) {
                this.maxL2capMtu = maxL2capMtu.coerceIn(27, 251)
            }
            if (isPhyLe2MSupported) this.supportedPhy.add(Phy.PHY_LE_2M)
            if (isPhyCodedSupported) this.supportedPhy.add(Phy.PHY_LE_CODED)
        }
    }
}