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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Permission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class NotificationOrderTest {
    private val serviceUuid = Uuid.random()
    private val characteristicUuid = Uuid.random()
    private var handle: Int = -1

    private val peripheralSpec = PeripheralSpec.simulatePeripheral(
        identifier = "AA:BB:CC:DD:EE:FF",
        // Android assumes PUBLIC address type for devices that weren't scanned.
        addressType = AddressType.PUBLIC,
    ) {
        advertising {
            CompleteLocalName("Test")
        }
        connectable(name = "Test", eventHandler = object : PeripheralSpecEventHandler {}) {
            Service(uuid = serviceUuid) {
                handle = Characteristic(
                    uuid = characteristicUuid,
                    property = CharacteristicProperty.NOTIFY,
                    permission = Permission.READ,
                )
            }
        }
    }

    @Test
    fun `notifications are received in the order they were sent`() = runBlocking {
        // The notifications are delivered by coroutines, so this has to run on a dispatcher with
        // many threads, where nothing but the mock keeps the order of the coroutines.
        val simulationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(30.seconds) {
                val centralManager = CentralManager.mock(scope = simulationScope)
                    .apply { simulatePeripherals(listOf(peripheralSpec)) }
                val peripheral = centralManager.getPeripheralById(peripheralSpec.identifier)!!
                centralManager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
                val characteristic = peripheral.services()
                    .filterIsInstance<RemoteServices.Discovered>()
                    .first()
                    .services
                    .first { it.uuid == serviceUuid }
                    .characteristics
                    .first { it.uuid == characteristicUuid }

                val received = Channel<ByteArray>(Channel.UNLIMITED)
                characteristic.subscribe()
                    .onEach { received.send(it) }
                    .launchIn(simulationScope)
                characteristic.isNotifying.first { it }

                // A link transmits the notifications one by one, in the order they were queued.
                // The values have different lengths, as sending a longer one takes longer. The
                // index is in the first (high byte) and the last (low byte) byte of the value.
                val count = 500
                repeat(count) { index ->
                    val value = ByteArray(2 + index % 19)
                    value[0] = (index shr 8).toByte()
                    value[value.size - 1] = index.toByte()
                    peripheralSpec.simulateValueUpdate(handle, value)
                }

                val order = List(count) {
                    val value = withTimeout(10.seconds) { received.receive() }
                    (value.first().toInt() and 0xFF shl 8) or (value.last().toInt() and 0xFF)
                }
                assertEquals((0 until count).toList(), order)
            }
        } finally {
            simulationScope.cancel()
        }
    }
}
