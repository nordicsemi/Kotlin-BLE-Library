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

package no.nordicsemi.kotlin.ble.client.android.mock

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.CentralManager.ConnectionOptions
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.core.Bluetooth5AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"
private val CODED = PhyInUse(Phy.PHY_LE_CODED, Phy.PHY_LE_CODED)

/**
 * Tests that the MTU and PHYs of a connection are limited to what both the phone (the environment
 * of the manager) and the peripheral support.
 */
class ConnectionEnvironmentLimitsTest {

    private val withoutAutoMtu = ConnectionOptions.Direct(automaticallyRequestHighestValueLength = false)

    /** A peripheral with the highest possible MTUs, supporting all PHYs. */
    private fun spec(
        isCodedAdvertising: Boolean = false,
    ): PeripheralSpec<String> = PeripheralSpec.simulatePeripheral(
        identifier = ADDRESS,
        addressType = AddressType.PUBLIC,
    ) {
        connectable(
            name = "Device",
            maxAttMtu = 517,
            maxL2capMtu = 251,
            isPhyLe2MSupported = true,
            isPhyCodedSupported = true,
            eventHandler = object : PeripheralSpecEventHandler {},
        ) {}
        advertising(
            parameters = when (isCodedAdvertising) {
                // Legacy advertising is always sent on LE 1M.
                false -> LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds)
                true -> Bluetooth5AdvertisingSetParameters(
                    connectable = true,
                    interval = 100.milliseconds,
                    primaryPhy = PrimaryPhy.PHY_LE_CODED,
                    secondaryPhy = Phy.PHY_LE_CODED,
                )
            },
        ) {
            CompleteLocalName("Device")
        }
    }

    private fun TestScope.manager(
        spec: PeripheralSpec<String>,
        environment: MockAndroidEnvironment,
    ): CentralManager = CentralManager.mock(environment = environment, scope = backgroundScope)
        .apply { simulatePeripherals(listOf(spec)) }

    private suspend fun TestScope.connect(
        spec: PeripheralSpec<String>,
        environment: MockAndroidEnvironment,
        options: ConnectionOptions = ConnectionOptions.Direct(),
    ): Peripheral {
        val manager = manager(spec, environment)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, options)
        runCurrent()
        return peripheral
    }

    // ---- MTU ----

    @Test
    fun `mtu requested by the client is capped by the environment`() = runTest {
        // Android 4.4 supports only the default MTU.
        val peripheral = connect(spec(), MockAndroidEnvironment.Api19(), withoutAutoMtu)

        peripheral.requestHighestValueLength()
        runCurrent()

        assertEquals(23, peripheral.mtu.value)
    }

    @Test
    fun `mtu requested by the peripheral is capped by the environment`() = runTest {
        val spec = spec()
        val peripheral = connect(spec, MockAndroidEnvironment.Api19(), withoutAutoMtu)

        spec.simulateMtuRequest(517)
        runCurrent()
        // The request takes a few connection intervals.
        testScheduler.advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(23, spec.mtu)
        assertEquals(23, peripheral.mtu.value)
    }

    @Test
    fun `mtu of a connection made by another application is capped by the environment`() = runTest {
        val spec = spec()
        val manager = manager(spec, MockAndroidEnvironment.Api19())
        spec.simulateConnection(mtu = 247)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        // The client requests the highest MTU, and gets the one negotiated before.
        manager.connect(peripheral)
        runCurrent()

        assertEquals(23, spec.mtu)
        assertEquals(23, peripheral.mtu.value)
    }

    @Test
    fun `mtu is not capped when the environment supports it`() = runTest {
        val spec = spec()
        val manager = manager(spec, MockAndroidEnvironment.Api31())
        spec.simulateConnection(mtu = 247)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        // The client requests the highest MTU, and gets the one negotiated before.
        manager.connect(peripheral)
        runCurrent()

        assertEquals(247, peripheral.mtu.value)
    }

    @Test
    fun `link layer mtu is capped by the environment`() = runTest {
        val spec = spec()
        connect(spec, MockAndroidEnvironment.Api31(maxLlMtu = 100))

        assertEquals(100, spec.llMtu)
    }

    @Test
    fun `link layer mtu of a connection made by another application is capped by the environment`() = runTest {
        val spec = spec()
        manager(spec, MockAndroidEnvironment.Api31(maxLlMtu = 100))

        spec.simulateConnection()

        assertEquals(100, spec.llMtu)
    }

    // ---- PHY ----

    @Test
    fun `device advertising on LE Coded is connected on LE Coded when the phone supports it`() = runTest {
        val peripheral = connect(spec(isCodedAdvertising = true), MockAndroidEnvironment.Api31())

        assertEquals(CODED, peripheral.phy.value)
    }

    @Test
    fun `device advertising on LE Coded can not be connected when the phone does not support it`() = runTest {
        val manager = manager(
            spec(isCodedAdvertising = true),
            MockAndroidEnvironment.Api31(isLeCodedPhySupported = false),
        )
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        // The phone can't receive the advertisements, so the device is never found.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(5.seconds) { manager.connect(peripheral) }
        }
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
    }

    @Test
    fun `device advertising on LE Coded can not be connected when the phone can not scan on it`() = runTest {
        val manager = manager(
            spec(isCodedAdvertising = true),
            MockAndroidEnvironment.Api31(isLeCodedPhySupported = true, isScanningOnLeCodedPhySupported = false),
        )
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(5.seconds) { manager.connect(peripheral) }
        }
    }

    @Test
    fun `preferred tx and rx phy are limited separately to the supported ones`() = runTest {
        // The peripheral supports LE 2M, and the phone doesn't support LE Coded.
        val spec = PeripheralSpec.simulatePeripheral(identifier = ADDRESS, addressType = AddressType.PUBLIC) {
            connectable(
                name = "Device",
                isPhyLe2MSupported = true,
                eventHandler = object : PeripheralSpecEventHandler {},
            ) {}
            advertising(
                parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds),
            ) {
                CompleteLocalName("Device")
            }
        }
        val peripheral = connect(spec, MockAndroidEnvironment.Api31(isLeCodedPhySupported = false))

        val phy = peripheral.setPreferredPhy(txPhy = Phy.PHY_LE_2M, rxPhy = Phy.PHY_LE_CODED)
        runCurrent()

        // TX is changed, RX stays the same, as LE Coded is not supported.
        assertEquals(PhyInUse(Phy.PHY_LE_2M, Phy.PHY_LE_1M), phy)
        assertEquals(phy, peripheral.phy.value)
    }

    @Test
    fun `phy of a connection made by another application is limited to the ones the phone supports`() = runTest {
        val spec = spec()
        val manager = manager(spec, MockAndroidEnvironment.Api31(isLeCodedPhySupported = false))
        spec.simulateConnection(phy = PrimaryPhy.PHY_LE_CODED)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.connect(peripheral)
        runCurrent()

        assertEquals(PhyInUse.PHY_LE_1M, spec.phy)
        assertEquals(PhyInUse.PHY_LE_1M, peripheral.phy.value)
    }

    @Test
    fun `initially connected device uses only the phys the phone supports`() = runTest {
        val spec = PeripheralSpec.simulatePeripheral(identifier = ADDRESS, addressType = AddressType.PUBLIC) {
            connected(
                name = "Device",
                isPhyCodedSupported = true,
                activePhy = CODED,
                eventHandler = object : PeripheralSpecEventHandler {},
                cachedServices = {},
            )
        }
        val manager = manager(spec, MockAndroidEnvironment.Api31(isLeCodedPhySupported = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.connect(peripheral)
        runCurrent()

        assertEquals(PhyInUse.PHY_LE_1M, peripheral.phy.value)
    }
}
