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

package no.nordicsemi.kotlin.ble.core.android.preview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment

/**
 * A stub implementation of [AndroidEnvironment] for Android.
 *
 * This can be used for showing `@Preview` in the Compose UI.
 */
class PreviewEnvironment(
    override val androidSdkVersion: Int = AndroidEnvironment.SdkVersion.LATEST,
    override val deviceName: String = "Stub",
    bluetoothState: Manager.State = Manager.State.POWERED_ON,
    override val locationState: StateFlow<Boolean> = MutableStateFlow(true),
    override val isBluetoothSupported: Boolean = true,
    override val isLocationRequiredForScanning: Boolean = false,
    override val isLocationPermissionGranted: Boolean = false,
    override val isLe2MPhySupported: Boolean = true,
    override val isLeCodedPhySupported: Boolean = true,
    override val isScanningOnLeCodedPhySupported: Boolean = true,
    override val isBluetoothScanPermissionGranted: Boolean = true,
    override val isBluetoothConnectPermissionGranted: Boolean = true,
    override val isBluetoothAdvertisePermissionGranted: Boolean = true,
    override val isBluetoothPrivilegedPermissionGranted: Boolean = false,
    override val isMultipleAdvertisementSupported: Boolean = true,
    override val isLeExtendedAdvertisingSupported: Boolean = true,
    override val isLePeriodicAdvertisingSupported: Boolean = true,
    override val leMaximumAdvertisingDataLength: Int = 1650,
): AndroidEnvironment {
    override val bluetoothState: StateFlow<Manager.State>
        field = MutableStateFlow(bluetoothState)

    override fun enableBluetooth() {
        this.bluetoothState.update { Manager.State.POWERED_ON }
    }
    override fun close() {}
}