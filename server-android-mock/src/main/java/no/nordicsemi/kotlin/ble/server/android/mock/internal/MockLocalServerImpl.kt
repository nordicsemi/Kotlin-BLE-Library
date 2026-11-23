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

package no.nordicsemi.kotlin.ble.server.android.mock.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.ble.server.android.Central
import no.nordicsemi.kotlin.ble.server.android.mock.MockAndroidCentral
import no.nordicsemi.kotlin.ble.server.android.mock.MockLocalServer
import no.nordicsemi.kotlin.ble.server.internal.LocalServerImpl
import no.nordicsemi.kotlin.ble.server.mock.MockGattServer

/**
 * The mock GATT server on Android, which behaves like the native one when Bluetooth is off
 * or the `BLUETOOTH_CONNECT` permission is missing.
 */
internal class MockAndroidGattServer(
    private val environment: MockAndroidEnvironment,
): MockGattServer<String, Central>(environment.bluetoothState) {

    override fun checkAvailable() {
        super.checkAvailable()
        if (environment.requiresBluetoothRuntimePermissions && !environment.isBluetoothConnectPermissionGranted) {
            throw SecurityException("BLUETOOTH_CONNECT permission not granted")
        }
    }
}

/**
 * The mock implementation of the [MockLocalServer]: the shared [LocalServerImpl]
 * running on top of a simulated GATT server.
 */
internal class MockLocalServerImpl private constructor(
    scope: CoroutineScope,
    private val environment: MockAndroidEnvironment,
    private val mockServer: MockAndroidGattServer,
): LocalServerImpl<String, Central>(scope, mockServer), MockLocalServer {

    constructor(
        scope: CoroutineScope,
        environment: MockAndroidEnvironment,
    ): this(scope, environment, MockAndroidGattServer(environment))

    private val centrals = mutableMapOf<String, MockAndroidCentral>()

    // Like on a real device, all connections are lost when Bluetooth is turned off.
    private val bluetoothWatcher: Job = internalScope.launch {
        environment.bluetoothState
            .collect { state ->
                if (state != Manager.State.POWERED_ON) {
                    mockServer.disconnectAll()
                }
            }
    }

    override val connectedCentrals: StateFlow<List<Central>>
        get() = mockServer.connectedCentrals

    override fun simulateCentral(address: String): MockAndroidCentral {
        require(address !in centrals) { "A central with address $address already exists" }
        return MockAndroidCentral(address, mockServer, environment)
            .also { centrals[address] = it }
    }

    override fun close() {
        bluetoothWatcher.cancel()
        super.close()
    }
}
