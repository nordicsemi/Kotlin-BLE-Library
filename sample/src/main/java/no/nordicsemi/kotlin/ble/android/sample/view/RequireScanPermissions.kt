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

package no.nordicsemi.kotlin.ble.android.sample.view

import android.Manifest.permission.ACCESS_FINE_LOCATION
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.environment.android.compose.LocalEnvironmentOwner

/**
 * Shows the [content] if the permissions required for scanning are granted.
 *
 * Otherwise, a button requesting the permissions is shown.
 */
@Composable
fun RequireScanPermissions(content: @Composable () -> Unit) {
    val environment = LocalEnvironmentOwner.current

    var permissions = arrayOf<String>()
    if (environment.isLocationRequiredForScanning) {
        // Location permission is required to scan for Bluetooth LE devices on Android 12 and below,
        // or when 'neverForLocation' is set to 'false' in the manifest.
        permissions += ACCESS_FINE_LOCATION
    }
    if (environment.requiresBluetoothRuntimePermissions) {
         // Bluetooth permissions are required to scan for Bluetooth LE devices on Android 12 and above.
         permissions += AndroidEnvironment.Permission.BLUETOOTH_SCAN
         permissions += AndroidEnvironment.Permission.BLUETOOTH_CONNECT
    }
    var permissionGranted by remember {
        val bluetoothPermissions =
            environment.requiresBluetoothRuntimePermissions &&
            environment.isBluetoothScanPermissionGranted &&
            environment.isBluetoothConnectPermissionGranted
        val locationPermission =
            environment.isLocationRequiredForScanning &&
            environment.isLocationPermissionGranted
        mutableStateOf(bluetoothPermissions || locationPermission)
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {
            // This may not work.
            // permissionGranted = it.values.all { true }
            // Use this instead:
            val bluetoothPermissions =
                environment.requiresBluetoothRuntimePermissions &&
                        environment.isBluetoothScanPermissionGranted &&
                        environment.isBluetoothConnectPermissionGranted
            val locationPermission =
                environment.isLocationRequiredForScanning &&
                        environment.isLocationPermissionGranted
            permissionGranted = bluetoothPermissions || locationPermission
        }
    )

    if (permissionGranted) {
        content()
    } else {
        Button(
            onClick = {  launcher.launch(permissions) }
        ) {
            Text("Grant required permissions")
        }
    }
}
