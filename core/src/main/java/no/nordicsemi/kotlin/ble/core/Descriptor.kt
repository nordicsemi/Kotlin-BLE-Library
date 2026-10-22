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

@file:Suppress("unused")

package no.nordicsemi.kotlin.ble.core

import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.uuid.Uuid

/**
 * Interface representing a Bluetooth GATT descriptor.
 */
interface Descriptor {

    companion object Uuids {
        /** Characteristic Extended Properties descriptor UUID. */
        val CHAR_EXT_PROP_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2900) }
        /** Characteristic User Description descriptor UUID. */
        val CHAR_USER_DESC_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2901) }
        /** Client Characteristic Configuration descriptor UUID. */
        val CLIENT_CHAR_CONF_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2902) }
        /** Server Characteristic Configuration descriptor UUID. */
        val SERVER_CHAR_CONF_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2903) }
        /** Characteristic Presentation Format descriptor UUID. */
        val CHAR_PRESENTATION_FORMAT_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2904) }
        /** Characteristic Aggregate Format descriptor UUID. */
        val CHAR_AGGREGATE_FORMAT_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2905) }
        // TODO Are any of the following descriptors read-only?
        /** Valid Range descriptor UUID. */
        val VALID_RANGE_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2906) }
        /** External Report Reference descriptor UUID. */
        val EXTERNAL_REPORT_REF_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2907) }
        /** Report Reference descriptor UUID. */
        val REPORT_REF_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2908) }
        /** Number of Digitals descriptor UUID. */
        val NUMBER_OF_DIGITALS_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2909) }
        /** Value Trigger Setting descriptor UUID. */
        val VALUE_TRIGGER_SETTING_UUID: Uuid by lazy { Uuid.fromShortUuid(0x290A) }
        /** Environmental Sensing Configuration descriptor UUID. */
        val ENV_SENSING_CONFIG_UUID: Uuid by lazy { Uuid.fromShortUuid(0x290B) }
        /** Environmental Sensing Measurement descriptor UUID. */
        val ENV_SENSING_MEASUREMENT_UUID: Uuid by lazy { Uuid.fromShortUuid(0x290C) }
        /** Environmental Sensing Trigger Setting descriptor UUID. */
        val ENV_SENSING_TRIGGER_SETTING_UUID: Uuid by lazy { Uuid.fromShortUuid(0x290D) }
        /** Time Trigger Setting descriptor UUID. */
        val TIME_TRIGGER_SETTING_UUID: Uuid by lazy { Uuid.fromShortUuid(0x290E) }
        /** Complete BR-EDR Transport Block Data descriptor UUID. */
        val COMPLETE_BR_EDR_TRANSPORT_BLOCK_DATA_UUID: Uuid by lazy { Uuid.fromShortUuid(0x290F) }
        /** Observation Schedule descriptor UUID. */
        val OBSERVATION_SCHEDULE_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2910) }
        /** Valid Range and Accuracy descriptor UUID. */
        val VALID_RANGE_AND_ACCURACY_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2911) }
        /** Measurement Description descriptor UUID. */
        val MEASUREMENT_DESCRIPTION_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2912) }
        /** Manufacturer Limits descriptor UUID. */
        val MANUFACTURER_LIMITS_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2913) }
        /** Process Tolerances descriptor UUID. */
        val PROCESS_TOLERANCES_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2914) }
        /** IMD Trigger Setting descriptor UUID. */
        val IMD_TRIGGER_SETTING_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2915) }
        /** Cooking Sensor Info descriptor UUID. */
        val COOKING_SENSOR_INFO_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2916) }
        /** Cooking Trigger Setting descriptor UUID. */
        val COOKING_TRIGGER_SETTING_UUID: Uuid by lazy { Uuid.fromShortUuid(0x2917) }
    }

    /**
     * The owner of the parent service of this descriptor.
     *
     * The owner is set to null when the service was invalidated.
     */
    val owner: Peer<*>?

    /**
     * [Uuid] of the descriptor.
     */
    val uuid: Uuid

    /**
     * An opaque value that distinguishes descriptors with the same [uuid] in the same characteristic.
     *
     * It is not unique across the peripheral and it is not an ATT handle. Its value differs
     * between platforms, Android versions and the mock, so do not persist it or compare it
     * across connections. Identify a descriptor by its characteristic and its own [uuid] and `instanceId`.
     */
    val instanceId: Int

    /**
     * The parent characteristic.
     */
    val characteristic: Characteristic<*>

    /**
     * Returns true if the descriptor is a Client Characteristic Configuration descriptor.
     */
    val isClientCharacteristicConfiguration: Boolean
        get() = uuid == CLIENT_CHAR_CONF_UUID

    /**
     * Checks whether the descriptor can be read.
     */
    fun isReadable() = true

    /**
     * Checks whether the descriptor can be written.
     *
     * As descriptors don't have properties, like characteristics, this depends on the descriptor
     * specification and permissions. Some descriptors are always read-only. When unknown,
     * it is assumed that the descriptor is writable, as the write operation will fail if not.
     */
    fun isWritable() =
        // Note: These 3 descriptors are read-only as per specification.
        //       Other descriptors may be writable depending on the implementation.
        uuid != CHAR_EXT_PROP_UUID &&
        uuid != CHAR_PRESENTATION_FORMAT_UUID &&
        uuid != CHAR_AGGREGATE_FORMAT_UUID

    /**
     * Returns whether reading or writing to the descriptor is restricted and will result
     * in a [SecurityException].
     */
    fun isRestricted() = characteristic.isRestricted()
}
