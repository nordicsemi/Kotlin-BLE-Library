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

package no.nordicsemi.kotlin.ble.client.mock.internal

import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.client.mock.SimulatedCentral
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.server.mock.MockCentral
import no.nordicsemi.kotlin.ble.server.mock.MockGattServer

/**
 * The simulated central of a [no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec] with
 * a hosted GATT server.
 *
 * It is a [MockCentral], which sends the requests of the client to the server.
 * The [no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec] connects and disconnects it, and
 * keeps its MTU in sync with the connection.
 */
internal class SimulatedCentralImpl(
    server: MockGattServer<String, SimulatedCentral>,
): MockCentral<String, SimulatedCentral>(IDENTIFIER, server), SimulatedCentral {

    override val state: StateFlow<ConnectionState>
        get() = connectionState

    override val mtu: StateFlow<Int>
        get() = attMtu

    override fun toString() = "SimulatedCentral(state=${connectionState.value}, mtu=${attMtu.value})"

    private companion object {
        const val IDENTIFIER = "MOCK-CENTRAL"
    }
}
