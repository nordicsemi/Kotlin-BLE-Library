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

package no.nordicsemi.kotlin.ble.client.android

/**
 * The state of the bond between the Android device and a peripheral, as seen by the system.
 *
 * Bonded means that this Android device (the central) stores the bond information (keys)
 * associated with the address of the peripheral. It is independent of the connection: the bond
 * exists when the peripheral is not connected. It does not say that the peripheral has the bond
 * information as well, and it does not mean that the link is encrypted, see [EncryptionState].
 * Android stores the keys also when the pairing was requested without bonding.
 *
 * @see Peripheral.bondState
 */
sealed class BondState {

    /**
     * The central has no bond information associated with the peripheral.
     *
     * @param reason Why the peripheral is not bonded: the reason the last bonding failed, or
     * the bond information was removed, as reported by the system.
     * `null` if there was no bonding yet.
     */
    data class None(val reason: Reason? = null) : BondState()

    /** The central is bonding with the peripheral. */
    data object Bonding : BondState()

    /**
     * The central has the bond information associated with the peripheral.
     *
     * Note, that this does not mean, that the device is connected, or that the link is encrypted.
     * Earlier versions of Android would not disconnect if establishing encryption would fail.
     */
    data object Bonded : BondState()

    /**
     * The reason why a device is not bonded after the bonding process, or after it had been bonded.
     *
     * The reasons are reported by the system. They depend on the Android version and manufacturer.
     *
     * @see BondState.None
     */
    sealed class Reason {
        /**
         * The system reported a reason that is not known to the library.
         *
         * @param code The code reported by the system.
         */
        data class Unknown(val code: Int) : Reason()
        /** The authentication failed, e.g. the PIN or passkey was wrong. */
        data object AuthenticationFailed : Reason()
        /** The peripheral rejected the pairing. */
        data object AuthenticationRejected : Reason()
        /** The pairing was cancelled by the user. */
        data object AuthenticationCanceled : Reason()
        /** The peripheral did not respond, for example it disconnected. */
        data object RemoteDeviceDown : Reason()
        /** Bonding was not possible because a discovery was in progress. */
        data object DiscoveryInProgress : Reason()
        /** The authentication timed out. */
        data object AuthenticationTimeout : Reason()
        /** Bonding was attempted too many times in a row. */
        data object RepeatedAttempts : Reason()
        /** The peripheral cancelled the pairing. */
        data object RemoteAuthenticationCanceled : Reason()
        /** The bond information was removed from Android side. */
        data object Removed : Reason()
    }
}
