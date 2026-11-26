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

package no.nordicsemi.kotlin.ble.android.sample.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.kotlin.ble.android.sample.scanner.profile.LedButtonProfile
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.server.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.LocalProfile
import no.nordicsemi.kotlin.ble.server.LocalServer
import no.nordicsemi.kotlin.ble.server.LocalService
import no.nordicsemi.kotlin.ble.server.WriteResponse
import timber.log.Timber

/**
 * The logic of the Nordic LED Button Service (LBS) hosted by this device.
 *
 * The Button is controlled by this device: changing [buttonState] notifies the subscribed
 * clients. The LED is controlled by the client: when it writes the LED characteristic, [ledState]
 * changes. The service itself is defined by the application; the profile starts when it appears
 * and restarts if the services are replaced.
 */
class LedButtonServerProfile : LocalProfile.Simple(
    serviceUuid = LedButtonProfile.SERVICE_UUID,
    name = "LED Button Server",
) {
    private var ledCharacteristic: LocalCharacteristic? = null
    private var buttonCharacteristic: LocalCharacteristic? = null

    /** The state of the LED, set by the client. */
    val ledState: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** The state of the Button, set by this device. A change is sent to the subscribed clients. */
    val buttonState = MutableStateFlow(false)

    /** The number of clients subscribed to the Button characteristic. */
    val subscribersCount: StateFlow<Int>
        field = MutableStateFlow(0)

    override fun prepare(server: LocalServer<*>, service: LocalService) {
        val characteristics = service.characteristics
        ledCharacteristic = characteristics.first { it.uuid == LedButtonProfile.LED_CHARACTERISTIC_UUID }
        buttonCharacteristic = characteristics.first { it.uuid == LedButtonProfile.BUTTON_CHARACTERISTIC_UUID }
    }

    override suspend fun CoroutineScope.initialize(server: LocalServer<*>) {
        val ledCharacteristic = ledCharacteristic!!
        val buttonCharacteristic = buttonCharacteristic!!

        // Initial values.
        buttonState.value = false
        ledCharacteristic.setValue(byteArrayOf(0))
        buttonCharacteristic.setValue(byteArrayOf(0))

        // The LED accepts exactly one byte.
        ledCharacteristic.onWrite {
            if (value.size == 1) {
                WriteResponse.Success
            } else {
                WriteResponse.Failure(OperationStatus.InvalidAttributeLength)
            }
        }
        // The state follows the value of the characteristic, so it is also correct after
        // the value was set from somewhere else.
        ledCharacteristic.value
            .onEach { value -> ledState.value = value.singleOrNull() == 1.toByte() }
            .launchIn(this)
        // The writes carry the client that sent them. They are used only for logging here.
        ledCharacteristic.writes
            .onEach { request -> Timber.i("LED written by ${request.client.identifier}") }
            .launchIn(this)
        // Every change of the button is sent to the clients that subscribed to it.
        buttonState
            .map { pressed -> byteArrayOf(if (pressed) 1 else 0) }
            .onEach { value ->
                try {
                    buttonCharacteristic.update(value)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to notify about the button state")
                }
            }
            .launchIn(this)
        buttonCharacteristic.subscribers
            .map { it.size }
            .onEach { count -> subscribersCount.value = count }
            .launchIn(this)

        try {
            awaitCancellation()
        } finally {
            buttonState.value = false
            subscribersCount.value = 0
        }
    }

    override suspend fun unsupported(server: LocalServer<*>) {
        Timber.w("The LED Button Service is not as expected")
    }
}
