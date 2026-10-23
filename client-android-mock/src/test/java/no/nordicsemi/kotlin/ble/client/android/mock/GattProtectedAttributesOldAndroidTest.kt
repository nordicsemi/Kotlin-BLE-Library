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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.mock.BondResult
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.client.android.BondState
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val readable = setOf(CharacteristicProperty.READ)

/**
 * Tests of accessing protected attributes on Android versions that do not repeat the request after
 * bonding (Android 7 and older, confirmed with an HCI log of Android 6.0.1): the system fails
 * the request with Insufficient Authentication, bonds in the background, and the library repeats
 * the request when the bonding succeeds.
 */
class GattProtectedAttributesOldAndroidTest {

    private val oldAndroid = MockAndroidEnvironment.Api23()

    @Test
    fun `read of an encrypted characteristic is repeated after bonding`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(byteArrayOf(0x01)) } }
            val fixture = gattSingleCharacteristic(
                handler,
                environment = oldAndroid,
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            val result = fixture.characteristic().read()

            assertContentEquals(byteArrayOf(0x01), result)
            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
            // The first attempt failed in the system, it never reached the peripheral.
            assertEquals(1, handler.reads.size)
            assertEquals(1, handler.bondRequests)
        }
    }

    @Test
    fun `write to an encrypted characteristic is repeated after bonding`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                environment = oldAndroid,
                properties = setOf(CharacteristicProperty.WRITE),
                permissions = setOf(Permission.WRITE_ENCRYPTED),
            )

            fixture.characteristic().write(byteArrayOf(0x01))

            assertEquals(1, handler.writeRequests.size)
            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
        }
    }

    @Test
    fun `encrypted descriptor read is repeated after bonding`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { descriptorReadHandler = { ReadResponse.Success(byteArrayOf(0x0A)) } }
            val fixture = gattFixture(handler, environment = oldAndroid) {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, setOf(Permission.READ)) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.READ_ENCRYPTED)
                    }
                }
            }

            val value = fixture.descriptor().read()

            assertContentEquals(byteArrayOf(0x0A), value)
            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
        }
    }

    @Test
    fun `enabling notifications on a protected characteristic is repeated after bonding`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattFixture(environment = oldAndroid) {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, setOf(CharacteristicProperty.NOTIFY), setOf(Permission.READ)) {
                        ClientCharacteristicConfigurationDescriptor(
                            permissions = setOf(Permission.READ, Permission.WRITE_ENCRYPTED),
                        )
                    }
                }
            }
            val characteristic = fixture.characteristic()

            characteristic.setNotifying(true)

            assertTrue(characteristic.isNotifying.first { it })
            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
        }
    }

    @Test
    fun `request is not answered when bonding is rejected, and fails when the link is terminated`() = runTest {
        withTimeout(10.seconds) {
            // The system drops the request, and then, after bonding failed, terminates the link.
            val handler = GattHandler().apply { bondResult = BondResult.Reject() }
            val fixture = gattSingleCharacteristic(
                handler,
                environment = oldAndroid,
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            assertFailsWith<InvalidAttributeException> { fixture.characteristic().read() }

            assertIs<BondState.None>(fixture.peripheral.bondState.value)
            assertTrue(handler.reads.isEmpty())
        }
    }

    @Test
    fun `request that failed with Insufficient Authentication is repeated after bonding`() = runTest {
        withTimeout(10.seconds) {
            // Some devices return the error to the app, and start bonding.
            lateinit var fixture: GattFixture
            val handler = GattHandler()
            var attempts = 0
            handler.readHandler = {
                if (attempts++ == 0) {
                    fixture.spec.simulateBonding()
                    ReadResponse.Failure(OperationStatus.InsufficientAuthentication)
                } else {
                    ReadResponse.Success(byteArrayOf(0x01))
                }
            }
            fixture = gattSingleCharacteristic(
                handler,
                environment = oldAndroid,
                properties = readable,
                permissions = setOf(Permission.READ),
            )

            val value = fixture.characteristic().read()

            assertContentEquals(byteArrayOf(0x01), value)
            assertEquals(2, attempts)
            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
        }
    }

    @Test
    fun `request completed shortly after bonding is not repeated`() = runTest {
        withTimeout(10.seconds) {
            // The bonding completes before the response arrives, but the system does answer
            // the request, like Android 8+ does.
            lateinit var fixture: GattFixture
            val handler = GattHandler()
            handler.readHandler = {
                fixture.spec.simulateBonding()
                ReadResponse.Success(byteArrayOf(0x01))
            }
            fixture = gattSingleCharacteristic(
                handler,
                environment = oldAndroid,
                properties = readable,
                permissions = setOf(Permission.READ),
            )

            val value = fixture.characteristic().read()

            assertContentEquals(byteArrayOf(0x01), value)
            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
            assertEquals(1, handler.reads.size)
        }
    }

    @Test
    fun `request that failed with Insufficient Authentication is not repeated when bonding is rejected`() = runTest {
        withTimeout(10.seconds) {
            lateinit var fixture: GattFixture
            val handler = GattHandler().apply { bondResult = BondResult.Reject() }
            handler.readHandler = {
                fixture.spec.simulateBonding()
                ReadResponse.Failure(OperationStatus.InsufficientAuthentication)
            }
            fixture = gattSingleCharacteristic(
                handler,
                environment = oldAndroid,
                properties = readable,
                permissions = setOf(Permission.READ),
            )

            val exception = assertFailsWith<OperationFailedException> { fixture.characteristic().read() }

            assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
            assertEquals(1, handler.reads.size)
        }
    }

    @Test
    fun `request is not repeated when the phone was bonded and the peripheral lost the bond`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                bonded = true,
                peripheralHasBond = false,
                environment = oldAndroid,
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            val exception = assertFailsWith<OperationFailedException> { fixture.characteristic().read() }

            assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
            assertEquals(0, handler.bondRequests)
        }
    }

    @Test
    fun `request that failed for another reason is not repeated`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply {
                readHandler = { ReadResponse.Failure(OperationStatus.InvalidHandle) }
            }
            val fixture = gattSingleCharacteristic(
                handler,
                environment = oldAndroid,
                properties = readable,
                permissions = setOf(Permission.READ),
            )

            val exception = assertFailsWith<OperationFailedException> { fixture.characteristic().read() }

            assertEquals(OperationStatus.InvalidHandle, exception.reason)
            assertEquals(1, handler.reads.size)
        }
    }

    @Test
    fun `system that repeats requests does not fail them`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                environment = MockAndroidEnvironment.Api26(),
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            fixture.characteristic().read()

            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
            assertEquals(1, handler.reads.size)
        }
    }
}
