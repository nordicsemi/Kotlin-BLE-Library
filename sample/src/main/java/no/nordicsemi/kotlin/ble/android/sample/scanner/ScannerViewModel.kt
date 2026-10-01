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

package no.nordicsemi.kotlin.ble.android.sample.scanner

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nordicsemi.kotlin.ble.android.sample.common.AttributeAction
import no.nordicsemi.kotlin.ble.android.sample.common.AttributeValue
import no.nordicsemi.kotlin.ble.android.sample.common.Timestamped
import no.nordicsemi.kotlin.ble.android.sample.scanner.profile.LedButtonProfile
import no.nordicsemi.kotlin.ble.android.sample.scanner.profile.impl.LedButtonServiceImpl
import no.nordicsemi.kotlin.ble.client.AnyRemoteService
import no.nordicsemi.kotlin.ble.client.ProfileServices
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewPeripheral
import no.nordicsemi.kotlin.ble.client.android.preview.PreviewScanResult
import no.nordicsemi.kotlin.ble.client.distinctByPeripheral
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.WriteType
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val centralManager: CentralManager,
    // We're not using ViewModelScope. For test purposes it's better to create a custom Scope,
    // also connected to the ViewModel lifecycle, but which can be replaced in tests.
    private val scope: CoroutineScope,
): ViewModel() {
    val state = centralManager.state

    private val _peripherals: MutableStateFlow<List<ScanResult>> = MutableStateFlow(
        listOf(
            // Note: It's not possible to connect to PreviewPeripheral instances.
            //       An exception is thrown, that it was obtained using a different CentralManager.
            // TODO Allow it?
            PreviewScanResult(
                peripheral = PreviewPeripheral(scope, phy = PhyInUse(txPhy = Phy.PHY_LE_1M, rxPhy = Phy.PHY_LE_2M))
                    .apply {
                        // Track state of each peripheral.
                        // Note, that the states are observed using the view model scope, even when the
                        // device isn't connected.
                        observePeripheralState(this, scope)
                        // Track bond state of each peripheral.
                        observeBondState(this, scope)
                    },
                isConnectable = true,
            )
        )
    )
    val peripherals = _peripherals.asStateFlow()

    private val _isScanning: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /**
     * The last values of characteristics and descriptors, keyed by the attribute instance.
     */
    private val _attributeValues: MutableStateFlow<Map<Any, AttributeValue>> = MutableStateFlow(emptyMap())
    val attributeValues: StateFlow<Map<Any, AttributeValue>> = _attributeValues.asStateFlow()

    /**
     * Subscriptions to notifications or indications started from the UI.
     */
    private val subscriptions = ConcurrentHashMap<RemoteCharacteristic, Job>()

    private var connectionScopeMap = mutableMapOf<Peripheral, CoroutineScope>()

    private var scanningJob: Job? = null

    fun onScanRequested() {
        scanningJob = centralManager
            .scan(5000.milliseconds) {
//                Any {
//                    ManufacturerData(0x0059)
//                    ServiceUuid(Uuid.fromShortUuid(0x1809))
//                }
//                ServiceUuid(Uuid.parse("00001523-1212-EFDE-1523-785FEABCD123"))
                Any {
                    Name("Pixel 5")
                    Name("Pixel 7")
                    Name("DFU1A06")
                    Name("nRFConnect")
                    Name("HR Sensor")
                    Name(Regex("Mesh.*"))
                    Name(Regex("Nordic.*"))
                }
            }
            .onStart {
                _isScanning.update { true }
            }
            .distinctByPeripheral()
            .filterNot { result ->
                _peripherals.value.any { it.peripheral == result.peripheral }
            }
            //.distinct()
            .onEach { result ->
                val newPeripheral = result.peripheral
                Timber.i("Found new device: ${newPeripheral.name} (${newPeripheral.address}), connectable: ${result.isConnectable}")
                _peripherals.update { peripherals.value + result }
            }
            .map { it.peripheral }
            .onEach { peripheral ->
                // Track state of each peripheral.
                // Note, that the states are observed using the view model scope, even when the
                // device isn't connected.
                observePeripheralState(peripheral, scope)
                // Track bond state of each peripheral.
                observeBondState(peripheral, scope)
            }
            .catch { t ->
                Timber.e("Scan failed: $t")
            }
            .onCompletion {
                _isScanning.update { false }
            }
            .launchIn(scope)
    }

    fun onStopScanRequested() {
        scanningJob?.cancel()
    }

    fun onPeripheralSelected(peripheral: Peripheral) {
        // If the connection scope exists for the given peripheral, that means we're connected
        // and the user initiated disconnection.
        val connectionScope = connectionScopeMap[peripheral]
        connectionScope?.launch {
            Timber.v("Disconnecting from ${peripheral.name}...")
            try {
                peripheral.disconnect()
                Timber.i("Disconnected from ${peripheral.name}!")
            } catch (e: Exception) {
                Timber.e(e, "Disconnect failed")
            }
        } ?: run {
            // Otherwise, create a new connection scope that will handle all events until we are
            // done with the device.
            connectionScopeMap[peripheral] = CoroutineScope(context = Dispatchers.IO)
                .apply {
                    launch {
                        try {
                            // This could be wrapped in withTimeout, but the Direct option
                            // already specifies a timeout.
                            connect(peripheral, true)

                            // The first time the app connects to the peripheral it needs to initiate
                            // observers for various parameters.
                            // The observers will get canceled when the connection scope gets canceled,
                            // that is when the device is manually disconnected in case of auto connect,
                            // or disconnects for any reason when auto connect was false.
                            observePhy(peripheral, this)
                            observeConnectionParameters(peripheral, this)
                            observeServices(peripheral, this)

                            installLbsProfile(peripheral, required = false) { state ->
                                // When the Button is long-clicked, cancel the profile scope.
                                // This will disconnect the device.
                                state.buttonLongPressed
                                    .onEach {
                                        Timber.w("LBS: Long button press detected, closing profile")
                                        cancel()
                                    }
                                    .launchIn(this)

                                // Tapping Button starts and stops blinking LED.
                                Timber.v("LBS: Click Button to toggle LED blinking, Long Click to disconnect")
                                while (isActive) {
                                    state.buttonPressed.firstOrNull() ?: break

                                    // Blink until the button is pressed again.
                                    val blinking = launch {
                                        while (isActive) {
                                            // We use NonCancellable to make sure the LED doesn't
                                            // quick-blink when turned off.
                                            withContext(NonCancellable) {
                                                state.led.value = true
                                                delay(250.milliseconds)
                                                state.led.value = false
                                                delay(250.milliseconds)
                                            }
                                        }
                                    }
                                    state.buttonPressed.firstOrNull()
                                    blinking.cancel()
                                }
                                // There's no need to await cancellation, as the loop ends only
                                // on cancellation.
                                // awaitCancellation()
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Connection attempt failed")
                            connectionScopeMap.remove(peripheral)?.cancel()
                        }
                    }
                }
        }
    }

    fun onBondRequested(peripheral: Peripheral) {
        scope.launch {
            try {
                Timber.i("Bonding with ${peripheral.name}...")
                peripheral.createBond()
                Timber.i("Bonding successful")
            } catch (e: Exception) {
                Timber.e(e, "Bonding failed")
            }
        }
    }

    fun onRemoveBondRequested(peripheral: Peripheral) {
        scope.launch {
            try {
                Timber.i("Removing bond information...")
                peripheral.removeBond()
                Timber.i("Bond removed")
            } catch (e: Exception) {
                Timber.e(e, "Removing bond failed")
            }
        }
    }

    fun onClearCacheRequested(peripheral: Peripheral) {
        scope.launch {
            try {
                Timber.i("Clearing cache...")
                peripheral.refreshCache()
                Timber.i("Cache cleared")
            } catch (e: Exception) {
                Timber.e(e, "Clearing cache failed")
            }
        }
    }

    fun onRssiRead(peripheral: Peripheral) {
        scope.launch {
            try {
                Timber.i("Reading RSSI...")
                val rssi = peripheral.readRssi()
                Timber.i("RSSI: $rssi dBm")
            } catch (e: Exception) {
                Timber.e(e, "Reading RSSI failed")
            }
        }
    }

    fun onReadPhy(peripheral: Peripheral) {
        scope.launch {
            try {
                Timber.i("Reading PHY...")
                val phy = peripheral.readPhy()
                Timber.i("PHY: $phy")
            } catch (e: Exception) {
                Timber.e(e, "Reading PHY failed")
            }
        }
    }

    fun onAttributeAction(action: AttributeAction) {
        when (action) {
            is AttributeAction.Read -> read(action.characteristic)
            is AttributeAction.Write -> write(action.characteristic, action.value, action.writeType)
            is AttributeAction.ToggleNotifications -> toggleNotifications(action.characteristic)
            is AttributeAction.ReadDescriptor -> read(action.descriptor)
        }
    }

    override fun onCleared() {
        super.onCleared()
        centralManager.close()
    }

    // ---- Implementation ----

    private suspend fun connect(peripheral: Peripheral, autoConnect: Boolean) {
        Timber.v("Connecting to ${peripheral.name}...")
        centralManager.connect(
            peripheral = peripheral,
            options = if (autoConnect) {
                CentralManager.ConnectionOptions.AutoConnect()
            } else {
                CentralManager.ConnectionOptions.Direct(
                    timeout = 3.seconds,
                    retry = 2,
                    retryDelay = 1.seconds,
                    automaticallyRequestHighestValueLength = true,
                )
            },
        )
        Timber.i("Connected to ${peripheral.name}!")
    }

    private suspend fun initiateConnection(peripheral: Peripheral) {
        try {
            // MTU request is done automatically on connection.
            // peripheral.requestHighestValueLength()

            // Check maximum write length
            val writeType = WriteType.WITHOUT_RESPONSE
            val length = peripheral.maximumWriteValueLength(writeType)
            Timber.i("Maximum write length for $writeType: $length bytes")

            // Read RSSI
            val rssi = peripheral.readRssi()
            Timber.i("RSSI: $rssi dBm")

            // Read PHY
            val phyInUse = peripheral.readPhy()
            Timber.i("PHY in use: $phyInUse")

            // Request connection priority
            val newConnectionParameters = peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
            Timber.i("Connection priority changed to HIGH")
            Timber.i("New connection parameters: $newConnectionParameters")
        } catch (e: Exception) {
            Timber.e("Peripheral disconnected before initialization completed: ${e.message}")
        }
    }

    private fun observePhy(peripheral: Peripheral, scope: CoroutineScope) {
        peripheral.phy
            .onEach {
                Timber.i("PHY changed to: $it")
            }
            .onEmpty {
                Timber.w("PHY didn't change")
            }
            .onCompletion {
                Timber.d("PHY collection completed")
            }
            .launchIn(scope)
    }

    private fun observeConnectionParameters(peripheral: Peripheral, scope: CoroutineScope) {
        peripheral.connectionParameters
            .onEach {
                Timber.i("Connection parameters changed to: $it")
            }
            .onEmpty {
                Timber.w("Connection parameters didn't change")
            }
            .onCompletion {
                Timber.d("Connection parameters collection completed")
            }
            .launchIn(scope)
    }

    private fun observeServices(peripheral: Peripheral, scope: CoroutineScope) {
        // Services will change multiple times. Initially, the services() will emit null (event 1).
        // When services are discovered, it will emit the list of services (event 2).
        // If the services change later, it will emit null again (event 3) and the new list (event 4).
        // The event index is printed to be able to track which services does this apply to.
        var event = 0

        peripheral.services()
            .onEach { services ->
                // On each services change, increment the event index.
                event += 1
                Timber.i("($event) Services changed: $services")
            }
            .onEach {
                // Forget values of invalidated attributes.
                _attributeValues.update { values ->
                    values.filterKeys { attribute ->
                        when (attribute) {
                            is RemoteCharacteristic -> attribute.owner != null
                            is RemoteDescriptor -> attribute.owner != null
                            else -> false
                        }
                    }
                }
            }
            .mapNotNull { (it as? RemoteServices.Discovered)?.services }
            .onEach { services ->
                // Keep the current event fixed in this block.
                val ce = event

                // Values are not read and notifications are not enabled automatically.
                // Use R, W, N and I buttons in the UI instead.
                services.allCharacteristics().forEach { remoteCharacteristic ->
                    // Observe notifications or indications state.
                    if (remoteCharacteristic.isSubscribable()) {
                        observeNotificationState(remoteCharacteristic, ce, scope)
                    }
                }
            }
            // This catch would cancel the flow and stop collecting.
            // Instead, exceptions are caught in onEach above.
            .catch { t->
                Timber.wtf(t, "Operation failed: ${t.message}")
            }
            .onCompletion {
                Timber.d("Service collection completed")
            }
            .launchIn(scope)
    }

    private fun read(characteristic: RemoteCharacteristic) {
        launchOperation(characteristic, "Reading ${characteristic.uuid}") {
            val value = characteristic.read()
            Timber.i("Value of ${characteristic.uuid}: 0x${value.toHexString()}")
            updateValue(characteristic) {
                copy(received = Timestamped(value), notificationCount = 0, error = null)
            }
        }
    }

    private fun write(characteristic: RemoteCharacteristic, value: ByteArray, writeType: WriteType) {
        launchOperation(characteristic, "Writing to ${characteristic.uuid}") {
            characteristic.write(value, writeType)
            Timber.i("Wrote 0x${value.toHexString()} to ${characteristic.uuid} using $writeType")
            updateValue(characteristic) { copy(sent = Timestamped(value), error = null) }
        }
    }

    private fun read(descriptor: RemoteDescriptor) {
        launchOperation(descriptor, "Reading descriptor ${descriptor.uuid}") {
            val value = descriptor.read()
            Timber.i("Value of descriptor ${descriptor.uuid}: 0x${value.toHexString()}")
            updateValue(descriptor) { copy(received = Timestamped(value), error = null) }
        }
    }

    private fun toggleNotifications(characteristic: RemoteCharacteristic) {
        // If the notifications are enabled, or are being enabled, disable them.
        // Mind, that they could have been enabled elsewhere, e.g. by the LBS profile.
        if (characteristic.isNotifying.value || subscriptions.containsKey(characteristic)) {
            val job = subscriptions.remove(characteristic)
            launchOperation(characteristic, "Disabling notifications on ${characteristic.uuid}") {
                // Cancelling the subscription stops collecting values, but does not
                // disable notifications on the peripheral. This has to be done manually.
                job?.cancelAndJoin()
                if (characteristic.isNotifying.value) {
                    characteristic.setNotifying(false)
                }
                updateValue(characteristic) { copy(error = null) }
            }
            return
        }

        // subscribe() enables notifications or indications when the Flow starts being collected.
        subscriptions.computeIfAbsent(characteristic) { collectValues(it) }
    }

    /**
     * Observes the state of notifications or indications of the given characteristic.
     *
     * Notifications may be enabled from the UI, or elsewhere, e.g. by the LBS profile.
     * Whenever they get enabled, the values are collected, so that they can be shown in the UI.
     * When they get disabled, collection is stopped.
     */
    /**
     * Returns all characteristics of the services, including those in included services.
     */
    private fun List<AnyRemoteService>.allCharacteristics(): List<RemoteCharacteristic> =
        flatMap { service ->
            service.characteristics + service.includedServices.allCharacteristics()
        }.distinct()

    private fun observeNotificationState(
        characteristic: RemoteCharacteristic,
        event: Int,
        scope: CoroutineScope,
    ) {
        characteristic.isNotifying
            .withIndex()
            .onEach { (index, isNotifying) ->
                // Skip the initial value, unless the notifications were already enabled.
                if (index == 0 && !isNotifying) return@onEach
                Timber.i("($event) Notifications for ${characteristic.uuid} are now ${if (isNotifying) "enabled" else "disabled"}")

                // The state is also reset when the characteristic gets invalidated.
                if (characteristic.owner == null) return@onEach

                // isNotifying changes right after the CCCD was written.
                recordCccdWrite(characteristic, isNotifying)

                if (isNotifying) {
                    // If the notifications were enabled elsewhere, start collecting values.
                    // They are already enabled, so subscribe() won't write the CCCD again.
                    // Mind, that values received before the collection starts are missed.
                    subscriptions.computeIfAbsent(characteristic) { collectValues(it) }
                } else {
                    // If the notifications were disabled elsewhere, stop collecting values.
                    subscriptions.remove(characteristic)?.cancel()
                }
            }
            .launchIn(scope)
    }

    /**
     * Subscribes to value changes of the given characteristic and records received values.
     *
     * @return The job collecting the values.
     */
    private fun collectValues(characteristic: RemoteCharacteristic): Job =
        flow {
            // subscribe() throws immediately if the characteristic is invalid or not subscribable.
            // Wrapping it in a flow allows to handle all errors in catch below.
            emitAll(
                characteristic.subscribe {
                    // This is called when the notifications are enabled.
                    Timber.i("Subscribed to $uuid")
                    updateValue(this) { copy(notificationCount = 0, error = null) }
                }
            )
        }
            .onEach { value ->
                // This is called when a notification or indication is received.
                Timber.i("Value of ${characteristic.uuid} changed: 0x${value.toHexString()}")
                updateValue(characteristic) {
                    copy(received = Timestamped(value), notificationCount = notificationCount + 1)
                }
            }
            .catch { e ->
                // This is called when subscription fails.
                Timber.e(e, "Subscription to ${characteristic.uuid} failed: ${e.message}")
                updateValue(characteristic) { copy(error = Timestamped(e.describe())) }
            }
            .onCompletion {
                // This is called when notifications were disabled, or when the characteristic
                // becomes invalid, that is on disconnection or service change.
                Timber.d("Stopped observing updates from ${characteristic.uuid}")
                // Don't remove a newer subscription, if the user toggled quickly.
                subscriptions.remove(characteristic, currentCoroutineContext().job)
            }
            .launchIn(scope)

    /**
     * Records the value written to the Client Characteristic Configuration descriptor.
     *
     * The CCCD is written internally by the library, so the value is recreated here
     * the same way.
     */
    private fun recordCccdWrite(characteristic: RemoteCharacteristic, enabled: Boolean) {
        val cccd = characteristic.descriptors
            .firstOrNull { it.isClientCharacteristicConfiguration }
            ?: return
        val value = when {
            !enabled -> byteArrayOf(0x00, 0x00)
            // Notifications have priority over indications, if both are supported.
            CharacteristicProperty.NOTIFY in characteristic.properties -> byteArrayOf(0x01, 0x00)
            else -> byteArrayOf(0x02, 0x00)
        }
        updateValue(cccd) { copy(sent = Timestamped(value), error = null) }
    }

    /**
     * Launches a GATT operation and records an error, if it fails.
     */
    private fun launchOperation(attribute: Any, name: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "$name failed: ${e.message}")
                updateValue(attribute) { copy(error = Timestamped(e.describe())) }
            }
        }
    }

    private fun updateValue(attribute: Any, transform: AttributeValue.() -> AttributeValue) {
        _attributeValues.update { values ->
            values + (attribute to (values[attribute] ?: AttributeValue()).transform())
        }
    }

    private fun Throwable.describe(): String = message ?: javaClass.simpleName

    private suspend fun installLbsProfile(
        peripheral: Peripheral,
        required: Boolean,
        block: suspend CoroutineScope.(LedButtonProfile.State) -> Unit,
    ) {
        peripheral.profile(
            serviceUuid = LedButtonProfile.SERVICE_UUID,
            required = required,
            name = "LBS",
        ) { result ->
            when (result) {
                is ProfileServices.Found -> {
                    val state = LedButtonServiceImpl(result.services, this)
                    Timber.i("LBS: LED Button Service found")
                    block(state)
                }
                is ProfileServices.Unsupported ->
                    Timber.i("LBS: LED Button Service not supported by this peripheral")
                is ProfileServices.Failed ->
                    Timber.w("LBS: Service discovery failed (reason: ${result.reason})")
            }
        }
    }

    private fun observePeripheralState(peripheral: Peripheral, scope: CoroutineScope) {
        peripheral.state
            .onEach {
                Timber.i("State of $peripheral: $it")

                // Each time a connection changes, handle the new state
                when (it) {
                    is ConnectionState.Connected -> {
                        connectionScopeMap[peripheral]?.launch {
                            initiateConnection(peripheral)
                        }
                    }

                    is ConnectionState.Disconnected -> {
                        // Just for testing, wait with cancelling the scope to get all the logs.
                        delay(500.milliseconds)
                        // Cancel connection scope, so that previously launched jobs are canceled.
                        connectionScopeMap.remove(peripheral)?.cancel()
                    }

                    else -> { /* Ignore */ }
                }
            }
            .onCompletion {
                Timber.d("State collection for $peripheral completed")
            }
            .launchIn(scope)
    }

    private fun observeBondState(peripheral: Peripheral, scope: CoroutineScope) {
        peripheral.bondState
            .onEach {
                Timber.i("Bond state of $peripheral: $it")
            }
            .onCompletion {
                Timber.d("Bond state collection for $peripheral completed")
            }
            .launchIn(scope)
    }
}
