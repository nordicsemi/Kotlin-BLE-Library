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

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nordicsemi.kotlin.ble.advertiser.android.AdvertisingPayload
import no.nordicsemi.kotlin.ble.advertiser.android.BluetoothLeAdvertiser
import no.nordicsemi.kotlin.ble.android.sample.scanner.profile.LedButtonProfile
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.server.WriteResponse
import no.nordicsemi.kotlin.ble.server.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.android.LocalServer
import timber.log.Timber
import javax.inject.Inject

/**
 * A ViewModel of the Server screen, which hosts a Nordic LED Button Service (LBS).
 *
 * The LED characteristic can be written by the connected central, and the Button characteristic
 * notifies the subscribed centrals about the state of the button.
 */
@HiltViewModel
class ServerViewModel @Inject constructor(
    private val localServer: LocalServer,
    private val advertiser: BluetoothLeAdvertiser,
    private val scope: CoroutineScope,
) : ViewModel() {
    val state = localServer.state

    /** Whether the server is running, that is the services are set up. */
    val isRunning: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Whether the server is advertising. */
    val isAdvertising: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** The state of the LED, set by the central. */
    val isLedOn: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** The state of the Button, set by the user. */
    val isButtonPressed: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** The number of centrals subscribed to the Button characteristic. */
    val subscribersCount: StateFlow<Int>
        field = MutableStateFlow(0)

    val error: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    /** The centrals connected to the server, together with their connection details. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val centrals: StateFlow<List<CentralInfo>> = localServer.connectedCentrals
        .flatMapLatest { centrals ->
            if (centrals.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    centrals.map { central ->
                        combine(
                            central.state,
                            central.mtu,
                            central.phy,
                            central.connectionParameters,
                        ) { state, mtu, phy, parameters ->
                            CentralInfo(
                                address = central.address,
                                state = state,
                                mtu = mtu,
                                phy = phy,
                                connectionParameters = parameters as? ConnectionParameters.Specified,
                            )
                        }
                    }
                ) { it.toList() }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var serverJob: Job? = null
    private var buttonCharacteristic: LocalCharacteristic? = null

    fun onStartRequested() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            error.update { null }
            try {
                coroutineScope {
                    val services = localServer.setup {
                        Service(LedButtonProfile.SERVICE_UUID) {
                            Characteristic(
                                uuid = LedButtonProfile.BUTTON_CHARACTERISTIC_UUID,
                                properties = CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
                                permission = Permission.READ,
                            )
                            Characteristic(
                                uuid = LedButtonProfile.LED_CHARACTERISTIC_UUID,
                                properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
                                permissions = Permission.READ and Permission.WRITE,
                            )
                        }
                    }
                    val characteristics = services.first().characteristics
                    val button = characteristics.first { it.uuid == LedButtonProfile.BUTTON_CHARACTERISTIC_UUID }
                    val led = characteristics.first { it.uuid == LedButtonProfile.LED_CHARACTERISTIC_UUID }
                    buttonCharacteristic = button

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
                            Timber.i("LED turned ${if (on) "ON" else "OFF"} by ${request.central.identifier}")
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

                    isRunning.update { true }
                    // Let the other devices find the server.
                    launch { advertise() }
                    awaitCancellation()
                }
            } catch (_: CancellationException) {
                Timber.i("Server stopped")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start the server")
                error.update { e.message ?: e.toString() }
            } finally {
                buttonCharacteristic = null
                isRunning.update { false }
                isButtonPressed.update { false }
                subscribersCount.update { 0 }
                // Remove the services.
                withContext(NonCancellable) {
                    try {
                        localServer.setup { }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to remove the services")
                    }
                }
            }
        }
    }

    fun onStopRequested() {
        serverJob?.cancel()
    }

    /** Sets the state of the button and notifies the subscribed centrals. */
    fun onButtonPressedChanged(pressed: Boolean) {
        val button = buttonCharacteristic ?: return
        val value = byteArrayOf(if (pressed) 1 else 0)
        // Update the value right away, so that the UI is responsive, then notify.
        button.setValue(value)
        scope.launch {
            try {
                button.update(value)
            } catch (e: Exception) {
                Timber.w(e, "Failed to notify about the button state")
            }
        }
    }

    private suspend fun advertise() {
        try {
            isAdvertising.update { true }
            advertiser.advertise(
                connectable = true,
                payload = AdvertisingPayload(
                    advertisingData = {
                        ServiceUuid(LedButtonProfile.SERVICE_UUID)
                    },
                    // The 128-bit service UUID takes most of the advertising packet,
                    // so the name goes to the scan response.
                    scanResponse = {
                        IncludeLocalName()
                    },
                ),
            )
        } catch (_: CancellationException) {
            Timber.i("Advertising cancelled")
        } catch (e: Exception) {
            Timber.e(e, "Advertising failed")
            error.update { e.message ?: e.toString() }
        } finally {
            isAdvertising.update { false }
        }
    }
}

/**
 * A central connected to the server.
 *
 * @property address The MAC address of the central.
 * @property state The connection state.
 * @property mtu The ATT MTU, or `null` if not known.
 * @property phy The PHY in use, or `null` if not known.
 * @property connectionParameters The connection parameters, or `null` if not known.
 */
data class CentralInfo(
    val address: String,
    val state: ConnectionState,
    val mtu: Int?,
    val phy: PhyInUse?,
    val connectionParameters: ConnectionParameters.Specified?,
)
