/*
 * Copyright (c) 2023, Nordic Semiconductor
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

package no.nordicsemi.android.kotlin.ble.profile.bps

import no.nordicsemi.android.kotlin.ble.profile.bps.data.BPMStatus
import no.nordicsemi.android.kotlin.ble.profile.bps.data.BloodPressureMeasurementData
import no.nordicsemi.android.kotlin.ble.profile.bps.data.BloodPressureType
import no.nordicsemi.android.kotlin.ble.profile.date.DateTimeParser
import no.nordicsemi.kotlin.data.FloatFormat
import no.nordicsemi.kotlin.data.IntFormat
import no.nordicsemi.kotlin.data.getFloat
import no.nordicsemi.kotlin.data.getInt
import no.nordicsemi.kotlin.data.hasBitSet
import java.nio.ByteOrder
import java.util.Calendar

object BloodPressureMeasurementParser {

    fun parse(bytes: ByteArray): BloodPressureMeasurementData? {
        if (bytes.size < 7) {
            return null
        }

        // First byte: flags
        var offset = 0
        val flags = bytes[offset++]

        // See UNIT_* for unit options
        val unit = if (flags hasBitSet 1) BloodPressureType.UNIT_MMHG else BloodPressureType.UNIT_KPA
        val timestampPresent = flags hasBitSet 2
        val pulseRatePresent = flags hasBitSet 3
        val userIdPresent = flags hasBitSet 4
        val measurementStatusPresent = flags hasBitSet 5

        if (bytes.size < (7
                    + (if (timestampPresent) 7 else 0) + (if (pulseRatePresent) 2 else 0)
                    + (if (userIdPresent) 1 else 0) + if (measurementStatusPresent) 2 else 0)
        ) {
            return null
        }

        // Following bytes - systolic, diastolic and mean arterial pressure
        val systolic = bytes.getFloat(offset, FloatFormat.IEEE_11073_16_BIT, ByteOrder.LITTLE_ENDIAN)
        val diastolic = bytes.getFloat(offset + 2, FloatFormat.IEEE_11073_16_BIT, ByteOrder.LITTLE_ENDIAN)
        val meanArterialPressure = bytes.getFloat(offset + 4, FloatFormat.IEEE_11073_16_BIT, ByteOrder.LITTLE_ENDIAN)
        offset += 6

        // Parse timestamp if present
        var calendar: Calendar? = null
        if (timestampPresent) {
            calendar = DateTimeParser.parse(bytes, offset)
            offset += 7
        }

        // Parse pulse rate if present
        var pulseRate: Float? = null
        if (pulseRatePresent) {
            pulseRate = bytes.getFloat(offset, FloatFormat.IEEE_11073_16_BIT, ByteOrder.LITTLE_ENDIAN)
            offset += 2
        }

        // Read user id if present
        var userId: Int? = null
        if (userIdPresent) {
            userId = bytes.getInt(offset, IntFormat.UINT8)
            offset += 1
        }

        // Read measurement status if present
        var status: BPMStatus? = null
        if (measurementStatusPresent) {
            val measurementStatus: Int = bytes.getInt(offset, IntFormat.INT16, ByteOrder.LITTLE_ENDIAN)
            // offset += 2;
            status = BPMStatus(measurementStatus)
        }

        return BloodPressureMeasurementData(
            systolic,
            diastolic,
            meanArterialPressure,
            unit,
            pulseRate,
            userId,
            status,
            calendar
        )
    }
}
