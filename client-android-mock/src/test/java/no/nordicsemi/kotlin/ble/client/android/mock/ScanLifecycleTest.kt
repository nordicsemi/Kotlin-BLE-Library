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

package no.nordicsemi.kotlin.ble.client.android.mock

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.ConjunctionFilterScope
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.android.exception.ScanningFailedToStartException
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"
private const val OTHER_ADDRESS = "CA:FE:00:00:00:02"

/**
 * Tests of the lifecycle of `CentralManager.scan()`: starting, stopping, timeout, failures
 * and environment conditions required for scanning.
 */
class ScanLifecycleTest {

    /** A non-connectable device advertising the given name at the given interval. */
    private fun sensor(
        address: String = ADDRESS,
        name: String = "Sensor",
        interval: Duration = 100.milliseconds,
    ) = PeripheralSpec.simulatePeripheral(address) {
        advertising(
            parameters = LegacyAdvertisingSetParameters(connectable = false, interval = interval),
        ) {
            CompleteLocalName(name)
        }
    }

    private class Collector(val job: Job, val results: List<ScanResult>)

    /** Starts collecting a scan in the background and lets it start. */
    private fun TestScope.collect(
        manager: CentralManager,
        timeout: Duration = Duration.INFINITE,
        filter: ConjunctionFilterScope.() -> Unit = {},
    ): Collector {
        val results = mutableListOf<ScanResult>()
        val job = backgroundScope.launch {
            manager.scan(timeout, filter).collect { results += it }
        }
        runCurrent()
        return Collector(job, results)
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    // ---- Timeout ----

    @Test
    fun `scan with a timeout ends after that time`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))

        val results = manager.scan(timeout = 2.seconds).toList()

        assertEquals(2000L, testScheduler.currentTime)
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `scan with a timeout reports nothing after the timeout`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        val scan = collect(manager, timeout = 1.seconds)

        advance(1.5.seconds)
        val countAtTimeout = scan.results.size
        advance(10.seconds)

        assertTrue(scan.job.isCompleted)
        assertTrue(countAtTimeout > 0)
        assertEquals(countAtTimeout, scan.results.size)
    }

    @Test
    fun `scan without a timeout keeps scanning`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor(interval = 1.seconds)))
        val scan = collect(manager)

        advance(30.minutes)

        assertTrue(scan.job.isActive)
        assertTrue(scan.results.size > 1000)
    }

    @Test
    fun `scan with a timeout and no devices still ends`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)

        val results = manager.scan(timeout = 3.seconds).toList()

        assertEquals(emptyList(), results)
        assertEquals(3000L, testScheduler.currentTime)
    }

    // ---- Cancelling ----

    @Test
    fun `cancelling the collector stops delivering results`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        val scan = collect(manager)
        advance(1.seconds)
        assertTrue(scan.results.isNotEmpty())

        scan.job.cancel()
        runCurrent()
        val countAtCancel = scan.results.size
        advance(5.seconds)

        assertEquals(countAtCancel, scan.results.size)
    }

    @Test
    fun `cancelling one scan does not stop another scan`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        val first = collect(manager)
        val second = collect(manager)
        advance(1.seconds)

        first.job.cancel()
        runCurrent()
        val secondCountAtCancel = second.results.size
        advance(1.seconds)

        assertTrue(second.job.isActive)
        assertTrue(second.results.size > secondCountAtCancel)
    }

    @Test
    fun `cancelling a scan does not stop the devices from advertising`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        collect(manager).job.cancel()
        advance(5.seconds)

        // A new scan, started later, still sees the device.
        val later = collect(manager)
        advance(1.seconds)

        assertTrue(later.results.isNotEmpty())
    }

    // ---- Concurrent and sequential scans ----

    @Test
    fun `two concurrent scans both get results of the same device`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        val first = collect(manager)
        val second = collect(manager)

        advance(1.seconds)

        assertTrue(first.results.isNotEmpty())
        assertEquals(first.results.size, second.results.size)
    }

    @Test
    fun `two concurrent scans with different filters each get their own devices`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(
            sensor(ADDRESS, name = "First"),
            sensor(OTHER_ADDRESS, name = "Second"),
        ))
        val first = collect(manager) { Name("First") }
        val second = collect(manager) { Name("Second") }

        advance(1.seconds)

        assertEquals(setOf(ADDRESS), first.results.map { it.peripheral.identifier }.toSet())
        assertEquals(setOf(OTHER_ADDRESS), second.results.map { it.peripheral.identifier }.toSet())
    }

    @Test
    fun `scanning again after the first scan timed out reports devices again`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))

        val first = manager.scan(timeout = 1.seconds).toList()
        val second = manager.scan(timeout = 1.seconds).toList()

        assertTrue(first.isNotEmpty())
        assertTrue(second.isNotEmpty())
        assertEquals(2000L, testScheduler.currentTime)
    }

    @Test
    fun `scanning again after the first scan was cancelled reports devices again`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        val first = collect(manager)
        advance(1.seconds)
        first.job.cancel()
        runCurrent()

        val second = collect(manager)
        advance(1.seconds)

        assertTrue(second.results.isNotEmpty())
    }

    @Test
    fun `a scan does not report advertisements from before it started`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor(interval = 1.seconds)))
        advance(5.5.seconds)

        val scan = collect(manager)

        // The device advertised several times, but nobody was listening.
        assertEquals(emptyList(), scan.results)
        advance(1.seconds)
        assertTrue(scan.results.isNotEmpty())
    }

    @Test
    fun `no results are reported after the simulation was torn down`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        val scan = collect(manager)
        advance(1.seconds)
        assertTrue(scan.results.isNotEmpty())

        manager.tearDownSimulation()
        runCurrent()
        val countAtTearDown = scan.results.size
        advance(5.seconds)

        assertEquals(countAtTearDown, scan.results.size)
    }

    // ---- Manager and environment state ----

    @Test
    fun `scan on a closed manager throws ManagerClosedException`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.close()

        assertFailsWith<ManagerClosedException> { manager.scan().first() }
    }

    @Test
    fun `scan with Bluetooth turned off throws BluetoothUnavailableException`() = runTest {
        val environment = MockAndroidEnvironment.Api37(isBluetoothEnabled = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)

        assertFailsWith<BluetoothUnavailableException> { manager.scan().first() }
    }

    @Test
    fun `scan with Bluetooth not supported throws BluetoothUnavailableException`() = runTest {
        val environment = MockAndroidEnvironment.Api37(isBluetoothSupported = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)

        assertFailsWith<BluetoothUnavailableException> { manager.scan().first() }
    }

    @Test
    fun `scan works after Bluetooth was turned on`() = runTest {
        val environment = MockAndroidEnvironment.Api37(isBluetoothEnabled = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        assertFailsWith<BluetoothUnavailableException> { manager.scan().first() }

        environment.simulatePowerOn()
        val scan = collect(manager)
        advance(1.seconds)

        assertTrue(scan.results.isNotEmpty())
    }

    @Test
    fun `scan without the BLUETOOTH_SCAN permission throws SecurityException`() = runTest {
        val environment = MockAndroidEnvironment.Api37(isBluetoothScanPermissionGranted = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)

        assertFailsWith<SecurityException> { manager.scan().first() }
    }

    @Test
    fun `scan works after the BLUETOOTH_SCAN permission was granted`() = runTest {
        val environment = MockAndroidEnvironment.Api37(isBluetoothScanPermissionGranted = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        assertFailsWith<SecurityException> { manager.scan().first() }

        environment.isBluetoothScanPermissionGranted = true
        val scan = collect(manager)
        advance(1.seconds)

        assertTrue(scan.results.isNotEmpty())
    }

    @Test
    fun `scan on Android 11 without location permission reports no devices`() = runTest {
        // Before Android 12 location permission is required to receive scan results.
        val environment = MockAndroidEnvironment.Api23(isLocationPermissionGranted = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))

        val scan = collect(manager)
        advance(2.seconds)

        assertEquals(emptyList(), scan.results)
    }

    @Test
    fun `scan on Android 11 with location turned off reports no devices until it is turned on`() = runTest {
        val environment = MockAndroidEnvironment.Api23(isLocationEnabled = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))
        val scan = collect(manager)
        advance(2.seconds)
        assertEquals(emptyList(), scan.results)

        environment.simulateLocationState(true)
        advance(1.seconds)

        assertFalse(scan.results.isEmpty())
    }

    // ---- Errors are thrown on collection ----

    @Test
    fun `scan does not throw when the manager is closed until the flow is collected`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.close()

        val flow = manager.scan()

        assertFailsWith<ManagerClosedException> { flow.first() }
    }

    @Test
    fun `scan errors can be handled with catch`() = runTest {
        val manager = CentralManager.mock(scope = backgroundScope)
        manager.close()
        var error: Throwable? = null

        manager.scan().catch { error = it }.toList()

        assertIs<ManagerClosedException>(error)
    }

    @Test
    fun `scan error of Bluetooth turned off can be handled with catch`() = runTest {
        val environment = MockAndroidEnvironment.Api37(isBluetoothEnabled = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        var error: Throwable? = null

        manager.scan().catch { error = it }.toList()

        assertIs<BluetoothUnavailableException>(error)
    }

    @Test
    fun `scan error of a missing permission can be handled with catch`() = runTest {
        val environment = MockAndroidEnvironment.Api37(isBluetoothScanPermissionGranted = false)
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        var error: Throwable? = null

        manager.scan().catch { error = it }.toList()

        assertIs<SecurityException>(error)
    }

    // ---- Scanner failures ----

    @Test
    fun `scan that fails to start throws ScanningFailedToStartException when collected`() = runTest {
        val environment = MockAndroidEnvironment.Api37(
            scanner = {
                Result.failure(
                    ScanningFailedToStartException(ScanningFailedToStartException.Reason.OutOfResources)
                )
            }
        )
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)

        val exception = assertFailsWith<ScanningFailedToStartException> {
            manager.scan(timeout = 10.seconds).toList()
        }

        assertEquals(ScanningFailedToStartException.Reason.OutOfResources, exception.reason)
    }

    @Test
    fun `scan on a device that silently reports nothing delivers no results`() = runTest {
        val environment = MockAndroidEnvironment.Api37(scanner = { Result.success(false) })
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        manager.simulatePeripherals(listOf(sensor()))

        val scan = collect(manager, timeout = 5.seconds)
        advance(10.seconds)

        assertEquals(emptyList(), scan.results)
        // The scan itself runs normally until the timeout.
        assertTrue(scan.job.isCompleted)
    }
}
