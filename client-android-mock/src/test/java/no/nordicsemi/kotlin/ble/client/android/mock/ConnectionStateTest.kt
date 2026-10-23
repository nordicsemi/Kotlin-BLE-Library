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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.DisconnectionReason
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.client.mock.ConnectionResult
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"

/**
 * Tests of the connection state of a peripheral: the transitions, their reasons, and how the
 * state reacts to events coming from the peripheral and from the manager.
 */
class ConnectionStateTest {

    /** Counts the connection requests, and accepts them unless [result] is changed. */
    private class ConnectionRequests : PeripheralSpecEventHandler {
        var count = 0
        var result: ConnectionResult = ConnectionResult.Accept

        override fun onConnectionRequest(peripheral: MockPeripheral, phy: PrimaryPhy): ConnectionResult {
            count++
            return result
        }
    }

    private class Events : PeripheralSpecEventHandler {
        val connectionsLost = mutableListOf<DisconnectionReason>()
        var resets = 0

        override fun onConnectionLost(peripheral: MockPeripheral, reason: DisconnectionReason) {
            connectionsLost += reason
        }

        override fun onReset(peripheral: MockPeripheral) {
            resets++
        }
    }

    private fun spec(
        eventHandler: PeripheralSpecEventHandler = object : PeripheralSpecEventHandler {},
        proximity: Proximity = Proximity.IMMEDIATE,
        supervisionTimeout: Int = 400,
    ): PeripheralSpec<String> = PeripheralSpec.simulatePeripheral(
        identifier = ADDRESS,
        // Android assumes PUBLIC address type for devices that weren't scanned.
        addressType = AddressType.PUBLIC,
        proximity = proximity,
    ) {
        connectable(
            name = "Device",
            preferredSupervisionTimeout = supervisionTimeout,
            eventHandler = eventHandler,
        ) {}
        advertising(
            parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds),
            isAdvertisingWhenConnected = true,
        ) {
            CompleteLocalName("Device")
        }
    }

    private fun TestScope.otherScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun TestScope.manager(
        spec: PeripheralSpec<String>,
        scope: CoroutineScope = backgroundScope,
    ): MockCentralManager = CentralManager.mock(scope = scope).apply {
        simulatePeripherals(listOf(spec))
    }

    /** Records all values of the peripheral state, including the current one. */
    private fun TestScope.recordStates(peripheral: Peripheral): List<ConnectionState> {
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.state.collect { states += it }
        }
        return states
    }

    private fun TestScope.settle(duration: Duration = 10.seconds) {
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `peripheral is initially disconnected with no reason`() = runTest {
        val manager = manager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertEquals(ConnectionState.Disconnected(null), peripheral.state.value)
    }

    @Test
    fun `connecting goes through Connecting to Connected`() = runTest {
        val manager = manager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val states = recordStates(peripheral)

        manager.connect(peripheral)

        assertEquals(
            listOf(ConnectionState.Disconnected(null), ConnectionState.Connecting, ConnectionState.Connected),
            states,
        )
    }

    @Test
    fun `connect returns only when the peripheral is connected`() = runTest {
        val manager = manager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.connect(peripheral)

        assertTrue(peripheral.isConnected)
        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `disconnecting goes through Disconnecting to Disconnected with Success`() = runTest {
        val manager = manager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        val states = recordStates(peripheral)

        peripheral.disconnect()

        assertEquals(
            listOf(
                ConnectionState.Connected,
                ConnectionState.Disconnecting,
                ConnectionState.Disconnected(Reason.Success),
            ),
            states,
        )
    }

    @Test
    fun `disconnecting a peripheral that was never connected does nothing`() = runTest {
        val manager = manager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        peripheral.disconnect()

        assertEquals(ConnectionState.Disconnected(null), peripheral.state.value)
    }

    @Test
    fun `disconnecting twice keeps the first reason`() = runTest {
        val manager = manager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.disconnect()
        peripheral.disconnect()

        assertEquals(ConnectionState.Disconnected(Reason.Success), peripheral.state.value)
    }

    @Test
    fun `connecting an already connected peripheral does nothing`() = runTest {
        val manager = manager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        val states = recordStates(peripheral)

        withTimeout(5.seconds) { manager.connect(peripheral) }

        assertEquals(listOf<ConnectionState>(ConnectionState.Connected), states)
    }

    @Test
    fun `peripheral can be connected again after disconnecting`() = runTest {
        val manager = manager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        peripheral.disconnect()

        withTimeout(10.seconds) { manager.connect(peripheral) }

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `peripheral can be connected again after link loss`() = runTest {
        val spec = spec()
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        settle(5.seconds)
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)

        spec.simulateProximityChange(Proximity.IMMEDIATE)
        withTimeout(10.seconds) { manager.connect(peripheral) }

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `peripheral disconnecting by itself is reported as TerminatePeerUser`() = runTest {
        val events = Events()
        val spec = spec(events)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        spec.simulateDisconnection()
        runCurrent()

        assertEquals(ConnectionState.Disconnected(Reason.TerminatePeerUser), peripheral.state.value)
        assertEquals(listOf<DisconnectionReason>(DisconnectionReason.TerminateLocalHost), events.connectionsLost)
    }

    @Test
    fun `awaitDisconnection returns the reason of the link loss`() = runTest {
        val spec = spec()
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)

        assertEquals(Reason.LinkLoss, peripheral.awaitDisconnection())
    }

    @Test
    fun `link loss is reported after the supervision timeout of the connection`() = runTest {
        // Supervision timeout of 100 × 10 ms = 1 s.
        val spec = spec(supervisionTimeout = 100)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        settle(900.milliseconds)
        assertEquals(ConnectionState.Connected, peripheral.state.value)

        settle(200.milliseconds)
        assertEquals(ConnectionState.Disconnected(Reason.LinkLoss), peripheral.state.value)
    }

    @Test
    fun `link loss notifies the peripheral event handler with Timeout`() = runTest {
        val events = Events()
        val spec = spec(events)
        val manager = manager(spec)
        manager.connect(checkNotNull(manager.getPeripheralById(ADDRESS)))

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        settle(5.seconds)

        assertEquals(listOf<DisconnectionReason>(DisconnectionReason.Timeout), events.connectionsLost)
    }

    @Test
    fun `returning in range before the supervision timeout keeps the connection`() = runTest {
        val spec = spec()
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        settle(2.seconds)
        spec.simulateProximityChange(Proximity.NEAR)
        settle(10.seconds)

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `auto connect peripheral is reconnecting when the peripheral disconnects right after connecting`() = runTest {
        val spec = spec()
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())

        // No time passes between connecting and the disconnection.
        spec.simulateDisconnection()
        runCurrent()

        assertEquals(ConnectionState.Connecting, peripheral.state.value)
    }

    @Test
    fun `auto connect peripheral is reconnecting after link loss`() = runTest {
        val spec = spec()
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        settle(5.seconds)

        // The system keeps trying to reconnect, which is reported as Connecting.
        assertEquals(ConnectionState.Connecting, peripheral.state.value)
    }

    @Test
    fun `auto connect peripheral reconnects when it gets back in range`() = runTest {
        val spec = spec()
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        settle(5.seconds)

        spec.simulateProximityChange(Proximity.NEAR)
        settle(10.seconds)

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `auto connect peripheral reconnects after link loss when it stays in range`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(requests)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())

        // The peripheral resets, so the link is lost, and it starts advertising again.
        spec.simulateReset()
        settle(10.seconds)

        assertEquals(ConnectionState.Connected, peripheral.state.value)
        assertEquals(2, requests.count)
    }

    @Test
    fun `auto connect peripheral waits for the device while it is out of range`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(requests)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        settle(60.seconds)

        assertEquals(ConnectionState.Connecting, peripheral.state.value)
        assertEquals(1, requests.count)
    }

    @Test
    fun `auto connect peripheral reconnects every time the link is lost`() = runTest {
        val spec = spec()
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())

        repeat(3) {
            spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
            settle(5.seconds)
            spec.simulateProximityChange(Proximity.NEAR)
            settle(10.seconds)

            assertEquals(ConnectionState.Connected, peripheral.state.value)
        }
    }

    @Test
    fun `auto connect peripheral keeps trying when the reconnection is denied`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(requests)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())

        requests.result = ConnectionResult.Deny
        spec.simulateReset()
        settle(10.seconds)
        assertEquals(ConnectionState.Connecting, peripheral.state.value)

        requests.result = ConnectionResult.Accept
        settle(10.seconds)
        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `auto connect peripheral does not reconnect after the client disconnected`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(requests)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())

        peripheral.disconnect()
        settle(10.seconds)

        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(1, requests.count)
    }

    @Test
    fun `auto connect peripheral does not reconnect after the manager is closed`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(requests)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        settle(5.seconds)
        manager.close()
        spec.simulateProximityChange(Proximity.NEAR)
        settle(10.seconds)

        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(1, requests.count)
    }

    @Test
    fun `direct connection does not reconnect after link loss`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(requests)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())

        spec.simulateReset()
        settle(10.seconds)

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.LinkLoss, state.reason)
        assertEquals(1, requests.count)
    }

    @Test
    fun `reset of the peripheral is reported as link loss and notifies the handler`() = runTest {
        val events = Events()
        val spec = spec(events)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        spec.simulateReset()
        settle(5.seconds)

        assertEquals(ConnectionState.Disconnected(Reason.LinkLoss), peripheral.state.value)
        assertEquals(1, events.resets)
        // onReset replaces onConnectionLost.
        assertTrue(events.connectionsLost.isEmpty())
    }

    @Test
    fun `disconnecting by the client notifies the handler with TerminatePeerUser`() = runTest {
        val events = Events()
        val manager = manager(spec(events))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.disconnect()

        assertEquals(listOf<DisconnectionReason>(DisconnectionReason.TerminatePeerUser), events.connectionsLost)
    }

    @Test
    fun `closing the manager disconnects its peripherals`() = runTest {
        val manager = manager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        manager.close()
        runCurrent()

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.TerminateLocalHost, state.reason)
    }

    @Test
    fun `cancelling the scope of the manager disconnects its peripherals`() = runTest {
        val scope = otherScope()
        val manager = manager(spec(), scope)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        scope.cancel()
        runCurrent()

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.TerminateLocalHost, state.reason)
    }

    @Test
    fun `turning Bluetooth off disconnects the peripherals`() = runTest {
        val environment = LatestApi()
        val spec = spec()
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
            .apply { simulatePeripherals(listOf(spec)) }
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        environment.simulatePowerOff()
        runCurrent()

        assertFalse(peripheral.isConnected)
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
    }

    @Test
    fun `connecting again after cancelling a connection right after it was accepted`() = runTest {
        val requests = ConnectionRequests()
        val spec = PeripheralSpec.simulatePeripheral(identifier = ADDRESS, addressType = AddressType.PUBLIC) {
            connectable(name = "Device", maxAttMtu = 247, eventHandler = requests) {}
            advertising(
                parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds),
            ) {
                CompleteLocalName("Device")
            }
        }
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val connecting = backgroundScope.launch {
            manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())
        }
        // Cancel as soon as the peripheral accepts the connection, before the MTU is negotiated.
        while (requests.count == 0) {
            advanceTimeBy(1.milliseconds)
        }
        connecting.cancel()
        // The MTU request of the cancelled connection is due after a few connection intervals.
        settle()

        manager.connect(peripheral, CentralManager.ConnectionOptions.AutoConnect())

        assertEquals(ConnectionState.Connected, peripheral.state.value)
        assertNotNull(spec.connectionParameters, "A new connection has connection parameters")
        // The MTU is negotiated on the new connection.
        assertEquals(247, peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE) + 3)
        assertEquals(247, spec.mtu)
    }

    @Test
    fun `connection parameters requested before disconnecting do not apply to the next connection`() = runTest {
        val spec = spec()
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        val initial = peripheral.connectionParameters.value
        spec.simulateConnectionParametersRequest(
            ConnectionParameters.Specified(connectionInterval = 80, latency = 4, supervisionTimeout = 1000)
        )
        peripheral.disconnect()
        settle()

        manager.connect(peripheral)

        assertEquals(initial, peripheral.connectionParameters.value)
    }
}

