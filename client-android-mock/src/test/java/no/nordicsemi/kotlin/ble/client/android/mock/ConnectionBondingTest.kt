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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.CentralManager.ConnectionOptions
import no.nordicsemi.kotlin.ble.client.android.exception.BondingFailedException
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.BondResult
import no.nordicsemi.kotlin.ble.client.mock.DisconnectionReason
import no.nordicsemi.kotlin.ble.client.mock.MockBondState
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.android.BondState
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.mock.BondingFailure
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ADDRESS = "CA:FE:00:00:00:01"
private const val OTHER_ADDRESS = "CA:FE:00:00:00:02"

// Two most significant bits of the address are 01: Resolvable Private Address.
private const val RPA_ADDRESS = "4A:11:22:33:44:55"

/**
 * Tests of bond information: the bond state, creating and removing bonds and retrieving bonded
 * peripherals.
 */
class ConnectionBondingTest {

    /** The peripheral accepts bonding, which is not the default. */
    private fun acceptingHandler() = object : PeripheralSpecEventHandler {
        override fun onBondRequest(peripheral: MockPeripheral) = BondResult.Accept
    }

    /** A peripheral that was connected before, and so is known by the phone, and may be bonded. */
    private fun spec(
        address: String = ADDRESS,
        addressType: AddressType = AddressType.PUBLIC,
        isBonded: Boolean,
        peripheralHasBond: Boolean = isBonded,
        eventHandler: PeripheralSpecEventHandler = acceptingHandler(),
    ): PeripheralSpec<String> = PeripheralSpec.simulatePeripheral(
        identifier = address,
        addressType = addressType,
    ) {
        connectable(
            name = "Device",
            isBonded = isBonded,
            peripheralHasBond = peripheralHasBond,
            eventHandler = eventHandler,
            cachedServices = {},
        )
        advertising(
            parameters = LegacyAdvertisingSetParameters(connectable = true, interval = 100.milliseconds),
        ) {
            CompleteLocalName("Device")
        }
    }

    private fun TestScope.manager(
        vararg specs: PeripheralSpec<String>,
        environment: MockAndroidEnvironment = MockAndroidEnvironment.Api31(),
    ): MockCentralManager = CentralManager.mock(environment = environment, scope = backgroundScope).apply {
        simulatePeripherals(specs.toList())
    }

    @Test
    fun `bonded peripheral has bond information`() = runTest {
        val manager = manager(spec(isBonded = true))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertEquals(BondState.Bonded, peripheral.bondState.value)
        assertTrue(peripheral.hasBondInformation)
    }

    @Test
    fun `not bonded peripheral has no bond information`() = runTest {
        val manager = manager(spec(isBonded = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertIs<BondState.None>(peripheral.bondState.value)
        assertFalse(peripheral.hasBondInformation)
    }

    @Test
    fun `bond information is kept after disconnecting`() = runTest {
        val manager = manager(spec(isBonded = true))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.disconnect()

        assertEquals(BondState.Bonded, peripheral.bondState.value)
    }

    @Test
    fun `bonded peripherals are returned`() = runTest {
        val manager = manager(
            spec(address = ADDRESS, isBonded = true),
            spec(address = OTHER_ADDRESS, isBonded = false),
        )

        val bonded = manager.getBondedPeripherals()

        assertEquals(listOf(ADDRESS), bonded.map { it.address })
    }

    @Test
    fun `no peripherals are returned when none is bonded`() = runTest {
        val manager = manager(spec(isBonded = false))

        assertTrue(manager.getBondedPeripherals().isEmpty())
    }

    @Test
    fun `bonded peripheral is the same instance as the one retrieved by id`() = runTest {
        val manager = manager(spec(isBonded = true))

        val bonded = manager.getBondedPeripherals().single()

        assertSame(bonded, manager.getPeripheralById(ADDRESS))
    }

    @Test
    fun `bonded peripheral can be connected without scanning`() = runTest {
        // Bonded devices using random addresses are known by the phone.
        val manager = manager(spec(addressType = AddressType.RANDOM_STATIC, isBonded = true))
        val peripheral = manager.getBondedPeripherals().single()

        manager.connect(peripheral, ConnectionOptions.Direct(timeout = 5.seconds))

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `auto connect to a bonded device with resolvable private address is supported`() = runTest {
        val manager = manager(spec(address = RPA_ADDRESS, addressType = AddressType.RANDOM_PRIVATE_RESOLVABLE, isBonded = true))
        val peripheral = manager.getBondedPeripherals().single()

        manager.connect(peripheral, ConnectionOptions.AutoConnect())

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `getting bonded peripherals on a closed manager throws`() = runTest {
        val manager = manager(spec(isBonded = true))

        manager.close()

        assertFailsWith<ManagerClosedException> { manager.getBondedPeripherals() }
    }

    @Test
    fun `getting bonded peripherals without the connect permission throws SecurityException`() = runTest {
        val environment = MockAndroidEnvironment.Api31(isBluetoothConnectPermissionGranted = false)
        val manager = manager(spec(isBonded = true), environment = environment)

        assertFailsWith<SecurityException> { manager.getBondedPeripherals() }
    }

    @Test
    fun `creating a bond bonds the peripheral`() = runTest {
        val manager = manager(spec(isBonded = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.createBond()

        assertEquals(BondState.Bonded, peripheral.bondState.value)
        assertTrue(peripheral.hasBondInformation)
    }

    @Test
    fun `created bond is returned with bonded peripherals`() = runTest {
        val manager = manager(spec(isBonded = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.createBond()

        assertEquals(listOf(ADDRESS), manager.getBondedPeripherals().map { it.address })
    }

    @Test
    fun `creating a bond with an already bonded peripheral does nothing`() = runTest {
        val manager = manager(spec(isBonded = true))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.createBond()

        assertEquals(BondState.Bonded, peripheral.bondState.value)
    }

    @Test
    fun `removing a bond removes the bond information`() = runTest {
        val manager = manager(spec(isBonded = true))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        peripheral.removeBond()

        assertIs<BondState.None>(peripheral.bondState.value)
        assertTrue(manager.getBondedPeripherals().isEmpty())
    }

    @Test
    fun `removing a bond terminates the connection`() = runTest {
        val manager = manager(spec(isBonded = true))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.removeBond()
        runCurrent()

        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
    }

    @Test
    fun `removing a bond that does not exist does nothing`() = runTest {
        val manager = manager(spec(isBonded = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        peripheral.removeBond()

        assertIs<BondState.None>(peripheral.bondState.value)
    }

    @Test
    fun `removing a bond on Android 17 requires the privileged permission`() = runTest {
        val manager = manager(spec(isBonded = true), environment = MockAndroidEnvironment.Api37())
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertFailsWith<SecurityException> { peripheral.removeBond() }
        assertEquals(BondState.Bonded, peripheral.bondState.value)
    }

    @Test
    fun `removing a bond on Android 17 is possible with the privileged permission`() = runTest {
        val environment = MockAndroidEnvironment.Api37(isBluetoothPrivilegedPermissionGranted = true)
        val manager = manager(spec(isBonded = true), environment = environment)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        peripheral.removeBond()

        assertIs<BondState.None>(peripheral.bondState.value)
    }

    @Test
    fun `rejected bond request fails creating the bond`() = runTest {
        val rejecting = object : PeripheralSpecEventHandler {
            override fun onBondRequest(peripheral: MockPeripheral) = BondResult.Reject()
        }
        val manager = manager(spec(isBonded = false, eventHandler = rejecting))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        assertFailsWith<BondingFailedException> { peripheral.createBond() }

        assertIs<BondState.None>(peripheral.bondState.value)
        assertTrue(manager.getBondedPeripherals().isEmpty())
    }

    @Test
    fun `bond request is passed to the event handler`() = runTest {
        var requests = 0
        val counting = object : PeripheralSpecEventHandler {
            override fun onBondRequest(peripheral: MockPeripheral): BondResult = BondResult.Accept.also { requests++ }
        }
        val manager = manager(spec(isBonded = false, eventHandler = counting))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.createBond()

        assertEquals(1, requests)
    }

    @Test
    fun `bond state goes through bonding`() = runTest {
        val manager = manager(spec(isBonded = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        val states = mutableListOf<BondState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.bondState.toList(states)
        }

        peripheral.createBond()

        assertEquals(listOf(BondState.None(), BondState.Bonding, BondState.Bonded), states)
    }

    @Test
    fun `bond is kept by the spec for other managers`() = runTest {
        val spec = spec(isBonded = false)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        peripheral.createBond()

        val otherManager = manager(spec)

        assertEquals(listOf(ADDRESS), otherManager.getBondedPeripherals().map { it.address })
    }

    @Test
    fun `peripheral stores the bond by default`() = runTest {
        assertTrue(spec(isBonded = true).peripheralHasBond)
        assertFalse(spec(isBonded = false).peripheralHasBond)
    }

    @Test
    fun `creating a bond makes the peripheral store it`() = runTest {
        val spec = spec(isBonded = false)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.createBond()

        assertTrue(spec.peripheralHasBond)
    }

    @Test
    fun `removing a bond does not make the peripheral forget it`() = runTest {
        val spec = spec(isBonded = true)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        peripheral.removeBond()

        assertTrue(spec.peripheralHasBond)
    }

    @Test
    fun `peripheral that still has the bond rejects bonding`() = runTest {
        var requests = 0
        val counting = object : PeripheralSpecEventHandler {
            override fun onBondRequest(peripheral: MockPeripheral): BondResult = BondResult.Accept.also { requests++ }
        }
        // The phone forgot the bond, but the peripheral did not.
        val spec = spec(isBonded = false, peripheralHasBond = true, eventHandler = counting)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        assertFailsWith<BondingFailedException> { peripheral.createBond() }

        assertIs<BondState.None>(peripheral.bondState.value)
        assertEquals(0, requests)
    }

    @Test
    fun `bonding succeeds after the peripheral forgets the bond`() = runTest {
        val spec = spec(isBonded = false, peripheralHasBond = true)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        spec.simulatePeripheralBondLoss()

        peripheral.createBond()

        assertEquals(BondState.Bonded, peripheral.bondState.value)
    }

    @Test
    fun `peripheral forgetting the bond does not remove the bond from the phone`() = runTest {
        val spec = spec(isBonded = true)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        spec.simulatePeripheralBondLoss()

        assertFalse(spec.peripheralHasBond)
        assertEquals(BondState.Bonded, peripheral.bondState.value)
    }

    @Test
    fun `simulated bond removal terminates the connection`() = runTest {
        val spec = spec(isBonded = true)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        spec.simulateBondRemoval()
        runCurrent()

        assertIs<BondState.None>(peripheral.bondState.value)
        assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertTrue(spec.peripheralHasBond)
    }

    @Test
    fun `simulated bonding bonds the peripheral`() = runTest {
        val spec = spec(isBonded = false)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        spec.simulateBondRequest()
        runCurrent()

        assertEquals(BondState.Bonded, peripheral.bondState.value)
    }

    private suspend fun TestScope.failedBondingReason(
        failure: BondingFailure,
        environment: MockAndroidEnvironment = MockAndroidEnvironment.Api31(),
    ): BondState.Reason? {
        val rejecting = object : PeripheralSpecEventHandler {
            override fun onBondRequest(peripheral: MockPeripheral) = BondResult.Reject(failure)
        }
        val manager = manager(spec(isBonded = false, eventHandler = rejecting), environment = environment)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        return assertFailsWith<BondingFailedException> { peripheral.createBond() }.reason
    }

    @Test
    fun `rejected bonding reports the peripheral rejecting it by default`() = runTest {
        assertEquals(
            BondState.Reason.AuthenticationRejected,
            failedBondingReason(BondingFailure.REJECTED),
        )
    }

    @Test
    fun `bonding cancelled by the user reports the reason`() = runTest {
        assertEquals(
            BondState.Reason.AuthenticationCanceled,
            failedBondingReason(BondingFailure.CANCELLED),
        )
    }

    @Test
    fun `bonding with a wrong PIN reports the reason`() = runTest {
        assertEquals(
            BondState.Reason.AuthenticationFailed,
            failedBondingReason(BondingFailure.WRONG_PIN),
        )
    }

    @Test
    fun `bonding that failed authentication is reported like a wrong PIN`() = runTest {
        assertEquals(
            failedBondingReason(BondingFailure.WRONG_PIN),
            failedBondingReason(BondingFailure.AUTH_FAILED),
        )
        assertEquals(
            BondState.Reason.AuthenticationFailed,
            failedBondingReason(BondingFailure.AUTH_FAILED),
        )
    }

    @Test
    fun `bonding that timed out reports the reason`() = runTest {
        assertEquals(
            BondState.Reason.AuthenticationTimeout,
            failedBondingReason(BondingFailure.TIMED_OUT),
        )
    }

    @Test
    fun `failed bonding reports the same reason in every environment`() = runTest {
        listOf(
            MockAndroidEnvironment.Api23(),
            MockAndroidEnvironment.Api26(),
            MockAndroidEnvironment.SamsungA8(),
            MockAndroidEnvironment.Api36(),
        ).forEach { environment ->
            assertEquals(
                BondState.Reason.AuthenticationCanceled,
                failedBondingReason(BondingFailure.CANCELLED, environment),
            )
        }
    }

    @Test
    fun `bonding with a peripheral that still has the bond reports it was rejected`() = runTest {
        val manager = manager(spec(isBonded = false, peripheralHasBond = true))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        val exception = assertFailsWith<BondingFailedException> { peripheral.createBond() }

        assertEquals(BondState.Reason.AuthenticationRejected, exception.reason)
    }

    @Test
    fun `reason of a failed bonding is not kept after bonding succeeds`() = runTest {
        var result: BondResult = BondResult.Reject(BondingFailure.CANCELLED)
        val handler = object : PeripheralSpecEventHandler {
            override fun onBondRequest(peripheral: MockPeripheral) = result
        }
        val spec = spec(isBonded = false, eventHandler = handler)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        assertFailsWith<BondingFailedException> { peripheral.createBond() }

        result = BondResult.Accept
        peripheral.createBond()

        assertEquals(BondState.Bonded, peripheral.bondState.value)
        assertEquals(MockBondState.Bonded, spec.bondState.value)
    }

    private fun rejectingHandler() = object : PeripheralSpecEventHandler {
        override fun onBondRequest(peripheral: MockPeripheral) = BondResult.Reject()
    }

    @Test
    fun `auto connect peripheral is disconnected when the link is lost after bonding failed`() = runTest {
        // Android terminates the link after a failed pairing, and then it does not reconnect,
        // although the connection was started with auto connect.
        val spec = spec(isBonded = false, eventHandler = rejectingHandler())
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, ConnectionOptions.AutoConnect())
        assertFailsWith<BondingFailedException> { peripheral.createBond() }

        spec.simulateReset()
        advanceTimeBy(10.seconds)
        runCurrent()

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(ConnectionState.Disconnected.Reason.LinkLoss, state.reason)
    }

    @Test
    fun `auto connect peripheral is disconnected after bonding initiated by the peripheral failed`() = runTest {
        // The bonding is not started by createBond(), but the link is terminated by the phone
        // after the failure, and it is not reconnected.
        val spec = spec(isBonded = false, eventHandler = rejectingHandler())
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, ConnectionOptions.AutoConnect())
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.state.toList(states)
        }

        spec.simulateBondRequest()
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(
            listOf(
                ConnectionState.Connected,
                ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.TerminateLocalHost),
            ),
            states,
        )
    }

    @Test
    fun `auto connect peripheral reconnects after the link is lost when bonding succeeded`() = runTest {
        val spec = spec(isBonded = false)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, ConnectionOptions.AutoConnect())
        peripheral.createBond()

        spec.simulateReset()
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `auto connect after a failed bonding reconnects again`() = runTest {
        val spec = spec(isBonded = false, eventHandler = rejectingHandler())
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, ConnectionOptions.AutoConnect())
        assertFailsWith<BondingFailedException> { peripheral.createBond() }
        peripheral.disconnect()

        manager.connect(peripheral, ConnectionOptions.AutoConnect())
        spec.simulateReset()
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    /** Rejects bonding and records how connections end. */
    private class RejectingHandler : PeripheralSpecEventHandler {
        val connectionsLost = mutableListOf<DisconnectionReason>()

        override fun onBondRequest(peripheral: MockPeripheral) = BondResult.Reject()

        override fun onConnectionLost(peripheral: MockPeripheral, reason: DisconnectionReason) {
            connectionsLost += reason
        }
    }

    @Test
    fun `phone terminates the link 3 seconds after bonding failed`() = runTest {
        val handler = RejectingHandler()
        val manager = manager(spec(isBonded = false, eventHandler = handler))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        assertFailsWith<BondingFailedException> { peripheral.createBond() }

        advanceTimeBy(2.9.seconds)
        runCurrent()
        assertEquals(ConnectionState.Connected, peripheral.state.value)

        advanceTimeBy(0.2.seconds)
        runCurrent()
        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(ConnectionState.Disconnected.Reason.TerminateLocalHost, state.reason)
        // The peripheral sees that the phone has terminated the connection.
        assertEquals(listOf<DisconnectionReason>(DisconnectionReason.TerminatePeerUser), handler.connectionsLost)
    }

    @Test
    fun `link is not terminated when bonding succeeded`() = runTest {
        val manager = manager(spec(isBonded = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        peripheral.createBond()

        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(ConnectionState.Connected, peripheral.state.value)
    }

    @Test
    fun `link is not terminated twice when it was lost before bonding timed out`() = runTest {
        val handler = RejectingHandler()
        val spec = spec(isBonded = false, eventHandler = handler)
        val manager = manager(spec)
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        assertFailsWith<BondingFailedException> { peripheral.createBond() }

        spec.simulateDisconnection()
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(listOf<DisconnectionReason>(DisconnectionReason.TerminateLocalHost), handler.connectionsLost)
    }

    @Test
    fun `bond removal is a disconnection initiated by the phone`() = runTest {
        val handler = RejectingHandler()
        val manager = manager(spec(isBonded = true, eventHandler = handler))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        peripheral.removeBond()
        runCurrent()

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(ConnectionState.Disconnected.Reason.TerminateLocalHost, state.reason)
        assertEquals(listOf<DisconnectionReason>(DisconnectionReason.TerminatePeerUser), handler.connectionsLost)
    }

    @Test
    fun `removing a bond does not reconnect a peripheral connected with auto connect`() = runTest {
        val manager = manager(spec(isBonded = true))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral, ConnectionOptions.AutoConnect())
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.state.toList(states)
        }

        peripheral.removeBond()
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(
            listOf(
                ConnectionState.Connected,
                ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.TerminateLocalHost),
            ),
            states,
        )
    }

    @Test
    fun `peripheral can disconnect when it rejects bonding`() = runTest {
        val handler = object : PeripheralSpecEventHandler {
            override fun onBondRequest(peripheral: MockPeripheral): BondResult {
                peripheral.simulateDisconnection()
                return BondResult.Reject(BondingFailure.WRONG_PIN)
            }
        }
        val manager = manager(spec(isBonded = false, eventHandler = handler))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)

        assertFailsWith<BondingFailedException> { peripheral.createBond() }
        advanceTimeBy(10.seconds)
        runCurrent()

        val state = assertIs<ConnectionState.Disconnected>(peripheral.state.value)
        assertEquals(ConnectionState.Disconnected.Reason.TerminatePeerUser, state.reason)
    }

    @Test
    fun `bond state has no reason when the peripheral was never bonded`() = runTest {
        val manager = manager(spec(isBonded = false))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        assertEquals(BondState.None(), peripheral.bondState.value)
    }

    @Test
    fun `bond state contains the reason when bonding failed`() = runTest {
        val manager = manager(spec(isBonded = false, eventHandler = RejectingHandler()))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        assertFailsWith<BondingFailedException> { peripheral.createBond() }

        assertEquals(BondState.None(BondState.Reason.AuthenticationRejected), peripheral.bondState.value)
    }

    @Test
    fun `bond state contains the reason when bonding was cancelled by the user`() = runTest {
        val handler = object : PeripheralSpecEventHandler {
            override fun onBondRequest(peripheral: MockPeripheral) = BondResult.Reject(BondingFailure.CANCELLED)
        }
        val manager = manager(spec(isBonded = false, eventHandler = handler))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        assertFailsWith<BondingFailedException> { peripheral.createBond() }

        assertEquals(BondState.None(BondState.Reason.AuthenticationCanceled), peripheral.bondState.value)
    }

    @Test
    fun `bond state contains the reason when the bond was removed`() = runTest {
        val manager = manager(spec(isBonded = true))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))

        peripheral.removeBond()

        assertEquals(BondState.None(BondState.Reason.Removed), peripheral.bondState.value)
    }

    @Test
    fun `reason of a failed bonding is cleared when bonding starts again`() = runTest {
        var result: BondResult = BondResult.Reject()
        val handler = object : PeripheralSpecEventHandler {
            override fun onBondRequest(peripheral: MockPeripheral) = result
        }
        val manager = manager(spec(isBonded = false, eventHandler = handler))
        val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
        manager.connect(peripheral)
        assertFailsWith<BondingFailedException> { peripheral.createBond() }

        result = BondResult.Accept
        peripheral.createBond()

        assertEquals(BondState.Bonded, peripheral.bondState.value)
    }
}

