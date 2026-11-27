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

package no.nordicsemi.kotlin.ble.core.android.internal

import no.nordicsemi.kotlin.ble.core.android.AdvertisingDataDefinition
import no.nordicsemi.kotlin.ble.core.android.AdvertisingDataScope
import no.nordicsemi.kotlin.ble.core.internal.AdvertisingDataScopeImpl
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.uuid.Uuid

/**
 * Implementation of the advertising data scope used to build the [AdvertisingDataDefinition].
 *
 * This implementation extends the core implementation by adding fields that can be advertised
 * on Android.
 */
class AdvertisingDataScopeImpl: AdvertisingDataScopeImpl(), AdvertisingDataScope {

    override fun build(): AdvertisingDataDefinition =
        AdvertisingDataDefinition(
            includeLocalName,
            includeTxPowerLevel,
            serviceUuids,
            serviceSolicitationUuids,
            serviceData,
            manufacturerData,
        )

    /** The flag indicating if the local name should be included in the advertisement data. */
    private var includeLocalName: Boolean = false
    /** The flag indicating if the TX power level should be included as AD type. */
    private var includeTxPowerLevel: Boolean = false
    /** The list of service solicitation UUIDs to be advertised. */
    private var serviceSolicitationUuids: List<Uuid>? = null
    /** The map of service data to be advertised. */
    private var serviceData: Map<Uuid, ByteArray>? = null
    /** The map of manufacturer data to be advertised. */
    private var manufacturerData: Map<Int, ByteArray>? = null

    override fun IncludeLocalName() {
        includeLocalName = true
    }

    override fun IncludeTxPowerLevel() {
        includeTxPowerLevel = true
    }

    override fun ServiceSolicitationUuid(uuid: Uuid) {
        serviceSolicitationUuids = serviceSolicitationUuids?.plus(uuid) ?: listOf(uuid)
    }

    override fun ServiceSolicitationUuid(shortUuid: Int) {
        ServiceSolicitationUuid(Uuid.fromShortUuid(shortUuid))
    }

    override fun ServiceData(uuid: Uuid, data: ByteArray) {
        serviceData = serviceData?.plus(uuid to data) ?: mapOf(uuid to data)
    }

    override fun ServiceData(shortUuid: Int, data: ByteArray) {
        ServiceData(Uuid.fromShortUuid(shortUuid), data)
    }

    override fun ManufacturerData(companyId: Int, data: ByteArray) {
        require(companyId in 0..0xFFFF) { "Company ID must be a 16-bit unsigned integer" }
        manufacturerData = manufacturerData?.plus(companyId to data) ?: mapOf(companyId to data)
    }

    override val isEmpty: Boolean
        get() = !includeLocalName && !includeTxPowerLevel &&
                serviceUuids.isNullOrEmpty() && serviceSolicitationUuids.isNullOrEmpty() &&
                serviceData.isNullOrEmpty() && manufacturerData.isNullOrEmpty()
}