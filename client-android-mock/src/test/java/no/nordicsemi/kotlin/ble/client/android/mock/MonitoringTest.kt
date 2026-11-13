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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.MonitoringEvent
import no.nordicsemi.kotlin.ble.client.PeripheralEnteredRange
import no.nordicsemi.kotlin.ble.client.PeripheralLeftRange
import no.nordicsemi.kotlin.ble.client.ProximityChanged
import no.nordicsemi.kotlin.ble.client.ProximityChanged.Proximity.FAR
import no.nordicsemi.kotlin.ble.client.ProximityChanged.Proximity.IMMEDIATE
import no.nordicsemi.kotlin.ble.client.ProximityChanged.Proximity.NEAR
import no.nordicsemi.kotlin.ble.client.ProximityChanged.Proximity.UNKNOWN
import no.nordicsemi.kotlin.ble.client.RangeEvent
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.mock.internal.MockCentralManagerImpl
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"
private const val OTHER_ADDRESS = "CA:FE:00:00:00:02"

/**
 * Tests of [CentralManager.monitor] and [CentralManager.range].
 */
class MonitoringTest {

    private fun spec(
        address: String = ADDRESS,
        name: String = "Device",
        proximity: Proximity = Proximity.IMMEDIATE,
    ): PeripheralSpec<String> = PeripheralSpec.simulatePeripheral(
        identifier = address,
        addressType = AddressType.PUBLIC,
        proximity = proximity,
    ) {
        advertising(
            parameters = LegacyAdvertisingSetParameters(connectable = false, interval = 1.seconds),
        ) {
            CompleteLocalName(name)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.monitor(
        manager: CentralManager,
        filter: no.nordicsemi.kotlin.ble.client.android.ConjunctionFilterScope.() -> Unit = {},
    ): List<MonitoringEvent<Peripheral>> {
        val events = mutableListOf<MonitoringEvent<Peripheral>>()
        backgroundScope.launch { manager.monitor(filter = filter).collect { events += it } }
        runCurrent()
        return events
    }

    private fun kotlinx.coroutines.test.TestScope.range(
        manager: CentralManager,
        peripheral: Peripheral,
    ): List<RangeEvent<Peripheral>> {
        val events = mutableListOf<RangeEvent<Peripheral>>()
        backgroundScope.launch { manager.range(peripheral).collect { events += it } }
        runCurrent()
        return events
    }

    private fun List<RangeEvent<Peripheral>>.transitions() =
        map { assertIs<ProximityChanged<Peripheral>>(it); it.previousProximity to it.proximity }

    // Monitoring

    @Test
    fun `monitor reports a peripheral entering the range once`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec()))
        val events = monitor(manager)

        advanceTimeBy(5.seconds)

        assertEquals(1, events.size)
        assertIs<PeripheralEnteredRange<Peripheral>>(events[0])
        assertEquals(ADDRESS, events[0].peripheral.identifier)
    }

    @Test
    fun `monitor reports a peripheral leaving the range after 10 seconds without packets`() = runTest {
        val spec = spec()
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))
        val events = monitor(manager)
        advanceTimeBy(3.seconds)

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        advanceTimeBy(8.seconds)
        assertEquals(1, events.size)

        advanceTimeBy(5.seconds)
        assertEquals(2, events.size)
        assertIs<PeripheralLeftRange<Peripheral>>(events[1])
    }

    @Test
    fun `monitor reports a peripheral that returned to the range again`() = runTest {
        val spec = spec()
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))
        val events = monitor(manager)
        advanceTimeBy(3.seconds)
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        advanceTimeBy(15.seconds)

        spec.simulateProximityChange(Proximity.NEAR)
        advanceTimeBy(3.seconds)

        assertEquals(
            listOf("entered", "left", "entered"),
            events.map { if (it is PeripheralEnteredRange) "entered" else "left" },
        )
    }

    @Test
    fun `monitor tracks peripherals independently`() = runTest {
        val first = spec(ADDRESS)
        val second = spec(OTHER_ADDRESS)
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(first, second))
        val events = monitor(manager)
        advanceTimeBy(3.seconds)

        second.simulateProximityChange(Proximity.OUT_OF_RANGE)
        advanceTimeBy(15.seconds)

        assertEquals(3, events.size)
        assertIs<PeripheralLeftRange<Peripheral>>(events.last())
        assertEquals(OTHER_ADDRESS, events.last().peripheral.identifier)
    }

    @Test
    fun `monitor applies the filter`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec(ADDRESS, "Wanted"), spec(OTHER_ADDRESS, "Other")))
        val events = monitor(manager) { Name("Wanted") }

        advanceTimeBy(5.seconds)

        assertEquals(listOf(ADDRESS), events.map { it.peripheral.identifier })
    }

    @Test
    fun `monitor completes when the timeout expires`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec()))

        val events = manager.monitor(timeout = 5.seconds) {}.toList()

        assertEquals(1, events.size)
        assertTrue(testScheduler.currentTime in 5_000..6_000)
    }

    // Ranging

    @Test
    fun `range reports the proximity of the peripheral`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec(proximity = Proximity.IMMEDIATE)))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val events = range(manager, peripheral)

        advanceTimeBy(5.seconds)

        assertEquals(listOf(UNKNOWN to IMMEDIATE), events.transitions())
        assertEquals(ADDRESS, events[0].peripheral.identifier)
    }

    @Test
    fun `range reports changes of the proximity`() = runTest {
        val spec = spec(proximity = Proximity.IMMEDIATE)
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val events = range(manager, peripheral)
        advanceTimeBy(5.seconds)

        spec.simulateProximityChange(Proximity.NEAR)
        advanceTimeBy(10.seconds)
        assertEquals(listOf(UNKNOWN to IMMEDIATE, IMMEDIATE to NEAR), events.transitions())

        spec.simulateProximityChange(Proximity.FAR)
        advanceTimeBy(10.seconds)
        assertEquals(NEAR to FAR, events.transitions().last())
    }

    @Test
    fun `range ignores other peripherals`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec(ADDRESS, proximity = Proximity.NEAR), spec(OTHER_ADDRESS, proximity = Proximity.IMMEDIATE)))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val events = range(manager, peripheral)

        advanceTimeBy(10.seconds)

        assertEquals(listOf(UNKNOWN to NEAR), events.transitions())
    }

    @Test
    fun `range closes the flow when the peripheral leaves the range`() = runTest {
        val spec = spec()
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val events = mutableListOf<RangeEvent<Peripheral>>()
        var completed = false
        backgroundScope.launch {
            manager.range(peripheral).collect { events += it }
            completed = true
        }
        advanceTimeBy(3.seconds)

        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        advanceTimeBy(8.seconds)
        assertTrue(!completed)
        advanceTimeBy(5.seconds)

        assertTrue(completed)
        assertEquals(listOf(UNKNOWN to IMMEDIATE, IMMEDIATE to UNKNOWN), events.transitions())
    }

    @Test
    fun `range completes without events when the peripheral is not around`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec(proximity = Proximity.OUT_OF_RANGE)))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        val events = manager.range(peripheral).toList()

        assertEquals(emptyList(), events)
        assertEquals(10_000, testScheduler.currentTime)
    }

    @Test
    fun `range completes when the timeout expires`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec()))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        val events = manager.range(peripheral, timeout = 5.seconds).toList()

        assertEquals(listOf(UNKNOWN to IMMEDIATE), events.transitions())
    }

    @Test
    fun `range of a peripheral from another manager fails`() = runTest {
        val first = CentralManager.mock(scope = backgroundScope)
        first.simulatePeripherals(listOf(spec()))
        val second = CentralManager.mock(scope = backgroundScope)
        second.simulatePeripherals(listOf(spec()))
        val peripheral = checkNotNull(first.getPeripheralById(ADDRESS))

        assertFailsWith<IllegalArgumentException> { second.range(peripheral).toList() }
    }

    // Shared ranging scan

    private val CentralManager.activeScans: Int
        get() = (this as MockCentralManagerImpl).activeScans.value

    private fun addresses(count: Int) = (1..count).map { "CA:FE:00:00:01:%02X".format(it) }

    @Test
    fun `ranging many peripherals uses a single scan`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        val addresses = addresses(10)
        manager.simulatePeripherals(addresses.map { spec(it) })
        val events = addresses.associateWith { address ->
            range(manager, checkNotNull(manager.getPeripheralById(address)))
        }

        advanceTimeBy(5.seconds)

        assertEquals(1, manager.activeScans)
        events.forEach { (address, list) ->
            assertEquals(listOf(UNKNOWN to IMMEDIATE), list.transitions(), address)
        }
    }

    @Test
    fun `monitoring and ranging use two scans`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        val addresses = addresses(5)
        manager.simulatePeripherals(addresses.map { spec(it) })
        monitor(manager)
        addresses.forEach { range(manager, checkNotNull(manager.getPeripheralById(it))) }

        advanceTimeBy(3.seconds)

        assertEquals(2, manager.activeScans)
    }

    @Test
    fun `ranging scan stops when the last range is cancelled`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec(ADDRESS), spec(OTHER_ADDRESS)))
        val jobs = listOf(ADDRESS, OTHER_ADDRESS).map { address ->
            val peripheral = checkNotNull(manager.getPeripheralById(address))
            backgroundScope.launch { manager.range(peripheral).collect { } }
        }
        advanceTimeBy(3.seconds)
        assertEquals(1, manager.activeScans)

        jobs[0].cancel()
        advanceTimeBy(1.seconds)
        assertEquals(1, manager.activeScans)

        jobs[1].cancel()
        advanceTimeBy(1.seconds)
        assertEquals(0, manager.activeScans)
    }

    @Test
    fun `ranging started after the previous ones finished starts a new scan`() = runTest {
        val spec = spec()
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        assertEquals(listOf(UNKNOWN to IMMEDIATE), manager.range(peripheral, timeout = 3.seconds).toList().transitions())
        assertEquals(0, manager.activeScans)

        val events = range(manager, peripheral)
        advanceTimeBy(3.seconds)

        assertEquals(1, manager.activeScans)
        assertEquals(listOf(UNKNOWN to IMMEDIATE), events.transitions())
    }

    @Test
    fun `ranging fails when the scan cannot be started`() = runTest {
        val environment = MockAndroidEnvironment.Api31(isBluetoothScanPermissionGranted = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        manager.simulatePeripherals(listOf(spec()))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<SecurityException> { manager.range(peripheral).toList() }
        assertEquals(0, manager.activeScans)
    }
}
