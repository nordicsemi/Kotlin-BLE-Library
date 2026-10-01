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

package no.nordicsemi.kotlin.ble.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import org.jetbrains.annotations.Range
import kotlin.time.Instant

/**
 * A Scan Result represents a single advertisement found during the scan.
 *
 * The [advertisingData] contain combined data from the Advertisement Packet and Scan Response
 * packet.
 *
 * @property peripheral The peripheral that was found.
 * @property isConnectable Whether the peripheral is connectable; `null` if the API does not
 * provide this information, i.e. until Android 8 Oreo.
 * @property advertisingData The advertising data (combined with scan response data,
 * if applicable).
 * @property rssi The received signal strength (RSSI), in dBm.
 * @property txPowerLevel The transmission power level, as advertised in Extended Advertising
 * packet; `null` if not present.
 * @property primaryPhy The primary PHY used to transmit the advertisement.
 * @property secondaryPhy The secondary PHY used to transmit the advertisement, or `null` if not used.
 * @property timestamp The time when the scan record was received.
 *
 * The timestamp is based on the system clock (wall-clock time), so it can be compared between
 * platforms and with times from other sources. It may jump when the system time is changed,
 * e.g. when the clock gets synchronized with the network.
 */
interface ScanResult<P: Peripheral<*, *>, AD: AdvertisingData> {
    val peripheral: P
    val isConnectable: Boolean?
    val advertisingData: AD
    val rssi: @Range(from = -127, to = 126) Int
    val txPowerLevel: @Range(from = -127, to = 126) Int?
    val primaryPhy: PrimaryPhy
    val secondaryPhy: Phy?
    val timestamp: Instant
}

/**
 * A flow which emits only one (first found) Scan Result from each discovered peripheral.
 */
fun <ID: Any, P: Peripheral<ID, *>, SR: ScanResult<P, *>> Flow<SR>.distinctByPeripheral(): Flow<SR> = flow {
    val peripherals = mutableSetOf<ID>()
    collect { result ->
        if (peripherals.add(result.peripheral.identifier)) {
            emit(result)
        }
    }
}