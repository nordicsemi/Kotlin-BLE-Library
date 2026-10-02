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

package no.nordicsemi.kotlin.ble.client.android

import no.nordicsemi.kotlin.ble.client.ScanResult
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import kotlin.time.Instant

/**
 * Bluetooth LE scan result for Android.
 *
 * @see no.nordicsemi.kotlin.ble.client.ScanResult
 */
open class ScanResult(
    override val peripheral: Peripheral,
    override val isConnectable: Boolean?,
    override val advertisingData: AdvertisingData,
    override val rssi: Int,
    override val txPowerLevel: Int?,
    override val primaryPhy: PrimaryPhy,
    override val secondaryPhy: Phy?,
    override val timestamp: Instant,
) : ScanResult<Peripheral, AdvertisingData> {

    override fun toString(): String = "ScanResult(" +
            "peripheral=${peripheral.identifier}, " +
            "isConnectable=$isConnectable, " +
            "advertisingData=0x${advertisingData.raw.toHexString(HexFormat.UpperCase)}, " +
            "rssi=$rssi, " +
            "txPowerLevel=$txPowerLevel, " +
            "primaryPhy=$primaryPhy, " +
            "secondaryPhy=$secondaryPhy, " +
            "timestamp=$timestamp)"
}