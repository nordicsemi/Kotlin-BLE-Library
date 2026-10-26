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

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.EncryptionState
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.mock.MockBondState
import no.nordicsemi.kotlin.ble.client.mock.MockEncryptionState
import no.nordicsemi.kotlin.ble.client.android.BondState
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

private val ENCRYPTED: EncryptionState = EncryptionState.Encrypted(EncryptionState.Algorithm.AES, keySize = 16)

/**
 * Tests of the encryption state of the link, which is reported by Android 16 and newer.
 */
class EncryptionStateTest {

    private fun TestScope.fixture(
        bonded: Boolean = false,
        peripheralHasBond: Boolean = bonded,
        environment: MockAndroidEnvironment = MockAndroidEnvironment.Api36(),
    ): GattFixture {
        val fixture = gattSingleCharacteristic(
            bonded = bonded,
            peripheralHasBond = peripheralHasBond,
            properties = setOf(CharacteristicProperty.READ),
            permissions = setOf(Permission.READ_ENCRYPTED),
        )
        // Use the requested environment instead of the default one.
        val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
            .apply { simulatePeripherals(listOf(fixture.spec)) }
        return GattFixture(fixture.spec, manager, fixture.handler)
    }

    @Test
    fun `encryption state is unknown when not connected`() = runTest {
        val fixture = fixture(bonded = true)
        val peripheral = checkNotNull(fixture.manager.getPeripheralById(GATT_ADDRESS))

        assertEquals(EncryptionState.Unknown, peripheral.encryptionState.value)
    }

    @Test
    fun `link with a not bonded peripheral is not encrypted`() = runTest {
        val fixture = fixture()

        fixture.connect()
        runCurrent()

        assertEquals(EncryptionState.NotEncrypted(), fixture.peripheral.encryptionState.value)
    }

    @Test
    fun `link with a bonded peripheral is encrypted when connected`() = runTest {
        val fixture = fixture(bonded = true)

        fixture.connect()
        runCurrent()

        assertEquals(ENCRYPTED, fixture.peripheral.encryptionState.value)
    }

    @Test
    fun `encryption state is reset when disconnected`() = runTest {
        val fixture = fixture(bonded = true)
        fixture.connect()
        runCurrent()

        fixture.peripheral.disconnect()
        runCurrent()

        assertEquals(EncryptionState.Unknown, fixture.peripheral.encryptionState.value)
    }

    @Test
    fun `link is encrypted before the bond is created`() = runTest {
        withTimeout(10.seconds) {
            val fixture = fixture()
            fixture.connect()
            // The peripheral reports the encryption first, and then the bond, like Android does.
            val encryptionWhenBonded = mutableListOf<MockEncryptionState?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.spec.bondState.collect {
                    if (it is MockBondState.Bonded) encryptionWhenBonded += fixture.spec.encryptionState.value
                }
            }

            val _ = fixture.characteristic().read()
            runCurrent()

            assertEquals(listOf<MockEncryptionState?>(MockEncryptionState.Encrypted), encryptionWhenBonded)
            assertEquals(ENCRYPTED, fixture.peripheral.encryptionState.value)
        }
    }

    @Test
    fun `peripheral that lost the bond fails the encryption`() = runTest {
        // The phone has the bond, but the peripheral forgot it.
        val fixture = fixture(bonded = true, peripheralHasBond = false)

        fixture.connect()
        runCurrent()

        val failed: EncryptionState = EncryptionState.NotEncrypted(EncryptionState.Reason.LeEncryptionFailure)
        assertEquals(failed, fixture.peripheral.encryptionState.value)
        // The bond information is kept by the phone.
        assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
    }

    @Test
    fun `protected attribute cannot be accessed when the encryption failed`() = runTest {
        withTimeout(10.seconds) {
            val fixture = fixture(bonded = true, peripheralHasBond = false)

            val exception = assertFailsWith<OperationFailedException> { fixture.characteristic().read() }

            assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
        }
    }

    @Test
    fun `encryption is restored after the peripheral bonds again`() = runTest {
        val fixture = fixture(bonded = true, peripheralHasBond = false)
        fixture.connect()
        // The user removes the stale bond, and bonds again.
        fixture.peripheral.disconnect()
        fixture.spec.simulateBondRemoval()
        fixture.connect()
        fixture.peripheral.createBond()
        runCurrent()

        assertEquals(ENCRYPTED, fixture.peripheral.encryptionState.value)
    }

    @Test
    fun `removing the bond resets the encryption state`() = runTest {
        val fixture = fixture(bonded = true)
        fixture.connect()
        runCurrent()

        fixture.peripheral.removeBond()
        runCurrent()

        assertEquals(EncryptionState.Unknown, fixture.peripheral.encryptionState.value)
        assertIs<BondState.None>(fixture.peripheral.bondState.value)
    }

    @Test
    fun `encryption state is unknown before Android 16`() = runTest {
        val fixture = fixture(bonded = true, environment = MockAndroidEnvironment.Api31())

        fixture.connect()
        runCurrent()

        assertEquals(EncryptionState.Unknown, fixture.peripheral.encryptionState.value)
    }
}
