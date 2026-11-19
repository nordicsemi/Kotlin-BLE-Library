/*
 * Copyright (c) 2025, Nordic Semiconductor
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

import kotlin.test.Test
import com.google.common.truth.Truth
import no.nordicsemi.kotlin.ble.core.AdvertisingDataFlag
import kotlin.uuid.Uuid

class AdvertisingDataTest {

    @Test
    fun `test incorrect AD`() {
        // This data contains an extra byte in the 32-bit UUID AD structure.
        // There are various method how such case should be handled.
        // The whole advertising packet could be rejected, the malformed AD structure could be skipped,
        // or the parser could try to recover and continue parsing the rest of the data.
        // The current implementation attempts to read as many valid UUIDs as possible from the structure,
        // ignoring any extra bytes.
        val crashRaw = byteArrayOf(
            // Flags: length = 2, type = 1, flags = 0x06
            0x02.toByte(), 0x01.toByte(), 0x06.toByte(),
            // 32-bit Service UUID: length = 6 (1 byte for the AD type, 4 bytes (32-bit UUID), 1 extra byte), type = 0x05 (32-bit UUID list)
            0x06.toByte(), 0x05.toByte(),
            // 1 x 32-bit UUID
            0x10.toByte(), 0x11.toByte(), 0x12.toByte(), 0x13.toByte(),
            // EXTRA BYTE HERE!
            0x14.toByte(),
            // Name: length = 3, type = 9, "AB"
            0x03.toByte(), 0x09.toByte(), 0x41.toByte(), 0x42.toByte()
        )
        val ad = AdvertisingData(crashRaw)
        Truth.assertThat(ad.serviceUuids.size).isEqualTo(1)
        Truth.assertThat(ad.name).isEqualTo("AB")

        // Check that flags were parsed correctly
        Truth.assertThat(ad.flags).containsAnyOf(
            AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE,
            AdvertisingDataFlag.BR_EDR_NOT_SUPPORTED
        )
        Truth.assertThat(ad.flags).containsNoneOf(
            AdvertisingDataFlag.LE_LIMITED_DISCOVERABLE_MODE,
            AdvertisingDataFlag.SIMULTANEOUS_LE_BR_EDR_TO_SAME_DEVICE_CAPABLE_CONTROLLER
        )
    }

    @Test
    fun `test 16-bit Service UUIDs`() {
        val crashRaw = byteArrayOf(
            // AD header: length = 9, type = 0x03 (16-bit UUID list)
            0x09.toByte(), 0x03.toByte(),
            // 4 x 16-bit UUID
            0x10.toByte(), 0x11.toByte(),
            0x12.toByte(), 0x13.toByte(),
            0x14.toByte(), 0x15.toByte(),
            0x16.toByte(), 0x17.toByte(),
            // Name: length = 3, type = 9, "☺"
            // Replace "ABC" with "☺" (U+263A -> 0xE2 0x98 0xBA)
            0x04.toByte(), 0x09.toByte(), 0xE2.toByte(), 0x98.toByte(), 0xBA.toByte()
        )
        val ad = AdvertisingData(crashRaw)
        Truth.assertThat(ad.serviceUuids.size).isEqualTo(4)
        Truth.assertThat(ad.name).isEqualTo("☺")
    }

    @Test
    fun `test 32-bit Service UUIDs`() {
        val crashRaw = byteArrayOf(
            // AD header: length = 9, type = 0x05 (32-bit UUID list)
            0x09.toByte(), 0x05.toByte(),
            // 2 x 32-bit UUID
            0x10.toByte(), 0x11.toByte(), 0x12.toByte(), 0x13.toByte(),
            0x14.toByte(), 0x15.toByte(), 0x16.toByte(), 0x17.toByte(),
            // Name: length = 3, type = 9, "AB"
            0x03.toByte(), 0x09.toByte(), 0x41.toByte(), 0x42.toByte()
        )
        val ad = AdvertisingData(crashRaw)
        Truth.assertThat(ad.serviceUuids.size).isEqualTo(2)
        Truth.assertThat(ad.name).isEqualTo("AB")
    }

    @Test
    fun `test 128-bit Service UUIDs`() {
        val crashRaw = byteArrayOf(
            // AD header: length = 17, type = 0x07 (128-bit UUID list)
            0x11.toByte(), 0x07.toByte(),
            // 1 x 128-bit UUID
            0x10.toByte(), 0x11.toByte(), 0x12.toByte(), 0x13.toByte(),
            0x14.toByte(), 0x15.toByte(), 0x16.toByte(), 0x17.toByte(),
            0x18.toByte(), 0x19.toByte(), 0x1A.toByte(), 0x1B.toByte(),
            0x1C.toByte(), 0x1D.toByte(), 0x1E.toByte(), 0x1F.toByte(),
        )
        val ad = AdvertisingData(crashRaw)
        Truth.assertThat(ad.serviceUuids.size).isEqualTo(1)
        Truth.assertThat(ad.name).isNull()
    }

    @Test
    fun `test 16-bit Service Data`() {
        val serviceDataUuid = Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb") // Heart Rate
        val serviceDataBytes = byteArrayOf(0x01, 0x02, 0x03)
        val rawData = byteArrayOf(
            // Service Data: length = 6, type = 0x16 (Service Data - 16-bit UUID)
            0x06.toByte(), 0x16.toByte(),
            // 16-bit Service UUID (0x180D)
            0x0D.toByte(), 0x18.toByte(),
            // Service Data bytes
            0x01.toByte(), 0x02.toByte(), 0x03.toByte()
        )

        val ad = AdvertisingData(rawData)
        val parsedData = ad.serviceData

        Truth.assertThat(parsedData).isNotNull()
        Truth.assertThat(parsedData.size).isEqualTo(1)

        val entry = parsedData.entries.first()
        Truth.assertThat(entry.key).isEqualTo(serviceDataUuid)
        Truth.assertThat(entry.value).isEqualTo(serviceDataBytes)
    }

        @Test
    fun `test 32-bit Service Data`() {
        val serviceDataUuid = Uuid.parse("0000180f-0000-1000-8000-00805f9b34fb") // Battery Service
        val serviceDataBytes = byteArrayOf(0x64) // 100%
        val rawData = byteArrayOf(
            // Service Data: length = 6, type = 0x20 (Service Data - 32-bit UUID)
            0x06.toByte(), 0x20.toByte(),
            // 32-bit Service UUID (0x0000180F) - Note: BLE uses Bluetooth base UUID
            0x0F.toByte(), 0x18.toByte(), 0x00.toByte(), 0x00.toByte(),
            // Service Data bytes
            0x64.toByte()
        )

        val ad = AdvertisingData(rawData)
        val parsedData = ad.serviceData

        Truth.assertThat(parsedData).isNotNull()
        Truth.assertThat(parsedData.size).isEqualTo(1)

        val entry = parsedData.entries.first()
        Truth.assertThat(entry.key).isEqualTo(serviceDataUuid)
        Truth.assertThat(entry.value).isEqualTo(serviceDataBytes)
    }

    @Test
    fun `test 128-bit Service Data`() {
        val serviceDataUuid = Uuid.parse("0000180a-0000-1000-8000-00805f9b34fb") // Device Information
        val serviceDataBytes = byteArrayOf(0x4E, 0x4F, 0x52, 0x44, 0x49, 0x43) // "NORDIC"
        val rawData = byteArrayOf(
            // Service Data: length = 23, type = 0x21 (Service Data - 128-bit UUID)
            0x17.toByte(), 0x21.toByte(),
            // 128-bit Service UUID (reversed)
            0xFB.toByte(), 0x34.toByte(), 0x9B.toByte(), 0x5F.toByte(), 0x80.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x0A.toByte(), 0x18.toByte(), 0x00.toByte(), 0x00.toByte(),
            // Service Data bytes
            0x4E.toByte(), 0x4F.toByte(), 0x52.toByte(), 0x44.toByte(), 0x49.toByte(), 0x43.toByte()
        )

        val ad = AdvertisingData(rawData)
        val parsedData = ad.serviceData

        Truth.assertThat(parsedData).isNotNull()
        Truth.assertThat(parsedData.size).isEqualTo(1)

        val entry = parsedData.entries.first()
        Truth.assertThat(entry.key).isEqualTo(serviceDataUuid)
        Truth.assertThat(entry.value).isEqualTo(serviceDataBytes)
    }

    @Test
    fun `test empty Service Data`() {
        val serviceDataUuid = Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb") // Heart Rate
        val rawData = byteArrayOf(
            // Service Data: length = 3, type = 0x16 (Service Data - 16-bit UUID)
            0x03.toByte(), 0x16.toByte(),
            // 16-bit Service UUID (0x180D), but no data follows
            0x0D.toByte(), 0x18.toByte()
        )

        val ad = AdvertisingData(rawData)
        val parsedData = ad.serviceData

        // The parser should extract the UUID and an empty byte array for the data.
        Truth.assertThat(parsedData).isNotNull()
        Truth.assertThat(parsedData.size).isEqualTo(1)

        val entry = parsedData.entries.first()
        Truth.assertThat(entry.key).isEqualTo(serviceDataUuid)
        Truth.assertThat(entry.value).isEmpty()
    }

    @Test
    fun `test Manufacturer Specific Data`() {
        val manufacturerId = 0x0059 // Nordic Semiconductor
        val manufacturerData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val rawData = byteArrayOf(
            // Manufacturer Specific Data: length = 7, type = 0xFF
            0x07.toByte(), 0xFF.toByte(),
            // Manufacturer ID (little-endian)
            0x59.toByte(), 0x00.toByte(),
            // Custom data
            0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte()
        )

        val ad = AdvertisingData(rawData)
        val parsedData = ad.manufacturerData

        Truth.assertThat(parsedData).isNotNull()
        Truth.assertThat(parsedData).containsKey(manufacturerId)
        Truth.assertThat(parsedData[manufacturerId]).isEqualTo(manufacturerData)
    }

    @Test
    fun `test malformed Manufacturer Data - too short`() {
        val rawData = byteArrayOf(
            // Manufacturer Specific Data: length = 2, type = 0xFF, but only 1 byte for ID
            0x02.toByte(), 0xFF.toByte(), 0x59.toByte()
        )

        val ad = AdvertisingData(rawData)

        // The parser should fail gracefully and not find any manufacturer data.
        Truth.assertThat(ad.manufacturerData).isEmpty()
    }

    @Test
    fun `test malformed Service Data 16-bit - too short`() {
        val rawData = byteArrayOf(
            // Service Data (16-bit UUID): length = 2, type = 0x16, but only 1 byte for the UUID
            0x02.toByte(), 0x16.toByte(), 0x0F.toByte(),
            // Complete Local Name: length = 3, type = 0x09, "AB"
            0x03.toByte(), 0x09.toByte(), 0x41.toByte(), 0x42.toByte()
        )

        val ad = AdvertisingData(rawData)

        // The malformed structure is skipped, the rest of the data is parsed.
        Truth.assertThat(ad.serviceData).isEmpty()
        Truth.assertThat(ad.name).isEqualTo("AB")
    }

    @Test
    fun `test malformed Service Data 32-bit - too short`() {
        val rawData = byteArrayOf(
            // Service Data (32-bit UUID): length = 4, type = 0x20, but only 3 bytes for the UUID
            0x04.toByte(), 0x20.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(),
            // Complete Local Name: length = 3, type = 0x09, "AB"
            0x03.toByte(), 0x09.toByte(), 0x41.toByte(), 0x42.toByte()
        )

        val ad = AdvertisingData(rawData)

        Truth.assertThat(ad.serviceData).isEmpty()
        Truth.assertThat(ad.name).isEqualTo("AB")
    }

    @Test
    fun `test malformed Service Data 128-bit - too short`() {
        // 15 bytes instead of 16 for the UUID.
        val rawData = byteArrayOf(
            0x10.toByte(), 0x21.toByte(),
            *ByteArray(15) { it.toByte() },
            // Complete Local Name: length = 3, type = 0x09, "AB"
            0x03.toByte(), 0x09.toByte(), 0x41.toByte(), 0x42.toByte()
        )

        val ad = AdvertisingData(rawData)

        Truth.assertThat(ad.serviceData).isEmpty()
        Truth.assertThat(ad.name).isEqualTo("AB")
    }

    @Test
    fun `test Service Data - UUID only, no data`() {
        val rawData = byteArrayOf(
            // Service Data (16-bit UUID): length = 3, type = 0x16, UUID = 0x180F, no data
            0x03.toByte(), 0x16.toByte(), 0x0F.toByte(), 0x18.toByte(),
            // Complete Local Name: length = 3, type = 0x09, "AB"
            0x03.toByte(), 0x09.toByte(), 0x41.toByte(), 0x42.toByte()
        )

        val ad = AdvertisingData(rawData)

        Truth.assertThat(ad.serviceData).hasSize(1)
        Truth.assertThat(ad.serviceData.values.single()).isEmpty()
    }

    @Test
    fun `test TX Power Level`() {
        val txPower = -20 // -20 dBm
        val rawData = byteArrayOf(
            // TX Power Level: length = 2, type = 0x0A
            0x02.toByte(), 0x0A.toByte(),
            // Power level value
            txPower.toByte()
        )

        val ad = AdvertisingData(rawData)

        Truth.assertThat(ad.txPowerLevel).isEqualTo(txPower)
    }

    @Test
    fun `test Shortened and Complete Local Name`() {
        val rawData = byteArrayOf(
            // Shortened Name: length = 5, type = 0x08, "Nord"
            0x05.toByte(), 0x08.toByte(), 0x4E.toByte(), 0x6F.toByte(), 0x72.toByte(), 0x64.toByte(),
            // Complete Name: length = 8, type = 0x09, "Nordic"
            0x07.toByte(), 0x09.toByte(), 0x4E.toByte(), 0x6F.toByte(), 0x72.toByte(), 0x64.toByte(), 0x69.toByte(), 0x63.toByte()
        )

        val ad = AdvertisingData(rawData)

        // The complete name, when present, should take precedence.
        Truth.assertThat(ad.name).isEqualTo("Nordic")
    }

    @Test
    fun `test only Shortened Local Name`() {
        val rawData = byteArrayOf(
            // Shortened Name: length = 5, type = 0x08, "Nord"
            0x05.toByte(), 0x08.toByte(), 0x4E.toByte(), 0x6F.toByte(), 0x72.toByte(), 0x64.toByte()
        )

        val ad = AdvertisingData(rawData)

        // If only the shortened name is available, it should be used.
        Truth.assertThat(ad.name).isEqualTo("Nord")
    }

}