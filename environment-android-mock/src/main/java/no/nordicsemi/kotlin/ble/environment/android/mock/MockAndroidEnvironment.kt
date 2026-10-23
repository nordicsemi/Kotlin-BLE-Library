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

@file:Suppress("unused")

package no.nordicsemi.kotlin.ble.environment.android.mock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import no.nordicsemi.kotlin.ble.core.ATT_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.ATT_MTU_MAX
import no.nordicsemi.kotlin.ble.core.LL_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.LL_MTU_MAX
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.TxPowerLevel
import no.nordicsemi.kotlin.ble.core.android.AdvertisingDataDefinition
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.mock.BondingFailure
import no.nordicsemi.kotlin.ble.core.mock.MockEnvironment
import org.jetbrains.annotations.Range
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A type alias for the lastest Android API.
 *
 * Currently, this is set to [MockAndroidEnvironment.Api37] and will change in the future to match
 * the latest Android API, when available.
 *
 * Note, that ApiXX is also valid for APIs greater than XX. A new type is only added when there's
 * a significant change in the Bluetooth-related API.
 */
typealias LatestApi = MockAndroidEnvironment.Api37

/**
 * A callback used for a mock advertiser.
 *
 * This callback is called when the app requests the mock Bluetooth LE advertiser to advertise.
 *
 * It should return an emulated value of TX power level used for mock advertising, in dBm, which
 * will be delivered the callback block given in `BluetoothLeAdvertiser.advertise(...)` method.
 *
 * Valid values are from -127 to +1 dBm.
 */
typealias MockAdvertiser = (requestedTxPower: Int, advertisingData: AdvertisingDataDefinition, scanResponse: AdvertisingDataDefinition?) -> Result<Int>

private const val DEFAULT_NAME = "Mock"
private val DEFAULT_MOCK_ADVERTISER: MockAdvertiser = { requestedTxPower, _, _ ->
    Result.success(requestedTxPower.coerceIn(TxPowerLevel.ULTRA_LOW..TxPowerLevel.HIGH))
}

/**
 * A callback used for a mock scanner.
 *
 * This callback is called when the mock central manager starts a scan for Bluetooth LE devices,
 * which happens each time the flow returned by its `scan` method is collected.
 *
 * Return [Result.success] with `true` to indicate that the scan was successful and that results
 * will be reported, or `false` to start the scan but not report any results.
 * This is useful, as some devices don't report scan error in any way, but just don't report
 * any results.
 *
 * [Result.failure] with `ScanningFailedToStartException` exception will be thrown to the collector
 * of that flow, as if `ScanCallback.onScanFailed(errorCode)` was called.
 */
typealias MockScanner = () -> Result<Boolean>

private val DEFAULT_MOCK_SCANNER: MockScanner = { Result.success(true) }

/**
 * A mock environment that can be used to test the behavior of the Central Manager.
 *
 * @property isLocationPermissionGranted Whether the fine location permission is granted.
 * The permission can be set to true to emulate it being granted in runtime.
 * @property isBluetoothScanPermissionGranted Whether the `BLUETOOTH_SCAN` permission is granted.
 * The permission can be set to true to emulate it being granted in runtime.
 * @property isBluetoothConnectPermissionGranted Whether the `BLUETOOTH_CONNECT` permission is granted.
 * The permission can be set to true to emulate it being granted in runtime.
 * @property isBluetoothAdvertisePermissionGranted Whether the `BLUETOOTH_ADVERTISE` permission is granted.
 * The permission can be set to true to emulate it being granted in runtime.
 * @property issueOnlyOneActiveScan Some early Android devices were sending only one *Scan Request*
 * message for a single device per scan. Non-connectable devices were reported continuously, but
 * connectable devices were reported only once. The client had to stop and start scanning again
 * to receive further advertisements. This flag simulates this issue. It was encountered e.g. on Nexus 4.
 * @property issueIncorrectLlTxMtu Some Android devices claim they can only transmit 27-byte long
 * PDUs on Link Layer in the *LLCP Data Length Update* procedure, while later trying to send 251 bytes.
 * This causes the peripheral to terminate the connection. This flag simulates this issue.
 * It was encountered e.g. on Samsung A8 and Samsung A8 Tab.
 * @property advertiser A callback that will be called when the app requests to advertise.
 * The callback should return the TX power level used for mock advertising.
 * @property scanner A callback that will be called when the mock central manager requests to scan
 * for devices. It returns whether the scan was successful, secretly failed, or returned an error.
 */
sealed class MockAndroidEnvironment(
    override val androidSdkVersion: Int,
    deviceName: String,
    override val isBluetoothSupported: Boolean,
    isBluetoothEnabled: Boolean,
    isLocationEnabled: Boolean = false,
    override val isLocationRequiredForScanning: Boolean = false,
    isLocationPermissionGranted: Boolean = false,
    isBluetoothScanPermissionGranted: Boolean = false,
    isBluetoothConnectPermissionGranted: Boolean = false,
    isBluetoothAdvertisePermissionGranted: Boolean = false,
    override val isBluetoothPrivilegedPermissionGranted: Boolean = false,
    override val isLe2MPhySupported: Boolean = false,
    override val isLeCodedPhySupported: Boolean = false,
    override val isMultipleAdvertisementSupported: Boolean = false, // TODO this is not used
    override val isLeExtendedAdvertisingSupported: Boolean = false,
    override val isLePeriodicAdvertisingSupported: Boolean = false,
    override val leMaximumAdvertisingDataLength: @Range(from = 31, to = 1650) Int = 31,
    override val maxLlMtu: @Range(from = 27, to = 251) Int = LL_MTU_DEFAULT,
    isScanningOnLeCodedPhySupported: Boolean = isLeCodedPhySupported,
    val issueOnlyOneActiveScan: Boolean = false, // Nexus 4 issue
    val issueIncorrectLlTxMtu: Boolean = false, // Samsung A8 Tab issue
    // TODO add the issue when Samsung S8 fails PHY update, tested with Memfault.
    // This issue can be workaround by delaying service discovery (?) and waiting
    // until the PHY request completes. It works in nRF Connect when SD is triggered manually.
    val advertiser: MockAdvertiser = { _, _, _ -> error("Not supported") },
    val scanner: MockScanner,
): AndroidEnvironment, MockEnvironment {

    // Allow granting permissions in runtime.
    override var isLocationPermissionGranted: Boolean = isLocationPermissionGranted
        set(value) { field = field || value && supportsRuntimePermissions }

    override var isBluetoothScanPermissionGranted: Boolean = isBluetoothScanPermissionGranted
        set(value) { field = field || value && requiresBluetoothRuntimePermissions }

    override var isBluetoothConnectPermissionGranted: Boolean = isBluetoothConnectPermissionGranted
        set(value) { field = field || value && requiresBluetoothRuntimePermissions }

    override var isBluetoothAdvertisePermissionGranted: Boolean = isBluetoothAdvertisePermissionGranted
        set(value) { field = field || value && requiresBluetoothRuntimePermissions }

    override val isScanningOnLeCodedPhySupported: Boolean = isLeCodedPhySupported && isScanningOnLeCodedPhySupported

    override fun disconnectionDelayAfterFailedBonding(failure: BondingFailure): Duration? = when {
        // Samsung A5 with Android 8 keeps the link after a failed pairing.
        androidSdkVersion == AndroidEnvironment.SdkVersion.OREO -> null
        // Pixel 7 with Android 17 terminates the link at once when the pairing timed out
        // (assumed for Android 16).
        failure == BondingFailure.TIMED_OUT && androidSdkVersion >= AndroidEnvironment.SdkVersion.BAKLAVA -> Duration.ZERO
        // Pixel 7 (Android 17), Pixel 5 (Android 14) and Folder (Android 6) terminate the link
        // about 3 seconds after the pairing failed.
        else -> 3.seconds
    }

    override val maxAttMtu: @Range(from = 23, to = 517) Int = when (androidSdkVersion) {
        in AndroidEnvironment.SdkVersion.JELLY_BEAN_MR2..AndroidEnvironment.SdkVersion.KITKAT -> ATT_MTU_DEFAULT
        else -> ATT_MTU_MAX
    }

    /**
     * Simulates turning on Bluetooth adapter on the mock device.
     *
     * @throws no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException If [isBluetoothSupported] is false.
     */
    fun simulatePowerOn() = simulateStateChange(Manager.State.POWERED_ON)

    /**
     * Simulates turning off Bluetooth adapter on the mock device.
     *
     * @throws no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException If [isBluetoothSupported] is false.
     */
    fun simulatePowerOff() = simulateStateChange(Manager.State.POWERED_OFF)

    /**
     * Simulates changing Bluetooth adapter state on the mock device.
     *
     * @param newState The new state of the Bluetooth adapter.
     * @throws no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException If [isBluetoothSupported] is false.
     */
    private fun simulateStateChange(newState: Manager.State) {
        require(isBluetoothSupported) {
            throw BluetoothUnavailableException()
        }

        // Ignore if the state has not changed.
        if (newState != bluetoothState.value) {
            _bluetoothState.update { newState }
        }
    }

    /**
     * Simulates turning on/off location service on the mock device.
     *
     * Enabled location is required to scan for Bluetooth LE devices when [isLocationRequiredForScanning]
     * is *true*. This is from Android 6.0 (Marshmallow) to Android 12 (S), where a new flag
     * `neverForLocation` was added to `BLUETOOTH_SCAN` permission allowing to scan for non-beacon
     * devices.
     *
     * @param newState The new state of the Location service.
     */
    fun simulateLocationState(newState: Boolean) {
        _locationState.update { newState }
    }

    private val _bluetoothState = MutableStateFlow(
        when {
            !isBluetoothSupported -> Manager.State.UNSUPPORTED
            !isBluetoothEnabled -> Manager.State.POWERED_OFF
            else -> Manager.State.POWERED_ON
        }
    )
    override val bluetoothState = _bluetoothState.asStateFlow()

    override fun enableBluetooth() {
        simulatePowerOn()
    }

    private val _locationState = MutableStateFlow(isLocationEnabled)
    override val locationState = _locationState.asStateFlow()

    private var _deviceName: String = deviceName
    override var deviceName: String
        get() {
            require(isBluetoothSupported) {
                throw BluetoothUnavailableException()
            }
            require(!requiresBluetoothRuntimePermissions || isBluetoothConnectPermissionGranted) {
                throw SecurityException("BLUETOOTH_CONNECT permission not granted")
            }
            return _deviceName
        }
        set(value) {
            require(isBluetoothSupported) {
                throw BluetoothUnavailableException()
            }
            require(!requiresBluetoothRuntimePermissions || isBluetoothConnectPermissionGranted) {
                throw SecurityException("BLUETOOTH_CONNECT permission not granted")
            }
            require(value.isNotEmpty())
            _deviceName = value
        }

    override fun close() {
        // Empty
    }

    /**
     * A mock environment for Android 4.3 (Jelly Bean MR2).
     *
     * This is the first Android version with Bluetooth LE support.
     *
     * @param deviceName The device name, by default set to "Mock".
     * @param isBluetoothSupported Whether Bluetooth is supported on the device.
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param isSystemApp Whether the app is running with system permissions. This is equivalent of
     * BLUETOOTH_PRIVILEGED permission added in API 19.
     * @param issueOnlyOneActiveScan Some early Android devices were sending only one Scan Request
     * message for a single device per scan. Non-connectable devices were reported continuously, but
     * connectable devices were reported only once. The client had to stop and start scanning again
     * to receive further advertisements. This flag simulates this issue. It was encountered e.g. on Nexus 4.
     */
    class Api18(
        deviceName: String = DEFAULT_NAME,
        isBluetoothSupported: Boolean = true,
        isBluetoothEnabled: Boolean = true,
        isSystemApp: Boolean = false,
        issueOnlyOneActiveScan: Boolean = false,
    ): MockAndroidEnvironment(
        androidSdkVersion = AndroidEnvironment.SdkVersion.JELLY_BEAN_MR2,
        deviceName = deviceName,
        isBluetoothSupported = isBluetoothSupported,
        isBluetoothEnabled = isBluetoothEnabled,
        isBluetoothPrivilegedPermissionGranted = isSystemApp,
        scanner = DEFAULT_MOCK_SCANNER,
        issueOnlyOneActiveScan = issueOnlyOneActiveScan,
    )

    /**
     * A mock environment for Android 4.4 (KitKat).
     *
     * @param deviceName The device name, by default set to "Mock".
     * @param isBluetoothSupported Whether Bluetooth is supported on the device.
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param isBluetoothPrivilegedPermissionGranted Whether the Bluetooth privileged permission is
     * granted. This permission can only be granted in own AOSP builds, not for 3rd party apps.
     * @param issueOnlyOneActiveScan Some early Android devices were sending only one Scan Request
     * message for a single device per scan. Non-connectable devices were reported continuously, but
     * connectable devices were reported only once. The client had to stop and start scanning again
     * to receive further advertisements. This flag simulates this issue. It was encountered e.g. on Nexus 4.
     */
    class Api19(
        deviceName: String = DEFAULT_NAME,
        isBluetoothSupported: Boolean = true,
        isBluetoothEnabled: Boolean = true,
        isBluetoothPrivilegedPermissionGranted: Boolean = false,
        issueOnlyOneActiveScan: Boolean = false,
    ): MockAndroidEnvironment(
        androidSdkVersion = AndroidEnvironment.SdkVersion.KITKAT,
        deviceName = deviceName,
        isBluetoothSupported = isBluetoothSupported,
        isBluetoothEnabled = isBluetoothEnabled,
        isBluetoothPrivilegedPermissionGranted = isBluetoothPrivilegedPermissionGranted,
        scanner = DEFAULT_MOCK_SCANNER,
        issueOnlyOneActiveScan = issueOnlyOneActiveScan,
    )

    /**
     * A mock environment for Android 5.0 (Lollipop).
     *
     * @param deviceName The device name, by default set to "Mock".
     * @param isBluetoothSupported Whether Bluetooth is supported on the device.
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param isBluetoothPrivilegedPermissionGranted Whether the Bluetooth privileged permission is
     * granted. This permission can only be granted in own AOSP builds, not for 3rd party apps.
     * @param isMultipleAdvertisementSupported Whether multi advertisement is supported by the chipset.
     * @param advertiser A callback that will be called when the app requests to advertise.
     * The callback should return TX power level used for mock advertising.
     * @param scanner A callback that will be called when the mock central manager requests to scan
     * for devices. It returns whether the scan was successful, secretly failed, or returned an error.
     * @param issueOnlyOneActiveScan Some early Android devices were sending only one Scan Request
     * message for a single device per scan. Non-connectable devices were reported continuously, but
     * connectable devices were reported only once. The client had to stop and start scanning again
     * to receive further advertisements. This flag simulates this issue. It was encountered e.g. on Nexus 4.
     */
    class Api21(
        deviceName: String = DEFAULT_NAME,
        isBluetoothSupported: Boolean = true,
        isBluetoothEnabled: Boolean = true,
        isBluetoothPrivilegedPermissionGranted: Boolean = false,
        isMultipleAdvertisementSupported: Boolean = true,
        advertiser: MockAdvertiser = DEFAULT_MOCK_ADVERTISER,
        scanner: MockScanner = DEFAULT_MOCK_SCANNER,
        issueOnlyOneActiveScan: Boolean = false,
    ): MockAndroidEnvironment(
        androidSdkVersion = AndroidEnvironment.SdkVersion.LOLLIPOP,
        deviceName = deviceName,
        isBluetoothSupported = isBluetoothSupported,
        isBluetoothEnabled = isBluetoothEnabled,
        isBluetoothPrivilegedPermissionGranted = isBluetoothPrivilegedPermissionGranted,
        isMultipleAdvertisementSupported = isMultipleAdvertisementSupported,
        advertiser = advertiser,
        scanner = scanner,
        issueOnlyOneActiveScan = issueOnlyOneActiveScan,
    )

    /**
     * A mock environment for Android 6.0 (Marshmallow).
     *
     * Since Android 6.0, location is required to scan for Bluetooth devices.
     *
     * @param deviceName The device name, by default set to "Mock".
     * @param isBluetoothSupported Whether Bluetooth is supported on the device.
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param isBluetoothPrivilegedPermissionGranted Whether the Bluetooth privileged permission is
     * granted. This permission can only be granted in own AOSP builds, not for 3rd party apps.
     * @param maxLlMtu The maximum Link Layer MTU size in range 27 - 251.
     * @param isMultipleAdvertisementSupported Whether multi advertisement is supported by the chipset.
     * @param isLocationPermissionGranted Whether the fine location permission is initially granted.
     * @param isLocationEnabled Whether location service is enabled on the device.
     * @param advertiser A callback that will be called when the app requests to advertise.
     * The callback should return TX power level used for mock advertising.
     * @param scanner A callback that will be called when the mock central manager requests to scan
     * for devices. It returns whether the scan was successful, secretly failed, or returned an error.
     * @param issueOnlyOneActiveScan Some early Android devices were sending only one Scan Request
     * message for a single device per scan. Non-connectable devices were reported continuously, but
     * connectable devices were reported only once. The client had to stop and start scanning again
     * to receive further advertisements. This flag simulates this issue. It was encountered e.g. on Nexus 4.
     * @param issueIncorrectLlTxMtu Some Android devices claim they can only transmit 27-byte long
     * PDUss on Link Layer in the LLCP Data Length Update procedure, while later trying to send 251 bytes.
     * This causes the peripheral to terminate the connection. This flag simulates this issue.
     * It was encountered e.g. on Samsung A8 and Samsung A8 Tab.
     */
    class Api23(
        deviceName: String = DEFAULT_NAME,
        isBluetoothSupported: Boolean = true,
        isBluetoothEnabled: Boolean = true,
        isBluetoothPrivilegedPermissionGranted: Boolean = false,
        isMultipleAdvertisementSupported: Boolean = true,
        isLocationPermissionGranted: Boolean = true,
        isLocationEnabled: Boolean = true,
        maxLlMtu: @Range(from = 27, to = 251) Int = LL_MTU_MAX,
        advertiser: MockAdvertiser = DEFAULT_MOCK_ADVERTISER,
        scanner: MockScanner = DEFAULT_MOCK_SCANNER,
        issueOnlyOneActiveScan: Boolean = false,
        issueIncorrectLlTxMtu: Boolean = false,
    ): MockAndroidEnvironment(
        androidSdkVersion = AndroidEnvironment.SdkVersion.MARSHMALLOW,
        deviceName = deviceName,
        isBluetoothSupported = isBluetoothSupported,
        isBluetoothEnabled = isBluetoothEnabled,
        isBluetoothPrivilegedPermissionGranted = isBluetoothPrivilegedPermissionGranted,
        isMultipleAdvertisementSupported = isMultipleAdvertisementSupported,
        isLocationRequiredForScanning = true,
        isLocationPermissionGranted = isLocationPermissionGranted,
        isLocationEnabled = isLocationEnabled,
        maxLlMtu = maxLlMtu,
        advertiser = advertiser,
        scanner = scanner,
        issueOnlyOneActiveScan = issueOnlyOneActiveScan,
        issueIncorrectLlTxMtu = issueIncorrectLlTxMtu,
    )

    /**
     * A mock environment for Android 8.0 (Oreo).
     *
     * Since Android 8.0, LE 2M and LE Coded PHY are supported by the API, but not necessarily by
     * the device. Also, some devices may not support scanning for Bluetooth LE devices advertising
     * on LE Coded PHY as Primary PHY despite supporting LE Coded PHY.
     *
     * @param deviceName The device name, by default set to "Mock".
     * @param isBluetoothSupported Whether Bluetooth is supported on the device.
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param isBluetoothPrivilegedPermissionGranted Whether the Bluetooth privileged permission is
     * granted. This permission can only be granted in own AOSP builds, not for 3rd party apps.
     * @param isMultipleAdvertisementSupported Whether multi advertisement is supported by the chipset.
     * @param isLeExtendedAdvertisingSupported Whether LE Extended Advertising feature is supported.
     * @param isLePeriodicAdvertisingSupported Whether LE Periodic Advertising feature is supported.
     * @param leMaximumAdvertisingDataLength The maximum LE advertising data length in bytes,
     * if LE Extended Advertising feature is supported.
     * @param maxLlMtu The maximum Link Layer MTU size in range 27 - 251.
     * @param isLe2MPhySupported Whether LE 2M PHY is supported on the device.
     * @param isLeCodedPhySupported Whether LE Coded PHY is supported on the device.
     * @param isScanningOnLeCodedPhySupported Whether the device can scan for Bluetooth LE devices
     * advertising on LE Coded PHY as Primary PHY.
     * @param isLocationPermissionGranted Whether the fine location permission is initially granted.
     * @param isLocationEnabled Whether location service is enabled on the device.
     * @param advertiser A callback that will be called when the app requests to advertise.
     * The callback should return TX power level used for mock advertising.
     * @param scanner A callback that will be called when the mock central manager requests to scan
     * for devices. It returns whether the scan was successful, secretly failed, or returned an error.
     * @param issueOnlyOneActiveScan Some early Android devices were sending only one Scan Request
     * message for a single device per scan. Non-connectable devices were reported continuously, but
     * connectable devices were reported only once. The client had to stop and start scanning again
     * to receive further advertisements. This flag simulates this issue. It was encountered e.g. on Nexus 4.
     * @param issueIncorrectLlTxMtu Some Android devices claim they can only transmit 27-byte long
     * PDUs on Link Layer in the LLCP Data Length Update procedure, while later trying to send 251 bytes.
     * This causes the peripheral to terminate the connection. This flag simulates this issue.
     * It was encountered e.g. on Samsung A8 and Samsung A8 Tab.
     */
    class Api26(
        deviceName: String = DEFAULT_NAME,
        isBluetoothSupported: Boolean = true,
        isBluetoothEnabled: Boolean = true,
        isBluetoothPrivilegedPermissionGranted: Boolean = false,
        isMultipleAdvertisementSupported: Boolean = true,
        isLeExtendedAdvertisingSupported: Boolean = true,
        isLePeriodicAdvertisingSupported: Boolean = isLeExtendedAdvertisingSupported,
        leMaximumAdvertisingDataLength: @Range(from = 31, to = 1650) Int =
            if (isLeExtendedAdvertisingSupported) 1650 else 31,
        maxLlMtu: @Range(from = 27, to = 251) Int = LL_MTU_MAX,
        isLe2MPhySupported: Boolean = true,
        isLeCodedPhySupported: Boolean = true,
        isScanningOnLeCodedPhySupported: Boolean = isLeCodedPhySupported,
        isLocationPermissionGranted: Boolean = true,
        isLocationEnabled: Boolean = true,
        advertiser: MockAdvertiser = DEFAULT_MOCK_ADVERTISER,
        scanner: MockScanner = DEFAULT_MOCK_SCANNER,
        issueOnlyOneActiveScan: Boolean = false,
        issueIncorrectLlTxMtu: Boolean = false,
    ): MockAndroidEnvironment(
        androidSdkVersion = AndroidEnvironment.SdkVersion.OREO,
        deviceName = deviceName,
        isBluetoothSupported = isBluetoothSupported,
        isBluetoothEnabled = isBluetoothEnabled,
        isBluetoothPrivilegedPermissionGranted = isBluetoothPrivilegedPermissionGranted,
        isMultipleAdvertisementSupported = isMultipleAdvertisementSupported,
        isLePeriodicAdvertisingSupported = isLePeriodicAdvertisingSupported,
        isLeExtendedAdvertisingSupported = isLeExtendedAdvertisingSupported,
        leMaximumAdvertisingDataLength = leMaximumAdvertisingDataLength,
        maxLlMtu = maxLlMtu,
        isLocationRequiredForScanning = true,
        isLocationPermissionGranted = isLocationPermissionGranted,
        isLocationEnabled = isLocationEnabled,
        isLe2MPhySupported = isLe2MPhySupported,
        isLeCodedPhySupported = isLeCodedPhySupported,
        isScanningOnLeCodedPhySupported = isScanningOnLeCodedPhySupported,
        advertiser = advertiser,
        scanner = scanner,
        issueOnlyOneActiveScan = issueOnlyOneActiveScan,
        issueIncorrectLlTxMtu = issueIncorrectLlTxMtu,
    )

    /**
     * A mock environment for Android 12 (S).
     *
     * Since Android 12, the `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions are required to
     * scan and connect to Bluetooth devices. When `BLUETOOTH_SCAN` permission is set using
     * `neverForLocation` flag, the location is not required to scan for Bluetooth devices.
     * In that case scan results won't contain beacons.
     *
     * See:
     * * [Bluetooth Permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
     * * [`neverForLocation` flag](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions#assert-never-for-location)
     *
     * @param deviceName The device name, by default set to "Mock".
     * @param isBluetoothSupported Whether Bluetooth is supported on the device.
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param isBluetoothPrivilegedPermissionGranted Whether the Bluetooth privileged permission is
     * granted. This permission can only be granted in own AOSP builds, not for 3rd party apps.
     * @param isMultipleAdvertisementSupported Whether multi advertisement is supported by the chipset.
     * @param isLeExtendedAdvertisingSupported Whether LE Extended Advertising feature is supported.
     * @param isLePeriodicAdvertisingSupported Whether LE Periodic Advertising feature is supported.
     * @param leMaximumAdvertisingDataLength The maximum LE advertising data length in bytes,
     * if LE Extended Advertising feature is supported.
     * @param maxLlMtu The maximum Link Layer MTU size in range 27 - 251.
     * @param isLe2MPhySupported Whether LE 2M PHY is supported on the device.
     * @param isLeCodedPhySupported Whether LE Coded PHY is supported on the device.
     * @param isScanningOnLeCodedPhySupported Whether the device can scan for Bluetooth LE devices
     * advertising on LE Coded PHY as Primary PHY.
     * @param isBluetoothScanPermissionGranted Whether the `BLUETOOTH_SCAN` permission is
     * initially granted.
     * @param isBluetoothConnectPermissionGranted Whether the `BLUETOOTH_CONNECT` permission is
     * initially granted.
     * @param isBluetoothAdvertisePermissionGranted Whether the `BLUETOOTH_ADVERTISE` permission is
     * initially granted.
     * @param isNeverForLocationFlagSet Whether the app is not using results of Bluetooth LE scanning
     * to estimate device location. By default, `neverForLocation` flag is assumed.
     * @param isLocationPermissionGranted Whether the fine location permission is initially granted.
     * @param isLocationEnabled Whether location service is enabled on the device.
     * @param advertiser A callback that will be called when the app requests to advertise.
     * The callback should return TX power level used for mock advertising.
     * @param scanner A callback that will be called when the mock central manager requests to scan
     * for devices. It returns whether the scan was successful, secretly failed, or returned an error.
     * @param issueOnlyOneActiveScan Some early Android devices were sending only one Scan Request
     * message for a single device per scan. Non-connectable devices were reported continuously, but
     * connectable devices were reported only once. The client had to stop and start scanning again
     * to receive further advertisements. This flag simulates this issue. It was encountered e.g. on Nexus 4.
     * @param issueIncorrectLlTxMtu Some Android devices claim they can only transmit 27-byte long
     * PDUs on Link Layer in the LLCP Data Length Update procedure, while later trying to send 251 bytes.
     * This causes the peripheral to terminate the connection. This flag simulates this issue.
     * It was encountered e.g. on Samsung A8 and Samsung A8 Tab.
     */
    class Api31(
        deviceName: String = DEFAULT_NAME,
        isBluetoothSupported: Boolean = true,
        isBluetoothEnabled: Boolean = true,
        isBluetoothPrivilegedPermissionGranted: Boolean = false,
        isMultipleAdvertisementSupported: Boolean = true,
        isLeExtendedAdvertisingSupported: Boolean = true,
        isLePeriodicAdvertisingSupported: Boolean = isLeExtendedAdvertisingSupported,
        leMaximumAdvertisingDataLength: @Range(from = 31, to = 1650) Int =
            if (isLeExtendedAdvertisingSupported) 1650 else 31,
        maxLlMtu: @Range(from = 27, to = 251) Int = LL_MTU_MAX,
        isLe2MPhySupported: Boolean = true,
        isLeCodedPhySupported: Boolean = true,
        isScanningOnLeCodedPhySupported: Boolean = isLeCodedPhySupported,
        isBluetoothScanPermissionGranted: Boolean = true,
        isBluetoothConnectPermissionGranted: Boolean = true,
        isBluetoothAdvertisePermissionGranted: Boolean = true,
        isNeverForLocationFlagSet: Boolean = true,
        isLocationPermissionGranted: Boolean = true,
        isLocationEnabled: Boolean = true,
        advertiser: MockAdvertiser = DEFAULT_MOCK_ADVERTISER,
        scanner: MockScanner = DEFAULT_MOCK_SCANNER,
        issueOnlyOneActiveScan: Boolean = false,
        issueIncorrectLlTxMtu: Boolean = false,
    ): MockAndroidEnvironment(
        androidSdkVersion = AndroidEnvironment.SdkVersion.S,
        deviceName = deviceName,
        isBluetoothSupported = isBluetoothSupported,
        isBluetoothEnabled = isBluetoothEnabled,
        isBluetoothPrivilegedPermissionGranted = isBluetoothPrivilegedPermissionGranted,
        isMultipleAdvertisementSupported = isMultipleAdvertisementSupported,
        isLePeriodicAdvertisingSupported = isLePeriodicAdvertisingSupported,
        isLeExtendedAdvertisingSupported = isLeExtendedAdvertisingSupported,
        leMaximumAdvertisingDataLength = leMaximumAdvertisingDataLength,
        maxLlMtu = maxLlMtu,
        isLocationRequiredForScanning = !isNeverForLocationFlagSet,
        isLocationPermissionGranted = isLocationPermissionGranted,
        isLocationEnabled = isLocationEnabled,
        isLe2MPhySupported = isLe2MPhySupported,
        isLeCodedPhySupported = isLeCodedPhySupported,
        isScanningOnLeCodedPhySupported = isScanningOnLeCodedPhySupported,
        isBluetoothScanPermissionGranted = isBluetoothScanPermissionGranted,
        isBluetoothConnectPermissionGranted = isBluetoothConnectPermissionGranted,
        isBluetoothAdvertisePermissionGranted = isBluetoothAdvertisePermissionGranted,
        advertiser = advertiser,
        scanner = scanner,
        issueOnlyOneActiveScan = issueOnlyOneActiveScan,
        issueIncorrectLlTxMtu = issueIncorrectLlTxMtu,
    )

    /**
     * A mock environment for Android 16 (Baklava).
     *
     * Android 16 added improved bond removal API. Apps will now receive broadcast events
     * when bond information was removed or bonding process failed.
     *
     * See ([Behavior Changes Android 16](https://developer.android.com/about/versions/16/behavior-changes-16#connectivity)).
     *
     * @param deviceName The device name, by default set to "Mock".
     * @param isBluetoothSupported Whether Bluetooth is supported on the device.
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param isBluetoothPrivilegedPermissionGranted Whether the Bluetooth privileged permission is
     * granted. This permission can only be granted in own AOSP builds, not for 3rd party apps.
     * @param isMultipleAdvertisementSupported Whether multi advertisement is supported by the chipset.
     * @param isLeExtendedAdvertisingSupported Whether LE Extended Advertising feature is supported.
     * @param isLePeriodicAdvertisingSupported Whether LE Periodic Advertising feature is supported.
     * @param leMaximumAdvertisingDataLength The maximum LE advertising data length in bytes,
     * if LE Extended Advertising feature is supported.
     * @param maxLlMtu The maximum Link Layer MTU size in range 27 - 251.
     * @param isLe2MPhySupported Whether LE 2M PHY is supported on the device.
     * @param isLeCodedPhySupported Whether LE Coded PHY is supported on the device.
     * @param isScanningOnLeCodedPhySupported Whether the device can scan for Bluetooth LE devices
     * advertising on LE Coded PHY as Primary PHY.
     * @param isBluetoothScanPermissionGranted Whether the `BLUETOOTH_SCAN` permission is
     * initially granted.
     * @param isBluetoothConnectPermissionGranted Whether the `BLUETOOTH_CONNECT` permission is
     * initially granted.
     * @param isBluetoothAdvertisePermissionGranted Whether the `BLUETOOTH_ADVERTISE` permission is
     * initially granted.
     * @param isNeverForLocationFlagSet Whether the app is not using results of Bluetooth LE scanning
     * to estimate device location. By default, `neverForLocation` flag is assumed.
     * @param isLocationPermissionGranted Whether the fine location permission is initially granted.
     * @param isLocationEnabled Whether location service is enabled on the device.
     * @param advertiser A callback that will be called when the app requests to advertise.
     * The callback should return TX power level used for mock advertising.
     * @param scanner A callback that will be called when the mock central manager requests to scan
     * for devices. It returns whether the scan was successful, secretly failed, or returned an error.
     * @param issueOnlyOneActiveScan Some early Android devices were sending only one Scan Request
     * message for a single device per scan. Non-connectable devices were reported continuously, but
     * connectable devices were reported only once. The client had to stop and start scanning again
     * to receive further advertisements. This flag simulates this issue. It was encountered e.g. on Nexus 4.
     * @param issueIncorrectLlTxMtu Some Android devices claim they can only transmit 27-byte long
     * PDUs on Link Layer in the LLCP Data Length Update procedure, while later trying to send 251 bytes.
     * This causes the peripheral to terminate the connection. This flag simulates this issue.
     * It was encountered e.g. on Samsung A8 and Samsung A8 Tab.
     */
    class Api36(
        deviceName: String = DEFAULT_NAME,
        isBluetoothSupported: Boolean = true,
        isBluetoothEnabled: Boolean = true,
        isBluetoothPrivilegedPermissionGranted: Boolean = false,
        isMultipleAdvertisementSupported: Boolean = true,
        isLeExtendedAdvertisingSupported: Boolean = true,
        isLePeriodicAdvertisingSupported: Boolean = isLeExtendedAdvertisingSupported,
        leMaximumAdvertisingDataLength: @Range(from = 31, to = 1650) Int =
            if (isLeExtendedAdvertisingSupported) 1650 else 31,
        maxLlMtu: @Range(from = 27, to = 251) Int = LL_MTU_MAX,
        isLe2MPhySupported: Boolean = true,
        isLeCodedPhySupported: Boolean = true,
        isScanningOnLeCodedPhySupported: Boolean = isLeCodedPhySupported,
        isBluetoothScanPermissionGranted: Boolean = true,
        isBluetoothConnectPermissionGranted: Boolean = true,
        isBluetoothAdvertisePermissionGranted: Boolean = true,
        isNeverForLocationFlagSet: Boolean = true,
        isLocationPermissionGranted: Boolean = true,
        isLocationEnabled: Boolean = true,
        advertiser: MockAdvertiser = DEFAULT_MOCK_ADVERTISER,
        scanner: MockScanner = DEFAULT_MOCK_SCANNER,
        issueOnlyOneActiveScan: Boolean = false,
        issueIncorrectLlTxMtu: Boolean = false,
    ): MockAndroidEnvironment(
        androidSdkVersion = AndroidEnvironment.SdkVersion.BAKLAVA,
        deviceName = deviceName,
        isBluetoothSupported = isBluetoothSupported,
        isBluetoothEnabled = isBluetoothEnabled,
        isMultipleAdvertisementSupported = isMultipleAdvertisementSupported,
        isLePeriodicAdvertisingSupported = isLePeriodicAdvertisingSupported,
        isLeExtendedAdvertisingSupported = isLeExtendedAdvertisingSupported,
        leMaximumAdvertisingDataLength = leMaximumAdvertisingDataLength,
        maxLlMtu = maxLlMtu,
        isLocationRequiredForScanning = !isNeverForLocationFlagSet,
        isLocationPermissionGranted = isLocationPermissionGranted,
        isLocationEnabled = isLocationEnabled,
        isLe2MPhySupported = isLe2MPhySupported,
        isLeCodedPhySupported = isLeCodedPhySupported,
        isScanningOnLeCodedPhySupported = isScanningOnLeCodedPhySupported,
        isBluetoothPrivilegedPermissionGranted = isBluetoothPrivilegedPermissionGranted,
        isBluetoothScanPermissionGranted = isBluetoothScanPermissionGranted,
        isBluetoothConnectPermissionGranted = isBluetoothConnectPermissionGranted,
        isBluetoothAdvertisePermissionGranted = isBluetoothAdvertisePermissionGranted,
        advertiser = advertiser,
        scanner = scanner,
        issueOnlyOneActiveScan = issueOnlyOneActiveScan,
        issueIncorrectLlTxMtu = issueIncorrectLlTxMtu,
    )

    /**
     * A mock environment for Android 17 (Cinnamon Bun).
     *
     * Android 17 added new `connectGatt` API with an option to automatically request high MTU,
     * and opportunistic connections. Bonding is no longer possible, unless the app has
     * `BLUETOOTH_PRIVILEDED` permission, which is not available for 3-rd party apps.
     * The app needs to be signed with the same certificate as the device.
     *
     * In scanning, a new scan type allows for passive scan.
     *
     * See ([Behavior Changes Android 17](https://developer.android.com/about/versions/17/behavior-changes-all#connectivity)).
     *
     * @param deviceName The device name, by default set to "Mock".
     * @param isBluetoothSupported Whether Bluetooth is supported on the device.
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param isBluetoothPrivilegedPermissionGranted Whether the Bluetooth privileged permission is
     * granted. This permission can only be granted in own AOSP builds, not for 3rd party apps.
     * @param isMultipleAdvertisementSupported Whether multi advertisement is supported by the chipset.
     * @param isLeExtendedAdvertisingSupported Whether LE Extended Advertising feature is supported.
     * @param isLePeriodicAdvertisingSupported Whether LE Periodic Advertising feature is supported.
     * @param leMaximumAdvertisingDataLength The maximum LE advertising data length in bytes,
     * if LE Extended Advertising feature is supported.
     * @param maxLlMtu The maximum Link Layer MTU size in range 27 - 251.
     * @param isLe2MPhySupported Whether LE 2M PHY is supported on the device.
     * @param isLeCodedPhySupported Whether LE Coded PHY is supported on the device.
     * @param isScanningOnLeCodedPhySupported Whether the device can scan for Bluetooth LE devices
     * advertising on LE Coded PHY as Primary PHY.
     * @param isBluetoothScanPermissionGranted Whether the `BLUETOOTH_SCAN` permission is
     * initially granted.
     * @param isBluetoothConnectPermissionGranted Whether the `BLUETOOTH_CONNECT` permission is
     * initially granted.
     * @param isBluetoothAdvertisePermissionGranted Whether the `BLUETOOTH_ADVERTISE` permission is
     * initially granted.
     * @param isNeverForLocationFlagSet Whether the app is not using results of Bluetooth LE scanning
     * to estimate device location. By default, `neverForLocation` flag is assumed.
     * @param isLocationPermissionGranted Whether the fine location permission is initially granted.
     * @param isLocationEnabled Whether location service is enabled on the device.
     * @param advertiser A callback that will be called when the app requests to advertise.
     * The callback should return TX power level used for mock advertising.
     * @param scanner A callback that will be called when the mock central manager requests to scan
     * for devices. It returns whether the scan was successful, secretly failed, or returned an error.
     * @param issueOnlyOneActiveScan Some early Android devices were sending only one Scan Request
     * message for a single device per scan. Non-connectable devices were reported continuously, but
     * connectable devices were reported only once. The client had to stop and start scanning again
     * to receive further advertisements. This flag simulates this issue. It was encountered e.g. on Nexus 4.
     * @param issueIncorrectLlTxMtu Some Android devices claim they can only transmit 27-byte long
     * PDUs on Link Layer in the LLCP Data Length Update procedure, while later trying to send 251 bytes.
     * This causes the peripheral to terminate the connection. This flag simulates this issue.
     * It was encountered e.g. on Samsung A8 and Samsung A8 Tab.
     */
    class Api37(
        deviceName: String = DEFAULT_NAME,
        isBluetoothSupported: Boolean = true,
        isBluetoothEnabled: Boolean = true,
        isBluetoothPrivilegedPermissionGranted: Boolean = false,
        isMultipleAdvertisementSupported: Boolean = true,
        isLeExtendedAdvertisingSupported: Boolean = true,
        isLePeriodicAdvertisingSupported: Boolean = isLeExtendedAdvertisingSupported,
        leMaximumAdvertisingDataLength: @Range(from = 31, to = 1650) Int =
            if (isLeExtendedAdvertisingSupported) 1650 else 31,
        maxLlMtu: @Range(from = 27, to = 251) Int = LL_MTU_MAX,
        isLe2MPhySupported: Boolean = true,
        isLeCodedPhySupported: Boolean = true,
        isScanningOnLeCodedPhySupported: Boolean = isLeCodedPhySupported,
        isBluetoothScanPermissionGranted: Boolean = true,
        isBluetoothConnectPermissionGranted: Boolean = true,
        isBluetoothAdvertisePermissionGranted: Boolean = true,
        isNeverForLocationFlagSet: Boolean = true,
        isLocationPermissionGranted: Boolean = true,
        isLocationEnabled: Boolean = true,
        advertiser: MockAdvertiser = DEFAULT_MOCK_ADVERTISER,
        scanner: MockScanner = DEFAULT_MOCK_SCANNER,
        issueOnlyOneActiveScan: Boolean = false,
        issueIncorrectLlTxMtu: Boolean = false,
    ): MockAndroidEnvironment(
        androidSdkVersion = AndroidEnvironment.SdkVersion.CINNAMON_BUN,
        deviceName = deviceName,
        isBluetoothSupported = isBluetoothSupported,
        isBluetoothEnabled = isBluetoothEnabled,
        isMultipleAdvertisementSupported = isMultipleAdvertisementSupported,
        isLePeriodicAdvertisingSupported = isLePeriodicAdvertisingSupported,
        isLeExtendedAdvertisingSupported = isLeExtendedAdvertisingSupported,
        leMaximumAdvertisingDataLength = leMaximumAdvertisingDataLength,
        maxLlMtu = maxLlMtu,
        isLocationRequiredForScanning = !isNeverForLocationFlagSet,
        isLocationPermissionGranted = isLocationPermissionGranted,
        isLocationEnabled = isLocationEnabled,
        isLe2MPhySupported = isLe2MPhySupported,
        isLeCodedPhySupported = isLeCodedPhySupported,
        isScanningOnLeCodedPhySupported = isScanningOnLeCodedPhySupported,
        isBluetoothPrivilegedPermissionGranted = isBluetoothPrivilegedPermissionGranted,
        isBluetoothScanPermissionGranted = isBluetoothScanPermissionGranted,
        isBluetoothConnectPermissionGranted = isBluetoothConnectPermissionGranted,
        isBluetoothAdvertisePermissionGranted = isBluetoothAdvertisePermissionGranted,
        advertiser = advertiser,
        scanner = scanner,
        issueOnlyOneActiveScan = issueOnlyOneActiveScan,
        issueIncorrectLlTxMtu = issueIncorrectLlTxMtu,
    )

    /**
     * A mock environment for the Nexus 4.
     *
     * This device may only use active scanning once per scan session for a given device. That means,
     * that Scan Request is only sent once per discovered connectable device, resulting in only a
     * single scan record received from such devices.
     *
     * @param deviceName The device name, by default set to "Nexus 4".
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param advertiser A callback that will be called when the app requests to advertise.
     * The callback should return TX power level used for mock advertising.
     * @param scanner A callback that will be called when the mock central manager requests to scan
     * for devices. It returns whether the scan was successful, secretly failed, or returned an error.
     */
    class Nexus4(
        deviceName: String = "Nexus 4",
        isBluetoothEnabled: Boolean = true,
        advertiser: MockAdvertiser = DEFAULT_MOCK_ADVERTISER,
        scanner: MockScanner = DEFAULT_MOCK_SCANNER,
    ): MockAndroidEnvironment(
        androidSdkVersion = 22, /* Android 5.1 */
        deviceName = deviceName,
        isBluetoothSupported = true,
        isBluetoothEnabled = isBluetoothEnabled,
        isMultipleAdvertisementSupported = true,
        advertiser = advertiser,
        scanner = scanner,
        issueOnlyOneActiveScan = true,
    )

    /**
     * A mock environment for the Samsung A8 with Android 14.
     *
     * That device fails to properly negotiate the Maximum Transfer Usage (MTU) and Data Length
     * Extension (DLE). It incorrectly claims, that can only transfer 27 bytes in a single LL packet,
     * while later trying to send 251 bytes.
     *
     * This causes the peer device to terminate the connection. A workaround for that is not requesting
     * MTU higher than 23 (maximum value length equal to 20 bytes).
     *
     * @param deviceName The device name, by default set to "Samsung A8".
     * @param isBluetoothEnabled Whether Bluetooth is enabled on the device.
     * @param isBluetoothScanPermissionGranted Whether the `BLUETOOTH_SCAN` permission is granted.
     * @param isBluetoothConnectPermissionGranted Whether the `BLUETOOTH_CONNECT` permission is granted.
     * @param isBluetoothAdvertisePermissionGranted Whether the `BLUETOOTH_ADVERTISE` permission is granted.
     * @param isNeverForLocationFlagSet Whether the app is not using results of Bluetooth LE scanning
     * to estimate device location. By default, `neverForLocation` flag is assumed.
     * @param isLocationPermissionGranted Whether the fine location permission is initially granted.
     * @param isLocationEnabled Whether location service is enabled on the device.
     * @param advertiser A callback that will be called when the app requests to advertise.
     * The callback should return TX power level used for mock advertising.
     * @param scanner A callback that will be called when the mock central manager requests to scan
     * for devices. It returns whether the scan was successful, secretly failed, or returned an error.
     */
    class SamsungA8(
        deviceName: String = "Samsung A8",
        isBluetoothEnabled: Boolean = true,
        isBluetoothScanPermissionGranted: Boolean = true,
        isBluetoothConnectPermissionGranted: Boolean = true,
        isBluetoothAdvertisePermissionGranted: Boolean = true,
        isNeverForLocationFlagSet: Boolean = true,
        isLocationPermissionGranted: Boolean = true,
        isLocationEnabled: Boolean = true,
        advertiser: MockAdvertiser = DEFAULT_MOCK_ADVERTISER,
        scanner: MockScanner = DEFAULT_MOCK_SCANNER,
    ): MockAndroidEnvironment(
        androidSdkVersion = 34, /* Android 14 */
        deviceName = deviceName,
        isBluetoothSupported = true,
        isBluetoothEnabled = isBluetoothEnabled,
        isMultipleAdvertisementSupported = true,
        isLeExtendedAdvertisingSupported = true,
        leMaximumAdvertisingDataLength = 1650,
        maxLlMtu = 100, // TODO to be verified
        isLocationRequiredForScanning = !isNeverForLocationFlagSet,
        isLocationPermissionGranted = isLocationPermissionGranted,
        isLocationEnabled = isLocationEnabled,
        isLe2MPhySupported = true,
        isLeCodedPhySupported = true,
        isScanningOnLeCodedPhySupported = false,
        isBluetoothScanPermissionGranted = isBluetoothScanPermissionGranted,
        isBluetoothConnectPermissionGranted = isBluetoothConnectPermissionGranted,
        isBluetoothAdvertisePermissionGranted = isBluetoothAdvertisePermissionGranted,
        advertiser = advertiser,
        scanner = scanner,
        issueIncorrectLlTxMtu = true,
    )
}