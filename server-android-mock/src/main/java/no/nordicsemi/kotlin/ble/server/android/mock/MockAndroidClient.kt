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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.ble.server.android.Client
import no.nordicsemi.kotlin.ble.server.mock.MockClient
import no.nordicsemi.kotlin.ble.server.mock.MockGattServer
import kotlin.time.Duration

/**
 * A simulated Android client, created using [MockLocalServer.simulateClient].
 *
 * It is the [Client] seen by the app (in requests and in [LocalServer.connectedClients]),
 * and at the same time a [MockClient], which is used to simulate what the client does: connecting,
 * reading, writing, subscribing, and changing the MTU, PHY and connection parameters.
 *
 * ```kotlin
 * val client = localServer.simulateClient()
 * client.connect()
 * client.simulateMtuChange(185)
 * val led = client.findCharacteristic(SERVICE_UUID, LED_UUID)!!
 * client.write(led, byteArrayOf(1))
 * ```
 *
 * The client is not connected until [connect] is called.
 */
class MockAndroidClient internal constructor(
    address: String,
    server: MockGattServer<String>,
    private val environment: MockAndroidEnvironment,
): MockClient<String>(address, server, environment.maxAttMtu), Client {

    private val _mtu = MutableStateFlow<Int?>(null)
    private val _phy = MutableStateFlow<PhyInUse?>(null)
    private val _connectionParameters = MutableStateFlow<ConnectionParameters?>(null)

    override val state: StateFlow<ConnectionState>
        get() = connectionState

    override val mtu: StateFlow<Int?> = _mtu.asStateFlow()

    override val phy: StateFlow<PhyInUse?> = _phy.asStateFlow()

    override val connectionParameters: StateFlow<ConnectionParameters?> = _connectionParameters.asStateFlow()

    override fun onConnected() {
        _mtu.value = attMtu.value
        _phy.value = PhyInUse.PHY_LE_1M
        _connectionParameters.value = ConnectionParameters.Unknown
    }

    override fun onDisconnected() {
        _mtu.value = null
        _phy.value = null
        _connectionParameters.value = null
    }

    override fun onMtuChanged(mtu: Int) {
        _mtu.value = mtu
    }

    /**
     * Disconnects the client, as if the local device terminated the connection.
     *
     * Does nothing if the client is not connected.
     */
    override suspend fun disconnect() {
        disconnect(ConnectionState.Disconnected.Reason.TerminateLocalHost)
    }

    /**
     * Changes the PHY of the connection, as if requested by the local device.
     *
     * The requested PHYs are limited to those supported by the environment: if LE 2M or
     * LE Coded is not supported, LE 1M is used instead.
     *
     * @return The PHYs in use after the change.
     * @throws IllegalStateException if the client is not connected.
     */
    override suspend fun setPreferredPhy(
        txPhy: Phy,
        rxPhy: Phy,
        phyOptions: PhyOption,
        timeout: Duration,
    ): PhyInUse {
        check(isConnected) { "Not connected" }
        val phy = PhyInUse(txPhy.supported(), rxPhy.supported())
        _phy.value = phy
        return phy
    }

    /**
     * Simulates the client changing the PHY of the connection.
     *
     * @param txPhy The new TX PHY, as seen from the local device.
     * @param rxPhy The new RX PHY, as seen from the local device.
     * @throws IllegalStateException if the client is not connected.
     */
    fun simulatePhyChange(txPhy: Phy, rxPhy: Phy = txPhy) {
        check(isConnected) { "Not connected" }
        _phy.value = PhyInUse(txPhy, rxPhy)
    }

    /**
     * Simulates the client changing the MTU of the connection, by requesting the MTU exchange.
     *
     * The new MTU is limited to the maximum MTU supported by the environment.
     *
     * @param mtu The MTU requested by the client.
     * @return The new MTU.
     * @throws IllegalStateException if the client is not connected.
     */
    fun simulateMtuChange(mtu: Int): Int = exchangeMtu(mtu)

    /**
     * Simulates the connection parameters being changed.
     *
     * @param parameters The new connection parameters.
     * @throws IllegalStateException if the client is not connected.
     */
    fun simulateConnectionParametersChange(parameters: ConnectionParameters) {
        check(isConnected) { "Not connected" }
        _connectionParameters.value = parameters
    }

    /**
     * Simulates the connection parameters being changed.
     *
     * @param connectionInterval The connection interval, in 1.25 ms units, 6 to 3200.
     * @param latency The peripheral latency, in connection events, 0 to 499.
     * @param supervisionTimeout The supervision timeout, in 10 ms units, 10 to 3200.
     * @throws IllegalStateException if the client is not connected.
     */
    fun simulateConnectionParametersChange(
        connectionInterval: Int,
        latency: Int,
        supervisionTimeout: Int,
    ) = simulateConnectionParametersChange(
        ConnectionParameters(connectionInterval, latency, supervisionTimeout)
    )

    private fun Phy.supported(): Phy = when (this) {
        Phy.PHY_LE_2M -> if (environment.isLe2MPhySupported) this else Phy.PHY_LE_1M
        Phy.PHY_LE_CODED -> if (environment.isLeCodedPhySupported) this else Phy.PHY_LE_1M
        else -> Phy.PHY_LE_1M
    }

    override fun toString() = "MockAndroidClient(address=$address, state=${state.value}, mtu=${mtu.value})"
}
