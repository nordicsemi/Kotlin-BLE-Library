/*
 * Copyright (c) 2026, Nordic Semiconductor
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

package no.nordicsemi.kotlin.ble.server.android

import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.server.Central
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A central device connected to the local GATT server on Android.
 *
 * The identifier of the central is its MAC address.
 */
interface Central: Central<String> {

    /** The MAC address of the central. */
    val address: String
        get() = identifier

    /**
     * The connection state of the central.
     *
     * Android reports connections of every central that connects to this device, even if
     * the connection was not initiated by the app's advertising.
     */
    val state: StateFlow<ConnectionState>

    /**
     * The current ATT MTU, or `null` when the central is not connected. The value is 23 until
     * the central requests a larger MTU.
     */
    val mtu: StateFlow<Int?>

    /**
     * The current PHY in use, or `null` when the central is not connected or the PHY is not known
     * (Android 8+).
     */
    val phy: StateFlow<PhyInUse?>

    /**
     * The current connection parameters, or `null` when the central is not connected.
     * They are [ConnectionParameters.Unknown] until they change (Android 8+).
     */
    val connectionParameters: StateFlow<ConnectionParameters?>

    /**
     * Requests changing the PHY of the connection (Android 8+).
     *
     * @return The PHYs in use after the change.
     */
    suspend fun setPreferredPhy(
        txPhy: Phy,
        rxPhy: Phy = txPhy,
        phyOptions: PhyOption = PhyOption.NO_PREFERRED,
        timeout: Duration = 3.seconds,
    ): PhyInUse

    /**
     * Disconnects the central, if it is connected. Does nothing otherwise.
     */
    suspend fun disconnect()
}
