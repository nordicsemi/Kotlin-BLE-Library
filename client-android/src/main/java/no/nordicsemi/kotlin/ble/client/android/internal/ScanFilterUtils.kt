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

package no.nordicsemi.kotlin.ble.client.android.internal

import android.os.Build
import android.os.ParcelUuid
import kotlin.uuid.toJavaUuid
import android.bluetooth.le.ScanFilter as NativeScanFilter

/**
 * Converts the list of [ScanFilter] to a list of native [ScanFilter][NativeScanFilter].
 *
 * This method may return an empty list if none of the filters contain any criteria that can be
 * offloaded to the Bluetooth controller.
 */
internal fun List<ScanFilter>.toNative(): List<NativeScanFilter> = mapNotNull { it.toNative() }

/**
 * Converts the [ScanFilter] to a native [ScanFilter][NativeScanFilter].
 *
 * This method may return null if the filter doesn't contain any criteria that can be offloaded
 * to the Bluetooth controller.
 *
 * See also [link](https://developer.android.com/reference/android/bluetooth/le/ScanFilter.Builder)
 */
internal fun ScanFilter.toNative(): NativeScanFilter? {
    if (name == null &&
        nameRegex == null &&
        serviceUuid == null &&
        serviceData == null &&
        manufacturerData == null &&
        // Offloaded filtering with service solicitation UUIDs was added in Android 10.
        // https://developer.android.com/reference/android/bluetooth/le/ScanFilter.Builder#setServiceSolicitationUuid(android.os.ParcelUuid,%20android.os.ParcelUuid)
        (serviceSolicitationUuid == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) &&
        // Offloaded filtering with custom advertising data was added in Android 13.
        // https://developer.android.com/reference/android/bluetooth/le/ScanFilter.Builder#setAdvertisingDataType(int)
        (customAdvertisingData == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)) {
        return null
    }
    return NativeScanFilter.Builder()
        .apply {
            // Note, that we are not setting the device address here.
            // This is because the address filter requires the device to be PUBLIC (registered in IEEE).
            // Instead, when address is set, we will filter the results in the library when results
            // are returned.
            //
            // address?.let { setDeviceAddress(it) }

            name?.let { setDeviceName(it) }
            serviceUuid?.let { (uuid, mask) ->
                setServiceUuid(
                    ParcelUuid(uuid.toJavaUuid()),
                    mask?.let { ParcelUuid(it.toJavaUuid()) }
                )
            }
            serviceData?.let { (uuid, data, mask) ->
                setServiceData(ParcelUuid(uuid.toJavaUuid()), data, mask)
            }
            manufacturerData?.let { (companyId, data, mask) ->
                setManufacturerData(companyId, data, mask)
            }
            // Offloaded filtering with service solicitation UUIDs was added in Android 10.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                serviceSolicitationUuid?.let { (uuid, mask) ->
                    setServiceSolicitationUuid(
                        ParcelUuid(uuid.toJavaUuid()),
                        mask?.let { ParcelUuid(it.toJavaUuid()) }
                    )
                }
            }
            // Offloaded filtering with custom advertising data was added in Android 13.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                customAdvertisingData?.let { (type, data, mask) ->
                    data?.let {
                        // Due to a bug in Android API, the mask is declared as @NonNull.
                        // However, it should be perfectly fine to pass null here.
                        // Instead, we will pass a mask with all zeros, which should have the same effect.
                        setAdvertisingDataTypeWithData(type.type, it, mask ?: ByteArray(it.size))
                    } ?: setAdvertisingDataType(type.type)
                }
            }
        }
        .build()
}