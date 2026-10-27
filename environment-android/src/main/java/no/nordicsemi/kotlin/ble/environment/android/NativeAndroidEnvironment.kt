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

package no.nordicsemi.kotlin.ble.environment.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.environment.android.internal.toState
import org.jetbrains.annotations.Range

/**
 * This is the environment of a native Android device.
 *
 * All parameters are read from the device properties.
 *
 * @param context The Android context, used to access system services.
 * @param isNeverForLocationFlagSet Whether the app is not using results of Bluetooth LE scanning
 * to estimate device location. This should be set if the `BLUETOOTH_SCAN` permission is declared with
 * `neverForLocation` flag.
 */
class NativeAndroidEnvironment private constructor(
    context: Context,
    isNeverForLocationFlagSet: Boolean,
): AndroidEnvironment {

    companion object {
        /** Singleton instance of the environment. */
        private lateinit var instance: NativeAndroidEnvironment

        /**
         * Creates or returns the singleton instance of the Android native environment.
         *
         * ### Important
         * When first time created, the environment registers a [BroadcastReceiver] to
         * listen to Bluetooth state changes. Make sure to call [close] to unregister the receiver.
         *
         * @param context The Android context, used to access system services. This can be any
         * [Context], as only the [Context.getApplicationContext] will be used.
         * @param isNeverForLocationFlagSet Whether the app is not using results of Bluetooth LE scanning
         * to estimate device location. This should be set if the `BLUETOOTH_SCAN` permission is declared with
         * `neverForLocation` flag.
         * @return The singleton instance of the environment.
         */
        fun getInstance(
            context: Context,
            isNeverForLocationFlagSet: Boolean,
        ): NativeAndroidEnvironment {
            if (!::instance.isInitialized) {
                instance = NativeAndroidEnvironment(context, isNeverForLocationFlagSet)
            }
            return instance
        }
    }

    /**
     * Application context.
     */
    val applicationContext: Context = context.applicationContext
    /**
     * Location manager.
     *
     * If `null`, the Location is not supported on the device.
     */
    val locationManager: LocationManager? = ContextCompat.getSystemService(applicationContext, LocationManager::class.java)
    /**
     * Bluetooth manager.
     *
     * If `null`, Bluetooth is not supported on the device.
     */
    val bluetoothManager: BluetoothManager? = ContextCompat.getSystemService(applicationContext, BluetoothManager::class.java)

    /**
     * State of the Bluetooth adapter.
     *
     * This is a [kotlinx.coroutines.flow.MutableStateFlow] that emits the current state of the Bluetooth adapter.
     * The state is updated when the adapter state changes.
     */
    override val bluetoothState: StateFlow<Manager.State>
        field = MutableStateFlow(bluetoothManager?.adapter?.state?.toState() ?: Manager.State.UNSUPPORTED)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun enableBluetooth() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        applicationContext.startActivity(intent)
    }

    /**
     * Broadcast receiver that listens for Bluetooth state changes and emits [bluetoothState].
     */
    private val bluetoothStateBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val oldState = bluetoothState.value
            val newState = bluetoothManager?.adapter?.state?.toState() ?: Manager.State.UNKNOWN

            // Ignore if the state has not changed.
            if (oldState != newState) {
                bluetoothState.update { newState }
            }
        }
    }

    override val locationState: StateFlow<Boolean>
        field = MutableStateFlow(locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager))

    /**
     * Broadcast receiver that listens for Location state changes and emits [locationState].
     */
    private val locationStateBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val oldState = locationState.value
            val newState = locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager)

            // Ignore if the state has not changed.
            if (oldState != newState) {
                locationState.update { newState }
            }
        }
    }

    init {
        // Register a broadcast receivers to monitor Bluetooth and Location state changes.
        val monitorBluetoothState = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(applicationContext, bluetoothStateBroadcastReceiver, monitorBluetoothState, ContextCompat.RECEIVER_EXPORTED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val monitorLocationState = IntentFilter(LocationManager.MODE_CHANGED_ACTION)
            ContextCompat.registerReceiver(applicationContext, locationStateBroadcastReceiver, monitorLocationState, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun close() {
        // Unregister the broadcast receivers.
        try {
            applicationContext.unregisterReceiver(bluetoothStateBroadcastReceiver)
        } catch (_: IllegalArgumentException) { /* Ignore */ }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                applicationContext.unregisterReceiver(locationStateBroadcastReceiver)
            } catch (_: IllegalArgumentException) { /* Ignore */ }
        }
    }

    override val androidSdkVersion = Build.VERSION.SDK_INT
    override var deviceName: String
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        get() = bluetoothManager?.adapter?.name ?: throw BluetoothUnavailableException()
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        set(value) {
            val adapter = requireNotNull(bluetoothManager?.adapter) {
                throw BluetoothUnavailableException()
            }
            adapter.name = value
        }

    override val isBluetoothSupported: Boolean
        get() = bluetoothManager?.adapter != null
    // This flag is set on Android 6 (Marshmallow) - 11 (Q),
    // and on Android 12+ (S) if `userPermissionFlags` contains `neverForLocation` flag.
    override val isLocationRequiredForScanning =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
       (Build.VERSION.SDK_INT <  Build.VERSION_CODES.S || !isNeverForLocationFlagSet)
    override val isLocationPermissionGranted: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override val isLe2MPhySupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bluetoothManager?.adapter?.isLe2MPhySupported ?: false
    override val isLeCodedPhySupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bluetoothManager?.adapter?.isLeCodedPhySupported ?: false
    override val isBluetoothScanPermissionGranted: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    override val isBluetoothConnectPermissionGranted: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    override val isBluetoothAdvertisePermissionGranted: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    override val isBluetoothPrivilegedPermissionGranted: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_PRIVILEGED) == PackageManager.PERMISSION_GRANTED
    override val isLePeriodicAdvertisingSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bluetoothManager?.adapter?.isLePeriodicAdvertisingSupported ?: false
    override val isLeExtendedAdvertisingSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bluetoothManager?.adapter?.isLeExtendedAdvertisingSupported ?: false
    override val leMaximumAdvertisingDataLength: @Range(from = 31, to = 1650) Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    bluetoothManager?.adapter?.leMaximumAdvertisingDataLength ?: 31
                else 31
    override val isMultipleAdvertisementSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bluetoothManager?.adapter?.isMultipleAdvertisementSupported ?: false
    override val isScanningOnLeCodedPhySupported: Boolean
        // This is not exactly true.
        // There's a lot of devices that don't scan packets sent on LE Coded PHY as Primary PHY.`
        get() = isLeCodedPhySupported
}