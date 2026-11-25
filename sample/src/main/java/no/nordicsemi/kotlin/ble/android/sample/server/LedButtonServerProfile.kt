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
import kotlinx.coroutines.flow.update
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
 * The LED characteristic can be written by the connected client, and the Button characteristic
 * notifies the subscribed clients about the state of the button. The service itself is defined
 * by the application; the profile starts when it appears and restarts if the services are replaced.
 */
class LedButtonServerProfile : LocalProfile.Simple(
    serviceUuid = LedButtonProfile.SERVICE_UUID,
    name = "LED Button Server",
) {
    private var led: LocalCharacteristic? = null
    private var button: LocalCharacteristic? = null

    /** The state of the LED, set by the client. */
    val isLedOn: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** The state of the Button, set by the user. */
    val isButtonPressed: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** The number of clients subscribed to the Button characteristic. */
    val subscribersCount: StateFlow<Int>
        field = MutableStateFlow(0)

    override fun prepare(server: LocalServer<*>, service: LocalService) {
        val characteristics = service.characteristics
        led = characteristics.first { it.uuid == LedButtonProfile.LED_CHARACTERISTIC_UUID }
        button = characteristics.first { it.uuid == LedButtonProfile.BUTTON_CHARACTERISTIC_UUID }
    }

    override suspend fun CoroutineScope.initialize(target: LocalServer<*>) {
        val led = led!!
        val button = button!!

        // Initial values.
        isLedOn.update { false }
        isButtonPressed.update { false }
        led.setValue(byteArrayOf(0))
        button.setValue(byteArrayOf(0))

        // The LED accepts exactly one byte.
        led.onWrite {
            if (value.size == 1) {
                WriteResponse.Success
            } else {
                WriteResponse.Failure(OperationStatus.InvalidAttributeLength)
            }
        }
        led.writes
            .onEach { request ->
                val on = request.value.first() != 0.toByte()
                Timber.i("LED turned ${if (on) "ON" else "OFF"} by ${request.client.identifier}")
                isLedOn.update { on }
            }
            .launchIn(this)
        // The button state follows the value of the characteristic, so it
        // changes also when the value is set from somewhere else.
        button.value
            .map { (it.firstOrNull() ?: 0) != 0.toByte() }
            .onEach { pressed -> isButtonPressed.update { pressed } }
            .launchIn(this)
        button.subscribers
            .map { it.size }
            .onEach { count -> subscribersCount.update { count } }
            .launchIn(this)

        try {
            awaitCancellation()
        } finally {
            isButtonPressed.update { false }
            subscribersCount.update { 0 }
        }
    }

    override suspend fun unsupported(target: LocalServer<*>) {
        Timber.w("The LED Button Service is not as expected")
    }

    /**
     * Sets the state of the button and notifies the subscribed clients.
     *
     * Does nothing if the profile is not running.
     */
    suspend fun setButtonPressed(pressed: Boolean) {
        val button = button ?: return
        val value = byteArrayOf(if (pressed) 1 else 0)
        // Update the value right away, so that the UI is responsive, then notify.
        button.setValue(value)
        button.update(value)
    }
}
