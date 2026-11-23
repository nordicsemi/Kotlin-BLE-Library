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

package no.nordicsemi.kotlin.ble.server.android.mock

import kotlinx.coroutines.CoroutineScope
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.ble.server.android.LocalServer
import no.nordicsemi.kotlin.ble.server.android.mock.internal.MockLocalServerImpl
import kotlin.random.Random

/**
 * Creates a mock implementation of a [LocalServer], which hosts a simulated GATT server.
 *
 * Use [MockLocalServer.simulateCentral] to simulate centrals that connect to the server and
 * send requests to it.
 *
 * Background work of the manager, like handling of requests, runs in the given [scope]. A scope
 * backed by a test dispatcher, e.g. `backgroundScope` of `runTest`, makes it follow the test
 * scheduler and its virtual time.
 *
 * @param environment The environment to use for the mock, defaults to the latest supported API.
 * Bluetooth state and permissions are taken from it.
 * @param scope The coroutine scope.
 */
fun LocalServer.Factory.mock(
    environment: MockAndroidEnvironment = LatestApi(),
    scope: CoroutineScope,
): MockLocalServer = MockLocalServerImpl(scope, environment)

/**
 * Generates a random MAC address in the form `AA:BB:CC:DD:EE:FF`.
 */
internal fun randomMacAddress(): String =
    Random.nextBytes(6)
        .joinToString(separator = ":") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
