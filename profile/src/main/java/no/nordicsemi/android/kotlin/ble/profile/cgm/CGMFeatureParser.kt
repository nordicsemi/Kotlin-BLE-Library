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

package no.nordicsemi.android.kotlin.ble.profile.cgm

import android.annotation.SuppressLint
import no.nordicsemi.android.common.core.IntFormat
import no.nordicsemi.android.kotlin.ble.profile.cgm.data.CGMFeatures
import no.nordicsemi.android.kotlin.ble.profile.cgm.data.CGMFeaturesEnvelope
import no.nordicsemi.android.kotlin.ble.profile.common.CRC16

object CGMFeatureParser {

    fun parse(bytes: ByteArray): CGMFeaturesEnvelope? {
        if (bytes.size != 6) {
            return null
        }

        val featuresValue: Int = bytes.getIntValue(IntFormat.FORMAT_UINT24_LE, 0) ?: return null
        val typeAndSampleLocation: Int = bytes.getIntValue(IntFormat.FORMAT_UINT8, 3) ?: return null
        val expectedCrc: Int = bytes.getIntValue(IntFormat.FORMAT_UINT16_LE, 4) ?: return null

        val features = CGMFeatures(featuresValue)
        if (features.e2eCrcSupported) {
            val actualCrc: Int = CRC16.MCRF4XX(bytes, 0, 4)
            if (actualCrc != expectedCrc) {
                return null
            }
        } else {
            // If the device doesn't support E2E-safety the value of the field shall be set to 0xFFFF.
            if (expectedCrc != 0xFFFF) {
                return null
            }
        }

        @SuppressLint("WrongConstant") val type = typeAndSampleLocation and 0x0F // least significant nibble

        val sampleLocation = typeAndSampleLocation shr 4 // most significant nibble

        return CGMFeaturesEnvelope(features, type, sampleLocation, features.e2eCrcSupported, features.e2eCrcSupported)
    }
}
