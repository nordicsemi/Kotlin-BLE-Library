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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager.ConnectionOptions
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.ConnectionResult
import no.nordicsemi.kotlin.ble.client.mock.DisconnectionReason
import no.nordicsemi.kotlin.ble.client.mock.MockServerScope
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import org.jetbrains.annotations.Range
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"
private const val OTHER_ADDRESS = "CA:FE:00:00:00:02"
private const val UNKNOWN_ADDRESS = "CA:FE:00:00:00:99"

/**
 * Tests of the lifecycle of the central manager, retrieving peripherals from it, and of
 * connections shared between managers and other applications.
 */
class ConnectionManagerLifecycleTest {

    private class Events : PeripheralSpecEventHandler {
        var connectionRequests = 0
        val phys = mutableListOf<PrimaryPhy>()
        val connectionsLost = mutableListOf<DisconnectionReason>()
        var result: ConnectionResult = ConnectionResult.Accept

        override fun onConnectionRequest(peripheral: MockPeripheral, phy: PrimaryPhy): ConnectionResult {
            connectionRequests++
            phys += phy
            return result
        }

        override fun onConnectionLost(peripheral: MockPeripheral, reason: DisconnectionReason) {
            connectionsLost += reason
        }
    }

    /** Creates a spec of a device that is already connected to another application. */
    private fun spec(
        address: String = ADDRESS,
        maxAttMtu: @Range(from = 23, to = 517) Int = 23,
        isPhyLe2MSupported: Boolean = true,
        isPhyCodedSupported: Boolean = true,
        events: Events = Events(),
        services: MockServerScope.() -> Unit = {},
    ): PeripheralSpec<String> = PeripheralSpec
        .simulatePeripheral(
            identifier = address,
            addressType = AddressType.PUBLIC,
        ) {
            connectable(
                name = "Device",
                maxAttMtu = maxAttMtu,
                isPhyLe2MSupported = isPhyLe2MSupported,
                isPhyCodedSupported = isPhyCodedSupported,
                eventHandler = events,
                services = services,
            )
            advertising(
                parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds),
            ) {
                CompleteLocalName("Device")
            }
        }

    /** Creates a spec of a device that is already connected to another application. */
    private fun initiallyConnectedSpec(
        address: String = ADDRESS,
        maxAttMtu: @Range(from = 23, to = 517) Int = 23,
        isPhyLe2MSupported: Boolean = true,
        isPhyCodedSupported: Boolean = true,
        events: Events = Events(),
    ): PeripheralSpec<String> = PeripheralSpec
        .simulatePeripheral(
            identifier = address,
            addressType = AddressType.PUBLIC
        ) {
            connected(
                name = "Device",
                maxAttMtu = maxAttMtu,
                isPhyLe2MSupported = isPhyLe2MSupported,
                isPhyCodedSupported = isPhyCodedSupported,
                eventHandler = events,
                cachedServices = {}
            )
        }

    private fun TestScope.newManager(vararg specs: PeripheralSpec<String>): MockCentralManager =
        CentralManager.mock(scope = backgroundScope).apply { simulatePeripherals(specs.toList()) }

    private fun TestScope.newManagerInOwnScope(spec: PeripheralSpec<String>): MockCentralManager {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        return CentralManager.mock(scope = scope).apply { simulatePeripherals(listOf(spec)) }
    }

    // ---- Retrieving peripherals ----

    @Test
    fun `peripheral retrieved twice by the same id is the same instance`() = runTest {
        val manager = newManager(spec())

        assertSame(manager.getPeripheralById(ADDRESS), manager.getPeripheralById(ADDRESS))
    }

    @Test
    fun `peripherals retrieved by ids are returned in the requested order`() = runTest {
        val manager = newManager(spec(ADDRESS), spec(OTHER_ADDRESS))
        val peripherals = manager.getPeripheralsById(listOf(OTHER_ADDRESS, ADDRESS))

        assertEquals(listOf(OTHER_ADDRESS, ADDRESS), peripherals.map { it.address })
    }

    @Test
    fun `peripheral that is not simulated can be retrieved by its address`() = runTest {
        val manager = newManager(spec())
        val peripheral = manager.getPeripheralById(UNKNOWN_ADDRESS)

        assertNotNull(peripheral)
        assertEquals(ConnectionState.Disconnected(null), peripheral.state.value)
    }

    @Test
    fun `connecting to a peripheral that is not simulated times out`() = runTest {
        val manager = newManager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(UNKNOWN_ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        }
    }

    @Test
    fun `retrieving a peripheral with an address of wrong length throws`() = runTest {
        val manager = newManager(spec())

        assertFailsWith<IllegalArgumentException> { manager.getPeripheralById("CA:FE:00") }
    }

    @Test
    fun `retrieving a peripheral with an address with no colons throws`() = runTest {
        val manager = newManager(spec())

        assertFailsWith<IllegalArgumentException> { manager.getPeripheralById("CAFE00000001XXXXX") }
    }

    @Test
    fun `retrieving a peripheral with non-hexadecimal characters in the address throws`() = runTest {
        val manager = newManager(spec())

        assertFailsWith<IllegalArgumentException> { manager.getPeripheralById("CA:FE:00:00:00:ZZ") }
    }

    @Test
    fun `retrieving a peripheral with a lower case address throws`() = runTest {
        val manager = newManager(spec())

        assertFailsWith<IllegalArgumentException> { manager.getPeripheralById("ca:fe:00:00:00:01") }
    }

    @Test
    fun `retrieving peripherals fails when any address is invalid`() = runTest {
        val manager = newManager(spec())

        assertFailsWith<IllegalArgumentException> {
            manager.getPeripheralsById(listOf(ADDRESS, "invalid address!!!"))
        }
    }

    // ---- Closing the manager ----

    @Test
    fun `connecting through a closed manager throws ManagerClosedException`() = runTest {
        val manager = newManager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.close()

        assertFailsWith<ManagerClosedException> { manager.connect(peripheral) }
    }

    @Test
    fun `retrieving a peripheral from a closed manager throws ManagerClosedException`() = runTest {
        val manager = newManager(spec())

        manager.close()

        assertFailsWith<ManagerClosedException> { manager.getPeripheralById(ADDRESS) }
    }

    @Test
    fun `closing the manager twice has no effect`() = runTest {
        val manager = newManager(spec())

        manager.close()
        manager.close()
    }

    @Test
    fun `peripheral of a closed manager stays disconnected`() = runTest {
        val manager = newManager(spec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        manager.close()
        runCurrent()

        assertFalse(peripheral.isConnected)
    }

    @Test
    fun `closing the manager disconnects all its peripherals`() = runTest {
        val manager = newManager(spec(ADDRESS), spec(OTHER_ADDRESS))
        val first = checkNotNull(manager.getPeripheralById(ADDRESS))
        val second = checkNotNull(manager.getPeripheralById(OTHER_ADDRESS))
        manager.connect(first)
        manager.connect(second)

        manager.close()
        runCurrent()

        assertFalse(first.isConnected)
        assertFalse(second.isConnected)
    }

    @Test
    fun `closing the manager does not cancel the scope given to it`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val manager = CentralManager.mock(scope = scope).apply { simulatePeripherals(listOf(spec())) }

        manager.close()
        runCurrent()

        assertTrue(scope.isActive)
    }

    @Test
    fun `closing the manager stops the advertising of the simulated peripherals`() = runTest {
        val manager = newManager(spec())
        var results = 0
        backgroundScope.launch { manager.scan().collect { results++ } }
        // The peripheral advertises every 100 ms.
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(results > 0)

        manager.close()
        runCurrent()
        val resultsAfterClose = results
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(resultsAfterClose, results)
    }

    @Test
    fun `closing the manager releases the connection when close returns`() = runTest {
        val events = Events()
        val spec = spec(events = events)
        val manager = newManager(spec)
        manager.connect(checkNotNull(manager.getPeripheralById(ADDRESS)))

        // No time passes, and the dispatcher is not run.
        manager.close()

        assertFalse(spec.isConnected)
        assertEquals(1, events.connectionsLost.size)
    }

    @Test
    fun `closing the manager lets the device know that the client has disconnected`() = runTest {
        val events = Events()
        val spec = spec(events = events)
        val manager = newManager(spec)
        manager.connect(checkNotNull(manager.getPeripheralById(ADDRESS)))

        manager.close()
        runCurrent()

        assertFalse(spec.isConnected)
        assertEquals(listOf<DisconnectionReason>(DisconnectionReason.TerminatePeerUser), events.connectionsLost)
    }

    @Test
    fun `device stays connected to another manager when a manager is closed`() = runTest {
        val events = Events()
        val spec = spec(events = events)
        val manager = newManager(spec)
        val otherManager = newManager(spec)
        manager.connect(checkNotNull(manager.getPeripheralById(ADDRESS)))
        otherManager.connect(checkNotNull(otherManager.getPeripheralById(ADDRESS)))

        manager.close()
        runCurrent()

        assertTrue(spec.isConnected)
        assertTrue(events.connectionsLost.isEmpty())

        // The device is told when the last client is gone.
        otherManager.close()
        runCurrent()

        assertFalse(spec.isConnected)
        assertEquals(1, events.connectionsLost.size)
    }

    @Test
    fun `closing the manager after the client disconnected does not release the connection of another client`() = runTest {
        val spec = spec()
        val manager = newManager(spec)
        val otherManager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        otherManager.connect(checkNotNull(otherManager.getPeripheralById(ADDRESS)))

        peripheral.disconnect()
        runCurrent()
        manager.close()
        runCurrent()

        assertTrue(spec.isConnected)
    }

    @Test
    fun `closing the manager after the connection ended does not release a new connection`() = runTest {
        val spec = spec()
        val manager = newManager(spec)
        val otherManager = newManager(spec)
        manager.connect(checkNotNull(manager.getPeripheralById(ADDRESS)))

        // The device terminates the connection, and then another client connects.
        spec.simulateDisconnection()
        runCurrent()
        otherManager.connect(checkNotNull(otherManager.getPeripheralById(ADDRESS)))
        runCurrent()

        manager.close()
        runCurrent()

        assertTrue(spec.isConnected)
    }

    // ---- Events of a single client ----

    @Test
    fun `disconnecting one client does not disconnect the other clients`() = runTest {
        val spec = spec()
        val manager = newManager(spec)
        val otherManager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val otherPeripheral = checkNotNull(otherManager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        otherManager.connect(otherPeripheral)

        peripheral.disconnect()
        runCurrent()

        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(ConnectionState.Connected, otherPeripheral.state.value)
    }

    @Test
    fun `service discovery is reported only to the client that requested it`() = runTest {
        val spec = spec { GenericAttributeService() }
        val manager = newManager(spec)
        val otherManager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val otherPeripheral = checkNotNull(otherManager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        otherManager.connect(otherPeripheral)

        val services = peripheral.services()
        advanceTimeBy(5.seconds)
        runCurrent()

        assertIs<RemoteServices.Discovered>(services.value)
        assertEquals(RemoteServices.Unknown, otherPeripheral.services().value)
    }

    @Test
    fun `link loss is reported to all the clients`() = runTest {
        val spec = spec()
        val manager = newManager(spec)
        val otherManager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val otherPeripheral = checkNotNull(otherManager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        otherManager.connect(otherPeripheral)

        spec.simulateReset()
        advanceTimeBy(10.seconds)
        runCurrent()

        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertIs<ConnectionState.Disconnected>(otherPeripheral.state.value)
    }

    // ---- Several peripherals and managers ----

    @Test
    fun `peripherals of one manager are connected independently`() = runTest {
        val manager = newManager(spec(ADDRESS), spec(OTHER_ADDRESS))
        val first = checkNotNull(manager.getPeripheralById(ADDRESS))
        val second = checkNotNull(manager.getPeripheralById(OTHER_ADDRESS))

        awaitAll(async { manager.connect(first) }, async { manager.connect(second) })
        first.disconnect()

        assertEquals(ConnectionState.Disconnected(Reason.Success), first.state.value)
        assertEquals(ConnectionState.Connected, second.state.value)
    }

    @Test
    fun `device stays connected until all managers disconnect`() = runTest {
        val events = Events()
        val spec = spec(events = events)
        val manager = newManager(spec)
        val otherManager = newManagerInOwnScope(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val otherPeripheral = checkNotNull(otherManager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        otherManager.connect(otherPeripheral)

        peripheral.disconnect()

        assertTrue(spec.isConnected)
        assertTrue(events.connectionsLost.isEmpty())
        assertEquals(ConnectionState.Connected, otherPeripheral.state.value)
    }

    @Test
    fun `device is disconnected after the last manager disconnects`() = runTest {
        val events = Events()
        val spec = spec(events = events)
        val manager = newManager(spec)
        val otherManager = newManagerInOwnScope(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val otherPeripheral = checkNotNull(otherManager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        otherManager.connect(otherPeripheral)

        peripheral.disconnect()
        otherPeripheral.disconnect()

        assertFalse(spec.isConnected)
        assertEquals(listOf<DisconnectionReason>(DisconnectionReason.TerminatePeerUser), events.connectionsLost)
    }

    @Test
    fun `device is asked for the connection only once when two managers connect`() = runTest {
        val events = Events()
        val spec = spec(events = events)
        val manager = newManager(spec)
        val otherManager = newManagerInOwnScope(spec)
        manager.connect(checkNotNull(manager.getPeripheralById(ADDRESS)))
        otherManager.connect(checkNotNull(otherManager.getPeripheralById(ADDRESS)))

        assertEquals(1, events.connectionRequests)
    }

    @Test
    fun `peripheral reports the primary phy of the advertisement in the connection request`() = runTest {
        val events = Events()
        val manager = newManager(spec(events = events))

        manager.connect(checkNotNull(manager.getPeripheralById(ADDRESS)))

        assertEquals(listOf(PrimaryPhy.PHY_LE_1M), events.phys)
    }

    // ---- Connections made by other applications ----

    @Test
    fun `connection can be made to a device connected by another application without advertising`() = runTest {
        val events = Events()
        // The device doesn't advertise when connected.
        val spec = spec(events = events)
        val manager = newManager(spec)
        spec.simulateConnection()
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 1.seconds))

        assertEquals(ConnectionState.Connected, peripheral.state.value)
        // The device was asked for the connection only by the other application.
        assertEquals(1, events.connectionRequests)
    }

    @Test
    fun `device stays connected to another application after the client disconnects`() = runTest {
        val events = Events()
        val spec = spec(events = events)
        val manager = newManager(spec)
        spec.simulateConnection()
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.disconnect()

        assertEquals(ConnectionState.Disconnected(Reason.Success), peripheral.state.value)
        assertTrue(spec.isConnected)
        assertTrue(events.connectionsLost.isEmpty())
    }

    @Test
    fun `connection from another application denied by the device is not established`() = runTest {
        val events = Events().apply { result = ConnectionResult.Deny }
        val spec = spec(events = events)
        val _ = newManager(spec)

        spec.simulateConnection()

        assertFalse(spec.isConnected)
    }

    @Test
    fun `device connected by another application is disconnected by the device`() = runTest {
        val spec = spec()
        val manager = newManager(spec)
        spec.simulateConnection()
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        spec.simulateDisconnection()
        runCurrent()

        assertEquals(ConnectionState.Disconnected(Reason.TerminatePeerUser), peripheral.state.value)
    }

    @Test
    fun `connection can be made to a device that was initially connected`() = runTest {
        val manager = newManager(initiallyConnectedSpec())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `mtu of a device that was initially connected is the one of the connection`() = runTest {
        val mtu = 64
        val spec = initiallyConnectedSpec(maxAttMtu = mtu)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        // mtu is a StateFlow derived from events, it is updated after connect() returns.
        runCurrent()

        assertEquals(mtu, spec.mtu)
        assertEquals(mtu, peripheral.mtu.value)
    }

    @Test
    fun `phy of a device that was initially connected is the one of the connection`() = runTest {
        val spec = initiallyConnectedSpec()
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        // phy is a StateFlow derived from events, it is updated after connect() returns.
        runCurrent()

        assertEquals(spec.phy, peripheral.phy.value)
    }

    @Test
    fun `mtu of a device connected by another application is the one of that connection`() = runTest {
        val mtu = 185
        val spec = initiallyConnectedSpec(maxAttMtu = mtu)
        val manager = newManager(spec)
        spec.simulateConnection(mtu = mtu)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        // mtu is a StateFlow derived from events, it is updated after connect() returns.
        runCurrent()

        // MTU is exchanged once per connection, so the client gets the value negotiated before.
        assertEquals(mtu, spec.mtu)
        assertEquals(mtu, peripheral.mtu.value)
    }

    @Test
    fun `phy of a device connected by another application is the one of that connection`() = runTest {
        val spec = spec()// initiallyConnectedSpec()
        val manager = newManager(spec)
        spec.simulateConnection(phy = PrimaryPhy.PHY_LE_CODED)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        // phy is a StateFlow derived from events, it is updated after connect() returns.
        runCurrent()

        val coded = PhyInUse(Phy.PHY_LE_CODED, Phy.PHY_LE_CODED)
        assertEquals(coded, spec.phy)
        assertEquals(coded, peripheral.phy.value)
    }

    @Test
    fun `device that was initially connected is not asked for the connection`() = runTest {
        val events = Events()
        val spec = initiallyConnectedSpec(events = events)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        runCatching { manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds)) }
        advanceTimeBy(1.seconds)

        assertEquals(0, events.connectionRequests)
    }

    @Test
    fun `peripheral can be connected again after the device disconnected`() = runTest {
        val spec = spec()
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        spec.simulateDisconnection()
        runCurrent()

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }
}
