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

import no.nordicsemi.kotlin.ble.core.ConnectionParameters

/**
 * A simulated peripheral, as seen by its [PeripheralSpecEventHandler].
 *
 * It is the peripheral for which the event was called. The handler can use it to make the
 * peripheral do something, e.g. disconnect, send a notification, or change its services,
 * and to check its state.
 *
 * The behavior is defined in [PeripheralSpec], which implements this interface. See the
 * documentation there for the details of each method.
 *
 * @see PeripheralSpec
 */
interface MockPeripheral {

    /** Whether the peripheral is connected. */
    val isConnected: Boolean

    /** Whether the phone has the bond information of the peripheral. */
    val isBonded: Boolean

    /** Whether the peripheral has the bond information of the phone. */
    val peripheralHasBond: Boolean

    /**
     * The peripheral disconnects.
     *
     * Connected clients will immediately be notified about the disconnection with reason
     * [TerminatePeerUser][no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.TerminatePeerUser].
     */
    fun simulateDisconnection()

    /**
     * The peripheral requests bonding, or a bonding request from the phone is processed.
     *
     * The bond state changes to [MockBondState.Bonding], and the result is given by
     * [PeripheralSpecEventHandler.onBondRequest].
     */
    fun simulateBondRequest()

    /**
     * The peripheral forgets the bond information, for example after a factory reset.
     *
     * The phone keeps its bond information.
     */
    fun simulatePeripheralBondLoss()

    /**
     * The peripheral resets. Connected clients get a link loss after the supervision timeout.
     */
    fun simulateReset()

    /**
     * The peripheral notifies clients that the value of a characteristic has changed.
     *
     * @param handle The handle of the characteristic.
     * @param value The new value of the characteristic.
     */
    fun simulateValueUpdate(handle: Int, value: ByteArray)

    /**
     * The peripheral changes the services it offers.
     *
     * @param newServices A builder for the new set of services.
     */
    fun simulateServiceChange(newServices: MockServerScope.() -> Unit)

    /**
     * The peripheral requests a connection parameters update.
     *
     * @param parameters The new connection parameters.
     * @param delay Number of old connection intervals to wait before applying new parameters.
     * Defaults to 6, the minimum number of connection events before the instant, in which the
     * Link Layer applies them.
     */
    fun simulateConnectionParametersRequest(parameters: ConnectionParameters.Specified, delay: Int = 6)

    /**
     * The peripheral requests an MTU exchange. The MTU may be changed only once per connection.
     *
     * @param mtu The requested MTU.
     */
    fun simulateMtuRequest(mtu: Int)
}
