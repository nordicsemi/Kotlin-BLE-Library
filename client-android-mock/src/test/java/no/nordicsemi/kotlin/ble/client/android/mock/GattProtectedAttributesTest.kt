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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.mock.BondResult
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.client.android.BondState
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val readable = setOf(CharacteristicProperty.READ)
private val writable = setOf(CharacteristicProperty.WRITE)

/**
 * Tests of bonding triggered by accessing an attribute that requires authentication.
 *
 * Like a real phone, the mock holds the request, bonds with the peripheral and then completes it.
 * The bonding level is not simulated, the peripheral may only accept or reject the bonding.
 */
class GattProtectedAttributesTest {

    @Test
    fun `bonded phone reads an encrypted characteristic without bonding`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { readHandler = { ReadResponse.Success(byteArrayOf(0x01)) } }
            val fixture = gattSingleCharacteristic(
                handler,
                bonded = true,
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            val result = fixture.characteristic().read()

            assertContentEquals(byteArrayOf(0x01), result)
            assertEquals(0, handler.bondRequests)
        }
    }

    @Test
    fun `bonding is requested once for many requests`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )
            val characteristic = fixture.characteristic()

            characteristic.read()
            characteristic.read()

            assertEquals(1, handler.bondRequests)
            assertEquals(2, handler.reads.size)
        }
    }

    @Test
    fun `characteristic that does not require authentication does not bond`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(handler, properties = readable, permissions = setOf(Permission.READ))

            fixture.characteristic().read()

            assertEquals(0, handler.bondRequests)
            assertIs<BondState.None>(fixture.peripheral.bondState.value)
        }
    }

    @Test
    fun `request is held until the bonding finishes`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattSingleCharacteristic(
                properties = writable,
                permissions = setOf(Permission.WRITE_ENCRYPTED),
            )
            val characteristic = fixture.characteristic()
            val states = mutableListOf<BondState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.peripheral.bondState.toList(states)
            }

            characteristic.write(byteArrayOf(0x01))

            assertEquals(listOf(BondState.None(), BondState.Bonding, BondState.Bonded), states)
        }
    }

    @Test
    fun `peripheral that still has the bond rejects bonding and the request fails`() = runTest {
        withTimeout(10.seconds) {
            // The phone forgot the bond, but the peripheral did not.
            val handler = GattHandler()
            val fixture = gattSingleCharacteristic(
                handler,
                peripheralHasBond = true,
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            val exception = assertFailsWith<OperationFailedException> { fixture.characteristic().read() }

            assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
            assertIs<BondState.None>(fixture.peripheral.bondState.value)
            assertEquals(0, handler.bondRequests)
        }
    }

    @Test
    fun `encrypted descriptor read bonds and succeeds`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { descriptorReadHandler = { ReadResponse.Success(byteArrayOf(0x0A)) } }
            val fixture = gattFixture(handler) {
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
    fun `encrypted descriptor read fails with InsufficientAuthentication when bonding is rejected`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler().apply { bondResult = BondResult.Reject() }
            val fixture = gattFixture(handler) {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, setOf(Permission.READ)) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.READ_ENCRYPTED)
                    }
                }
            }

            val exception = assertFailsWith<OperationFailedException> { fixture.descriptor().read() }

            assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
            assertTrue(handler.descriptorReads.isEmpty())
        }
    }

    @Test
    fun `encrypted descriptor write bonds and succeeds`() = runTest {
        withTimeout(10.seconds) {
            val handler = GattHandler()
            val fixture = gattFixture(handler) {
                Service(GATT_SERVICE_UUID) {
                    Characteristic(GATT_CHAR_UUID, readable, setOf(Permission.READ)) {
                        Descriptor(GATT_DESCRIPTOR_UUID, Permission.WRITE_ENCRYPTED)
                    }
                }
            }

            fixture.descriptor().write(byteArrayOf(0x01))

            assertEquals(1, handler.descriptorWrites.size)
            assertEquals(BondState.Bonded, fixture.peripheral.bondState.value)
        }
    }

    @Test
    fun `bond created by a protected attribute is returned with bonded peripherals`() = runTest {
        withTimeout(10.seconds) {
            val fixture = gattSingleCharacteristic(
                properties = readable,
                permissions = setOf(Permission.READ_ENCRYPTED),
            )

            fixture.characteristic().read()

            assertEquals(listOf(GATT_ADDRESS), fixture.manager.getBondedPeripherals().map { it.address })
        }
    }
}
