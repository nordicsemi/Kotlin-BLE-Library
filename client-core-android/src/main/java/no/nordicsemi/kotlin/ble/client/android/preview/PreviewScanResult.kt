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

package no.nordicsemi.kotlin.ble.client.android.preview

import no.nordicsemi.kotlin.ble.client.android.AdvertisingData
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Preview implementation of [ScanResult] for showing scan results on Compose previews.
 *
 * @param peripheral The peripheral associated with the scan result.
 * @param isConnectable Whether the peripheral is connectable. Use `null` if the API doesn't
 * provide this information.
 * @param advertisingData The advertising data associated with the scan result. By default, this
 * is set to the result of [fromName] using the peripheral's name.
 * @param rssi The received signal strength (RSSI) in dBm, defaults to -50 dBm.
 * @param txPowerLevel The transmission power level.
 * @param primaryPhy The primary PHY used to transmit the advertisement, defaults to PHY LE 1M.
 * @param secondaryPhy The secondary PHY used to transmit the advertisement, or `null` (default) if not used.
 * @param timestamp The time when the scan record was received, defaults to now.
 */
class PreviewScanResult(
    peripheral: Peripheral,
    isConnectable: Boolean?,
    advertisingData: AdvertisingData = fromName(peripheral.name),
    rssi: Int = -50,
    txPowerLevel: Int? = null,
    primaryPhy: PrimaryPhy = PrimaryPhy.PHY_LE_1M,
    secondaryPhy: Phy? = null,
    timestamp: Instant = Clock.System.now(),
): ScanResult(
    peripheral = peripheral,
    isConnectable = isConnectable,
    advertisingData = advertisingData,
    rssi = rssi,
    txPowerLevel = txPowerLevel,
    primaryPhy = primaryPhy,
    secondaryPhy = secondaryPhy,
    timestamp = timestamp
)

private fun fromName(name: String?): AdvertisingData = AdvertisingData(
    raw = name?.let { name ->
        byteArrayOf(
            0x02, 0x01, 0x06, // LE General Discoverable Mode, BR/EDR Not Supported
            name.length.toByte(),
            0x09, // Complete Local Name
            *name.encodeToByteArray().take(31 - 5).toByteArray(),
        )
    } ?: byteArrayOf(0x02, 0x01, 0x06) // LE General Discoverable Mode, BR/EDR Not Supported
)