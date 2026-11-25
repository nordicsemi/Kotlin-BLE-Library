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

package no.nordicsemi.kotlin.ble.android.sample.scanner.profile.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.android.sample.scanner.profile.LedButtonProfile
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import timber.log.Timber

/**
 * This class implements the [LedButtonProfile.State] interface.
 *
 * The given remote GATT service is validated against the LBS profile requirements:
 * * Required characteristics (button, LED)
 * * Required properties (notifications, write, etc.)
 *
 * The Bluetooth LE implementation is then exposed as a high-level interface of a device, allowing
 * to control the device using [led] and [button]. Additional [buttonPressed] and
 * [buttonLongPressed] events emit when the button is pressed.
 */
class LedButtonServiceImpl(
    private val ledButtonService: RemoteService,
    private val scope: CoroutineScope,
): LedButtonProfile.State {
    init {
        require(ledButtonService.uuid == LedButtonProfile.SERVICE_UUID) {
            "Unrecognized service UUID: ${ledButtonService.uuid}"
        }
    }

    /**
     * The GATT characteristics of the LED Button Service (LBS) for controlling the LED state on
     * the remote peripheral.
     *
     * Possible values are:
     * * 0x00 - LED is off.
     * * 0x01 - LED is on.
     * @see LedButtonProfile.LED_CHARACTERISTIC_UUID
     */
    private var ledCharacteristic: RemoteCharacteristic = ledButtonService.characteristics
        .first { it.uuid == LedButtonProfile.LED_CHARACTERISTIC_UUID }

    /**
     * The GATT characteristics of the LED Button Service (LBS) notified when the Button state on
     * the remote peripheral changes.
     *
     * Possible values are:
     * * 0x00 - Button is released.
     * * 0x01 - Button is pressed.
     * @see LedButtonProfile.BUTTON_CHARACTERISTIC_UUID
     */
    private var buttonCharacteristic: RemoteCharacteristic = ledButtonService.characteristics
        .first { it.uuid == LedButtonProfile.BUTTON_CHARACTERISTIC_UUID }

    init {
        require(ledCharacteristic.isWritable()) {
            "LED characteristic must have WRITE or WRITE WITHOUT RESPONSE property"
        }
        require(buttonCharacteristic.isSubscribable()) {
            "Button characteristic must have NOTIFY or INDICATE property"
        }
    }

    override val led = MutableStateFlow(false)
        .also { flow ->
            scope.launch(Dispatchers.IO) {
                // Read initial state from the characteristic.
                try {
                    val rawLedValue = ledCharacteristic.read()
                    val isOn = rawLedValue.state

                    // Update the local state.
                    flow.update { isOn }
                } catch (e: CancellationException) {
                    // Rethrow the cancellation exception.
                    throw e
                } catch (_: InvalidAttributeException) {
                    // This exception is thrown when the device disconnects, or invalidates services.
                    Timber.w("Services invalidated before reading the LED characteristic")
                } catch (e: OperationFailedException) {
                    // In some implementations the LED characteristic is not readable.
                    Timber.w("Reading LED characteristic failed: ${e.message}")
                } catch (e: Exception) {
                    // Other errors.
                    Timber.e("Reading LED characteristic failed: ${e.message}")
                }

                // Whenever the value changes, write the value to the characteristic.
                try {
                    flow.collect { value ->
                        try {
                            val command = byteArrayOf(if (value) 1 else 0)
                            ledCharacteristic.write(command)
                        } catch (e: CancellationException) {
                            // Rethrow the cancellation exception.
                            throw e
                        } catch (_: InvalidAttributeException) {
                            // This exception is thrown when the device disconnects, or invalidates services.
                            Timber.w("Services invalidated before writing to LED characteristic")
                        } catch (e: OperationFailedException) {
                            // This exception is thrown when the device disconnects, but the client
                            // doesn't know about it before writing to the characteristic.
                            // This usually indicates error 133.
                            Timber.w("Writing to LED characteristic failed: ${e.message}")
                        } catch (e: Exception) {
                            // Other errors.
                            Timber.e("Writing to LED characteristic failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.d("Stopped observing LED events")
                    throw e
                }
            }
        }

    override val button: StateFlow<Boolean> = MutableStateFlow(false)
        .also { flow ->
            scope.launch {
                try {
                    // Read initial state from the characteristic.
                    val rawButtonValue = buttonCharacteristic.read()
                    val pressed = rawButtonValue.state

                    // Update the local state.
                    flow.update { pressed }
                } catch (e: CancellationException) {
                    // Rethrow the cancellation exception.
                    throw e
                } catch (_: InvalidAttributeException) {
                    // This exception is thrown when the device disconnects, or invalidates services.
                    Timber.w("Services invalidated before reading the Button characteristic")
                } catch (e: OperationFailedException) {
                    // In some implementations the Button characteristic is not readable.
                    Timber.w("Reading button characteristic failed: ${e.message}")
                } catch (e: Exception) {
                    // Other errors.
                    Timber.e("Reading button characteristic failed: ${e.message}")
                }

                // By having a local mutable flow we call subscribe() only once.
                try {
                    buttonCharacteristic.subscribe()
                        .catch { t ->
                            Timber.w("Button characteristic subscription failed: ${t.message}")
                        }
                        .collect { flow.emit(it.state) }
                } catch (e: CancellationException) {
                    // Rethrow the cancellation exception.
                    throw e
                } catch (e: Exception) {
                    Timber.w("Button characteristic subscription failed: ${e.message}")
                }
            }
        }

    override val buttonPressed: Flow<Unit> = flow {
        /** Flag set when a long press is detected. */
        var isLongPress = false

        // Drop the initial value, button state is a StateFlow.
        button.drop(1).collect { pressed ->
            // If the button was pressed, start a timeout to detect a long press events.
            if (pressed) {
                try {
                    withTimeout(LedButtonProfile.LONG_PRESS_TIMEOUT) {
                        // Await the button to be released before the timeout.
                        button.drop(1).first { !it }
                    }
                } catch (_: TimeoutCancellationException) {
                    // Button has not been released before the time runed out.
                    isLongPress = true
                }
            } else {
                // If released, emit the event only if it was not a long press.
                if (!isLongPress) {
                    emit(Unit)
                }
                isLongPress = false
            }
        }
    }

    override val buttonLongPressed: Flow<Unit> = button
        .flatMapLatest { pressed ->
            if (pressed) flow {
                delay(LedButtonProfile.LONG_PRESS_TIMEOUT)
                emit(Unit)
            } else emptyFlow()
        }

    /**
     * Parses the raw value of LED and Button (0x00 or 0x01) to [Boolean].
     *
     * Note: In LED Button Service both LED and Button use the same state encoding.
     */
    private val ByteArray.state: Boolean
        get() = size == 1 && this[0] == 0x01.toByte()
}
