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

package no.nordicsemi.kotlin.ble.client.android.mock.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.android.BondState
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority
import no.nordicsemi.kotlin.ble.client.android.EncryptionState
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.mock.MockBondState
import no.nordicsemi.kotlin.ble.client.mock.MockEncryptionState
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.internal.MockExecutor
import no.nordicsemi.kotlin.ble.client.mock.internal.MockScanResult
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.PeripheralType
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.core.mock.BondingFailure
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import org.jetbrains.annotations.Range
import kotlin.time.Duration.Companion.seconds

/**
 * A mock implementation of [Peripheral] for Android.
 *
 * @param peripheralSpec The peripheral specification.
 * @param name Name of the peripheral read from the advertisement data or peripheral spec
 * when it was connected / bonded before.
 * @param environment The mock environment.
 * @param advertisements A flow of advertisements emitted by the mock advertiser.
 * @param scope The scope of the mock central manager, in which the connection runs its jobs.
 */
open class MockExecutor(
    peripheralSpec: PeripheralSpec<String>,
    name: String?,
    override val environment: MockAndroidEnvironment,
    advertisements: Flow<MockScanResult<String>>,
    scope: CoroutineScope,
): MockExecutor(peripheralSpec, name, environment, advertisements, scope), Peripheral.Executor {
    override val type: PeripheralType = peripheralSpec.type

    // The bond state follows the spec right away (Unconfined), so that a change is visible as soon
    // as it is made, like for the native executor.
    override val bondState: StateFlow<BondState> = peripheralSpec.bondState
        .map { it.toBondState() }
        .stateIn(
            scope = CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined),
            started = SharingStarted.Eagerly,
            initialValue = peripheralSpec.bondState.value.toBondState(),
        )

    private fun MockBondState.toBondState(): BondState = when (this) {
        is MockBondState.None -> BondState.None(reason?.toBondStateReason())
        MockBondState.Bonding -> BondState.Bonding
        MockBondState.Bonded -> BondState.Bonded
    }

    private fun MockBondState.Reason.toBondStateReason(): BondState.Reason = when (this) {
        is MockBondState.Reason.Failed -> failure.toBondStateReason()
        MockBondState.Reason.Removed -> BondState.Reason.Removed
    }

    /**
     * Converts the failure to the reason reported by Android.
     *
     * Android versions report different reasons for the same event. The values below were
     * observed on: Samsung A5 (Android 8), Pixel 5 (Android 14) and Pixel 7 (Android 17).
     * Android 16 is assumed to behave like Android 17.
     * Where the version was not tested, the reason of the closest tested version is used.
     */
    private fun BondingFailure.toBondStateReason(): BondState.Reason {
        val sdk = environment.androidSdkVersion
        // Pixel 5 reports all failures as Removed.
        val isAndroid14 = sdk > AndroidEnvironment.SdkVersion.TIRAMISU &&
                sdk < AndroidEnvironment.SdkVersion.BAKLAVA
        val isAndroid16 = sdk >= AndroidEnvironment.SdkVersion.BAKLAVA
        return when (this) {
            BondingFailure.REJECTED ->
                if (isAndroid14) BondState.Reason.Removed else BondState.Reason.AuthenticationRejected
            // Cancelling the dialog is Removed on Android 8 and 14, and AuthenticationFailed on 16.
            BondingFailure.CANCELLED ->
                if (isAndroid16) BondState.Reason.AuthenticationFailed else BondState.Reason.Removed
            BondingFailure.WRONG_PIN ->
                if (isAndroid14) BondState.Reason.Removed else BondState.Reason.AuthenticationFailed
            // Android 17 reports Removed when the pairing timed out (assumed for Android 16).
            BondingFailure.TIMED_OUT ->
                if (isAndroid16) BondState.Reason.Removed else BondState.Reason.AuthenticationTimeout
        }
    }

    override val encryptionState: StateFlow<EncryptionState> = peripheralSpec.encryptionState
        .map { it.toEncryptionState() }
        .stateIn(scope, SharingStarted.Eagerly, peripheralSpec.encryptionState.value.toEncryptionState())

    /** The encryption state is reported by Android 16 and newer. */
    private fun MockEncryptionState?.toEncryptionState(): EncryptionState {
        if (environment.androidSdkVersion < AndroidEnvironment.SdkVersion.BAKLAVA) {
            return EncryptionState.Unknown
        }
        return when (this) {
            // The peripheral is not connected.
            null -> EncryptionState.Unknown
            MockEncryptionState.Encrypted -> EncryptionState.Encrypted(EncryptionState.Algorithm.AES, keySize = 16)
            is MockEncryptionState.NotEncrypted -> EncryptionState.NotEncrypted(reason?.toEncryptionReason())
        }
    }

    private fun MockEncryptionState.Reason.toEncryptionReason(): EncryptionState.Reason = when (this) {
        MockEncryptionState.Reason.KEY_MISSING -> EncryptionState.Reason.LeEncryptionFailure
    }

    // Implementation

    override suspend fun connect(
        autoConnect: Boolean,
        autoMtu: Boolean,
        opportunistic: Boolean,
    ) {
        if (autoConnect) {
            // There is no timeout for auto connect attempts.
            super.connect(true, autoMtu, opportunistic)
        } else {
            // Android has a timeout of 30 seconds for connection attempts.
            // User may set a shorter timeout in ConnectionOptions.Direct.
            withTimeout(30.seconds) {
                super.connect(false, autoMtu, opportunistic)
            }
        }
    }

    override suspend fun createBond(): Boolean {
        // The result is reported by the bond state. A rejected request ends with BondState.None.
        peripheralSpec.simulateBonding()
        return true
    }

    override suspend fun removeBond(): Boolean {
        // Removing the bond information terminates the connection.
        peripheralSpec.simulateBondRemoval()
        return true
    }

    override suspend fun refreshCache(): Boolean {
        return gatt?.refreshCache() ?: false
    }

    override suspend fun requestConnectionPriority(priority: ConnectionPriority): Boolean {
        gatt?.let { gatt ->
            val result = gatt.requestConnectionParameters(priority.toConnectionParameters(environment))
            if (!result) {
                return false
            }

            // Prior to Android Oreo there is no callback for connection parameters change.
            if (!environment.reportsConnectionParameters) {
                gatt.onConnectionUpdated()
            }
            return true
        }
        return false
    }

    override suspend fun requestMtu(mtu: @Range(from = 23, to = 517) Int): Boolean {
        return gatt?.requestMtu(mtu) ?: false
    }

    override suspend fun requestPhy(txPhy: Phy, rxPhy: Phy, phyOptions: PhyOption): Boolean {
        gatt?.let { gatt ->
            if (environment.androidSdkVersion >= AndroidEnvironment.SdkVersion.OREO) {
                gatt.setPreferredPhy(txPhy, rxPhy, phyOptions)
            } else {
                gatt.setPreferredPhy(
                    Phy.PHY_LE_1M,
                    Phy.PHY_LE_1M,
                    PhyOption.NO_PREFERRED
                )
            }
            return true
        }
        return false
    }

    override suspend fun readPhy(): Boolean {
        gatt?.let {  gatt ->
            // It's not possible to set PHY to anything other than 1M on Android versions below Oreo,
            // so we can just read it.
            gatt.readPhy()
            return true
        }
        return false
    }

    override fun beginReliableWrite(): Boolean {
        if (isClosed || !peripheralSpec.isConnected) {
            return false
        }
        isReliableWriteEnabled = true
        return true
    }

    override suspend fun executeReliableWrite(): Boolean {
        if (isClosed || !peripheralSpec.isConnected) {
            return false
        }
        if (isReliableWriteEnabled) {
            isReliableWriteEnabled = false
            return gatt?.endReliableWrites(true) ?: false
        }
        return true
    }

    override suspend fun abortReliableWrite(): Boolean {
        if (isClosed || !peripheralSpec.isConnected) {
            return false
        }
        if (isReliableWriteEnabled) {
            isReliableWriteEnabled = false
            return gatt?.endReliableWrites(false) ?: false
        }
        return true
    }

    private fun ConnectionPriority.toConnectionParameters(environment: MockAndroidEnvironment): ConnectionParameters.Specified = when (this) {
        ConnectionPriority.BALANCED -> ConnectionParameters.Specified(
            connectionInterval = 24,
            latency = 0,
            supervisionTimeout = if (environment.androidSdkVersion >= AndroidEnvironment.SdkVersion.OREO) 500 else 2000
        )
        ConnectionPriority.HIGH -> if (environment.androidSdkVersion >= AndroidEnvironment.SdkVersion.MARSHMALLOW) {
            ConnectionParameters.Specified(
                connectionInterval = 9,
                latency = 0,
                supervisionTimeout = if (environment.androidSdkVersion >= AndroidEnvironment.SdkVersion.OREO) 500 else 2000
            )
        } else {
            ConnectionParameters.Specified(
                connectionInterval = 6,
                latency = 0,
                supervisionTimeout = 2000
            )
        }
        ConnectionPriority.LOW_POWER -> ConnectionParameters.Specified(
            connectionInterval = 80,
            latency = 2,
            supervisionTimeout = if (environment.androidSdkVersion >= AndroidEnvironment.SdkVersion.OREO) 500 else 2000
        )
        ConnectionPriority.DIGITAL_CAR_KEY -> ConnectionParameters.Specified(
            connectionInterval = 24,
            latency = 0,
            supervisionTimeout = 500
        )
    }
}