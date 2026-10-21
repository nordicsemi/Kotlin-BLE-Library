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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.client.CentralManager
import no.nordicsemi.kotlin.ble.client.ConnectionParametersChanged
import no.nordicsemi.kotlin.ble.client.ConnectionStateChanged
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.MtuChanged
import no.nordicsemi.kotlin.ble.client.PhyChanged
import no.nordicsemi.kotlin.ble.client.ReliableWriteCompleted
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.ServiceDiscoveryFailed
import no.nordicsemi.kotlin.ble.client.RssiRead
import no.nordicsemi.kotlin.ble.client.ServicesChanged
import no.nordicsemi.kotlin.ble.client.ServicesDiscovered
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicChanged
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteService
import no.nordicsemi.kotlin.ble.client.mock.internal.MockScanResult
import no.nordicsemi.kotlin.ble.core.ATT_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.ATT_MTU_MAX
import no.nordicsemi.kotlin.ble.core.AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.Bluetooth5AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.BondState
import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.LL_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.LL_MTU_MAX
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
 * @param activePhy The PHYs (TX and RX) used by the connection the peripheral is initially connected with,
 * or `null` if the peripheral is not initially connected.
 * @param isKnown Whether the peripheral has been scanned before and its address type is cached.
 * It is not possible to connect to an unknown device without scanning.
 * @param isBonded Whether the phone has the bond information of the peripheral.
 * @param peripheralHasBond Whether the peripheral has the bond information of the phone.
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
    activePhy: PhyInUse?,
    isKnown: Boolean,
    isBonded: Boolean,
    peripheralHasBond: Boolean,
    internal val advertisingSets: List<MockAdvertisingSet>?,
    internal val eventHandler: PeripheralSpecEventHandler?,
    private var services: List<ServiceDefinition>?,
    private val cachedServices: List<ServiceDefinition>?,
    isServiceCacheValid: Boolean,
) {
    /** A flow of GATT events emitted by the peripheral. */
    private val _events: MutableSharedFlow<GattEvent> = MutableSharedFlow(extraBufferCapacity = 64)
    val events: SharedFlow<GattEvent> = _events.asSharedFlow()

    /** Guards [simulations] and [sharedJob]. */
    private val lock = Any()

    /**
     * Simulation scopes of the mock central managers simulating this peripheral, oldest first.
     *
     * @see attach
     */
    private val simulations = mutableListOf<CoroutineScope>()

    /**
     * The parent of all work shared by the clients, see [launchShared].
     *
     * It belongs to the peripheral, not to any manager, and is cancelled when the last manager
     * stops simulating the peripheral.
     */
    private var sharedJob: Job? = null

    /**
     * The environment of the mock central manager simulating this peripheral, or `null` if it is
     * not known yet.
     *
     * A peripheral is simulated in one environment. It limits the MTU and the PHYs of connections
     * to what both the phone and the peripheral support.
     */
    private var simulationEnvironment: MockEnvironment? = null

    /** The scope used when the peripheral is not simulated by any mock central manager. */
    private val fallbackScope by lazy { CoroutineScope(Dispatchers.IO) }

    /**
     * Launches work of the peripheral that is shared by all connected clients, i.e. delayed
     * events like link loss, notifications or MTU changes.
     *
     * The work runs with the dispatcher, the clock and the exception handler of the mock central
     * manager that started simulating this peripheral most recently, so that it follows e.g.
     * a test scheduler. It is not a child of that manager's scope, though: it survives when that
     * manager's scope is cancelled or its simulation is torn down, as long as at least one other
     * manager still simulates the peripheral. It is cancelled when the last one stops.
     *
     * Managers simulating the same peripheral should use the same clock, e.g. one test scheduler.
     *
     * Work of a single connection runs in the scope of the manager that created it instead,
     * see [Api].
     */
    private fun launchShared(block: suspend CoroutineScope.() -> Unit) {
        val context = synchronized(lock) {
            val newest = simulations.lastOrNull() ?: return@synchronized null
            val job = sharedJob ?: SupervisorJob().also { sharedJob = it }
            newest.coroutineContext.minusKey(Job) + job
        }
        if (context != null) CoroutineScope(context).launch(block = block)
        else fallbackScope.launch(block = block)
    }

    /**
     * Starts simulating this peripheral in the scope of a mock central manager.
     *
     * The returned scope uses a [SupervisorJob]: a failure in one piece of simulation work
     * doesn't cancel the rest of the simulation or the manager's scope. It is reported to the
     * exception handler of the manager's scope instead, which e.g. fails a test using `runTest`.
     *
     * The simulation ends when the returned scope is cancelled, either by [detach] or together
     * with the manager's scope.
     *
     * @param parent The scope of the mock central manager.
     * @param environment The environment of the mock central manager, if known.
     * @return The scope of the simulation. Pass it to [detach] to stop the simulation.
     */
    internal fun attach(parent: CoroutineScope, environment: MockEnvironment? = null): CoroutineScope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
            .also { simulation ->
                if (environment != null) {
                    useEnvironment(environment)
                }
                synchronized(lock) { simulations += simulation }
                simulation.coroutineContext.job.invokeOnCompletion { release(simulation) }
            }

    /**
     * Stops the simulation started with [attach], canceling all work started in its scope.
     *
     * Work shared by the clients keeps running as long as another manager simulates the peripheral.
     *
     * @param simulation The scope returned by [attach].
     */
    internal fun detach(simulation: CoroutineScope) {
        simulation.cancel()
    }

    private fun release(simulation: CoroutineScope) = synchronized(lock) {
        simulations -= simulation
        if (simulations.isEmpty()) {
            sharedJob?.cancel()
            sharedJob = null
        }
    }

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

                launchShared {
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

    private val _bondState = MutableStateFlow(if (isBonded) BondState.BONDED else BondState.NONE)

    /**
     * The bond state of the peripheral, as seen by the phone.
     *
     * The bond information is stored by both sides: the phone and the peripheral. This is the
     * phone's side. It is created by a client requesting bonding, or by accessing an attribute that
     * requires authentication (see [PeripheralSpecEventHandler.onBondRequest]).
     *
     * @see peripheralHasBond
     */
    val bondState: StateFlow<BondState> = _bondState.asStateFlow()

    private val _linkEncryption = MutableStateFlow(LinkEncryption.NotConnected)

    /**
     * The encryption of the link with the phone.
     *
     * A bonded peripheral encrypts the link right after connecting, unless it does not have the
     * bond information ([peripheralHasBond]), which the phone has. Bonding encrypts the link.
     */
    val linkEncryption: StateFlow<LinkEncryption> = _linkEncryption.asStateFlow()

    /**
     * Updates [linkEncryption] after the connection state or bond state changed.
     *
     * @param bonded Whether the phone is (being) bonded.
     */
    private fun updateLinkEncryption(bonded: Boolean = isBonded) {
        _linkEncryption.value = when {
            !isConnected -> LinkEncryption.NotConnected
            !bonded -> LinkEncryption.NotEncrypted
            peripheralHasBond -> LinkEncryption.Encrypted
            else -> LinkEncryption.KeyMissing
        }
    }

    @Volatile
    private var _bondFailureReason: BondResult.Reject.Reason? = null

    /**
     * The reason why the most recent bonding failed, or `null` if it did not.
     *
     * It is set before [bondState] changes to [BondState.NONE].
     */
    val bondFailureReason: BondResult.Reject.Reason?
        get() = _bondFailureReason

    /** Whether the phone has the bond information of the peripheral. */
    val isBonded: Boolean
        get() = _bondState.value == BondState.BONDED

    /**
     * Whether the peripheral has the bond information of the phone.
     *
     * This may differ from [isBonded]: when the phone forgot the bond, but the peripheral did not,
     * the peripheral rejects an attempt to pair again, until it forgets the bond too.
     */
    @Volatile
    var peripheralHasBond: Boolean = peripheralHasBond
        private set

    /** Whether the peripheral is connected. */
    val isConnected: Boolean
        get() = connectionsCount > 0

    /**
     * The number of times all the connections ended, i.e. [connectionsCount] was 0.
     *
     * A client that was connected in an earlier epoch has no connection to release.
     */
    private var connectionEpoch = 0

    /**
     * Number of active virtual connections to the peripheral.
     *
     * This is used to simulate multiple clients connecting to the same peripheral.
     *
     * Calling [simulateReset] sets this value to 0.
     */
    private var connectionsCount = if (activePhy != null) 1 else 0
        set(value) {
            field = value.coerceAtLeast(0)

            // If no more connections, reset connection parameters.
            if (field == 0) {
                // All the connections ended, also those that did not disconnect themselves.
                connectionEpoch++
                phy = null
                mtu = null
                llMtu = null
                connectionParameters = null
                updateLinkEncryption()
            } else if (mtu == null) {
                // If first connection, set default parameters.
                setDefaultConnectionState(PhyInUse.PHY_LE_1M)
                updateLinkEncryption()
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

    /**
     * The PHYs currently used by the connection: TX is the PHY used by the central to transmit,
     * and RX is the PHY used to receive. They may differ.
     */
    var phy: PhyInUse? = null
        private set

    init {
        // The initial value of connectionsCount doesn't call its setter, so the state of the
        // connection the peripheral is initially connected with is set here.
        if (activePhy != null) {
            setDefaultConnectionState(activePhy)
            updateLinkEncryption()
        }
    }

    /**
     * Sets the environment the peripheral is simulated in.
     *
     * If the peripheral is initially connected, the PHYs of that connection are limited to the
     * ones supported by the environment.
     */
    private fun useEnvironment(environment: MockEnvironment) {
        simulationEnvironment = environment
        phy = phy?.limitedTo(environment)
    }

    /** Whether the PHY is supported by the peripheral and by the given environment, if known. */
    private fun isSupported(phy: Phy, environment: MockEnvironment?): Boolean = phy in supportedPhy &&
            when (phy) {
                Phy.PHY_LE_2M -> environment?.isLe2MPhySupported ?: true
                Phy.PHY_LE_CODED -> environment?.isLeCodedPhySupported ?: true
                else -> true
            }

    /** Replaces PHYs that are not supported by both sides with LE 1M, which is always supported. */
    private fun PhyInUse.limitedTo(environment: MockEnvironment?) = PhyInUse(
        txPhy = txPhy.takeIf { isSupported(it, environment) } ?: Phy.PHY_LE_1M,
        rxPhy = rxPhy.takeIf { isSupported(it, environment) } ?: Phy.PHY_LE_1M,
    )

    /** The maximum ATT MTU supported by both the peripheral and the environment, if known. */
    private fun maxAttMtu(environment: MockEnvironment?): Int =
        min(maxAttMtu ?: ATT_MTU_DEFAULT, environment?.maxAttMtu ?: ATT_MTU_MAX)

    /** The maximum Link Layer MTU supported by both the peripheral and the environment, if known. */
    private fun maxLlMtu(environment: MockEnvironment?): Int =
        min(maxLlMtu ?: LL_MTU_DEFAULT, environment?.maxLlMtu ?: LL_MTU_MAX)

    /** Sets the parameters of a new connection, which has just been established. */
    private fun setDefaultConnectionState(phy: PhyInUse) {
        this.phy = phy
        this.mtu = ATT_MTU_DEFAULT
        this.llMtu = LL_MTU_DEFAULT
        this.connectionParameters = ConnectionParameters.Specified(
            connectionInterval = preferredConnectionInterval!!.first,
            latency = preferredSlaveLatency!!,
            supervisionTimeout = preferredSupervisionTimeout!!
        )
    }

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
     * @param isFromPeripheral whether the data is sent by the peripheral (e.g. a notification), so
     * it uses the RX PHY of the connection, instead of the TX PHY.
     * @return The estimated duration of the transfer.
     * @throws IllegalStateException if the peripheral is not connected.
     */
    internal fun estimateTransferDuration(
        value: ByteArray,
        isWrite: Boolean,
        withResponse: Boolean = true,
        isFromPeripheral: Boolean = false,
    ): Duration {
        val mtu = checkNotNull(mtu) { "Peripheral not connected" }
        val llMtu = checkNotNull(this@PeripheralSpec.llMtu)
        // Data sent by the peripheral is received by the central, so it uses the RX PHY.
        val phy = checkNotNull(phy).let { if (isFromPeripheral) it.rxPhy else it.txPhy }
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

    /**
     * Emulates the time it takes to transfer a single Write or Read packet, see
     * [estimateTransferDuration], by waiting for it.
     *
     * If the link is lost in the meantime, i.e. the peripheral is no longer connected, the
     * operation does not complete: this method throws [IllegalStateException], which the client
     * reports as [no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException], as the
     * attribute has been invalidated. The client also gets the disconnection event.
     *
     * @param value The attribute value to be written or read.
     * @param isWrite Whether this is a Write, as opposed to a Read.
     * @param withResponse Whether the operation requires a response.
     * @throws IllegalStateException If the peripheral is not connected.
     */
    internal suspend fun emulateTransferDuration(
        value: ByteArray,
        isWrite: Boolean,
        withResponse: Boolean = true,
    ) {
        delay(estimateTransferDuration(value, isWrite, withResponse))
        check(isConnected) { "The peripheral disconnected during the operation" }
    }

    /**
     * Emulates the delay of a single connection interval, in which a packet that does not carry
     * a value, like an error response, is delivered, by waiting for it.
     *
     * Like [emulateTransferDuration], it throws [IllegalStateException] if the peripheral is not
     * connected after the delay.
     *
     * @throws IllegalStateException If the peripheral is not connected.
     */
    internal suspend fun emulateDelay() {
        val connectionInterval = checkNotNull(connectionParameters) { "Peripheral not connected" }
            .connectionIntervalMillis.milliseconds
        delay(connectionInterval)
        check(isConnected) { "The peripheral disconnected during the operation" }
    }

    companion object {

        /**
         * The number of connection events after which the Link Layer applies a new set of connection
         * parameters, or a new PHY.
         *
         * Both the Connection Update and the PHY Update procedures are procedures with an *instant*,
         * the connection event in which the change happens. Bluetooth Core Specification 6.3,
         * Vol 6, Part B (Link Layer Specification), 5.5 Procedures with Instants: "If the Link Layer
         * of the Central is selecting the instant value, then the Central should allow a minimum of
         * 6 connection events when it intends to transmit and that the Peripheral will be listening
         * for before the instant occurs."
         *
         * This is that minimum, in old connection intervals. The slave latency, which may make
         * the Peripheral not listen to some of the events, is not modelled.
         */
        private const val LL_UPDATE_INSTANT_EVENTS = 6

        /**
         * The time after which the phone terminates the link when pairing failed.
         *
         * Observed in the HCI log of Android phones (Android 6, 16): the HCI Disconnect command
         * is sent exactly 3 seconds after the SMP Pairing Failed.
         */
        private val FAILED_PAIRING_DISCONNECTION_DELAY = 3.seconds

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
     * @param phy The PHY used to connect to the peripheral. It must be supported by the
     * peripheral, as it could not receive the connection request otherwise.
     * @param mtu The ATT MTU negotiated for the connection, defaults to the max ATT MTU
     * supported by the [PeripheralSpec].
     * @throws IllegalStateException if the peripheral is not connectable.
     * @throws IllegalArgumentException if the PHY is not supported by the peripheral. The
     * [PeripheralSpecEventHandler.onConnectionRequest] is not called then.
     */
    fun simulateConnection(
        phy: PrimaryPhy = PrimaryPhy.PHY_LE_1M,
        mtu: Int = maxAttMtu ?: ATT_MTU_DEFAULT,
    ) {
        // Make sure the device is connectable.
        val maxAttMtu = this.maxAttMtu ?: return
        val maxLlMtu = this.maxLlMtu ?: return

        // The peripheral can't receive a connection request sent on a PHY it doesn't support.
        require(supportedPhy.contains(phy.toPhy())) {
            "PHY ${phy.toPhy()} is not supported by the peripheral."
        }

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
                // The connection is limited to what both the phone and the peripheral support.
                this.mtu = mtu.coerceIn(ATT_MTU_DEFAULT, maxAttMtu(simulationEnvironment))
                this.llMtu = maxLlMtu(simulationEnvironment)
                if (isSupported(phy.toPhy(), simulationEnvironment)) {
                    this.phy = PhyInUse(phy.toPhy(), phy.toPhy())
                } // else defaults to PHY LE 1M, as the phone does not support the PHY
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
        terminateConnection(
            reason = Reason.TerminatePeerUser,
            handlerReason = DisconnectionReason.TerminateLocalHost,
        )
    }

    /**
     * Terminates the connection of all the clients, which is initiated by the phone.
     *
     * The clients are notified with [Reason.TerminateLocalHost] and the event handler of the
     * peripheral with [DisconnectionReason.TerminatePeerUser].
     */
    private fun terminateConnectionByPhone() {
        terminateConnection(
            reason = Reason.TerminateLocalHost,
            handlerReason = DisconnectionReason.TerminatePeerUser,
        )
    }

    private fun terminateConnection(reason: Reason, handlerReason: DisconnectionReason) {
        if (isConnected) {
            // TODO Should we delay this to simulate connection interval?
            connectionsCount = 0
            eventHandler!!.onConnectionLost(handlerReason)
            _events.tryEmit(ConnectionStateChanged(ConnectionState.Disconnected(reason)))
        }
    }

    /**
     * Simulates bonding of the phone and the peripheral.
     *
     * The bond state changes to [BondState.BONDING] and then, depending on the response of
     * [PeripheralSpecEventHandler.onBondRequest], to [BondState.BONDED] or back to [BondState.NONE].
     * The peripheral rejects bonding if it still has the bond information ([peripheralHasBond])
     * and the phone does not, or if it is not connectable.
     *
     * When the bonding fails while the peripheral is connected, the phone terminates the link
     * 3 seconds later, like Android does. The clients get [Reason.TerminateLocalHost].
     *
     * Does nothing if the peripheral is already bonded, or the bonding is in progress.
     */
    fun simulateBonding() {
        if (!_bondState.compareAndSet(BondState.NONE, BondState.BONDING)) {
            return
        }
        _bondFailureReason = null
        // The result is reported later, so that the clients can observe the BONDING state.
        launchShared {
            val result = try {
                when {
                    // The peripheral does not pair again with a phone that it is bonded with.
                    peripheralHasBond -> BondResult.Reject()
                    else -> eventHandler?.onBondRequest() ?: BondResult.Reject()
                }
            } catch (_: Exception) {
                BondResult.Reject()
            }
            when (result) {
                BondResult.Accept -> {
                    peripheralHasBond = true
                    // The link is encrypted, and then the bond is created.
                    updateLinkEncryption(bonded = true)
                    _bondState.value = BondState.BONDED
                }
                is BondResult.Reject -> {
                    // The reason is set before the state, so that it is available when the
                    // clients are notified.
                    _bondFailureReason = result.reason
                    _bondState.value = BondState.NONE
                    // Android terminates the link after a failed pairing.
                    if (isConnected) {
                        val epoch = connectionEpoch
                        delay(FAILED_PAIRING_DISCONNECTION_DELAY)
                        // The connection could have ended or been established again in the meantime.
                        if (epoch == connectionEpoch) {
                            terminateConnectionByPhone()
                        }
                    }
                }
            }
        }
    }

    /**
     * Ensures the link is authenticated, as the system does when a protected attribute is accessed.
     *
     * If the phone is not bonded, bonding is started and the operation is held until it finishes.
     * The bonding level is not simulated: the link is authenticated if the bonding succeeds.
     *
     * If the system does not repeat requests after bonding
     * ([no.nordicsemi.kotlin.ble.core.Environment.repeatsRequestsAfterBonding]), bonding is started
     * in the background, and the request is dropped without any response, like Android 6.0.1 does.
     *
     * @return The result of the authentication.
     */
    internal suspend fun requestAuthentication(): Authentication {
        simulateBonding()
        if (simulationEnvironment?.repeatsRequestsAfterBonding == false && !isBonded) {
            return Authentication.Dropped
        }
        val bonded = _bondState.first { it != BondState.BONDING } == BondState.BONDED
        // The peripheral does not have the key, so the link cannot be encrypted.
        return when {
            bonded && _linkEncryption.value != LinkEncryption.KeyMissing -> Authentication.Granted
            else -> Authentication.Denied
        }
    }

    /** The result of authenticating the link before accessing a protected attribute. */
    internal enum class Authentication {
        /** The link is authenticated, and the request can be handled. */
        Granted,
        /** The link could not be authenticated, the request fails with Insufficient Authentication. */
        Denied,
        /** The system started bonding and dropped the request; it will never be answered. */
        Dropped,
    }

    /**
     * Simulates the phone removing the bond information of the peripheral.
     *
     * The peripheral keeps its bond information ([peripheralHasBond]). The connection is terminated
     * by the phone.
     *
     * @see simulatePeripheralBondLoss
     */
    fun simulateBondRemoval() {
        _bondState.value = BondState.NONE
        terminateConnectionByPhone()
    }

    /**
     * Simulates the peripheral forgetting the bond information, for example after a factory reset.
     *
     * The phone keeps its bond information ([isBonded]).
     *
     * @see simulateBondRemoval
     */
    fun simulatePeripheralBondLoss() {
        peripheralHasBond = false
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
            launchShared {
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
     * A notification or indication carries at most ATT MTU - 3 bytes, so a longer value is
     * truncated to that length, as a real peripheral would do. The MTU is the one of the connection
     * when this method is called.
     *
     * The *handle number* is returned when creating the characteristic using
     * [PeripheralSpec.Builder.connectable] from [ServiceScope.Characteristic].
     *
     * @param handle The handle of the characteristic.
     * @param value The new value of the characteristic.
     */
    fun simulateValueUpdate(handle: Int, value: ByteArray) {
        if (!isConnected) return

        // Bluetooth Core Specification 6.2, Vol 3 (Host), Part F (ATT), 3.4.7.1 Handle Value
        // Notification: the value is truncated to ATT_MTU - 3 bytes (opcode and handle take 3).
        // The maximum length of an attribute value is 512 bytes.
        val maxLength = min(checkNotNull(mtu) - 3, 512)
        val notifiedValue = value.take(maxLength).toByteArray()

        // Notify clients that the value has changed.
        launchShared {
            val transferDuration = estimateTransferDuration(
                value = notifiedValue,
                isWrite = true,
                withResponse = false,
                isFromPeripheral = true,
            )
            delay(transferDuration)

            // If any client is still connected, emit the event.
            if (isConnected) {
                _events.emit(CharacteristicChanged(handle, notifiedValue))
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
            launchShared {
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
            _bondState.value = BondState.NONE
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
     * Defaults to 6, the minimum number of connection events before the instant, in which the
     * Link Layer applies them.
     */
    fun simulateConnectionParametersRequest(
        parameters: ConnectionParameters.Specified,
        delay: Int = LL_UPDATE_INSTANT_EVENTS,
    ) {
        // TODO Validate parameters against preferred / possible parameters?
        connectionParameters?.connectionIntervalMillis?.let { connectionInterval ->
            val epoch = connectionEpoch
            launchShared {
                // Wait for few (old) connection intervals before applying new parameters.
                delay(connectionInterval.milliseconds * delay)

                // The connection could have ended in the meantime.
                if (epoch != connectionEpoch) return@launchShared

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
        if (maxAttMtu == null) return
        val newMtu = mtu.coerceIn(ATT_MTU_DEFAULT, maxAttMtu(simulationEnvironment))

        // MTU may be changed only once per connection.
        // If MTU is grater than 23, it has already been changed.
        if (currentMtu != ATT_MTU_DEFAULT || newMtu == currentMtu) {
            return
        }

        // Wait for few connection intervals before applying new parameters.
        val connectionInterval = checkNotNull(connectionParameters).connectionIntervalMillis.milliseconds
        val epoch = connectionEpoch
        launchShared {
            delay(connectionInterval * 2)

            // The connection could have ended in the meantime. Its MTU must not leak to the next one.
            if (epoch != connectionEpoch) return@launchShared

            this@PeripheralSpec.mtu = newMtu
            _events.emit(MtuChanged(newMtu))
        }
    }

    // TODO Add more simulation methods

    // Implementation

    /**
     * Initiates a mock GATT connection to the peripheral.
     *
     * This method does not wait for the connection: it starts it and returns the [Api] of the
     * client, like `connectGatt()` does on a phone. The connection awaits first connectable
     * advertisement from the given flow of advertisements, and then is simulated by calling
     * [Api.connect]. The client is notified with an event. The attempt can be cancelled with
     * [Api.disconnect] and [Api.close].
     *
     * If a peripheral is already connected, it just emits the connection event.
     *
     * @param environment The mock environment.
     * @param autoConnect Whether to use auto-connect mode.
     * @param advertisements A flow of advertisements emitted by the mock advertiser.
     * @param managerScope The scope of the mock central manager connecting to the peripheral.
     * @param clientEvents The events of the client connecting to the peripheral. They get the
     * events meant only for this client, see [Api]. The events for all the clients are in [events].
     * @return The mock GATT object.
     */
    internal fun connectGatt(
        environment: MockEnvironment,
        autoConnect: Boolean,
        autoMtu: Boolean,
        opportunistic: Boolean,
        advertisements: Flow<MockScanResult<*>>,
        managerScope: CoroutineScope,
        clientEvents: MutableSharedFlow<GattEvent>,
    ): Api {
        if (simulationEnvironment == null) {
            useEnvironment(environment)
        }
        return Api(environment, managerScope, clientEvents).also { gatt ->
            gatt.startConnecting(autoConnect, autoMtu, advertisements)
        }
    }

    /**
     * Returns whether the phone can receive the advertisement, that is whether it supports
     * the PHYs it was sent on.
     */
    private fun MockEnvironment.canReceive(result: MockScanResult<*>): Boolean = when {
        result.primaryPhy == PrimaryPhy.PHY_LE_CODED && !isScanningOnLeCodedPhySupported -> false
        result.secondaryPhy == Phy.PHY_LE_CODED && !isLeCodedPhySupported -> false
        result.secondaryPhy == Phy.PHY_LE_2M && !isLe2MPhySupported -> false
        else -> true
    }

    /**
     * A mock GATT connection of a single client.
     *
     * Jobs of the connection run in the scope of the mock central manager that created it,
     * so they don't depend on other managers simulating the same peripheral.
     *
     * @param managerScope The scope of the mock central manager that created the connection.
     * @param clientEvents The events of this client. The events concerning the whole connection are
     * emitted by the peripheral to all its clients (see [events]). The ones that are the result of
     * an action of this client, and concern only this client, like its connection, disconnection or
     * service discovery, are emitted here, so that other clients do not receive them.
     * @hide
     */
    inner class Api internal constructor(
        val environment: MockEnvironment,
        private val managerScope: CoroutineScope,
        private val clientEvents: MutableSharedFlow<GattEvent>,
    ) {
        /**
         * A job for observing [ServicesChanged] events, that invalidates the service cache.
         *
         * Each connected client may invalidate services, but the change applies to all of them.
         */
        private val cacheMonitoring = events
            .filterIsInstance<ServicesChanged>()
            .onEach { cachedServices = null }
            .launchIn(managerScope)

        /** The job establishing the connection, see [startConnecting]. */
        private var connection: Job? = null

        /**
         * Starts connecting to the peripheral, without waiting for the result.
         *
         * @param autoConnect Whether to use auto-connect mode.
         * @param autoMtu Whether the MTU should be automatically negotiated upon connection.
         * @param advertisements A flow of advertisements emitted by the mock advertiser.
         */
        internal fun startConnecting(
            autoConnect: Boolean,
            autoMtu: Boolean,
            advertisements: Flow<MockScanResult<*>>,
        ) {
            connection = managerScope.launch {
                establishConnection(autoConnect, autoMtu, advertisements)
            }
        }

        /** Waits for the peripheral to accept the connection, and reports the result. */
        private suspend fun establishConnection(
            autoConnect: Boolean,
            autoMtu: Boolean,
            advertisements: Flow<MockScanResult<*>>,
        ) {
            // If the mock peripheral is already connected (to the phone) and another
            // (virtual) client (that is Peripheral instance) tries to connect to it,
            // just notify about the success.
            if (isConnected) {
                // This event will be ignored by already connected clients, but can re-connect
                // a disconnected client, if not closed.
                connectionsCount += 1
                holdConnection()
                if (autoConnect) {
                    reconnectOnLinkLoss(advertisements, autoMtu)
                }
                clientEvents.emit(ConnectionStateChanged(ConnectionState.Connected))
                return
            }

            // Android does not allow to create an auto-connection to a peripheral with RPA
            // if the device is not bonded.
            // Note: If a device is not known (has not been scanned before), Android
            //       assumes PUBLIC address type.
            // Note 2: On iOS it is not possible to get a peripheral by Device Address at all.
            //         Devices must be scanned so that the Identifier is known.
            if (autoConnect && !isBonded && isKnown && addressType == AddressType.RANDOM_PRIVATE_RESOLVABLE) {
                // Older systems accept the request, but never connect, and do not report anything.
                if (!environment.rejectsAutoConnectToUnbondedPrivateAddress) {
                    return
                }
                // Some Android devices return error 133, newer ones 135.
                // This event is later translated to UnsupportedAddress.
                clientEvents.emit(ConnectionStateChanged(ConnectionState.Disconnected(Reason.Unknown(135))))
                return
            }

            // If a device was not scanned before, Android assumes it has PUBLIC address type.
            // Skip listening for advertisements if the spec has different address type.
            // TODO Does iOS behave the same way?
            val assumedPublicAddress = !isKnown
            if (assumedPublicAddress && addressType != AddressType.PUBLIC) {
                return
            }

            // With auto-connect the system keeps connecting, also when the link is lost later.
            if (autoConnect) {
                reconnectOnLinkLoss(advertisements, autoMtu)
            }

            // Connection Request is sent as a response to a connectable advertisement.
            // Note: The advertisements flow is not filtered by the PHYs supported by the phone,
            // so a device advertising on a PHY the phone can't receive must be skipped here,
            // just like the scanner does.
            // With auto-connect the system keeps trying, until the request is accepted. A direct
            // connection is tried once, and when the request is denied, it times out.
            do {
                val advertisement = advertisements.first { scanResult ->
                    scanResult.peripheralSpec.identifier == identifier &&
                            scanResult.isConnectable &&
                            environment.canReceive(scanResult)
                }

                // Note: Ignoring "preferred PHY" set by user. Choosing the primary PHY of scanned packet.
                val accepted = connect(advertisement.primaryPhy, autoMtu)
            } while (autoConnect && !accepted)
        }

        /** The job observing the connection state, started for auto-connect connections. */
        private var autoReconnectMonitoring: Job? = null

        /** The job waiting for the peripheral to advertise after the link has been lost. */
        private var reconnection: Job? = null

        /**
         * Makes the client reconnect when the link is lost, like with `autoConnect` set to `true`
         * on Android: the system keeps connecting, and the connection is established when
         * the peripheral advertises, as soon as it is in range.
         *
         * The client does not reconnect after it has disconnected itself, or when it has been
         * closed.
         *
         * @param advertisements A flow of advertisements emitted by the mock advertiser.
         * @param autoMtu Whether the MTU should be automatically negotiated upon connection.
         */
        internal fun reconnectOnLinkLoss(advertisements: Flow<MockScanResult<*>>, autoMtu: Boolean) {
            autoReconnectMonitoring?.cancel()
            autoReconnectMonitoring = clientEvents
                .filterIsInstance<ConnectionStateChanged>()
                .map { it.newState }
                .filterIsInstance<ConnectionState.Disconnected>()
                .onEach { state ->
                    reconnection?.cancel()
                    // Like the client, which keeps an auto-connect peripheral open (Connecting)
                    // unless it was disconnected by the user, or the bond information is lost.
                    val isLinkLost = !state.isUserInitiated &&
                            state.reason !is Reason.InsufficientAuthentication
                    reconnection = when (isLinkLost) {
                        true -> managerScope.launch { reconnect(advertisements, autoMtu) }
                        false -> null
                    }
                }
                .launchIn(managerScope)
        }

        /** Connects when the peripheral advertises, until the connection is accepted. */
        private suspend fun reconnect(advertisements: Flow<MockScanResult<*>>, autoMtu: Boolean) {
            do {
                val advertisement = advertisements.first { scanResult ->
                    scanResult.peripheralSpec.identifier == identifier &&
                            scanResult.isConnectable &&
                            environment.canReceive(scanResult)
                }
            } while (!connect(advertisement.primaryPhy, autoMtu))
        }

        /**
         * The epoch of the connection this client holds, or `null` if it holds none.
         *
         * @see connectionEpoch
         */
        private var heldEpoch: Int? = null

        /** Marks that this client has been counted in [connectionsCount]. */
        internal fun holdConnection() {
            heldEpoch = connectionEpoch
        }

        /**
         * Closes the GATT connection.
         *
         * Like `BluetoothGatt.close()`, it also ends the connection if it is still established,
         * without waiting for the link to be terminated, and the peripheral is notified.
         */
        internal fun close() {
            cachedServices = null
            cacheMonitoring.cancel()
            connection?.cancel()
            autoReconnectMonitoring?.cancel()
            reconnection?.cancel()

            // Release the connection, unless it has already ended (disconnect, link loss, reset).
            if (heldEpoch == connectionEpoch) {
                heldEpoch = null
                connectionsCount -= 1
                if (connectionsCount == 0) {
                    eventHandler?.onConnectionLost(DisconnectionReason.TerminatePeerUser)
                }
            }
            heldEpoch = null
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
        }?.map { MockRemoteService(this@PeripheralSpec, it, clientEvents) }
            private set

        /**
         * Simulates connecting to the peripheral.
         *
         * @param phy The PHY used for the connection. This is the primary PHY of the connectable
         * advertisement, which triggered the connection request.
         * @param autoMtu Whether the MTU should be automatically negotiated upon connection.
         * @return `true` if the connection was accepted by the peripheral.
         * @throws IllegalStateException when the device is not connectable.
         */
        suspend fun connect(phy: PrimaryPhy, autoMtu: Boolean): Boolean {
            val maxAttMtu = maxAttMtu ?: return false
            val maxLlMtu = this@PeripheralSpec.maxLlMtu ?: return false
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
                    holdConnection()
                    // The connection is established on the PHY of the advertisement, unless
                    // it is not supported by both sides.
                    val connectionPhy = phy.toPhy().takeIf { isSupported(it, environment) } ?: Phy.PHY_LE_1M
                    this@PeripheralSpec.phy = PhyInUse(connectionPhy, connectionPhy)
                    this@PeripheralSpec.llMtu = maxSupportedLlMtu
                    // TODO set L2CAP MTU to max supported value
                    // Note: onConnectionRequest callback can request MTU, bonding or PHY update.
                    // TODO Make sure these requests are handled after state is reported
                    clientEvents.emit(ConnectionStateChanged(ConnectionState.Connected))

                    if (autoMtu) {
                        simulateMtuRequest(maxSupportedAttMtu)
                    }
                    return true
                }

                ConnectionResult.Deny -> {
                    // Do nothing. The connection request should time out.
                    return false
                }
            }
        }

        /**
         * Simulates disconnecting from the peripheral.
         *
         * If the client is only connecting, the attempt is cancelled.
         */
        suspend fun disconnect() = disconnect(Reason.Success)

        internal suspend fun disconnect(reason: Reason) {
            // A client that holds no connection is connecting, or, with auto-connect, connecting
            // again after the link was lost. Cancel the attempt, there is no link to terminate.
            if (heldEpoch != connectionEpoch) {
                connection?.cancel()
                reconnection?.cancel()
                clientEvents.emit(ConnectionStateChanged(ConnectionState.Disconnected(reason)))
                return
            }

            val connectionParameters =
                checkNotNull(connectionParameters) { "Peripheral not connected." }
            val connectionInterval = connectionParameters.connectionIntervalMillis.milliseconds
            val eventHandler = checkNotNull(eventHandler)

            delay(connectionInterval)

            // The connection of this client is released below. The client closes the peripheral
            // when it receives the event, which must not release it again.
            heldEpoch = null
            clientEvents.emit(ConnectionStateChanged(ConnectionState.Disconnected(reason)))

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
                emitServicesDiscovered(it)
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
                                latency = connectionParameters.latency,
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
                    val discoveredServices = services!!.map { MockRemoteService(this@PeripheralSpec, it, clientEvents) }
                    // An empty result is a failure, so it's not cached.
                    if (discoveredServices.isNotEmpty()) {
                        isServiceCacheValid = true
                        cachedServices = discoveredServices
                    }
                    emitServicesDiscovered(discoveredServices)

                    // Restore connection parameters.
                    if (environment.reportsConnectionParameters) {
                        managerScope.launch {
                            delay(connectionInterval * 2)
                            _events.emit(ConnectionParametersChanged(connectionParameters))
                        }
                    }
                }

                is ServiceDiscoveryResult.Failure -> {
                    // Let's say the failure happens after some delay.
                    delay(connectionInterval * 2)
                    // No exact error is reported, just like an empty result.
                    clientEvents.emit(ServiceDiscoveryFailed(RemoteServices.Failed.Reason.EmptyResult))
                }
            }
            return true
        }

        /**
         * Reports the result of the service discovery.
         *
         * A real peripheral has at least Generic Access and Generic Attribute services, so
         * a discovery returning no services failed. This is how it's reported by the client,
         * see `onServicesDiscovered` in `NativeGattCallback`.
         */
        private suspend fun emitServicesDiscovered(services: List<RemoteService>) {
            when {
                services.isEmpty() ->
                    clientEvents.emit(ServiceDiscoveryFailed(RemoteServices.Failed.Reason.EmptyResult))
                else -> clientEvents.emit(ServicesDiscovered(services))
            }
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
            if (maxAttMtu == null) return false
            val newMtu = mtu.coerceIn(ATT_MTU_DEFAULT, maxAttMtu(environment))

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
                delay(connectionParameters.connectionIntervalMillis.milliseconds * LL_UPDATE_INSTANT_EVENTS)

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
            val currentPhy = this@PeripheralSpec.phy ?: return

            // Only PHYs supported by both the phone and the peripheral can be used.
            // For the others the current PHY stays in use.
            val requestedPhy = PhyInUse(
                txPhy = txPhy.takeIf { isSupported(it, environment) } ?: currentPhy.txPhy,
                rxPhy = rxPhy.takeIf { isSupported(it, environment) } ?: currentPhy.rxPhy,
            )

            // If the requested PHY is already in use, just emit it.
            // The collector is waiting for the PhyChanged event.
            if (currentPhy == requestedPhy) {
                _events.emit(PhyChanged(currentPhy))
                return
            }

            val connectionParameters = connectionParameters ?: return

            // Simulate a delay for requesting PHY update.
            delay(connectionParameters.connectionIntervalMillis.milliseconds * LL_UPDATE_INSTANT_EVENTS)

            // Check if the peripheral is still connected.
            if (isConnected) {
                this@PeripheralSpec.phy = requestedPhy
                _events.emit(PhyChanged(requestedPhy))
            }
        }

        /**
         * Simulates reading the currently used PHY.
         */
        suspend fun readPhy() {
            val phy = phy ?: return
            _events.emit(PhyChanged(phy.limitedTo(environment)))
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
        private var activePhy: PhyInUse? = null
        private var isKnown: Boolean = false
        private var isBonded: Boolean = false
        private var peripheralHasBond: Boolean = false
        private var isServiceCacheValid: Boolean = false

        /**
         * Adds an advertising set to the peripheral advertisement configuration.
         *
         * @param parameters The advertising parameters. As on a real device, the time between
         * advertising events is the interval plus a random 0-10 ms.
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
         * @param parameters The advertising parameters. As on a real device, the time between
         * advertising events is the interval plus a random 0-10 ms.
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
         * @param isBonded Whether the phone has the bond information of the peripheral.
         * @param peripheralHasBond Whether the peripheral has the bond information of the phone.
         * By default, the same as [isBonded].
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
            peripheralHasBond: Boolean = isBonded,
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
            this.peripheralHasBond = peripheralHasBond
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
         * This value must be in range 23..517. The actual MTU for the connection will be the
         * calculated using `min(maxAttMtu, environment.maxAttMtu)`.
         * @param maxL2capMtu The maximum supported L2CAP MTU (Maximum Transfer Unit used on L2CAP
         * Layer using Data Length Extension (DLE)). This value must be in range 27..251.
         * @param isPhyLe2MSupported Whether the device supports PHY LE 2M.
         * @param isPhyCodedSupported Whether the device supports PHY LE Coded.
         * @param activePhy The PHYs (TX and RX) used by the existing connection. Both must be
         * supported, so to use LE 2M or LE Coded set [isPhyLe2MSupported] or [isPhyCodedSupported] too.
         * Ignored if the peripheral is out of range, as it is not connected then.
         * @param isBonded Whether the phone has the bond information of the peripheral.
         * @param peripheralHasBond Whether the peripheral has the bond information of the phone.
         * By default, the same as [isBonded].
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
            activePhy: PhyInUse = PhyInUse.PHY_LE_1M,
            isBonded: Boolean = false,
            peripheralHasBond: Boolean = isBonded,
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
            this.peripheralHasBond = peripheralHasBond
            // Note: The PHY here may be unsupported. This is checked in build(), as
            // `highSpeed` may be called later and add supported PHYs.
            this.activePhy = activePhy
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
            activePhy = activePhy
                ?.also {
                    require(it.txPhy in supportedPhy && it.rxPhy in supportedPhy) {
                        "Active PHY ($it) is not supported by the peripheral."
                    }
                }
                // A peripheral that is out of range can't be connected.
                ?.takeIf { proximity != Proximity.OUT_OF_RANGE },
            isKnown = isKnown,
            isBonded = isBonded,
            peripheralHasBond = peripheralHasBond,
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