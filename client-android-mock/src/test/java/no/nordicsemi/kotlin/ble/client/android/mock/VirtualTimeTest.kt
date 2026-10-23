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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.DisconnectionReason
import no.nordicsemi.kotlin.ble.client.mock.MockServerScope
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val ADDRESS = "CA:FE:00:00:00:01"
private const val OTHER_ADDRESS = "CA:FE:00:00:00:02"
private const val NAME = "Mock Device"
private val OLD_SERVICE = Uuid.fromShortUuid(0x180F)
private val NEW_SERVICE = Uuid.fromShortUuid(0x180D)

/**
 * Tests verifying that the whole simulation runs in the scope given to the mock central manager,
 * so that it can be driven by a test scheduler and its virtual time.
 */
class VirtualTimeTest {

    private fun connectableSpec(
        address: String = ADDRESS,
        advertiseWhenConnected: Boolean = false,
        eventHandler: PeripheralSpecEventHandler = object : PeripheralSpecEventHandler {},
        services: MockServerScope.() -> Unit = {},
    ): PeripheralSpec<String> =
        PeripheralSpec.simulatePeripheral(
            identifier = address,
            // Android assumes PUBLIC address type for devices that weren't scanned.
            addressType = AddressType.PUBLIC,
        ) {
            connectable(
                name = NAME,
                eventHandler = eventHandler,
                services = services,
            )
            advertising(
                parameters = LegacyAdvertisingSetParameters(
                    connectable = true,
                    interval = 1.seconds,
                ),
                isAdvertisingWhenConnected = advertiseWhenConnected,
            ) {
                CompleteLocalName(NAME)
            }
        }

    /**
     * Returns a scope on the test scheduler that can be cancelled on its own,
     * e.g. like a scope of a single screen.
     */
    private fun TestScope.otherScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    /** Returns the UUIDs of the discovered services. */
    private fun StateFlow<RemoteServices>.serviceUuids(): List<Uuid> =
        assertIs<RemoteServices.Discovered>(value).services.map { it.uuid }

    @Test
    fun `connect completes under virtual time`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(connectableSpec()))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        // A timeout set by the app counts on the virtual clock, just like the simulation.
        withTimeout(10.seconds) {
            manager.connect(peripheral)
        }

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `peripheral simulation timers run on virtual time`() = runTest {
        val spec = connectableSpec()
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        // Moving the device out of range drops the link after the supervision timeout,
        // which defaults to 400 × 10 ms = 4 s.
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)

        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(ConnectionState.Connected, peripheral.state.value)

        advanceTimeBy(2.seconds)
        runCurrent()
        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(ConnectionState.Disconnected.Reason.LinkLoss, state.reason)
    }

    @Test
    fun `tearDownSimulation cancels pending simulation work`() = runTest {
        // Records which peripherals lost their connection.
        val connectionsLost = mutableListOf<String>()
        fun trackedSpec(address: String) = connectableSpec(
            address = address,
            eventHandler = object : PeripheralSpecEventHandler {
                override fun onConnectionLost(peripheral: MockPeripheral, reason: DisconnectionReason) {
                    connectionsLost += address
                }
            },
        )
        val spec = trackedSpec(ADDRESS)
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))
        manager.connect(checkNotNull(manager.getPeripheralById(ADDRESS)))

        // A control peripheral, simulated by another manager that is not torn down.
        val controlSpec = trackedSpec(OTHER_ADDRESS)
        val otherManager = CentralManager.mock(scope = backgroundScope)
        otherManager.simulatePeripherals(listOf(controlSpec))
        otherManager.connect(checkNotNull(otherManager.getPeripheralById(OTHER_ADDRESS)))

        // Schedule a link loss on both peripherals, due in 4 s,
        // and tear down the simulation of the first one before it happens.
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        controlSpec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        manager.tearDownSimulation()

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(listOf(OTHER_ADDRESS), connectionsLost)
    }

    @Test
    fun `peripheral is still simulated after another manager's scope is cancelled`() = runTest {
        val spec = connectableSpec()
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))

        // Another manager simulates the same peripheral, e.g. one created for a single screen,
        // and its scope gets cancelled without tearing the simulation down.
        val otherScope = otherScope()
        CentralManager.mock(scope = otherScope).simulatePeripherals(listOf(spec))
        otherScope.cancel()

        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
    }

    @Test
    fun `tearing down one manager leaves the peripheral simulated by the others`() = runTest {
        val spec = connectableSpec()
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        // Another manager simulates the same peripheral and then tears its simulation down.
        val otherManager = CentralManager.mock(scope = otherScope())
        otherManager.simulatePeripherals(listOf(spec))
        otherManager.tearDownSimulation()

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
    }

    @Test
    fun `managers connected to the same peripheral all lose the link`() = runTest {
        val spec = connectableSpec(advertiseWhenConnected = true)
        val manager = CentralManager.mock(scope = backgroundScope)
        val otherManager = CentralManager.mock(scope = otherScope())
        manager.simulatePeripherals(listOf(spec))
        otherManager.simulatePeripherals(listOf(spec))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val otherPeripheral = checkNotNull(otherManager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        otherManager.connect(otherPeripheral)

        // A single link-loss timer runs for the peripheral, and both managers get notified.
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertIs<ConnectionState.Disconnected>(otherPeripheral.state.value)
    }

    @Test
    fun `connection gets new services after the newest manager is torn down`() = runTest {
        val spec = connectableSpec {
            GenericAttributeService()
            Service(OLD_SERVICE)
        }
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))

        // Another manager starts simulating the same peripheral before the connection is made.
        val otherManager = CentralManager.mock(scope = otherScope())
        otherManager.simulatePeripherals(listOf(spec))

        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        val services = peripheral.services()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertContains(services.serviceUuids(), OLD_SERVICE)

        // The other manager goes away while the connection is still in use,
        // and then the peripheral changes its services, indicating it with Service Changed.
        otherManager.tearDownSimulation()
        spec.simulateServiceChange {
            GenericAttributeService()
            Service(NEW_SERVICE)
        }

        // The connection discovers the services again, instead of getting the old ones from cache.
        advanceTimeBy(1.seconds)
        runCurrent()
        assertContains(services.serviceUuids(), NEW_SERVICE)
    }

    @Test
    fun `pending link loss reaches older manager when the newest manager's scope is cancelled`() = runTest {
        val spec = connectableSpec(advertiseWhenConnected = true)
        val manager = CentralManager.mock(scope = backgroundScope)
        val otherScope = otherScope()
        val otherManager = CentralManager.mock(scope = otherScope)
        manager.simulatePeripherals(listOf(spec))
        otherManager.simulatePeripherals(listOf(spec))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        // The link loss is scheduled in the newest manager, which goes away before it happens.
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        advanceTimeBy(1.seconds)
        otherScope.cancel()

        advanceTimeBy(5.seconds)
        runCurrent()
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
    }

    @Test
    fun `pending link loss reaches older manager when the newest manager is torn down`() = runTest {
        val spec = connectableSpec()
        val manager = CentralManager.mock(scope = backgroundScope)
        val otherManager = CentralManager.mock(scope = otherScope())
        manager.simulatePeripherals(listOf(spec))
        otherManager.simulatePeripherals(listOf(spec))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        spec.simulateReset()
        otherManager.tearDownSimulation()

        advanceTimeBy(5.seconds)
        runCurrent()
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
    }
}
