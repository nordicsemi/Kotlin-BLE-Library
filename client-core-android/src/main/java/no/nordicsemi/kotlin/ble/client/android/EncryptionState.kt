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
 * The state of the encryption of the link with a peripheral.
 *
 * Having the bond information ([Peripheral.hasBondInformation]) does not mean that the link is
 * encrypted: the bond information is stored by this Android device, whether the peripheral is
 * connected or not. A bonded peripheral should encrypt the link right after connecting, but it may
 * fail to do so, for example when the peripheral lost the keys. Use this state to check that the
 * connection is secure.
 *
 * The state is reported from Android 16 (API 36). Earlier versions do not tell whether the link
 * is encrypted, and the state is [Unknown]. Note, that old Android versions did not require
 * encryption to be resumed when connecting to a bonded device, so a device could pretend to be
 * another one, and use an unencrypted link.
 *
 * @see Peripheral.encryptionState
 */
sealed class EncryptionState {

    /**
     * It is not known whether the link is encrypted.
     *
     * This state is reported when the peripheral is not connected, encryption has not been
     * established yet, or when the Android version does not report the encryption state.
     */
    data object Unknown : EncryptionState()

    /**
     * The link is not encrypted.
     *
     * @param reason Why the link is not encrypted, e.g. the controller failed to encrypt it, or
     * the peripheral does not have the key. `null` if it is not known, or the link is simply
     * not encrypted. The reason is kept until the encryption state changes, or the peripheral
     * disconnects.
     */
    data class NotEncrypted(val reason: Reason? = null) : EncryptionState()

    /**
     * The link is encrypted.
     *
     * @param algorithm The algorithm used to encrypt the link.
     * @param keySize The size of the encryption key, in bytes, or `null` if not reported.
     */
    data class Encrypted(
        val algorithm: Algorithm,
        val keySize: Int? = null,
    ) : EncryptionState()

    /** The encryption algorithm. */
    enum class Algorithm {
        /** The E0 stream cipher, used by Bluetooth BR/EDR. */
        E0,
        /** The AES-CCM cipher. */
        AES,
        /** The algorithm is not known. */
        Unknown,
    }

    /** The reason why the link is not encrypted. */
    sealed class Reason {
        /**
         * The system reported a reason that is not known to the library.
         *
         * @param code The code reported by the system.
         */
        data class Unknown(val code: Int) : Reason()

        /**
         * The controller failed to encrypt the link.
         *
         * @param status The HCI error code returned by the controller.
         */
        data class ControllerError(val status: Int) : Reason()

        /** The peripheral did not have the key (LE encryption failure). The bond is no longer valid. */
        data object LeEncryptionFailure : Reason()

        /** The peripheral started pairing again (LE), so the bond information is no longer valid. */
        data object LeIncomingPairing : Reason()

        /** Authentication failed (Bluetooth BR/EDR). */
        data object BrEdrAuthenticationFailure : Reason()

        /** The peripheral started pairing again (Bluetooth BR/EDR). */
        data object BrEdrIncomingPairing : Reason()
    }
}
