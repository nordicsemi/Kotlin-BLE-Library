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

package no.nordicsemi.kotlin.ble.server.android.mock

import no.nordicsemi.kotlin.ble.server.android.LocalServer
import no.nordicsemi.kotlin.ble.server.mock.MockClientHost

/**
 * A mock [LocalServer], which hosts a simulated GATT server and is used by simulated clients.
 *
 * Create it using `LocalServer.mock(...)`, define services using `setup { ... }` and
 * simulate clients using [simulateClient].
 *
 * ```kotlin
 * val environment = MockAndroidEnvironment.Api31()
 * val manager = LocalServer.mock(environment, scope)
 * val services = manager.setup { Service(SERVICE_UUID) { Characteristic(...) } }
 *
 * val client = manager.simulateClient()
 * client.connect()
 * val characteristic = client.server.services(listOf(SERVICE_UUID))
 *     .filterIsInstance<RemoteServices.Discovered>()
 *     .first { it.services.isNotEmpty() }
 *     .services.single().characteristics.first { it.uuid == CHAR_UUID }
 * val value = characteristic.read()
 * ```
 *
 * Bluetooth state is taken from the [MockAndroidEnvironment][no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment].
 * When Bluetooth is turned off, all simulated clients are disconnected, and [setup] throws
 * `BluetoothUnavailableException`.
 */
interface MockLocalServer: LocalServer, MockClientHost<String> {

    /**
     * Creates a simulated client, which can connect to the local GATT server.
     *
     * The client is not connected until [MockAndroidClient.connect] is called.
     *
     * @param address The MAC address of the client, by default a random one.
     * @return The simulated client.
     * @throws IllegalArgumentException if a client with the same address has already been created.
     */
    fun simulateClient(address: String = randomMacAddress()): MockAndroidClient

    /** Returns the simulated client with the address, or creates it. See [MockClientHost]. */
    override fun simulatedClient(identifier: String): MockAndroidClient
}
