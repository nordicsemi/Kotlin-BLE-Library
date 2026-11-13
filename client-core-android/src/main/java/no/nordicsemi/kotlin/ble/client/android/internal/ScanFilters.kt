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

import no.nordicsemi.kotlin.ble.client.android.ConjunctionFilterScope
import no.nordicsemi.kotlin.ble.client.android.DisjunctionFilterScope
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.core.AdvertisingDataType
import no.nordicsemi.kotlin.ble.core.util.and
import no.nordicsemi.kotlin.ble.core.util.toShortString
import kotlin.experimental.and
import kotlin.uuid.Uuid

/**
 * A filter that requires all the criteria to be satisfied.
 */
class ConjunctionFilter: ConjunctionFilterScope {

    /**
~     * A list of [DisjunctionFilter]s that will be cross merged with
     * each other and criteria set in this filter.
     */
    private var disjunctionFilters = mutableListOf<DisjunctionFilter>()

    /**
     * A set of conditions that must be satisfied for this filter.
     */
    private var filter: ScanFilter? = null

    val filters: List<ScanFilter>?
        get() {
            // If there is no DisjunctionFilter, return the single filter.
            if (disjunctionFilters.isEmpty()) return filter?.let { listOf(it) }
            // Create a list of filters to be merged with disjunction filters.
            var input = mutableListOf<ScanFilter>()
            // Add the local filter, if such was set.
            filter?.let { input.add(it) }
            // Create a temporary list of merged filters.
            var output = mutableListOf<ScanFilter>()
            // Now, cross merge each DisjunctionFilter with all filters from the input list,
            // replacing the input list with the output list after each iteration.
            disjunctionFilters.forEach { disjunctionFilter ->
                // An empty Any doesn't set any criteria.
                val alternatives = disjunctionFilter.filters
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                if (input.isEmpty()) {
                    input.add(ScanFilter())
                }
                input.forEach { localFilter ->
                    alternatives.forEach { runtimeFilter ->
                        output.add(localFilter.merged(runtimeFilter))
                    }
                }
                // Merged filters are now in the output list, swap the lists and clear the output.
                if (output.isNotEmpty()) {
                    input = output
                    output = mutableListOf()
                }
            }
            // If no criteria were set at all, there is nothing to filter.
            return input.takeIf { it.isNotEmpty() }
        }

    override fun Any(filter: DisjunctionFilterScope.() -> Unit) {
        disjunctionFilters.add(DisjunctionFilter().apply(filter))
    }

    override fun All(filter: ConjunctionFilterScope.() -> Unit) {
        // Simply apply the filter here.
        filter()
    }

    override fun Name(name: String) {
        filter = (filter ?: ScanFilter()).also {
            it.name = name
        }
    }

    override fun Name(regex: Regex) {
        // ScanFilter only supports exact name matching.
        // Save the regex for runtime filtering.
        filter = (filter ?: ScanFilter()).also {
            it.nameRegex = regex
        }
    }

    override fun Address(address: String) {
        // The address checked natively by setDeviceAddress is of type PUBLIC.
        // Save the address for runtime filtering.
        filter = (filter ?: ScanFilter()).also {
            it.address = address
        }
    }

    override fun ServiceUuid(uuid: Uuid, mask: Uuid?) {
        filter = (filter ?: ScanFilter()).also {
            it.serviceUuid = ScanFilter.ServiceUuid(uuid, mask)
        }
    }

    override fun ServiceSolicitationUuid(uuid: Uuid, mask: Uuid?) {
        filter = (filter ?: ScanFilter()).also {
            it.serviceSolicitationUuid = ScanFilter.ServiceUuid(uuid, mask)
        }
    }

    override fun ServiceData(uuid: Uuid, data: ByteArray, mask: ByteArray?) {
        filter = (filter ?: ScanFilter()).also {
            it.serviceData = ScanFilter.ServiceData(uuid, data, mask)
        }
    }

    override fun ManufacturerData(companyId: Int, data: ByteArray, mask: ByteArray?) {
        filter = (filter ?: ScanFilter()).also {
            it.manufacturerData = ScanFilter.ManufacturerData(companyId, data, mask)
        }
    }

    override fun ManufacturerData(companyId: Int) {
        filter = (filter ?: ScanFilter()).also {
            it.manufacturerData = ScanFilter.ManufacturerData(companyId, byteArrayOf(), null)
        }
    }

    override fun Custom(type: AdvertisingDataType) {
        filter = (filter ?: ScanFilter()).also {
            it.customAdvertisingData = ScanFilter.Custom(type, null, null)
        }
    }

    override fun Custom(type: AdvertisingDataType, data: ByteArray, mask: ByteArray?) {
        filter = (filter ?: ScanFilter()).also {
            it.customAdvertisingData = ScanFilter.Custom(type, data, mask)
        }
    }
}

/**
 * A filter that requires at least one of the criteria to be satisfied.
 */
class DisjunctionFilter: DisjunctionFilterScope {
    /**
     * A list of [ConjunctionFilter]s. Each filter will be applied separately.
     */
    private val conjunctionFilters = mutableListOf<ConjunctionFilter>()

    /**
     * Returns a list of filters that should be used for scanning.
     */
    val filters: List<ScanFilter>?
        get() {
            if (conjunctionFilters.isEmpty()) {
                return null
            }
            val filters = mutableListOf<ScanFilter>()
            conjunctionFilters.forEach { conjunction ->
                conjunction.filters?.let { filters.addAll(it) }
            }
            return filters
        }

    override fun Any(filter: DisjunctionFilterScope.() -> Unit) {
        // Simply apply the filter here.
        filter()
    }

    override fun All(filter: ConjunctionFilterScope.() -> Unit) {
        conjunctionFilters.add(ConjunctionFilter().apply(filter))
    }

    override fun Name(name: String) {
        conjunctionFilters.add(ConjunctionFilter().apply { Name(name) })
    }

    override fun Name(regex: Regex) {
        conjunctionFilters.add(ConjunctionFilter().apply { Name(regex) })
    }

    override fun Address(address: String) {
        conjunctionFilters.add(ConjunctionFilter().apply { Address(address) })
    }

    override fun ServiceUuid(uuid: Uuid, mask: Uuid?) {
        conjunctionFilters.add(ConjunctionFilter().apply { ServiceUuid(uuid, mask) })
    }

    override fun ServiceSolicitationUuid(uuid: Uuid, mask: Uuid?) {
        conjunctionFilters.add(ConjunctionFilter().apply { ServiceSolicitationUuid(uuid, mask) })
    }

    override fun ServiceData(uuid: Uuid, data: ByteArray, mask: ByteArray?) {
        if (mask != null) {
            require(data.size == mask.size) { "Data and mask must have the same length" }
        }
        conjunctionFilters.add(ConjunctionFilter().apply { ServiceData(uuid, data, mask) })
    }

    override fun ManufacturerData(companyId: Int, data: ByteArray, mask: ByteArray?) {
        if (mask != null) {
            require(data.size == mask.size) { "Data and mask must have the same length" }
        }
        conjunctionFilters.add(ConjunctionFilter().apply { ManufacturerData(companyId, data, mask) })
    }

    override fun ManufacturerData(companyId: Int) {
        conjunctionFilters.add(ConjunctionFilter().apply { ManufacturerData(companyId) })
    }

    override fun Custom(type: AdvertisingDataType) {
        conjunctionFilters.add(ConjunctionFilter().apply { Custom(type) })
    }

    override fun Custom(type: AdvertisingDataType, data: ByteArray, mask: ByteArray?) {
        if (mask != null) {
            require(data.size == mask.size) { "Data and mask must have the same length" }
        }
        conjunctionFilters.add(ConjunctionFilter().apply { Custom(type, data, mask) })
    }

}

/**
 * A filter that will be used in the library to filter out devices.
 *
 * They are either not supported by the Android API, or are not supported on some Android versions.
 */
class ScanFilter {
    class ServiceUuid(val uuid: Uuid, val mask: Uuid?) {
        operator fun component1(): Uuid = uuid
        operator fun component2(): Uuid? = mask
        override fun toString() = "${uuid.toShortString()}${mask?.let { " (mask: ${it.toShortString()})" } ?: ""}"
    }
    class ServiceData(val uuid: Uuid, val data: ByteArray, val mask: ByteArray?) {
        operator fun component1(): Uuid = uuid
        operator fun component2(): ByteArray = data
        operator fun component3(): ByteArray? = mask
        override fun toString() = "${uuid.toShortString()}, data: ${data.toHexString()}${mask?.let { " (mask: ${it.toHexString()})" } ?: ""}"
    }
    class ManufacturerData(val companyId: Int, val data: ByteArray, val mask: ByteArray?) {
        operator fun component1(): Int = companyId
        operator fun component2(): ByteArray = data
        operator fun component3(): ByteArray? = mask
        override fun toString() = "$companyId, data: ${data.toHexString()}${mask?.let { " (mask: ${it.toHexString()})" } ?: ""}"
    }
    class Custom(val type: AdvertisingDataType, val data: ByteArray?, val mask: ByteArray?) {
        operator fun component1() = type
        operator fun component2() = data
        operator fun component3() = mask
        override fun toString() = "$type, data: ${data?.toHexString()}${mask?.let { " (mask: ${it.toHexString()})" } ?: ""}"
    }

    /** Private or public MAC address. */
    var address: String? = null
    /** Device name. */
    var name: String? = null
    /** Regular expression for the device name. */
    var nameRegex: Regex? = null
    /** Service UUID. */
    var serviceUuid: ServiceUuid? = null
    /** Service solicitation UUID. */
    var serviceSolicitationUuid: ServiceUuid? = null
    /** Service data. */
    var serviceData: ServiceData? = null
    /** Manufacturer data. */
    var manufacturerData: ManufacturerData? = null
    /** Custom advertising data type. */
    var customAdvertisingData: Custom? = null

    fun merged(other: ScanFilter) = ScanFilter().also {
        it.address = address ?: other.address
        it.name = name ?: other.name
        it.nameRegex = nameRegex ?: other.nameRegex
        it.serviceUuid = serviceUuid ?: other.serviceUuid
        it.serviceSolicitationUuid = serviceSolicitationUuid ?: other.serviceSolicitationUuid
        it.serviceData = serviceData ?: other.serviceData
        it.manufacturerData = manufacturerData ?: other.manufacturerData
        it.customAdvertisingData = customAdvertisingData ?: other.customAdvertisingData
    }

    internal fun matches(scanResult: ScanResult): Boolean {
        // First, let's check if the address matches.
        address?.let { address ->
            if (scanResult.peripheral.address != address) {
                return false
            }
        }

        // If the address matches or is not set, let's check the advertising data.
        val advertisingData = scanResult.advertisingData
        name?.let { name ->
            if (advertisingData.name != name) {
                return false
            }
        }
        nameRegex?.let { nameRegex ->
            if (advertisingData.name?.matches(nameRegex) != true) {
                return false
            }
        }
        serviceUuid?.let { (uuid, mask) ->
            advertisingData.serviceUuids.firstOrNull { it.matches(uuid, mask) } ?: return false
        }
        serviceSolicitationUuid?.let { (uuid, mask) ->
            advertisingData.serviceSolicitationUuids.firstOrNull { it.matches(uuid, mask) } ?: return false
        }
        // A device that doesn't advertise the data doesn't match.
        serviceData?.let { (uuid, data, mask) ->
            if (advertisingData.serviceData[uuid]?.matches(data, mask) != true) {
                return false
            }
        }
        manufacturerData?.let { (companyId, data, mask) ->
            if (advertisingData.manufacturerData[companyId]?.matches(data, mask) != true) {
                return false
            }
        }
        customAdvertisingData?.let { (type, data, mask) ->
            // The device must advertise the AD type, and, if data is set, at least one structure
            // of that type must match it.
            val adStructures = advertisingData.adStructures[type] ?: return false
            if (data != null && adStructures.none { it.matches(data, mask) }) {
                return false
            }
        }
        return true
    }

    override fun toString() = buildString {
        append("ScanFilter: (")
        if (address != null) append("Address: $address, ")
        if (name != null) append("Name: $name, ")
        if (nameRegex != null) append("Name Regex: $nameRegex, ")
        if (serviceUuid != null) append("Service UUID: $serviceUuid, ")
        if (serviceSolicitationUuid != null) append("Service Solicitation UUID: $serviceSolicitationUuid, ")
        if (serviceData != null) append("Service Data: $serviceData, ")
        if (manufacturerData != null) append("Manufacturer Data: $manufacturerData, ")
        if (customAdvertisingData != null) append("Custom: $customAdvertisingData, ")
        if (length > 14) setLength(length - 2)
        append(")")
    }
}

private fun Uuid.matches(uuid: Uuid, mask: Uuid?): Boolean {
    if (mask == null) {
        return this == uuid
    }
    return (this and mask) == (uuid and mask)
}

private fun ByteArray.matches(data: ByteArray, mask: ByteArray?): Boolean {
    if (size < data.size) {
        return false
    }
    if (mask == null) {
        for (i in data.indices) {
            if (this[i] != data[i]) {
                return false
            }
        }
        return true
    }
    for (i in data.indices) {
        if ((mask[i] and this[i]) != (mask[i] and data[i])) {
            return false
        }
    }
    return true
}

fun List<ScanFilter>.match(scanResult: ScanResult): Boolean = any { it.matches(scanResult) }