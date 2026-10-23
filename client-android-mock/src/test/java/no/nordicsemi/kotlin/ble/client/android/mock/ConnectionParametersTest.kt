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

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.exception.PeripheralNotConnectedException
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"

/**
 * Tests of the connection parameters: initial values, requests made with connection priority,
 * updates initiated by the peripheral and the temporary change during service discovery.
 */
class ConnectionParametersTest {

    private val preferred = ConnectionParameters.Specified(connectionInterval = 25, latency = 0, supervisionTimeout = 400)

    private fun spec(): PeripheralSpec<String> = PeripheralSpec.simulatePeripheral(
        identifier = ADDRESS,
        addressType = AddressType.PUBLIC,
    ) {
        connectable(
            name = "Device",
            preferredConnectionInterval = 25..40,
            preferredSlaveLatency = 0,
            preferredSupervisionTimeout = 400,
            eventHandler = object : PeripheralSpecEventHandler {},
        ) {}
        advertising(
            parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds),
        ) {
            CompleteLocalName("Device")
        }
    }

    private suspend fun TestScope.connect(
        spec: PeripheralSpec<String> = spec(),
        environment: MockAndroidEnvironment = MockAndroidEnvironment.Api37(),
    ): Peripheral {
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec)) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        runCurrent()
        return peripheral
    }

    @Test
    fun `connection parameters are null when disconnected`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec())) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        runCurrent()

        assertNull(peripheral.connectionParameters.value)
    }

    @Test
    fun `connection parameters are unknown right after connecting`() = runTest {
        val peripheral = connect()

        assertEquals(ConnectionParameters.Unknown, peripheral.connectionParameters.value)
    }

    @Test
    fun `connection parameters are null after disconnecting`() = runTest {
        val peripheral = connect()

        peripheral.disconnect()
        runCurrent()

        assertNull(peripheral.connectionParameters.value)
    }

    @Test
    fun `high connection priority requests short connection interval`() = runTest {
        val peripheral = connect()

        val parameters = peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
        runCurrent()

        assertEquals(ConnectionParameters.Specified(connectionInterval = 9, latency = 0, supervisionTimeout = 500), parameters)
        assertEquals(parameters, peripheral.connectionParameters.value)
    }

    @Test
    fun `low power connection priority requests long connection interval and latency`() = runTest {
        val peripheral = connect()

        val parameters = peripheral.requestConnectionPriority(ConnectionPriority.LOW_POWER)

        assertEquals(ConnectionParameters.Specified(connectionInterval = 80, latency = 2, supervisionTimeout = 500), parameters)
    }

    @Test
    fun `balanced connection priority restores default parameters`() = runTest {
        val peripheral = connect()
        peripheral.requestConnectionPriority(ConnectionPriority.HIGH)

        val parameters = peripheral.requestConnectionPriority(ConnectionPriority.BALANCED)

        assertEquals(ConnectionParameters.Specified(connectionInterval = 24, latency = 0, supervisionTimeout = 500), parameters)
    }

    @Test
    fun `connection priority request before Android 8 returns unknown parameters`() = runTest {
        val peripheral = connect(environment = MockAndroidEnvironment.Api23())

        val parameters = peripheral.requestConnectionPriority(ConnectionPriority.HIGH)

        assertEquals(ConnectionParameters.Unknown, parameters)
    }

    @Test
    fun `connection priority request when disconnected throws`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec())) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<PeripheralNotConnectedException> {
            peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
        }
    }

    @Test
    fun `connection parameters updated by the peripheral are reported`() = runTest {
        val spec = spec()
        val peripheral = connect(spec)
        val newParameters = ConnectionParameters.Specified(connectionInterval = 12, latency = 4, supervisionTimeout = 200)

        spec.simulateConnectionParametersRequest(newParameters)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(newParameters, peripheral.connectionParameters.value)
    }

    @Test
    fun `connection parameters updated by the peripheral are applied after the requested delay`() = runTest {
        val spec = spec()
        val peripheral = connect(spec)
        val newParameters = ConnectionParameters.Specified(connectionInterval = 12, latency = 4, supervisionTimeout = 200)

        // 5 connection intervals of 31 ms each.
        spec.simulateConnectionParametersRequest(newParameters, delay = 5)
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertEquals(ConnectionParameters.Unknown, peripheral.connectionParameters.value)

        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertEquals(newParameters, peripheral.connectionParameters.value)
    }

    @Test
    fun `connection parameters requested by the peripheral are applied after 6 connection intervals by default`() = runTest {
        val spec = spec()
        val peripheral = connect(spec)
        val newParameters = ConnectionParameters.Specified(connectionInterval = 12, latency = 4, supervisionTimeout = 200)

        // The Central allows a minimum of 6 connection events before the instant.
        // The connection interval of 25 units is 31 ms, 6 intervals is 186 ms.
        spec.simulateConnectionParametersRequest(newParameters)
        advanceTimeBy(180.milliseconds)
        runCurrent()
        assertEquals(ConnectionParameters.Unknown, peripheral.connectionParameters.value)

        advanceTimeBy(10.milliseconds)
        runCurrent()
        assertEquals(newParameters, peripheral.connectionParameters.value)
    }

    @Test
    fun `connection priority request takes 6 connection intervals`() = runTest {
        val peripheral = connect()
        val start = testScheduler.currentTime

        peripheral.requestConnectionPriority(ConnectionPriority.HIGH)

        // The connection interval of 25 units is 31 ms.
        assertEquals(6 * 31L, testScheduler.currentTime - start)
    }

    @Test
    fun `connection parameters changed during service discovery are restored afterwards`() = runTest {
        val peripheral = connect()
        val values = mutableListOf<ConnectionParameters?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.connectionParameters.collect { values += it }
        }

        peripheral.services()
        advanceTimeBy(10.seconds)
        runCurrent()

        // Android uses the 7.5 ms connection interval for service discovery...
        assertTrue(values.any { it is ConnectionParameters.Specified && it.connectionInterval == 6 })
        // ...and then goes back to the previous parameters.
        assertEquals(preferred, values.last())
        assertEquals(preferred, peripheral.connectionParameters.value)
    }

    @Test
    fun `connection parameters requested with priority are restored after service discovery`() = runTest {
        val peripheral = connect()
        val high = peripheral.requestConnectionPriority(ConnectionPriority.HIGH)

        peripheral.services()
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(high, peripheral.connectionParameters.value)
    }

    @Test
    fun `supervision timeout of the connection decides when the link is lost`() = runTest {
        val spec = PeripheralSpec.simulatePeripheral(identifier = ADDRESS, addressType = AddressType.PUBLIC) {
            connectable(
                name = "Device",
                preferredSupervisionTimeout = 200,
                eventHandler = object : PeripheralSpecEventHandler {},
            ) {}
            advertising(parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds)) {
                CompleteLocalName("Device")
            }
        }
        val peripheral = connect(spec)

        // A request to change the supervision timeout is applied when the parameters change.
        val newParameters = ConnectionParameters.Specified(connectionInterval = 25, latency = 0, supervisionTimeout = 100)
        spec.simulateConnectionParametersRequest(newParameters)
        advanceTimeBy(1.seconds)
        runCurrent()
        val lost = async { peripheral.awaitDisconnection() }
        spec.simulateProximityChange(no.nordicsemi.kotlin.ble.client.mock.Proximity.OUT_OF_RANGE)
        advanceTimeBy(900.milliseconds)
        runCurrent()
        assertTrue(!lost.isCompleted)

        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertTrue(lost.isCompleted)
    }
}
