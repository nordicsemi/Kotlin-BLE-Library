/*
 * Copyright (c) 2025, Nordic Semiconductor
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

package no.nordicsemi.kotlin.ble.core.android

import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.core.Environment
import no.nordicsemi.kotlin.ble.core.Manager
import org.jetbrains.annotations.Range

/**
 * A mock environment that can be used to test the behavior of the Central Manager.
 *
 * @property bluetoothState A flow emitting the current Bluetooth state.
 * @property locationState A flow emitting the state of the location service, *true* when enabled,
 * *false* otherwise.
 * Location service is required to scan for Bluetooth LE beacons when
 * [isLocationRequiredForScanning] is *true*.
 * @property isLocationRequiredForScanning Whether location is required to scan for Bluetooth LE devices.
 * @property isLocationPermissionGranted Whether the `ACCESS_FINE_LOCATION` permission is granted.
 * @property isLocationEnabled Whether location service is enabled on the device.
 * @property androidSdkVersion The Android SDK version.
 * @property deviceName The name of the device, used for Bluetooth LE advertising if local name is set.
 * Reading and setting the name requires `BLUETOOTH_CONNECT` permission.
 * @property isLe2MPhySupported Whether LE 2M PHY is supported on the device.
 * @property isLeCodedPhySupported Whether LE Coded PHY is supported on the device.
 * @property isBluetoothPrivilegedPermissionGranted Whether `BLUETOOTH_PRIVILEGED` permission is granted.
 * This permission cannot be granted to 3-rd party apps.
 * @property isBluetoothScanPermissionGranted Whether the `BLUETOOTH_SCAN` permission is granted.
 * @property isBluetoothConnectPermissionGranted Whether the `BLUETOOTH_CONNECT` permission is granted.
 * @property isBluetoothAdvertisePermissionGranted Whether the `BLUETOOTH_ADVERTISE` permission is granted.
 * @property isMultipleAdvertisementSupported Whether multi advertisement is supported by the chipset.
 * @property isLeExtendedAdvertisingSupported Whether LE Extended Advertising feature is supported.
 * @property isLePeriodicAdvertisingSupported Whether LE Periodic Advertising feature is supported.
 * @property leMaximumAdvertisingDataLength The maximum LE advertising data length in bytes,
 * if LE Extended Advertising feature is supported.
 */
interface AndroidEnvironment : Environment {
    /**
     * Android permissions.
     *
     * The constants are useful to request permissions on devices running older Android version,
     * i.e. using mock implementation.
     */
    object Permission {
        /** Bluetooth Connect permission. */
        const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        /** Bluetooth Scan permission. */
        const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
        /** Bluetooth Advertise permission. */
        const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    }

    /**
     * Android SDK versions.
     *
     * This object contains the Android SDK version constants, in which significant changes
     * to Bluetooth functionality have been made.
     */
    object SdkVersion {
        /**
         * Android 4.3.
         *
         * First version with Bluetooth LE.
         */
        const val JELLY_BEAN_MR2 = 18
        /**
         * Android 4.4.
         *
         * New features:
         * * Added support for HID (Human Interface Device, aka Keyboard, Mouse, etc.) service.
         *
         * Note: An attempt to read or write an attribute belonging to the HID service will result
         *       in an exception.
         */
        const val KITKAT = 19
        /**
         * Android 5.0.
         *
         * New features:
         * * Advertising with Bluetooth LE.
         * * Scan filters and parameters.
         */
        const val LOLLIPOP = 21
        /**
         * Android 6.0.
         *
         * New features:
         * * More Bluetooth LE scanning options.
         * * Data Length Extension (DLE) support.
         * * Runtime permissions.
         * * Location permission required for Bluetooth LE scanning.
         */
        const val MARSHMALLOW = 23
        /**
         * Android 8.0.
         *
         * New features:
         * * PHY LE2M and Coded support.
         * * Periodic advertisement support.
         * * Scanning with PendingIntents.
         */
        const val OREO = 26
        /**
         * Android 12.
         *
         * New features:
         * * New Bluetooth runtime permissions: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`.
         */
        const val S = 31
        /**
         * Android 13.
         *
         * New features:
         * * Bluetooth is a Mainline module.
         * * Auto connect to a device that is not bonded and uses a Resolvable Private Address
         *   fails with an error. Before, the request was silently accepted, and never succeeded.
         */
        const val TIRAMISU = 33
        /**
         * Android 15.
         *
         * New features:
         * * [API](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#getAddressType()) to get the Address Type.
         */
        const val VANILLA_ICE_CREAM = 35
        /**
         * Android 16.
         *
         * New features:
         * * Channel Sounding (ranging) support.
         * * Improved API for handling bond removal
         *   ([Behavior Changes Android 16](https://developer.android.com/about/versions/16/behavior-changes-16#connectivity)).
         *
         * New features in API 36.1:
         * * [API](https://developer.android.com/reference/android/bluetooth/le/ScanSettings.Builder#setScanType(int)) for passive scanning.
         */
        const val BAKLAVA = 36
        /**
         * Android 17.
         *
         * New features:
         * * PHY HDT (High Data Throughput) support.
         */
        const val CINNAMON_BUN = 37
        /**
         * Latest Android version.
         *
         * Use this value to test using all available features.
         */
        const val LATEST = CINNAMON_BUN
    }

    val bluetoothState: StateFlow<Manager.State>
    override val isBluetoothEnabled: Boolean
        get() = bluetoothState.value == Manager.State.POWERED_ON

    /**
     * Sends an intent to enable Bluetooth adapter on the device.
     */
    fun enableBluetooth()

    val locationState: StateFlow<Boolean>
    val isLocationEnabled: Boolean
        get() = locationState.value

    val androidSdkVersion: Int
    val isLocationRequiredForScanning: Boolean
    val isLocationPermissionGranted: Boolean
    val isLe2MPhySupported: Boolean
    val isLeCodedPhySupported: Boolean
    val isBluetoothScanPermissionGranted: Boolean
    val isBluetoothConnectPermissionGranted: Boolean
    val isBluetoothAdvertisePermissionGranted: Boolean
    val isBluetoothPrivilegedPermissionGranted: Boolean
    val isMultipleAdvertisementSupported: Boolean
    val isLeExtendedAdvertisingSupported: Boolean
    val isLePeriodicAdvertisingSupported: Boolean
    val leMaximumAdvertisingDataLength: @Range(from = 31, to = 1650) Int

    override val reportsConnectionParameters: Boolean
        // onConnectionUpdated callback was added in Android 8 Oreo.
        get() = androidSdkVersion >= SdkVersion.OREO

    override val repeatsRequestsAfterBonding: Boolean
        // Android 6 returns Insufficient Authentication to the app, and starts bonding. The request
        // has to be repeated by the app. Android 8 repeats it after bonding. Android 7 is assumed
        // to behave like Android 6 (confirmed on Android 6.0.1 with an HCI snoop log).
        get() = androidSdkVersion >= SdkVersion.OREO

    override val rejectsAutoConnectToUnbondedPrivateAddress: Boolean
        // The Bluetooth stack of Android 12 and older logs "Unable to add RPA to background
        // connection" and returns success, so the connection never happens, and the app is not
        // notified. Android 13 returns an error (status 133 or 135). In AOSP the change was made by
        // the commit 88e5c86895e8 "GATT: Add more logs in GATT to help with debugging" in
        // system/stack/gatt/gatt_api.cc, GATT_Connect(). Confirmed on Android 6, 8, 9 and 12
        // (silent) and Android 14 and 17 (error).
        get() = androidSdkVersion >= SdkVersion.TIRAMISU

    override val reportsConnectableFlag: Boolean
        // isConnectable flag was added to ScanResult in Android 8 Oreo.
        get() = androidSdkVersion >= SdkVersion.OREO

    override val automaticallyRequestsMtu: Boolean
        // New connectGatt API from Android 17 Cinnamon Bun allows to request MTU automatically.
        get() = androidSdkVersion >= SdkVersion.CINNAMON_BUN

    override val allowsBondRemoval: Boolean
        // From Android 17 Cinnamon Bun bond information can only be removed with BLUETOOTH_PRIVILEGED
        // permission, or using CompanionDeviceManager (not supported in this library).
        // Change:
        // https://cs.android.com/android/_/android/platform/packages/modules/Bluetooth/+/f4c525723297ede881a618bb325f4b78a9babb1c
        get() = androidSdkVersion < SdkVersion.CINNAMON_BUN || isBluetoothPrivilegedPermissionGranted

    override val isSystem: Boolean
        // System apps have Bluetooth Privileged permission granted.
        get() = isBluetoothPrivilegedPermissionGranted

    /**
     * The local Bluetooth adapter name, or *null* if the required permission is not granted.
     */
    val deviceNameOrNull: String?
        get() = try { deviceName } catch (_: Exception) { null }

    /**
     * Whether the device requires runtime permissions to use Bluetooth.
     *
     * See: [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
     */
    val requiresBluetoothRuntimePermissions: Boolean
        get() = androidSdkVersion >= SdkVersion.S

    /**
     * Whether the device supports runtime permissions.
     *
     * See: [Request runtime permissions](https://developer.android.com/training/permissions/requesting)
     */
    val supportsRuntimePermissions: Boolean
        get() = androidSdkVersion >= SdkVersion.MARSHMALLOW

    /**
     * Unregisters the broadcast receiver that listens for Bluetooth state changes.
     *
     * This should be called when the environment is no longer needed.
     */
    fun close()
}