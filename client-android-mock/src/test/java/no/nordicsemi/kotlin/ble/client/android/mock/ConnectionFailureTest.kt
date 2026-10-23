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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.CentralManager.ConnectionOptions
import no.nordicsemi.kotlin.ble.client.exception.ConnectionFailedException
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.ConnectionResult
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"

// Two most significant bits of the address are 01: Resolvable Private Address.
private const val RPA_ADDRESS = "4A:11:22:33:44:55"

/**
 * Tests of connection attempts that don't complete immediately or fail: timeouts, auto connect,
 * denied requests, devices that cannot be connected and invalid arguments.
 */
class ConnectionFailureTest {

    private fun spec(
        address: String = ADDRESS,
        addressType: AddressType = AddressType.PUBLIC,
        proximity: Proximity = Proximity.IMMEDIATE,
        advertising: Boolean = true,
        connectableAdvertising: Boolean = true,
        eventHandler: PeripheralSpecEventHandler = object : PeripheralSpecEventHandler {},
        isBonded: Boolean = false,
    ): PeripheralSpec<String> = PeripheralSpec.simulatePeripheral(
        identifier = address,
        addressType = addressType,
        proximity = proximity,
    ) {
        if (isBonded) {
            connectable(name = "Device", isBonded = true, eventHandler = eventHandler, cachedServices = {})
        } else {
            connectable(name = "Device", eventHandler = eventHandler) {}
        }
        if (advertising) {
            advertising(
                parameters = LegacyAdvertisingSetParameters(
                    connectable = connectableAdvertising,
                    interval = 100.milliseconds,
                ),
            ) {
                CompleteLocalName("Device")
            }
        }
    }

    private fun TestScope.newManager(
        vararg specs: PeripheralSpec<String>,
        environment: MockAndroidEnvironment = no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi(),
    ): MockCentralManager = CentralManager.mock(environment = environment, scope = backgroundScope).apply {
        simulatePeripherals(specs.toList())
    }

    @Test
    fun `direct connection to a device out of range times out`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        }

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertIs<Reason.Timeout>(state.reason)
    }

    @Test
    fun `direct connection timeout is the one set in the options`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            manager.connect(peripheral, ConnectionOptions.Direct(timeout = 3.seconds))
        }

        assertEquals(3_000, testScheduler.currentTime)
        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.Timeout(3.seconds), state.reason)
    }

    @Test
    fun `timeout reason of the default connection options is their timeout`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> { manager.connect(peripheral) }

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.Timeout(10.seconds), state.reason)
    }

    @Test
    fun `timeout reason of a direct connection is the timeout of the options also when it is not a whole number of seconds`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            manager.connect(peripheral, ConnectionOptions.Direct(timeout = 2500.milliseconds))
        }

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.Timeout(2500.milliseconds), state.reason)
    }

    @Test
    fun `timeout reason of a direct connection is the timeout of withTimeout when it fires first`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(2.seconds) {
                manager.connect(peripheral, ConnectionOptions.Direct(timeout = 30.seconds))
            }
        }

        assertEquals(2_000, testScheduler.currentTime)
        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.Timeout(2.seconds), state.reason)
    }

    @Test
    fun `timeout reason of a direct connection is the timeout of the options when it fires first`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(30.seconds) {
                manager.connect(peripheral, ConnectionOptions.Direct(timeout = 3.seconds))
            }
        }

        assertEquals(3_000, testScheduler.currentTime)
        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.Timeout(3.seconds), state.reason)
    }

    @Test
    fun `timeout reason of an auto connect connection is the timeout of withTimeout`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(4.seconds) {
                manager.connect(peripheral, ConnectionOptions.AutoConnect())
            }
        }

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.Timeout(4.seconds), state.reason)
    }

    @Test
    fun `default connection options time out after 10 seconds`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> { manager.connect(peripheral) }

        assertEquals(10_000, testScheduler.currentTime)
    }

    @Test
    fun `connecting to a device that does not advertise times out`() = runTest {
        val manager = newManager(spec(advertising = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        }
    }

    @Test
    fun `connecting to a device that advertises as not connectable times out`() = runTest {
        val manager = newManager(spec(connectableAdvertising = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        }
    }

    @Test
    fun `connection request denied by the device times out`() = runTest {
        val deny = object : PeripheralSpecEventHandler {
            override fun onConnectionRequest(peripheral: MockPeripheral, phy: PrimaryPhy) = ConnectionResult.Deny
        }
        val manager = newManager(spec(eventHandler = deny))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        }

        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(false, peripheral.isConnected)
    }

    @Test
    fun `device that was not scanned and has other address type than public cannot be connected`() = runTest {
        // Android assumes PUBLIC address type for devices that were not scanned, so the
        // connection request is never answered by a device using a random address.
        val manager = newManager(spec(addressType = AddressType.RANDOM_STATIC))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<TimeoutCancellationException> {
            manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))
        }
    }

    @Test
    fun `device with random address can be connected after it was scanned`() = runTest {
        val spec = spec(addressType = AddressType.RANDOM_STATIC)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        spec.simulateCaching()

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `auto connect to a device out of range keeps waiting`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        // Much longer than the timeout of a direct connection.
        val result = withTimeoutOrNull(10.minutes) {
            manager.connect(peripheral, ConnectionOptions.AutoConnect())
        }

        assertNull(result)
    }

    @Test
    fun `auto connect to a device out of range is in Connecting state`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        val job = async { manager.connect(peripheral, ConnectionOptions.AutoConnect()) }
        advanceTimeBy(5.minutes)
        runCurrent()

        assertEquals(ConnectionState.Connecting, peripheral.state.value)
        assertEquals(true, job.isActive)
        job.cancelAndJoin()
    }

    @Test
    fun `auto connect connects when the device appears later`() = runTest {
        val spec = spec(proximity = Proximity.OUT_OF_RANGE)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = async { manager.connect(peripheral, ConnectionOptions.AutoConnect()) }
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(ConnectionState.Connecting, peripheral.state.value)

        spec.simulateProximityChange(Proximity.NEAR)
        advanceTimeBy(5.seconds)
        runCurrent()

        job.await()
        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `direct connect connects when the device appears before the timeout`() = runTest {
        val spec = spec(proximity = Proximity.OUT_OF_RANGE)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = async { manager.connect(peripheral, ConnectionOptions.Direct(timeout = 10.seconds)) }
        advanceTimeBy(3.seconds)
        runCurrent()

        spec.simulateProximityChange(Proximity.NEAR)
        advanceTimeBy(2.seconds)
        runCurrent()

        job.await()
        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `cancelling a direct connection attempt reports Cancelled`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = async { manager.connect(peripheral, ConnectionOptions.Direct(timeout = 10.seconds)) }
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(ConnectionState.Connecting, peripheral.state.value)

        job.cancelAndJoin()

        assertEquals(ConnectionState.Disconnected(Reason.Cancelled), peripheral.state.value)
    }

    @Test
    fun `cancelling an auto connect attempt reports Cancelled`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = async { manager.connect(peripheral, ConnectionOptions.AutoConnect()) }
        advanceTimeBy(2.minutes)
        runCurrent()

        job.cancelAndJoin()

        assertEquals(ConnectionState.Disconnected(Reason.Cancelled), peripheral.state.value)
    }

    /** Counts the connection requests the peripheral gets. */
    private class ConnectionRequests : PeripheralSpecEventHandler {
        var count = 0
        var result: ConnectionResult = ConnectionResult.Accept

        override fun onConnectionRequest(peripheral: MockPeripheral, phy: PrimaryPhy): ConnectionResult {
            count++
            return result
        }
    }

    @Test
    fun `device that appears after a pending connection was cancelled is not connected`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(proximity = Proximity.OUT_OF_RANGE, eventHandler = requests)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = async { runCatching { manager.connect(peripheral, ConnectionOptions.AutoConnect()) } }
        advanceTimeBy(1.minutes)
        runCurrent()

        peripheral.disconnect()
        runCurrent()
        spec.simulateProximityChange(Proximity.NEAR)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertFalse(spec.isConnected)
        assertEquals(0, requests.count)
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertNotNull(job.await().exceptionOrNull())
    }

    @Test
    fun `disconnect cancels a pending direct connection attempt`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(proximity = Proximity.OUT_OF_RANGE, eventHandler = requests)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = async {
            runCatching { manager.connect(peripheral, ConnectionOptions.Direct(timeout = 1.minutes)) }
        }
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(ConnectionState.Connecting, peripheral.state.value)

        peripheral.disconnect()
        runCurrent()
        spec.simulateProximityChange(Proximity.NEAR)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertFalse(spec.isConnected)
        assertEquals(0, requests.count)
        assertNotNull(job.await().exceptionOrNull())
    }

    @Test
    fun `cancelling the coroutine of a pending connection attempt does not connect the device later`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(proximity = Proximity.OUT_OF_RANGE, eventHandler = requests)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = async { manager.connect(peripheral, ConnectionOptions.AutoConnect()) }
        advanceTimeBy(5.seconds)
        runCurrent()

        job.cancel()
        runCurrent()
        spec.simulateProximityChange(Proximity.NEAR)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertFalse(spec.isConnected)
        assertEquals(0, requests.count)
        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.Cancelled, state.reason)
    }

    @Test
    fun `auto connect peripheral waiting for the link to come back can be disconnected`() = runTest {
        val requests = ConnectionRequests()
        val spec = spec(eventHandler = requests)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, ConnectionOptions.AutoConnect())
        spec.simulateProximityChange(Proximity.OUT_OF_RANGE)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(ConnectionState.Connecting, peripheral.state.value)

        peripheral.disconnect()
        runCurrent()
        spec.simulateProximityChange(Proximity.NEAR)
        advanceTimeBy(10.seconds)
        runCurrent()

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.Success, state.reason)
        assertFalse(spec.isConnected)
        assertEquals(1, requests.count)
    }

    @Test
    fun `disconnect cancels a pending connection attempt`() = runTest {
        val manager = newManager(spec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = async { runCatching { manager.connect(peripheral, ConnectionOptions.AutoConnect()) } }
        advanceTimeBy(1.minutes)
        runCurrent()

        peripheral.disconnect()
        runCurrent()

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(Reason.Success, state.reason)
        assertNotNull(job.await().exceptionOrNull())
    }

    /** A device with a Resolvable Private Address, which is known (was scanned) and not bonded. */
    private fun rpaSpec(
        proximity: Proximity = Proximity.IMMEDIATE,
        eventHandler: PeripheralSpecEventHandler = object : PeripheralSpecEventHandler {},
    ) = spec(
        address = RPA_ADDRESS,
        addressType = AddressType.RANDOM_PRIVATE_RESOLVABLE,
        proximity = proximity,
        eventHandler = eventHandler,
    ).also {
        // The device must be known (scanned) to have its address type recognized.
        it.simulateCaching()
    }

    @Test
    fun `auto connect to a not bonded device with resolvable private address connects directly first`() = runTest {
        val manager = newManager(rpaSpec())
        val peripheral = checkNotNull(manager.getPeripheralById(RPA_ADDRESS))

        manager.connect(peripheral, ConnectionOptions.AutoConnect())

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `auto connect to a not bonded device with resolvable private address connects directly first before Android 13`() = runTest {
        val manager = newManager(rpaSpec(), environment = MockAndroidEnvironment.Api31())
        val peripheral = checkNotNull(manager.getPeripheralById(RPA_ADDRESS))

        manager.connect(peripheral, ConnectionOptions.AutoConnect())

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `auto connect to a not bonded device with resolvable private address out of range is not supported`() = runTest {
        val manager = newManager(rpaSpec(proximity = Proximity.OUT_OF_RANGE))
        val peripheral = checkNotNull(manager.getPeripheralById(RPA_ADDRESS))

        // The direct connection times out, and then the system refuses to wait for the device.
        val exception = assertFailsWith<ConnectionFailedException> {
            manager.connect(peripheral, ConnectionOptions.AutoConnect())
        }

        assertEquals(Reason.UnsupportedAddress, exception.reason)
        assertEquals(ConnectionState.Disconnected(Reason.UnsupportedAddress), peripheral.state.value)
    }

    @Test
    fun `auto connect to a not bonded device with resolvable private address out of range silently waits before Android 13`() = runTest {
        val manager = newManager(rpaSpec(proximity = Proximity.OUT_OF_RANGE), environment = MockAndroidEnvironment.Api31())
        val peripheral = checkNotNull(manager.getPeripheralById(RPA_ADDRESS))
        val job = async { runCatching { manager.connect(peripheral, ConnectionOptions.AutoConnect()) } }

        advanceTimeBy(1.minutes)
        runCurrent()

        // No error, the system just waits.
        assertEquals(ConnectionState.Connecting, peripheral.state.value)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun `auto connect to a not bonded device uses auto connect when the direct connection was denied`() = runTest {
        val requests = ConnectionRequests().apply { result = ConnectionResult.Deny }
        val manager = newManager(spec(eventHandler = requests))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = launch { manager.connect(peripheral, ConnectionOptions.AutoConnect()) }

        // The direct connection times out, and the system keeps trying (auto connect).
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(ConnectionState.Connecting, peripheral.state.value)

        requests.result = ConnectionResult.Accept
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(ConnectionState.Connected, peripheral.state.value)
        job.join()
    }

    @Test
    fun `auto connect denied forever stops trying when the connection is cancelled`() = runTest {
        val requests = ConnectionRequests().apply { result = ConnectionResult.Deny }
        // A bonded device, so there is no direct attempt before auto connect.
        val manager = newManager(spec(eventHandler = requests, isBonded = true))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        val job = launch { manager.connect(peripheral, ConnectionOptions.AutoConnect()) }
        advanceTimeBy(5.seconds)
        runCurrent()
        // The request is retried each time the peripheral advertises.
        assertTrue(requests.count > 10, "Expected many attempts, but was ${requests.count}")

        job.cancelAndJoin()
        val attempts = requests.count
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(attempts, requests.count)
    }

    @Test
    fun `not bonded device connected directly uses auto connect after the link is lost`() = runTest {
        val spec = spec()
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, ConnectionOptions.AutoConnect())

        spec.simulateReset()
        advanceTimeBy(10.seconds)
        runCurrent()

        // The direct connection is not reconnected by the system, so the peripheral waits for the device.
        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `not bonded device with resolvable private address cannot be waited for after the link is lost`() = runTest {
        val spec = rpaSpec()
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(RPA_ADDRESS))
        manager.connect(peripheral, ConnectionOptions.AutoConnect())

        spec.simulateReset()
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(ConnectionState.Disconnected(Reason.UnsupportedAddress), peripheral.state.value)
    }

    @Test
    fun `not bonded device with resolvable private address is silently waited for after the link is lost before Android 13`() = runTest {
        val spec = rpaSpec()
        val manager = newManager(spec, environment = MockAndroidEnvironment.Api31())
        val peripheral = checkNotNull(manager.getPeripheralById(RPA_ADDRESS))
        manager.connect(peripheral, ConnectionOptions.AutoConnect())

        spec.simulateReset()
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(ConnectionState.Connecting, peripheral.state.value)
    }

    @Test
    fun `direct connection to a device with resolvable private address is supported`() = runTest {
        val spec = spec(address = RPA_ADDRESS, addressType = AddressType.RANDOM_PRIVATE_RESOLVABLE)
        val manager = newManager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(RPA_ADDRESS))
        spec.simulateCaching()

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 10.seconds))

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `connecting a peripheral obtained from another manager throws`() = runTest {
        val spec = spec()
        val manager = newManager(spec)
        val otherManager = newManager(spec)
        val foreignPeripheral = checkNotNull(otherManager.getPeripheralById(ADDRESS))
        // Make the first manager know the device as well.
        manager.getPeripheralById(ADDRESS)

        assertFailsWith<IllegalArgumentException> {
            manager.connect(foreignPeripheral)
        }
    }

    @Test
    fun `connecting without the connect permission throws SecurityException`() = runTest {
        val environment = MockAndroidEnvironment.Api31(isBluetoothConnectPermissionGranted = false)
        val manager = newManager(spec(), environment = environment)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<SecurityException> { manager.connect(peripheral) }
    }

    @Test
    fun `connecting with Bluetooth turned off throws BluetoothUnavailableException`() = runTest {
        val environment = MockAndroidEnvironment.Api37()
        val manager = newManager(spec(), environment = environment)
        // Peripherals can be retrieved only when Bluetooth is on.
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        environment.simulatePowerOff()

        assertFailsWith<no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException> {
            manager.connect(peripheral)
        }
    }
}
