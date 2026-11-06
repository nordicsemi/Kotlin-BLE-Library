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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.CentralManager.ConnectionOptions
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.BondResult
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Permission
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Operations are executed one at a time on a peripheral, but peripherals do not wait for each other.
 */
class OperationLockTest {

    /**
     * A peripheral with a readable characteristic. Every read takes one connection interval, which
     * is 2 seconds with the given interval of 1600 (in 1.25 ms units).
     */
    private fun spec(
        address: String,
        connectionInterval: Int,
        handler: GattHandler = GattHandler().apply {
            readHandler = { ReadResponse.Success(byteArrayOf(1, 2, 3)) }
        },
        permissions: Set<Permission> = setOf(Permission.READ),
    ) = PeripheralSpec.simulatePeripheral(
        identifier = address,
        // Android assumes PUBLIC address type for devices that weren't scanned.
        addressType = AddressType.PUBLIC,
    ) {
        advertising {
            CompleteLocalName("Test")
        }
        connectable(
            name = "Test",
            preferredConnectionInterval = connectionInterval..connectionInterval,
            eventHandler = handler,
        ) {
            Service(GATT_SERVICE_UUID) {
                Characteristic(
                    GATT_CHAR_UUID,
                    setOf(CharacteristicProperty.READ),
                    permissions,
                )
            }
        }
    }

    private suspend fun CentralManager.characteristicOf(address: String): RemoteCharacteristic {
        val peripheral = checkNotNull(getPeripheralById(address))
        connect(peripheral, ConnectionOptions.Direct(automaticallyRequestHighestValueLength = false))
        return peripheral.services()
            .filterIsInstance<RemoteServices.Discovered>()
            .first()
            .services
            .first { it.uuid == GATT_SERVICE_UUID }
            .characteristics
            .first { it.uuid == GATT_CHAR_UUID }
    }

    private fun TestScope.centralManager(vararg specs: PeripheralSpec<String>) =
        CentralManager.mock(scope = backgroundScope).apply { simulatePeripherals(specs.toList()) }

    @Test
    fun `a slow operation on one peripheral does not delay another peripheral`() = runTest {
        withTimeout(60.seconds) {
            val manager = centralManager(spec(SLOW, 1600), spec(FAST, 24))
            val slow = manager.characteristicOf(SLOW)
            val fast = manager.characteristicOf(FAST)

            val start = testScheduler.currentTime
            var slowDone = 0L
            var fastDone = 0L
            listOf(
                async { val _ = slow.read(); slowDone = testScheduler.currentTime - start },
                async { val _ = fast.read(); fastDone = testScheduler.currentTime - start },
            ).awaitAll()

            assertTrue(slowDone >= 2_000, "The slow read took $slowDone ms")
            assertTrue(fastDone < 500, "The fast read waited for the slow one, it took $fastDone ms")
        }
    }

    @Test
    fun `bonding of one peripheral does not delay another peripheral`() = runTest {
        withTimeout(60.seconds) {
            // Reading the characteristic requires encryption, so the phone starts bonding and the
            // read is held until the user confirms pairing, which takes 5 seconds.
            val pairing = GattHandler().apply {
                readHandler = { ReadResponse.Success(byteArrayOf(1, 2, 3)) }
                bondResult = BondResult.Accept(5.seconds)
            }
            val manager = centralManager(
                spec(SLOW, 24, pairing, setOf(Permission.READ_ENCRYPTED)),
                spec(FAST, 24),
            )
            val secured = manager.characteristicOf(SLOW)
            val fast = manager.characteristicOf(FAST)

            val start = testScheduler.currentTime
            var securedDone = 0L
            var fastDone = 0L
            listOf(
                async { val _ = secured.read(); securedDone = testScheduler.currentTime - start },
                async { val _ = fast.read(); fastDone = testScheduler.currentTime - start },
            ).awaitAll()

            assertTrue(securedDone >= 5_000, "The read waited only $securedDone ms for the bonding")
            assertTrue(fastDone < 500, "The read waited for the bonding of another peripheral, it took $fastDone ms")
        }
    }

    @Test
    fun `operations on one peripheral are executed one at a time`() = runTest {
        withTimeout(60.seconds) {
            val manager = centralManager(spec(SLOW, 1600))
            val slow = manager.characteristicOf(SLOW)

            val start = testScheduler.currentTime
            val done = mutableListOf<Long>()
            List(2) {
                async { val _ = slow.read(); done += testScheduler.currentTime - start }
            }.awaitAll()

            // The second read starts when the first one ends.
            assertTrue(done[0] in 2_000 until 3_000, "The first read took ${done[0]} ms")
            assertTrue(done[1] >= 4_000, "The second read took ${done[1]} ms, it did not wait for the first one")
        }
    }

    private companion object {
        const val SLOW = "AA:BB:CC:DD:EE:01"
        const val FAST = "AA:BB:CC:DD:EE:02"
    }
}
