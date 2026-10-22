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

package no.nordicsemi.kotlin.ble.core

import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.uuid.Uuid

/**
 * Interface representing a Bluetooth GATT service.
 */
sealed interface Service<C: Characteristic<*>> {

    companion object Uuids {
        /** The UUID of Generic Access Service. */
        val GENERIC_ACCESS_UUID: Uuid by lazy { Uuid.fromShortUuid(0x1800) }
        /** The UUID of Generic Attribute Service. */
        val GENERIC_ATTRIBUTE_UUID: Uuid by lazy { Uuid.fromShortUuid(0x1801) }

        /**
         * Set of restricted GATT Services.
         *
         * Accessing any attribute in those will result in a [SecurityException].
         *
         * The list is based on the following sources:
         * * [GattUtil](https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Bluetooth/android/app/src/com/android/bluetooth/gatt/GattUtil.kt)
         * * [isRestrictedSrvcUuid](https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Bluetooth/android/app/src/com/android/bluetooth/gatt/GattService.java;l=1654)
         * * [HidHostService](https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Bluetooth/android/app/src/com/android/bluetooth/hid/HidHostService.java)
         */
        private object Restricted {
            /** The UUID of the Android TV Remote Service. */
            val ANDROID_TV_REMOTE_UUID: Uuid by lazy { Uuid.parse("AB5E0001-5A21-4F05-BC7D-AF01F617B664") }
            /** The UUID of the Android Head Tracker Service. */
            val ANDROID_HEADTRACKER_UUID: Uuid by lazy { Uuid.parse("109b862f-50e3-45cc-8ea1-ac62de4846d1") }
            /** The UUID of the Apple Notification Center Service (ANCS). */
            val ANCS_UUID: Uuid by lazy { Uuid.parse("7905F431-B5CE-4E99-A40F-4B1E122D00D0") }
            /** The UUID of the FIDO Service. */
            val FIDO_SERVICE_UUID: Uuid by lazy { Uuid.fromShortUuid(0xFFFD) } // U2F
            /** Set of LE Audio Services. */
            val LE_AUDIO_SERVICE_UUIDS: Set<Uuid> by lazy {
                setOf(
                    Uuid.fromShortUuid(0x1843), // AICS
                    Uuid.fromShortUuid(0x1844), // VCS
                    Uuid.fromShortUuid(0x1845), // VOCS
                    Uuid.fromShortUuid(0x1846), // CSIS
                    Uuid.fromShortUuid(0x184E), // ASCS
                    Uuid.fromShortUuid(0x184F), // BASS
                    Uuid.fromShortUuid(0x1850), // PACS
                    Uuid.fromShortUuid(0x1854), // HAP
                )
            }
            /** Checks whether the given [uuid] is restricted. */
            fun contains(uuid: Uuid) =
                // 4 characteristics in HID service are restricted.
                uuid == ANDROID_TV_REMOTE_UUID ||
                uuid == ANDROID_HEADTRACKER_UUID ||
                // uuid == ANCS_UUID ||
                uuid == FIDO_SERVICE_UUID ||
                uuid in LE_AUDIO_SERVICE_UUIDS
            // TODO Test LE Audio and ANCS on a real device
        }
    }

    /**
     * [Uuid] of a service.
     */
    val uuid: Uuid

    /**
     * An opaque value that distinguishes services with the same [uuid] on the peripheral.
     *
     * It is not unique across the peripheral and it is not an ATT handle. Its value differs
     * between platforms, Android versions and the mock, so do not persist it or compare it
     * across connections. Identify a service by its [uuid] and `instanceId`.
     */
    val instanceId: Int

    /**
     * List of characteristics of this service.
     */
    val characteristics: List<C>

    /**
     * List of included secondary services.
     */
    val includedServices: List<IncludedService<C>>

    /**
     * Returns whether the service is restricted and accessing it will result in a
     * [SecurityException].
     */
    fun isRestricted() = Restricted.contains(uuid)
}

/**
 * An interface representing a primary or an included service.
 */
interface AnyService<C: Characteristic<*>>: Service<C> {

    /**
     * The owner of the service.
     *
     * The owner is set to null when the service was invalidated.
     */
    val owner: Peer<*>?

    /**
     * Whether the service is a primary service.
     *
     * A primary service is a root service that is not included in any other service.
     */
    val isPrimary: Boolean
}

/**
 * An interface representing a primary service.
 */
interface PrimaryService<C: Characteristic<*>>: AnyService<C> {
    override val isPrimary: Boolean
        get() = true
}

/**
 * An interface representing a service included in another service.
 *
 * There are no limits to the number of include definitions or
 * the depth of nested includes in a service definition.
 */
interface IncludedService<C: Characteristic<*>>: AnyService<C> {

    /**
     * The parent service of the service.
     */
    val service: AnyService<C>

    override val owner: Peer<*>?
        get() = service.owner

    override val isPrimary: Boolean
        get() = false
}
