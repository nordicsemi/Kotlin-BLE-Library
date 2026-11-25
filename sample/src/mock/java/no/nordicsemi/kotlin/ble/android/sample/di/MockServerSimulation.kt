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

package no.nordicsemi.kotlin.ble.android.sample.di

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.android.sample.scanner.profile.LedButtonProfile
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.server.android.mock.MockLocalServer
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Simulates a client running the LED Button client (the Connection screen of another phone).
 *
 * Whenever the LED Button Service is available in the manager, the client connects, negotiates
 * a larger MTU and subscribes to the Button characteristic. Then it toggles the LED every
 * few seconds and, in between, the button is pressed and released, which sends notifications
 * to the client. When the service is removed, the client disconnects.
 */
fun MockLocalServer.simulateLedButtonClient(scope: CoroutineScope) {
    val client = simulateClient(address = "AA:BB:CC:DD:EE:01")
    scope.launch {
        services
            .map { services -> services.any { it.uuid == LedButtonProfile.SERVICE_UUID } }
            .distinctUntilChanged()
            .collectLatest { available ->
                if (!available) {
                    client.disconnect()
                    return@collectLatest
                }
                try {
                    delay(1.seconds)
                    client.connect()
                    val _ = client.simulateMtuChange(247)

                    // Discover the service of the local server, the way a client application does.
                    val service = client.server
                        .services(listOf(LedButtonProfile.SERVICE_UUID))
                        .filterIsInstance<RemoteServices.Discovered>()
                        .first { it.services.isNotEmpty() }
                        .services.single()
                    val led = service.characteristics.first { it.uuid == LedButtonProfile.LED_CHARACTERISTIC_UUID }
                    val button = service.characteristics.first { it.uuid == LedButtonProfile.BUTTON_CHARACTERISTIC_UUID }
                    // Subscribing enables notifications, which are not used by the simulation itself.
                    launch { button.subscribe().collect { Timber.d("Simulated client: button %s", it.toList()) } }

                    // The characteristic of the local GATT server, to simulate button presses.
                    val buttonCharacteristic = services.value
                        .first { it.uuid == LedButtonProfile.SERVICE_UUID }
                        .characteristics
                        .first { it.uuid == LedButtonProfile.BUTTON_CHARACTERISTIC_UUID }

                    coroutineScope {
                        launch {
                            var on = false
                            while (true) {
                                delay(4.seconds)
                                on = !on
                                led.write(byteArrayOf(if (on) 1 else 0), WriteType.WITH_RESPONSE)
                            }
                        }
                        launch {
                            while (true) {
                                delay(3.seconds)
                                buttonCharacteristic.update(byteArrayOf(1))
                                delay(600.milliseconds)
                                buttonCharacteristic.update(byteArrayOf(0))
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Simulated client failed")
                }
            }
    }
}
