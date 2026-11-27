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

package no.nordicsemi.kotlin.ble.client.mock

import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.mock.BondingFailure
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * The result of a connection request.
 *
 * A connection request may either succeed or be denied (ignored by the peripheral). It cannot fail
 * with an error. Issues may arise during an open connection which will cause the link to be terminated,
 * but the connection itself is successful.
 *
 * Read more about connection process on [DevAcademy](https://academy.nordicsemi.com/courses/bluetooth-low-energy-fundamentals/lessons/lesson-3-bluetooth-le-connections/topic/connection-process/).
 */
sealed class ConnectionResult {
    /**
     * The connection request succeeded.
     *
     * This is the default behavior.
     */
    object Accept : ConnectionResult()
    /**
     * The connection request was denied.
     *
     * This may mock a situation where the peripheral is not accepting connections
     * or the peripheral's ID is not in the accept list.
     */
    object Deny : ConnectionResult()

    // TODO Android sometimes returns error on connection request. Consider adding it here?
}

/**
 * The result of a bonding request.
 *
 * The bonding level (Just Works, Passkey, Numeric Comparison) is not simulated. Bonding can only
 * be accepted, or rejected, as when the user cancels pairing or enters a wrong PIN.
 */
sealed class BondResult {
    /**
     * How long the bonding takes, from the request until its result.
     *
     * Bonding may take long, for example when the user has to confirm pairing on the phone.
     * Operations of a peripheral that wait for the bonding to finish are held for that time, which
     * lets tests check that they do not delay other peripherals. By default, bonding is immediate.
     */
    abstract val duration: Duration

    /**
     * The bonding succeeded.
     *
     * This is the default behavior.
     *
     * @param duration How long the bonding takes.
     */
    data class Accept(override val duration: Duration = Duration.ZERO) : BondResult()
    /**
     * The bonding failed, for example because the user cancelled it or entered a wrong PIN.
     *
     * @param failure What went wrong. The environment decides how it is reported to the client.
     * @param duration How long the bonding takes before it fails.
     */
    data class Reject(
        val failure: BondingFailure = BondingFailure.REJECTED,
        override val duration: Duration = Duration.ZERO,
    ) : BondResult()
}

/**
 * The result of a service discovery request.
 */
sealed class ServiceDiscoveryResult {
    /**
     * The service discovery operation succeeded.
     *
     * The client will receive a list of requested services. If the peripheral has no services
     * at all, the discovery is reported as failed, as a real peripheral always has at least
     * the Generic Access and Generic Attribute services.
     */
    object Success : ServiceDiscoveryResult()
    /**
     * The service discovery operation failed.
     *
     * Reporting [Failure] will report a failed service discovery to the client, with the same
     * reason as when no services were found.
     *
     * Note: This library does not provide a way to report an exact error during service discovery.
     * Instead, it simply reports that no services were found.
     */
    object Failure : ServiceDiscoveryResult()
}

/**
 * This is the interface of a peripheral event handler.
 *
 * The implementation should respond to various events occurring on the mock peripheral,
 * such as connection requests, bonding, service discovery, etc. The GATT requests (reads and
 * writes) are handled by the [MockPeripheralServer] of the peripheral, see [PeripheralSpec.server].
 *
 * For example, a mock Blinky device can handle requests to turn on an LED or send notifications
 * when a button was clicked.
 *
 * Each callback receives the [MockPeripheral] the event is for, so that the handler can simulate
 * the behavior of the peripheral, e.g. call [MockPeripheral.simulateDisconnection] when it rejects
 * a bonding request, without keeping a reference to the peripheral.
 */
interface PeripheralSpecEventHandler {

    /**
     * Called when a connection request is made to the mock peripheral.
     *
     * By default, the connection request is accepted.
     *
     * @param peripheral The peripheral the event is for. It can be used to simulate its behavior.
     * @param phy The PHY used for connection.
     * @return The result of the connection request, by default [ConnectionResult.Accept].
     */
    // Note: This cannot be suspended! It can request MTU, bonding, PHY update, etc., but in a coroutine.
    fun onConnectionRequest(peripheral: MockPeripheral, phy: PrimaryPhy): ConnectionResult {
        return ConnectionResult.Accept
    }

    /**
     * Called when a client requests bonding with the mock peripheral.
     *
     * By default, the request is rejected, as if the peripheral did not support bonding.
     *
     * @param peripheral The peripheral the event is for. It can be used to simulate its behavior.
     * @return The result of the bonding request, by default [BondResult.Reject].
     */
    fun onBondRequest(peripheral: MockPeripheral): BondResult {
        return BondResult.Reject(BondingFailure.REJECTED)
    }

    /**
     * Called when the connection is lost.
     *
     * @param peripheral The peripheral the event is for. It can be used to simulate its behavior.
     * @param reason The reason for the disconnection.
     */
    fun onConnectionLost(peripheral: MockPeripheral, reason: DisconnectionReason) {
        // no-op
    }

    /**
     * Called when the peripheral is reset.
     *
     * This method is called when user calls [PeripheralSpec.simulateReset].
     *
     * All existing connections will time out. [onConnectionLost] will NOT be called.
     *
     * @param peripheral The peripheral the event is for. It can be used to simulate its behavior.
     */
    fun onReset(peripheral: MockPeripheral) {
        // no-op
    }

    /**
     * Called when a service discovery request is made to the mock peripheral.
     *
     * Note, that this method does not return the list of services. Instead, it only indicates
     * whether the discovery should succeed or fail. The actual list of services is determined
     * by the [PeripheralSpec] definition.
     *
     * @param peripheral The peripheral the event is for. It can be used to simulate its behavior.
     * @param uuids The list of service UUIDs requested by the client.
     * @return The result of the service discovery request, by default [ServiceDiscoveryResult.Success].
     */
    fun onServiceDiscoveryRequest(peripheral: MockPeripheral, uuids: List<Uuid>): ServiceDiscoveryResult {
        return ServiceDiscoveryResult.Success
    }

}
