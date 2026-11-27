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

package no.nordicsemi.kotlin.ble.client.mock

import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.server.Client

/**
 * The client device, that is the phone, as seen by the services hosted by a [MockPeripheralServer].
 *
 * It is the [Client] given in [no.nordicsemi.kotlin.ble.server.ReadRequest] and
 * [no.nordicsemi.kotlin.ble.server.WriteRequest], and in
 * [no.nordicsemi.kotlin.ble.server.LocalCharacteristic.subscribers].
 *
 * A mock peripheral is connected to the phone with a single connection, even when it is used by
 * more than one mock central manager (or more than one client of the same manager), so there is
 * only one client. It is connected when the [PeripheralSpec] is connected, and disconnected
 * when the connection ends. The state kept by the server for the client, like the subscriptions,
 * is dropped then, as it would be for a real peripheral that does not have a bond with the phone.
 *
 * The maximum length of a notification or an indication, [maximumUpdateValueLength], follows
 * the ATT MTU of the connection, see [PeripheralSpec.mtu].
 */
interface SimulatedClient: Client<String> {

    /**
     * The connection state of the client. It is connected when the [PeripheralSpec] is connected.
     */
    val state: StateFlow<ConnectionState>

    /**
     * The current ATT MTU of the connection. The value is retained after disconnecting.
     */
    val mtu: StateFlow<Int>
}
