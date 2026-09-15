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

package no.nordicsemi.kotlin.ble.core.mock

import no.nordicsemi.kotlin.ble.core.AdvertisingDataDefinition
import no.nordicsemi.kotlin.ble.core.AdvertisingDataFlag
import no.nordicsemi.kotlin.ble.core.AdvertisingDataType
import no.nordicsemi.kotlin.ble.core.util.fromBytes
import no.nordicsemi.kotlin.ble.core.util.is16BitUuid
import no.nordicsemi.kotlin.ble.core.util.is32BitUuid
import no.nordicsemi.kotlin.ble.core.util.toShortByteArray
import no.nordicsemi.kotlin.ble.core.value
import kotlin.uuid.Uuid

/**
 * Advertising data packet container for mock Bluetooth LE advertising.
 *
 * This represents the data to be advertised in the Advertising Data as well as the Scan Response
 * data.
 *
 * @constructor Constructs an Advertising Data packet.
 * @param raw The raw advertising data.
 * @property flags The set of flags in the "Flags" AD type.
 * @property completeLocalName The value of "Complete Local Name" AD type.
 * @property shortenedLocalName The value of "Shortened Local Name" AD type.
 * @property txPowerLevel The "TX Power Level" AD Type.
 * @property serviceUuids A list of service UUID to advertise.
 * @property serviceSolicitationUuids Service solicitation UUID to advertise data.
 * @property serviceData Service data to be advertised.
 * @property manufacturerData Manufacturer specific data. The keys should be the Company ID as
 * defined in [Assigned Numbers](https://www.bluetooth.com/specifications/assigned-numbers/).
 * @property meshPbAdv The Bluetooth Mesh PB-ADV field.
 * @property meshMessage The Bluetooth Mesh Message field.
 * @property meshBeacon The Bluetooth Mesh Beacon field.
 */
class AdvertisingDataDefinition(
    val raw: ByteArray
): AdvertisingDataDefinition(
    serviceUuids = parseServiceUuids(raw)
) {
    val flags: Set<AdvertisingDataFlag>?
    val completeLocalName: String?
    val shortenedLocalName: String?
    val txPowerLevel: Int?
    val serviceSolicitationUuids: List<Uuid>?
    val serviceData: Map<Uuid, ByteArray>?
    val manufacturerData: Map<Int, ByteArray>?
    var meshPbAdv: ByteArray?
    var meshMessage: ByteArray?
    var meshBeacon: ByteArray?

    // Note: This init is a copy of no.nordicsemi.kotlin.ble.client.android.AdvertisingData
    init {
        var flags: MutableSet<AdvertisingDataFlag>? = null
        var completeName: String? = null
        var shortenedName: String? = null
        var serviceSolicitationUuids: MutableList<Uuid>? = null
        var serviceData: MutableMap<Uuid, ByteArray>? = null
        var txPowerLevel: Int? = null
        var manufacturerData: MutableMap<Int, ByteArray>? = null
        var meshPbAdv: ByteArray? = null
        var meshMessage: ByteArray? = null
        var meshBeacon: ByteArray? = null

        // Advertisement data is a list of AD structures.
        var i = 0
        while (raw.size > i + 3) {
            // The first byte is the length of the AD structure.
            val length = (raw[i++].toInt() and 0xFF) - 1 // minus 1 as the length includes the type byte.

            // The data must be at least 1 byte and must not be longer than the remaining data.
            if (length < 1 || length >= raw.size - i) break

            // The second byte is the AD type, as specified in Assigned Numbers: 2.3 Common Data Types
            // https://www.bluetooth.com/specifications/assigned-numbers/
            val rawType = raw[i++].toInt() and 0xFF
            val type = AdvertisingDataType.createOrNull(rawType)

            when (type) {
                AdvertisingDataType.FLAGS -> {
                    flags = (flags ?: mutableSetOf()).apply {
                        val value = raw[i].toInt()
                        if (value and 0x01 != 0) add(AdvertisingDataFlag.LE_LIMITED_DISCOVERABLE_MODE)
                        if (value and 0x02 != 0) add(AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE)
                        if (value and 0x04 != 0) add(AdvertisingDataFlag.BR_EDR_NOT_SUPPORTED)
                        if (value and 0x08 != 0) add(AdvertisingDataFlag.SIMULTANEOUS_LE_BR_EDR_TO_SAME_DEVICE_CAPABLE_CONTROLLER)
                        @Suppress("DEPRECATION")
                        if (value and 0x10 != 0) add(AdvertisingDataFlag.SIMULTANEOUS_LE_BR_EDR_TO_SAME_DEVICE_CAPABLE_HOST)
                        // Other bits are reserved.
                    }
                }
                AdvertisingDataType.COMPLETE_LOCAL_NAME -> {
                    completeName = raw.decodeToString(i, i + length)
                }
                AdvertisingDataType.SHORTENED_LOCAL_NAME -> {
                    shortenedName = raw.decodeToString(i, i + length)
                }
                AdvertisingDataType.SERVICE_DATA_16_BIT -> {
                    val uuid = Uuid.fromBytes(raw, i, 2)
                    val data = raw.copyOfRange(i + 2, i + length)
                    serviceData = (serviceData ?: mutableMapOf()).apply {
                        put(uuid, data)
                    }
                }
                AdvertisingDataType.SERVICE_DATA_32_BIT -> {
                    val uuid = Uuid.fromBytes(raw, i, 4)
                    val data = raw.copyOfRange(i + 4, i + length)
                    serviceData = (serviceData ?: mutableMapOf()).apply {
                        put(uuid, data)
                    }
                }
                AdvertisingDataType.SERVICE_DATA_128_BIT -> {
                    val uuid = Uuid.fromBytes(raw, i, 16)
                    val data = raw.copyOfRange(i + 16, i + length)
                    serviceData = (serviceData ?: mutableMapOf()).apply {
                        put(uuid, data)
                    }
                }
                AdvertisingDataType.TX_POWER_LEVEL -> {
                    txPowerLevel = raw[i].toInt()
                }
                AdvertisingDataType.LIST_OF_16_BIT_SERVICE_SOLICITATION_UUIDS -> {
                    serviceSolicitationUuids = (serviceSolicitationUuids ?: mutableListOf()).apply {
                        for (j in i until i + length step 2) {
                            add(Uuid.fromBytes(raw, j, 2))
                        }
                    }
                }
                AdvertisingDataType.LIST_OF_32_BIT_SERVICE_SOLICITATION_UUIDS -> {
                    serviceSolicitationUuids = (serviceSolicitationUuids ?: mutableListOf()).apply {
                        for (j in i until i + length step 4) {
                            add(Uuid.fromBytes(raw, j, 4))
                        }
                    }
                }
                AdvertisingDataType.LIST_OF_128_BIT_SERVICE_SOLICITATION_UUIDS -> {
                    serviceSolicitationUuids = (serviceSolicitationUuids ?: mutableListOf()).apply {
                        for (j in i until i + length step 16) {
                            add(Uuid.fromBytes(raw, j, 16))
                        }
                    }
                }
                AdvertisingDataType.MANUFACTURER_SPECIFIC_DATA -> {
                    // The structure must contain at least the 2-byte Company ID.
                    // Shorter structures are malformed and are skipped.
                    if (length >= 2) {
                        val companyId = (raw[i].toInt() and 0xFF) or (raw[i + 1].toInt() and 0xFF shl 8)
                        val data = raw.copyOfRange(i + 2, i + length)
                        manufacturerData = (manufacturerData ?: mutableMapOf()).apply {
                            put(companyId, data)
                        }
                    }
                }
                AdvertisingDataType.PB_ADV -> {
                    meshPbAdv = raw.copyOfRange(i, i + length)
                }
                AdvertisingDataType.MESH_MESSAGE -> {
                    meshMessage = raw.copyOfRange(i, i + length)
                }
                AdvertisingDataType.MESH_BEACON -> {
                    meshBeacon = raw.copyOfRange(i, i + length)
                }
                else -> {
                    // Ignore unknown AD types.
                }
            }

            i += length
        }

        this.flags = flags
        this.completeLocalName = completeName
        this.shortenedLocalName = shortenedName
        this.serviceSolicitationUuids = serviceSolicitationUuids
        this.serviceData = serviceData
        this.txPowerLevel = txPowerLevel
        this.manufacturerData = manufacturerData
        this.meshPbAdv = meshPbAdv
        this.meshMessage = meshMessage
        this.meshBeacon = meshBeacon
    }

    /**
     * Constructs an Advertising Data packet.
     *
     * @param flags The set of flags in the "Flags" AD type.
     * @param completeLocalName The value of "Complete Local Name" AD type.
     * @param shortenedLocalName The value of "Shortened Local Name" AD type.
     * @param txPowerLevel The "TX Power Level" AD Type.
     * @param serviceUuids A list of service UUID to advertise.
     * @param serviceSolicitationUuids Service solicitation UUID to advertise data.
     * @param serviceData Service data to be advertised.
     * @param manufacturerData Manufacturer specific data. The keys should be the Company ID as
     * defined in Assigned Numbers.
     * @param meshPbAdv The Bluetooth Mesh PB-ADV field.
     * @param meshMessage The Bluetooth Mesh Message field.
     * @param meshBeacon The Bluetooth Mesh Beacon field.
     */
    constructor(
        flags: Set<AdvertisingDataFlag>? = null,
        completeLocalName: String? = null,
        shortenedLocalName: String? = null,
        txPowerLevel: Int? = null,
        serviceUuids: List<Uuid>? = null,
        serviceSolicitationUuids: List<Uuid>? = null,
        serviceData: Map<Uuid, ByteArray>? = null,
        manufacturerData: Map<Int, ByteArray>? = null,
        meshPbAdv: ByteArray? = null,
        meshMessage: ByteArray? = null,
        meshBeacon: ByteArray? = null
    ) : this(
        byteArrayOf(
            *encode(AdvertisingDataType.FLAGS, flags?.value),
            *encode(AdvertisingDataType.COMPLETE_LOCAL_NAME, completeLocalName?.encodeToByteArray()),
            *encode(AdvertisingDataType.SHORTENED_LOCAL_NAME, shortenedLocalName?.encodeToByteArray()),
            *encode(AdvertisingDataType.TX_POWER_LEVEL, txPowerLevel?.toByte()),
            *encodeServiceUuids(serviceUuids),
            *encodeServiceSolicitationUuids(serviceSolicitationUuids),
            *encodeServiceData(serviceData),
            *encodeManufacturerData(manufacturerData),
            *encode(AdvertisingDataType.PB_ADV, meshPbAdv),
            *encode(AdvertisingDataType.MESH_MESSAGE, meshMessage),
            *encode(AdvertisingDataType.MESH_BEACON, meshBeacon),
        )
    )

    private companion object {

        private fun parseServiceUuids(raw: ByteArray): List<Uuid>? {
            var serviceUuids: MutableList<Uuid>? = null


            // Advertisement data is a list of AD structures.
            var i = 0
            while (raw.size > i + 3) {
                // The first byte is the length of the AD structure.
                val length =
                    (raw[i++].toInt() and 0xFF) - 1 // minus 1 as the length includes the type byte.

                // The data must be at least 1 byte and must not be longer than the remaining data.
                if (length < 1 || length >= raw.size - i) break

                // The second byte is the AD type, as specified in Assigned Numbers: 2.3 Common Data Types
                // https://www.bluetooth.com/specifications/assigned-numbers/
                val rawType = raw[i++].toInt() and 0xFF
                val type = AdvertisingDataType.createOrNull(rawType)

                when (type) {
                    AdvertisingDataType.INCOMPLETE_LIST_OF_16_BIT_SERVICE_UUIDS,
                    AdvertisingDataType.COMPLETE_LIST_OF_16_BIT_SERVICE_UUIDS -> {
                        serviceUuids = (serviceUuids ?: mutableListOf()).apply {
                            for (j in i until i + length step 2) {
                                add(Uuid.fromBytes(raw, j, 2))
                            }
                        }
                    }
                    AdvertisingDataType.INCOMPLETE_LIST_OF_32_BIT_SERVICE_UUIDS,
                    AdvertisingDataType.COMPLETE_LIST_OF_32_BIT_SERVICE_UUIDS -> {
                        serviceUuids = (serviceUuids ?: mutableListOf()).apply {
                            for (j in i until i + length step 4) {
                                add(Uuid.fromBytes(raw, j, 4))
                            }
                        }
                    }
                    AdvertisingDataType.INCOMPLETE_LIST_OF_128_BIT_SERVICE_UUIDS,
                    AdvertisingDataType.COMPLETE_LIST_OF_128_BIT_SERVICE_UUIDS -> {
                        serviceUuids = (serviceUuids ?: mutableListOf()).apply {
                            for (j in i until i + length step 16) {
                                add(Uuid.fromBytes(raw, j, 16))
                            }
                        }
                    }
                    else -> {
                        // Ignore other AD types.
                    }
                }

                i += length
            }
            return serviceUuids
        }

        private fun encode(type: AdvertisingDataType, byte: Byte?): ByteArray {
            return byte
                ?.let { byteArrayOf(2, type.type.toByte(), byte) }
                ?: byteArrayOf()
        }

        private fun encode(type: AdvertisingDataType, bytes: ByteArray?): ByteArray {
            return bytes
                ?.takeIf { it.isNotEmpty() }
                ?.let { byteArrayOf((bytes.size + 1).toByte(), type.type.toByte(), *bytes) }
                ?: byteArrayOf()
        }

        private fun encodeServiceUuids(serviceUUIDs: List<Uuid>?): ByteArray {
            if (serviceUUIDs.isNullOrEmpty()) return byteArrayOf()
            var uuids16bit = byteArrayOf()
            var uuids32bit = byteArrayOf()
            var uuids128bit = byteArrayOf()
            serviceUUIDs.forEach { uuid ->
                when {
                    uuid.is16BitUuid -> uuids16bit += uuid.toShortByteArray()
                    uuid.is32BitUuid -> uuids32bit += uuid.toShortByteArray()
                    else -> uuids128bit += uuid.toByteArray().reversedArray()
                }
            }
            return byteArrayOf(
                *encode(AdvertisingDataType.COMPLETE_LIST_OF_16_BIT_SERVICE_UUIDS, uuids16bit),
                *encode(AdvertisingDataType.COMPLETE_LIST_OF_32_BIT_SERVICE_UUIDS, uuids32bit),
                *encode(AdvertisingDataType.COMPLETE_LIST_OF_128_BIT_SERVICE_UUIDS, uuids128bit),
            )
        }

        private fun encodeServiceSolicitationUuids(serviceUUIDs: List<Uuid>?): ByteArray {
            if (serviceUUIDs.isNullOrEmpty()) return byteArrayOf()
            var uuids16bit = byteArrayOf()
            var uuids32bit = byteArrayOf()
            var uuids128bit = byteArrayOf()
            serviceUUIDs.forEach { uuid ->
                when {
                    uuid.is16BitUuid -> uuids16bit += uuid.toShortByteArray()
                    uuid.is32BitUuid -> uuids32bit += uuid.toShortByteArray()
                    else -> uuids128bit += uuid.toByteArray().reversedArray()
                }
            }
            return byteArrayOf(
                *encode(AdvertisingDataType.LIST_OF_16_BIT_SERVICE_SOLICITATION_UUIDS, uuids16bit),
                *encode(AdvertisingDataType.LIST_OF_32_BIT_SERVICE_SOLICITATION_UUIDS, uuids32bit),
                *encode(
                    AdvertisingDataType.LIST_OF_128_BIT_SERVICE_SOLICITATION_UUIDS,
                    uuids128bit
                ),
            )
        }

        private fun encodeServiceData(serviceData: Map<Uuid, ByteArray>?): ByteArray {
            fun typeOf(bytes: ByteArray): AdvertisingDataType = when (bytes.size) {
                2 -> AdvertisingDataType.SERVICE_DATA_16_BIT
                4 -> AdvertisingDataType.SERVICE_DATA_32_BIT
                else -> AdvertisingDataType.SERVICE_DATA_128_BIT
            }
            return serviceData
                ?.map { entry ->
                    val uuid = entry.key.toShortByteArray()
                    val data = entry.value
                    encode(typeOf(uuid), uuid + data)
                }
                ?.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
                ?: byteArrayOf()
        }

        private fun encodeManufacturerData(manufacturerData: Map<Int, ByteArray>?): ByteArray {
            return manufacturerData
                ?.map { entry ->
                    val companyId = ByteArray(2) { (entry.key ushr (it * 8)).toByte() }
                    val data = entry.value
                    encode(AdvertisingDataType.MANUFACTURER_SPECIFIC_DATA, companyId + data)
                }
                ?.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
                ?: byteArrayOf()
        }
    }
}