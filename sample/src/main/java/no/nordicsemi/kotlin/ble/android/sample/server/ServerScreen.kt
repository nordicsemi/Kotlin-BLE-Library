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

package no.nordicsemi.kotlin.ble.android.sample.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.nordicsemi.kotlin.ble.android.sample.view.AppBarActions
import no.nordicsemi.kotlin.ble.android.sample.view.AppBarToggleButton
import no.nordicsemi.kotlin.ble.android.sample.view.BluetoothStateWarning
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.environment.android.compose.LocalEnvironmentOwner

@Composable
fun ServerScreen() {
    val vm = hiltViewModel<ServerViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    val isAdvertising by vm.isAdvertising.collectAsStateWithLifecycle()
    val isLedOn by vm.ledState.collectAsStateWithLifecycle()
    val isButtonPressed by vm.buttonState.collectAsStateWithLifecycle()
    val subscribersCount by vm.subscribersCount.collectAsStateWithLifecycle()
    val clients by vm.clients.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BluetoothStateWarning(state)

        RequireServerPermissions {
            AppBarActions {
                AppBarToggleButton(
                    isActive = isRunning,
                    startLabel = "Start",
                    stopLabel = "Stop",
                    onClick = {
                        if (!isRunning)
                            vm.onStartRequested()
                        else
                            vm.onStopRequested()
                    },
                )
            }

            ServerView(
                isRunning = isRunning,
                isAdvertising = isAdvertising,
                isLedOn = isLedOn,
                isButtonPressed = isButtonPressed,
                subscribersCount = subscribersCount,
                clients = clients,
                errorMessage = error,
                onButtonPressedChanged = vm::onButtonPressedChanged,
            )
        }
    }
}

/**
 * Shows the [content] if the permissions required to advertise and to serve connections are granted.
 *
 * Otherwise, a button requesting the permissions is shown.
 */
@Composable
private fun RequireServerPermissions(content: @Composable () -> Unit) {
    val environment = LocalEnvironmentOwner.current

    // Bluetooth runtime permissions are required on Android 12 and above.
    // On older versions they are granted at install time.
    val permissions = if (environment.requiresBluetoothRuntimePermissions) {
        arrayOf(
            AndroidEnvironment.Permission.BLUETOOTH_ADVERTISE,
            AndroidEnvironment.Permission.BLUETOOTH_CONNECT,
        )
    } else {
        emptyArray()
    }
    fun isGranted() = !environment.requiresBluetoothRuntimePermissions || (
            environment.isBluetoothAdvertisePermissionGranted &&
            environment.isBluetoothConnectPermissionGranted
    )

    var permissionGranted by remember { mutableStateOf(isGranted()) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissionGranted = isGranted() },
    )

    if (permissionGranted) {
        content()
    } else {
        Button(
            onClick = { launcher.launch(permissions) }
        ) {
            Text("Grant required permissions")
        }
    }
}
