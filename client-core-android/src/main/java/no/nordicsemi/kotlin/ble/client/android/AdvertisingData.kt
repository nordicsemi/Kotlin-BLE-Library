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

import no.nordicsemi.kotlin.ble.client.AdvertisingData
import no.nordicsemi.kotlin.ble.core.AdvertisingDataFlag
import no.nordicsemi.kotlin.ble.core.AdvertisingDataType
import no.nordicsemi.kotlin.ble.core.util.fromBytes
import kotlin.uuid.Uuid

/**
 * Android-specific class of Bluetooth LE advertising data.
 *
 * @property raw The raw advertisement data.
 * @property flags The set of flags from the "Flags" AD type.
 * @property completeLocalName The value of "Complete Local Name" AD type.
 * @property shortenedLocalName The value of "Shortened Local Name" AD type.
 * @property meshPbAdv The Bluetooth Mesh Provisioning PDU, if present.
 * @property meshMessage The Bluetooth Mesh Network PDU, if present.
 * @property meshBeacon The Bluetooth Mesh Beacon, if present.
 * @property adStructures A map of AD structures found in the advertisement data. This map contains
 * all AD structures found in the raw data that have a matching [AdvertisingDataType],
 * including those that are available with dedicated properties.
 */
class AdvertisingData(
    val raw: ByteArray
) : AdvertisingData {
    val flags: Set<AdvertisingDataFlag>?
    override val name: String?
    val completeLocalName: String?
    val shortenedLocalName: String?
    override val serviceUuids: List<Uuid>
    override val serviceSolicitationUuids: List<Uuid>
    override val serviceData: Map<Uuid, ByteArray>
    override val txPowerLevel: Int?
    override val manufacturerData: Map<Int, ByteArray>
    val meshPbAdv: ByteArray?
    val meshMessage: ByteArray?
    val meshBeacon: ByteArray?
    val adStructures: Map<AdvertisingDataType, List<ByteArray>>

    // Note: This init is a copy of no.nordicsemi.kotlin.ble.core.mock.AdvertisingDataDefinition.
    init {
        var flags: MutableSet<AdvertisingDataFlag>? = null
        var completeName: String? = null
        var shortenedName: String? = null
        var serviceUuids: MutableList<Uuid>? = null
        var serviceSolicitationUuids: MutableList<Uuid>? = null
        var serviceData: MutableMap<Uuid, ByteArray>? = null
        var txPowerLevel: Int? = null
        var manufacturerData: MutableMap<Int, ByteArray>? = null
        var meshPbAdv: ByteArray? = null
        var meshMessage: ByteArray? = null
        var meshBeacon: ByteArray? = null
        var adStructures: MutableMap<AdvertisingDataType, MutableList<ByteArray>>? = null

        // Advertisement data is a list of AD structures: LENGTH (1B) | TYPE (1B) | DATA (1+B)
        var i = 0
        while (raw.size >= i + 3) {
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
                    completeName = String(raw, i, length)
                }
                AdvertisingDataType.SHORTENED_LOCAL_NAME -> {
                    shortenedName = String(raw, i, length)
                }
                AdvertisingDataType.INCOMPLETE_LIST_OF_16_BIT_SERVICE_UUIDS,
                AdvertisingDataType.COMPLETE_LIST_OF_16_BIT_SERVICE_UUIDS -> {
                    serviceUuids = (serviceUuids ?: mutableListOf()).apply {
                        for (j in i until (i + length - 1) step 2) {
                            add(Uuid.fromBytes(raw, j, 2))
                        }
                    }
                }
                AdvertisingDataType.INCOMPLETE_LIST_OF_32_BIT_SERVICE_UUIDS,
                AdvertisingDataType.COMPLETE_LIST_OF_32_BIT_SERVICE_UUIDS -> {
                    serviceUuids = (serviceUuids ?: mutableListOf()).apply {
                        for (j in i until (i + length - 3) step 4) {
                            add(Uuid.fromBytes(raw, j, 4))
                        }
                    }
                }
                AdvertisingDataType.INCOMPLETE_LIST_OF_128_BIT_SERVICE_UUIDS,
                AdvertisingDataType.COMPLETE_LIST_OF_128_BIT_SERVICE_UUIDS -> {
                    serviceUuids = (serviceUuids ?: mutableListOf()).apply {
                        for (j in i until (i + length - 15) step 16) {
                            add(Uuid.fromBytes(raw, j, 16))
                        }
                    }
                }
                AdvertisingDataType.SERVICE_DATA_16_BIT -> {
                    // The structure must contain at least the 2-byte UUID.
                    // Shorter structures are malformed and are skipped.
                    if (length >= 2) {
                        val uuid = Uuid.fromBytes(raw, i, 2)
                        val data = raw.copyOfRange(i + 2, i + length)
                        serviceData = (serviceData ?: mutableMapOf()).apply {
                            put(uuid, data)
                        }
                    }
                }
                AdvertisingDataType.SERVICE_DATA_32_BIT -> {
                    // The structure must contain at least the 4-byte UUID.
                    // Shorter structures are malformed and are skipped.
                    if (length >= 4) {
                        val uuid = Uuid.fromBytes(raw, i, 4)
                        val data = raw.copyOfRange(i + 4, i + length)
                        serviceData = (serviceData ?: mutableMapOf()).apply {
                            put(uuid, data)
                        }
                    }
                }
                AdvertisingDataType.SERVICE_DATA_128_BIT -> {
                    // The structure must contain at least the 16-byte UUID.
                    // Shorter structures are malformed and are skipped.
                    if (length >= 16) {
                        val uuid = Uuid.fromBytes(raw, i, 16)
                        val data = raw.copyOfRange(i + 16, i + length)
                        serviceData = (serviceData ?: mutableMapOf()).apply {
                            put(uuid, data)
                        }
                    }
                }
                AdvertisingDataType.TX_POWER_LEVEL -> {
                    txPowerLevel = raw[i].toInt()
                }
                AdvertisingDataType.LIST_OF_16_BIT_SERVICE_SOLICITATION_UUIDS -> {
                    serviceSolicitationUuids = (serviceSolicitationUuids ?: mutableListOf()).apply {
                        for (j in i until (i + length - 1) step 2) {
                            add(Uuid.fromBytes(raw, j, 2))
                        }
                    }
                }
                AdvertisingDataType.LIST_OF_32_BIT_SERVICE_SOLICITATION_UUIDS -> {
                    serviceSolicitationUuids = (serviceSolicitationUuids ?: mutableListOf()).apply {
                        for (j in i until (i + length - 3) step 4) {
                            add(Uuid.fromBytes(raw, j, 4))
                        }
                    }
                }
                AdvertisingDataType.LIST_OF_128_BIT_SERVICE_SOLICITATION_UUIDS -> {
                    serviceSolicitationUuids = (serviceSolicitationUuids ?: mutableListOf()).apply {
                        for (j in i until (i + length - 15) step 16) {
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
            // Store the raw data for the AD structure.
            if (type != null) {
                adStructures = (adStructures ?: mutableMapOf()).apply {
                    getOrPut(type) { mutableListOf() }.add(raw.copyOfRange(i, i + length))
                }
            }

            i += length
        }

        this.flags = flags
        this.completeLocalName = completeName
        this.shortenedLocalName = shortenedName
        this.serviceUuids = serviceUuids ?: emptyList()
        this.serviceSolicitationUuids = serviceSolicitationUuids?: emptyList()
        this.serviceData = serviceData ?: emptyMap()
        this.txPowerLevel = txPowerLevel
        this.manufacturerData = manufacturerData ?: emptyMap()
        this.meshPbAdv = meshPbAdv
        this.meshMessage = meshMessage
        this.meshBeacon = meshBeacon
        this.adStructures = adStructures ?: emptyMap()
        // The name is the Complete Local Name, if present, or the Shortened Local Name.
        this.name = completeName ?: shortenedName
    }
}
