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

@file:Suppress("unused")

package no.nordicsemi.kotlin.ble.client.android.mock

import kotlinx.coroutines.CoroutineScope
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.mock.internal.MockCentralManagerImpl
import no.nordicsemi.kotlin.ble.client.mock.SimulationProvider
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.ble.server.android.LocalServer
import kotlin.time.Clock

/**
 * Creates a mock implementation of a [CentralManager] that can emulate scanning and connecting
 * to Bluetooth Low Energy devices.
 *
 * Use [MockCentralManager.simulatePeripherals] to set up mock peripherals and other
 * methods from [SimulationProvider] to control the simulation.
 *
 * Advertising and the background work of the simulated peripherals, like link loss or
 * notifications, run in the given [scope]. A scope backed by a test dispatcher, e.g.
 * `backgroundScope` of `runTest`, makes them follow the test scheduler and its virtual time.
 *
 * @param environment The environment to use for the mock, defaults to the latest supported API.
 * @param scope The coroutine scope.
 * @param server The local GATT server of this device. Its services are available to the profiles
 * registered on peripherals, see `Peripheral.profile`. If not set, and [CentralManager.server] is
 * accessed, the server will be automatically created.
 * @param clock The clock used for timestamps of scan results, defaults to the system clock.
 * Tests using virtual time may pass a clock based on the test scheduler.
 */
fun CentralManager.Factory.mock(
    environment: MockAndroidEnvironment = LatestApi(),
    scope: CoroutineScope,
    server: LocalServer? = null,
    clock: Clock = Clock.System,
): MockCentralManager = MockCentralManagerImpl(scope, environment, clock, server)