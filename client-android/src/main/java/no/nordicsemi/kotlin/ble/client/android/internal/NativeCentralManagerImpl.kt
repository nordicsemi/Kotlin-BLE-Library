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

package no.nordicsemi.kotlin.ble.client.android.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nordicsemi.kotlin.ble.client.MonitoringEvent
import no.nordicsemi.kotlin.ble.client.RangeEvent
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.CentralManager.ScanOptions
import no.nordicsemi.kotlin.ble.client.android.BondState
import no.nordicsemi.kotlin.ble.client.android.ConjunctionFilterScope
import no.nordicsemi.kotlin.ble.client.android.EncryptionState
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.android.exception.ScanningFailedToStartException
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.internal.CallSiteException
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment
import no.nordicsemi.kotlin.log.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import android.bluetooth.BluetoothAdapter.LeScanCallback as NativeLegacyScanCallback
import android.bluetooth.le.ScanCallback as NativeScanCallback
import android.bluetooth.le.ScanResult as NativeScanResult
import android.bluetooth.le.ScanSettings as NativeScanSettings

/** The hidden extra of the bond state broadcast with the reason why the bond state is NONE. */
private const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON"
private const val UNBOND_REASON_UNKNOWN = 0

/** The number of bond and encryption state broadcasts that can wait to be processed. */
private const val BROADCAST_BUFFER_CAPACITY = 32

/**
 * A native implementation of [CentralManager] for Android.
 *
 * @param scope The coroutine scope.
 * @param environment Native Android environment object.
 */
internal class NativeCentralManagerImpl(
    scope: CoroutineScope,
    private val environment: NativeAndroidEnvironment,
): CentralManagerImpl(scope, environment) {
    override var logger: Log.Sink<Layer>? = Log.Sink.Null
    override val state = environment.bluetoothState

    /** A change of the bond state of the device with the given [address]. */
    private class BondEvent(val address: String, val state: BondState)

    // The system may send a few broadcasts within a millisecond, e.g. Bonding and None when
    // bonding fails immediately. None of them can be lost, as the last one is the current state.
    private val bondEvents = MutableSharedFlow<BondEvent>(extraBufferCapacity = BROADCAST_BUFFER_CAPACITY)

    private val bondStateBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent!!.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent!!.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE).toBondState()
            // When the bond state changes to NONE, the intent may have a hidden extra with the reason.
            val unbondReasonCode = intent
                .takeIf { it.hasExtra(EXTRA_UNBOND_REASON) }
                ?.getIntExtra(EXTRA_UNBOND_REASON, UNBOND_REASON_UNKNOWN)
            val systemBondState = intent
                .getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                .toBondState(unbondReasonCode?.toBondStateReason())
            // Many phones report a failed bonding (cancelled by the user, refused by the peripheral,
            // wrong PIN or timeout) as Removed, although no bond existed. Report such failures
            // with a reason that makes sense. The reason reported by the system is logged.
            val bondState = if (
                previousBondState == BondState.Bonding &&
                systemBondState == BondState.None(BondState.Reason.Removed)
            ) BondState.None(BondState.Reason.AuthenticationFailed) else systemBondState
            logger?.info(Layer.SMP) {
                "Bond state of $device changed: $previousBondState -> $systemBondState" +
                        if (bondState != systemBondState) ", reported as $bondState" else ""
            }
            if (!bondEvents.tryEmit(BondEvent(device.address, bondState))) {
                logger?.warn(Layer.SMP) { "Bond state change of $device was lost" }
            }
        }
    }

    /** A change of the state of the encryption of the link with the device with the given [address]. */
    private class EncryptionEvent(val address: String, val state: EncryptionState)

    private val encryptionEvents = MutableSharedFlow<EncryptionEvent>(extraBufferCapacity = BROADCAST_BUFFER_CAPACITY)

    /**
     * Whether the system is Android 16 QPR2 (API 36.1) or newer.
     *
     * Android 16 made the encryption broadcasts public, but the bond loss reason, the constants
     * of its values, and [BluetoothDevice.ENCRYPTION_ALGORITHM_UNKNOWN] were added in 36.1.
     */
    private val isBaklava1OrNewer: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

    /**
     * Receives the encryption state of the link.
     *
     * The broadcasts are public since Android 16 (API 36). Before, they required
     * BLUETOOTH_PRIVILEGED permission.
     */
    private val encryptionBroadcastReceiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        override fun onReceive(context: Context?, intent: Intent?) {
            val intent = intent ?: return
            // The receiver is registered on Android 16+ only, so the typed getter is available.
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
            // Only Bluetooth LE links are supported by the library.
            if (intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE) != BluetoothDevice.TRANSPORT_LE) {
                return
            }
            val state = when (intent.action) {
                BluetoothDevice.ACTION_ENCRYPTION_CHANGE -> intent.toEncryptionState()
                // Without the reason, which was added in 36.1, the broadcast has no use.
                BluetoothDevice.ACTION_KEY_MISSING -> if (isBaklava1OrNewer) intent.toKeyMissingState() else return
                else -> return
            }
            logger?.info(Layer.SMP) { "Encryption state of $device changed: $state" }
            if (!encryptionEvents.tryEmit(EncryptionEvent(device.address, state))) {
                logger?.warn(Layer.SMP) { "Encryption state change of $device was lost" }
            }
        }
    }

    /** Converts the [BluetoothDevice.ACTION_ENCRYPTION_CHANGE] broadcast. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun Intent.toEncryptionState(): EncryptionState {
        val status = getIntExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, 0)
        return when {
            status != 0 -> EncryptionState.NotEncrypted(EncryptionState.Reason.ControllerError(status))
            getBooleanExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, false) -> EncryptionState.Encrypted(
                // ENCRYPTION_ALGORITHM_UNKNOWN is not available in 36.0, so any other value is unknown.
                algorithm = when (getIntExtra(BluetoothDevice.EXTRA_ENCRYPTION_ALGORITHM, BluetoothDevice.ENCRYPTION_ALGORITHM_NONE)) {
                    BluetoothDevice.ENCRYPTION_ALGORITHM_AES -> EncryptionState.Algorithm.AES
                    BluetoothDevice.ENCRYPTION_ALGORITHM_E0 -> EncryptionState.Algorithm.E0
                    else -> EncryptionState.Algorithm.Unknown
                },
                keySize = getIntExtra(BluetoothDevice.EXTRA_KEY_SIZE, 0).takeIf { it > 0 },
            )
            else -> EncryptionState.NotEncrypted()
        }
    }

    /**
     * Converts the [BluetoothDevice.ACTION_KEY_MISSING] broadcast.
     *
     * The bond loss reason is available since Android 16 QPR2 (API 36.1).
     */
    @SuppressLint("InlinedApi")
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun Intent.toKeyMissingState(): EncryptionState {
        val code = getIntExtra(BluetoothDevice.EXTRA_BOND_LOSS_REASON, BluetoothDevice.BOND_LOSS_REASON_UNKNOWN)
        return EncryptionState.NotEncrypted(
            when (code) {
                BluetoothDevice.BOND_LOSS_REASON_LE_ENCRYPT_FAILURE -> EncryptionState.Reason.LeEncryptionFailure
                BluetoothDevice.BOND_LOSS_REASON_LE_INCOMING_PAIRING -> EncryptionState.Reason.LeIncomingPairing
                BluetoothDevice.BOND_LOSS_REASON_BREDR_AUTH_FAILURE -> EncryptionState.Reason.BrEdrAuthenticationFailure
                BluetoothDevice.BOND_LOSS_REASON_BREDR_INCOMING_PAIRING -> EncryptionState.Reason.BrEdrIncomingPairing
                else -> EncryptionState.Reason.Unknown(code)
            }
        )
    }

    init {
        val monitorBondState = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(environment.applicationContext, bondStateBroadcastReceiver, monitorBondState, ContextCompat.RECEIVER_EXPORTED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val monitorEncryption = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE)
                // In 36.0 the broadcast has no extras.
                if (isBaklava1OrNewer) {
                    addAction(BluetoothDevice.ACTION_KEY_MISSING)
                }
            }
            ContextCompat.registerReceiver(environment.applicationContext, encryptionBroadcastReceiver, monitorEncryption, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    /** Forwards the bond state and encryption state of the device with the given address. */
    private fun NativeExecutor.observeBroadcasts(address: String) {
        bondEvents
            .filter { it.address == address }
            .onEach { onBondStateChanged(it.state) }
            .launchIn(scope)
        encryptionEvents
            .filter { it.address == address }
            .onEach { onEncryptionStateChanged(it.state) }
            .launchIn(scope)
    }

    override fun getPeripheralsById(ids: List<String>): List<Peripheral> {
        // Ensure the central manager has not been closed.
        ensureOpen()

        // The identifiers are validated first, regardless of the state.
        ids.forEach { id ->
            require(BluetoothAdapter.checkBluetoothAddress(id)) {
                "$id is not a valid Bluetooth address"
            }
        }

        // Like on iOS, peripherals are returned only when Bluetooth is powered on.
        val adapter = environment.bluetoothManager?.adapter?.takeIf { state.value == Manager.State.POWERED_ON }
        if (adapter == null) {
            logger?.warn(Layer.GAP) { "Peripherals can be retrieved only when Bluetooth is powered on (state: ${state.value})" }
            return emptyList()
        }
        return ids.map { id ->
            peripheral(id) {
                Peripheral(
                    scope = scope,
                    impl = NativeExecutor(environment, adapter.getRemoteDevice(it), null)
                        .apply { observeBroadcasts(id) }
                ).also { p -> p.logger = logger }
            }
        }
    }

    override fun getBondedPeripherals(): List<Peripheral> {
        // Ensure the central manager has not been closed.
        ensureOpen()

        // Verify the BLUETOOTH_CONNECT permission is granted (Android 12+).
        checkConnectPermission()

        val adapter = environment.bluetoothManager?.adapter?.takeIf { state.value == Manager.State.POWERED_ON }
        if (adapter == null) {
            logger?.warn(Layer.GAP) { "Bonded peripherals can be retrieved only when Bluetooth is powered on (state: ${state.value})" }
            return emptyList()
        }
        val ids = adapter.bondedDevices?.map { it.address } ?: return emptyList()
        return getPeripheralsById(ids)
    }

    override fun scan(
        timeout: Duration,
        options: ScanOptions,
        filter: ConjunctionFilterScope.() -> Unit
    ): Flow<ScanResult> {
        // Captured immediately, on the caller's thread, before the flow below is even built.
        val calledHere = CallSiteException("scan() was called here")

        return callbackFlow {
            // Captured when collection of the flow actually starts, which may happen later and
            // on a different coroutine than the one that called scan().
            val collectedHere = CallSiteException("scan() Flow started being collected here")

            fun closeWithCallSite(e: Throwable) {
                e.addSuppressed(calledHere)
                e.addSuppressed(collectedHere)
                close(e)
            }

            // Ensure the central manager has not been closed.
            try {
                ensureOpen()
            } catch (e: Exception) {
                closeWithCallSite(e)
                return@callbackFlow
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                scanLollipop(timeout, options, filter, ::closeWithCallSite)
            } else {
                scanJellyBean(timeout, options, filter, ::closeWithCallSite)
            }
        }
            // If the collector falls behind, keep the newest 64 results rather than the oldest.
            // Not Channel.BUFFERED: with DROP_OLDEST, it means a buffer of 1 instead of 64.
            .buffer(64, BufferOverflow.DROP_OLDEST)
    }

    override fun monitor(
        timeout: Duration,
        filter: ConjunctionFilterScope.() -> Unit
    ): Flow<MonitoringEvent<Peripheral>> {
        TODO("Not yet implemented")
    }

    override fun range(peripheral: Peripheral, timeout: Duration): Flow<RangeEvent<Peripheral>> {
        TODO("Not yet implemented")
    }

    override fun close() {
        // Ignore if already closed.
        if (!isOpen) return
        super.close()

        // Release resources.
        try {
            environment.applicationContext.unregisterReceiver(bondStateBroadcastReceiver)
        } catch (_: Exception) {
            // Ignore
        }
        try {
            environment.applicationContext.unregisterReceiver(encryptionBroadcastReceiver)
        } catch (_: Exception) {
            // Ignore, the receiver is registered on Android 16+ only.
        }
    }

    // Implementations

    /**
     * Whether the phone can scan on the selected PHY.
     *
     * Scanning on LE Coded requires Android 8 and a phone that supports it. The PHY is ignored
     * when scanning for legacy advertisements only.
     */
    private val ScanOptions.isPhySupported: Boolean
        get() = legacyOnly || phy != PrimaryPhy.PHY_LE_CODED || environment.isLeCodedPhySupported

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private suspend fun ProducerScope<ScanResult>.scanLollipop(
        timeout: Duration,
        options: ScanOptions,
        filter: ConjunctionFilterScope.() -> Unit,
        closeWithCallSite: (Throwable) -> Unit
    ) {
        // Ensure the Bluetooth is enabled and Bluetooth LE Scanner is available.
        val scanner = environment.bluetoothManager?.adapter
            ?.takeIf { it.isEnabled }
            ?.bluetoothLeScanner
            ?: run {
                closeWithCallSite(BluetoothUnavailableException())
                return
            }

        // Verify the BLUETOOTH_SCAN permission is granted (Android 12+).
        try {
            checkScanningPermission()
        } catch (e: Exception) {
            closeWithCallSite(e)
            return
        }

        // Ensure the phone can scan on the selected PHY.
        if (!options.isPhySupported) {
            closeWithCallSite(ScanningFailedToStartException(ScanningFailedToStartException.Reason.FeatureUnsupported))
            return
        }

        // Build the filter based on the provided builder.
        val filters = ConjunctionFilter().apply(filter).filters

        // Build the scan settings based on the scan options.
        val settings = NativeScanSettings.Builder()
            .apply {
                setScanMode(options.mode.toScanMode())
                // Before Android 8 only legacy advertisements are reported.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setLegacy(options.legacyOnly)
                    if (!options.legacyOnly) {
                        setPhy(options.phy.toScanPhy())
                    }
                }
                // Passive scanning is available from Android 16 QPR2 (API 36.1).
                if (options.passive &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                    Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                    setScanType(NativeScanSettings.SCAN_TYPE_PASSIVE)
                }
            }
            .build()

        // Define the callback that will emit scan results.
        val callback = object : NativeScanCallback() {
            override fun onScanResult(callbackType: Int, result: NativeScanResult) {
                // A result matching the offloaded filter was found, convert it to ScanResult.
                val scanResult = result.toScanResult(
                    peripheral = { device, name ->
                        peripheral(device.address) {
                            Peripheral(
                                scope = scope,
                                impl = NativeExecutor(environment, device, name)
                                    .apply { observeBroadcasts(device.address) }
                            ).also { p -> p.logger = logger }
                        }
                    }
                ) ?: return

                // Check other filters that cannot be checked by the controller.
                if (filters?.match(scanResult) == false) return

                trySend(scanResult)
            }

            override fun onBatchScanResults(results: List<NativeScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                with (ScanningFailedToStartException(errorCode.errorCodeToReason())) {
                    logger?.error(Layer.GAP, this)
                    closeWithCallSite(this)
                }
            }
        }

        // Finally, start the scan.
        logger?.trace(Layer.GAP) {
            "Starting scanning with $options and ${filters?.let { "filters: $it" } ?: "no filters"}"
        }
        // startScan() blocks until the scan client is registered, so it is not called on the
        // thread collecting the flow, which may be the main thread.
        try {
            withContext(Dispatchers.IO) {
                scanner.startScan(filters?.toNative(), settings, callback)
            }
        } catch (e: CancellationException) {
            // The flow was canceled when the scan was being started. awaitClose() is not reached,
            // so the scan has to be stopped here.
            scanner.stopScan(callback)
            throw e
        }

        // Set a timeout to stop the scan.
        if (timeout > 0.milliseconds) {
            launch(Dispatchers.IO) {
                // If the flow is canceled before the timeout, the delay() method will throw
                // a CancellationException, which will be ignored.
                delay(timeout)
                // If we reached the timeout, close the flow manually.
                logger?.trace(Layer.GAP) { "Scanning timed out after $timeout" }
                close()
            }
        }
        awaitClose {
            scanner.stopScan(callback)
            logger?.trace(Layer.GAP) { "Scanning stopped" }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun ProducerScope<ScanResult>.scanJellyBean(
        timeout: Duration,
        options: ScanOptions,
        filter: ConjunctionFilterScope.() -> Unit,
        closeWithCallSite: (Throwable) -> Unit
    ) {
        // Ensure the Bluetooth is enabled and Bluetooth LE Scanner is available.
        val adapter = environment.bluetoothManager?.adapter
            ?.takeIf { it.isEnabled }
            ?: run {
                closeWithCallSite(BluetoothUnavailableException())
                return
            }

        // Scan options can't be set before Android 5, but a scan on LE Coded must still fail.
        if (!options.isPhySupported) {
            closeWithCallSite(ScanningFailedToStartException(ScanningFailedToStartException.Reason.FeatureUnsupported))
            return
        }

        // Build the filter based on the provided builder.
        val filters = ConjunctionFilter().apply(filter).filters

        // Define the callback that will emit scan results.
        val callback = object : NativeLegacyScanCallback {
            override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
                if (device == null || scanRecord == null) return
                val scanResult = scanRecord.toScanResult(
                    rssi = rssi,
                    peripheral = { name ->
                        peripheral(device.address) {
                            Peripheral(
                                scope = scope,
                                impl = NativeExecutor(environment, device, name)
                                    .apply { observeBroadcasts(device.address) }
                            ).also { p -> p.logger = logger }
                        }
                    }
                )

                // Check other filters that cannot be checked by the controller.
                if (filters?.match(scanResult) == false) return

                trySend(scanResult)
            }
        }

        // Finally, start the scan.
        logger?.trace(Layer.GAP) {
            "Starting scanning with ${filters?.let { "filters: $it" } ?: "no filters"}"
        }
        // startLeScan() blocks until the scan client is registered (up to 10 seconds), so it is
        // not called on the thread collecting the flow, which may be the main thread.
        val started = try {
            withContext(Dispatchers.IO) { adapter.startLeScan(callback) }
        } catch (e: CancellationException) {
            // The flow was canceled when the scan was being started. awaitClose() is not reached,
            // so the scan has to be stopped here.
            adapter.stopLeScan(callback)
            throw e
        }
        if (!started) {
            with (ScanningFailedToStartException(ScanningFailedToStartException.Reason.InternalError)) {
                logger?.error(Layer.GAP, this)
                closeWithCallSite(this)
            }
            return
        }

        // Set a timeout to stop the scan.
        if (timeout > 0.milliseconds) {
            launch(Dispatchers.IO) {
                // If the flow is canceled before the timeout, the delay() method will throw
                // a CancellationException, which will be ignored.
                delay(timeout)
                // If we reached the timeout, close the flow manually.
                logger?.trace(Layer.GAP) { "Scanning timed out after $timeout" }
                close()
            }
        }
        awaitClose {
            adapter.stopLeScan(callback)
            logger?.trace(Layer.GAP) { "Scanning stopped" }
        }
    }
}