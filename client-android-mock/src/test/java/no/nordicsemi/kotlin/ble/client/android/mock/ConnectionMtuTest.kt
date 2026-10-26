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

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.MtuChanged
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.CentralManager.ConnectionOptions
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.exception.PeripheralNotConnectedException
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"

/**
 * Tests of the ATT MTU negotiation and of the maximum write value length derived from it.
 */
class ConnectionMtuTest {

    private fun spec(maxAttMtu: Int): PeripheralSpec<String> = PeripheralSpec.simulatePeripheral(
        identifier = ADDRESS,
        addressType = AddressType.PUBLIC,
    ) {
        connectable(
            name = "Device",
            maxAttMtu = maxAttMtu,
            maxL2capMtu = 251,
            eventHandler = object : PeripheralSpecEventHandler {},
        ) {}
        advertising(
            parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds),
        ) {
            CompleteLocalName("Device")
        }
    }

    private suspend fun TestScope.connect(
        spec: PeripheralSpec<String>,
        options: ConnectionOptions = ConnectionOptions.Direct(),
        environment: MockAndroidEnvironment = LatestApi(),
    ): Peripheral {
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec)) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, options)
        runCurrent()
        return peripheral
    }

    private val withoutAutoMtu = ConnectionOptions.Direct(automaticallyRequestHighestValueLength = false)

    @Test
    fun `mtu is null when disconnected`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec(247))) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        runCurrent()

        assertNull(peripheral.mtu.value)
    }

    @Test
    fun `mtu is the default 23 when the peripheral does not support more`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 23))

        assertEquals(23, peripheral.mtu.value)
    }

    @Test
    fun `highest mtu supported by the peripheral is negotiated on connection`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 247))

        assertEquals(247, peripheral.mtu.value)
    }

    @Test
    fun `mtu is requested only once on Android 17`() = runTest {
        val spec = spec(maxAttMtu = 247)
        val changes = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            spec.events.filterIsInstance<MtuChanged>().collect { changes += it.mtu }
        }

        val _ = connect(spec, environment = MockAndroidEnvironment.Api37())
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(listOf(247), changes)
    }

    @Test
    fun `highest mtu is negotiated on connection also before Android 17`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 247), environment = MockAndroidEnvironment.Api31())

        assertEquals(247, peripheral.mtu.value)
    }

    @Test
    fun `mtu is capped by the maximum supported by the environment`() = runTest {
        // Android 4.4 supports only the default MTU.
        val peripheral = connect(spec(maxAttMtu = 517), environment = MockAndroidEnvironment.Api19())

        assertEquals(23, peripheral.mtu.value)
    }

    @Test
    fun `mtu is not negotiated when automatic request is disabled`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 247), withoutAutoMtu)

        assertEquals(23, peripheral.mtu.value)
    }

    @Test
    fun `requesting the highest value length negotiates the mtu`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 247), withoutAutoMtu)

        peripheral.requestHighestValueLength()
        runCurrent()

        assertEquals(247, peripheral.mtu.value)
    }

    @Test
    fun `requesting the highest value length twice negotiates the mtu only once`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 247), withoutAutoMtu)
        peripheral.requestHighestValueLength()

        // The second request is ignored, it doesn't fail or time out.
        peripheral.requestHighestValueLength()
        runCurrent()

        assertEquals(247, peripheral.mtu.value)
        assertEquals(244, peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE))
    }

    @Test
    fun `requesting the highest value length when disconnected throws`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec(247))) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<PeripheralNotConnectedException> { peripheral.requestHighestValueLength() }
    }

    @Test
    fun `maximum write value length depends on the write type`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 247))

        assertEquals(512, peripheral.maximumWriteValueLength(WriteType.WITH_RESPONSE))
        assertEquals(244, peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE))
        assertEquals(232, peripheral.maximumWriteValueLength(WriteType.SIGNED))
    }

    @Test
    fun `maximum write value length with the default mtu`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 23))

        assertEquals(512, peripheral.maximumWriteValueLength(WriteType.WITH_RESPONSE))
        assertEquals(20, peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE))
        assertEquals(8, peripheral.maximumWriteValueLength(WriteType.SIGNED))
    }

    @Test
    fun `maximum write value length without response is limited to 512 bytes`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 517))

        assertEquals(517, peripheral.mtu.value)
        assertEquals(512, peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE))
    }

    @Test
    fun `maximum write value length when disconnected throws`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec(247))) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<PeripheralNotConnectedException> {
            peripheral.maximumWriteValueLength(WriteType.WITH_RESPONSE)
        }
    }

    @Test
    fun `mtu requested by the peripheral is reported`() = runTest {
        val spec = spec(maxAttMtu = 247)
        val peripheral = connect(spec, withoutAutoMtu)

        spec.simulateMtuRequest(185)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(185, peripheral.mtu.value)
        assertEquals(182, peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE))
    }

    @Test
    fun `mtu requested by the peripheral is capped by its maximum`() = runTest {
        val spec = spec(maxAttMtu = 185)
        val peripheral = connect(spec, withoutAutoMtu)

        spec.simulateMtuRequest(500)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(185, peripheral.mtu.value)
    }

    @Test
    fun `mtu can be changed only once per connection`() = runTest {
        val spec = spec(maxAttMtu = 247)
        val peripheral = connect(spec, withoutAutoMtu)
        spec.simulateMtuRequest(185)
        advanceTimeBy(1.seconds)
        runCurrent()

        spec.simulateMtuRequest(247)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(185, peripheral.mtu.value)
    }

    @Test
    fun `mtu requested by the peripheral is ignored after it was negotiated by the client`() = runTest {
        val spec = spec(maxAttMtu = 247)
        val peripheral = connect(spec)

        spec.simulateMtuRequest(100)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(247, peripheral.mtu.value)
    }

    @Test
    fun `mtu is null after disconnection`() = runTest {
        val peripheral = connect(spec(maxAttMtu = 247))

        peripheral.disconnect()
        runCurrent()

        assertNull(peripheral.mtu.value)
    }

    @Test
    fun `mtu is reset to the default after reconnecting`() = runTest {
        val spec = spec(maxAttMtu = 247)
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec)) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        peripheral.disconnect()

        manager.connect(peripheral, withoutAutoMtu)
        runCurrent()

        assertEquals(23, peripheral.mtu.value)
    }

    @Test
    fun `mtu is negotiated again after reconnecting`() = runTest {
        val spec = spec(maxAttMtu = 247)
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec)) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        peripheral.disconnect()

        manager.connect(peripheral)
        runCurrent()

        assertEquals(247, peripheral.mtu.value)
    }
}
