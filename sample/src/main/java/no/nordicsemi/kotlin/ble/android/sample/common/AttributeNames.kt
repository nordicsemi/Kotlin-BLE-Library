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

package no.nordicsemi.kotlin.ble.android.sample.common

import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.uuid.Uuid

/**
 * Returns a human-readable name of a well-known service, characteristic or descriptor,
 * or `null` if the UUID is not known.
 *
 * This is a small subset of assigned numbers, just to make the sample app easier to read.
 */
val Uuid.name: String?
    get() = names[this]

private val sigNames = mapOf(
    // Services
    0x1800 to "Generic Access",
    0x1801 to "Generic Attribute",
    0x1805 to "Current Time",
    0x1808 to "Glucose",
    0x1809 to "Health Thermometer",
    0x180A to "Device Information",
    0x180D to "Heart Rate",
    0x180F to "Battery",
    0x1810 to "Blood Pressure",
    0x1812 to "Human Interface Device",
    0x1814 to "Running Speed and Cadence",
    0x1816 to "Cycling Speed and Cadence",
    0xFE59 to "Nordic Secure DFU",
    // Characteristics
    0x2A00 to "Device Name",
    0x2A01 to "Appearance",
    0x2A04 to "Peripheral Preferred Connection Parameters",
    0x2A05 to "Service Changed",
    0x2A19 to "Battery Level",
    0x2A23 to "System ID",
    0x2A24 to "Model Number String",
    0x2A25 to "Serial Number String",
    0x2A26 to "Firmware Revision String",
    0x2A27 to "Hardware Revision String",
    0x2A28 to "Software Revision String",
    0x2A29 to "Manufacturer Name String",
    0x2A37 to "Heart Rate Measurement",
    0x2A38 to "Body Sensor Location",
    0x2A39 to "Heart Rate Control Point",
    0x2A50 to "PnP ID",
    0x2AA6 to "Central Address Resolution",
    0x2B29 to "Client Supported Features",
    0x2B2A to "Database Hash",
    0x2B3A to "Server Supported Features",
    // Descriptors
    0x2900 to "Characteristic Extended Properties",
    0x2901 to "Characteristic User Description",
    0x2902 to "Client Characteristic Configuration",
    0x2903 to "Server Characteristic Configuration",
    0x2904 to "Characteristic Presentation Format",
    0x2905 to "Characteristic Aggregate Format",
)

private val vendorNames = mapOf(
    // LED Button Service (LBS)
    "00001523-1212-EFDE-1523-785FEABCD123" to "LED Button Service",
    "00001524-1212-EFDE-1523-785FEABCD123" to "Button",
    "00001525-1212-EFDE-1523-785FEABCD123" to "LED",
    // Nordic UART Service (NUS)
    "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" to "Nordic UART Service",
    "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" to "UART RX",
    "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" to "UART TX",
    // Simple Management Protocol (SMP)
    "8D53DC1D-1DB7-4CD3-868B-8A527460AA84" to "SMP Service",
    "DA2E7828-FBCE-4E01-AE9E-261174997C48" to "SMP",
)

private val names: Map<Uuid, String> =
    sigNames.mapKeys { (shortUuid, _) -> Uuid.fromShortUuid(shortUuid) } +
    vendorNames.mapKeys { (uuid, _) -> Uuid.parse(uuid) }
