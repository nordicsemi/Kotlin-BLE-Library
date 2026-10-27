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

package no.nordicsemi.kotlin.ble.android.sample.advertiser

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.advertiser.android.AdvertisingPayload
import no.nordicsemi.kotlin.ble.advertiser.android.BluetoothLeAdvertiser
import no.nordicsemi.kotlin.ble.core.AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.Bluetooth5AdvertisingSetParameters
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class AdvertiserViewModel @Inject constructor(
    private val advertiser: BluetoothLeAdvertiser,
    // We're not using ViewModelScope. For test purposes it's better to create a custom Scope,
    // also connected to the ViewModel lifecycle, but which can be replaced in tests.
    private val scope: CoroutineScope,
): ViewModel() {
    val isAdvertising: StateFlow<Boolean>
        field = MutableStateFlow(false)
    val error: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    private var job: Job? = null

    fun startAdvertising(
        parameters: AdvertisingSetParameters,
    ) {
        job = scope.launch {
            try {
                error.update { null }
                advertiser.advertise(
                    parameters = parameters,
                    payload = AdvertisingPayload(
                        advertisingData = {
                            IncludeLocalName()
                            // Environmental Sensing Service UUID
                            ServiceUuid(0x181A)
                        },
                        scanResponse = {
                            // Add scan response only for connectable or scannable advertisements.
                            if (parameters.connectable || (parameters is Bluetooth5AdvertisingSetParameters && parameters.scannable)) {
                                // Temperature characteristic UUID : 22'C
                                ServiceData(0x2A6E, byteArrayOf(22))
                                ManufacturerData(0x0059, "Kaczka".toByteArray())
                            }
                        },
                    ),
                    timeout = 3.seconds,
                ) { txPower ->
                    Timber.i("Tx power: $txPower")
                    isAdvertising.update { true }
                }
            } catch (_: CancellationException) {
                Timber.i("Advertising cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Advertising failed")
                error.update { e.message }
            } finally {
                isAdvertising.update { false }
            }
        }
    }

    fun stopAdvertising() {
        job?.cancel()
    }
}