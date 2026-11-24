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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.android.sample.scanner.profile.LedButtonProfile
import no.nordicsemi.kotlin.ble.server.android.mock.MockLocalServer
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Simulates a central running the LED Button client (the Connection screen of another phone).
 *
 * Whenever the LED Button Service is available in the manager, the central connects, negotiates
 * a larger MTU and subscribes to the Button characteristic. Then it toggles the LED every
 * few seconds and, in between, the button is pressed and released, which sends notifications
 * to the central. When the service is removed, the central disconnects.
 */
fun MockLocalServer.simulateLedButtonCentral(scope: CoroutineScope) {
    val central = simulateCentral(address = "AA:BB:CC:DD:EE:01")
    scope.launch {
        services
            .map { services -> services.any { it.uuid == LedButtonProfile.SERVICE_UUID } }
            .distinctUntilChanged()
            .collectLatest { available ->
                if (!available) {
                    central.disconnect()
                    return@collectLatest
                }
                try {
                    delay(1.seconds)
                    central.connect()
                    val _ = central.simulateMtuChange(247)

                    val led = central.findCharacteristic(
                        LedButtonProfile.SERVICE_UUID,
                        LedButtonProfile.LED_CHARACTERISTIC_UUID,
                    ) ?: return@collectLatest
                    val button = central.findCharacteristic(
                        LedButtonProfile.SERVICE_UUID,
                        LedButtonProfile.BUTTON_CHARACTERISTIC_UUID,
                    ) ?: return@collectLatest
                    val _ = central.subscribe(button)

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
                                val _ = central.write(led, byteArrayOf(if (on) 1 else 0))
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
                    Timber.w(e, "Simulated central failed")
                }
            }
    }
}
