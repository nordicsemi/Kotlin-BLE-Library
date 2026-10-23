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

package no.nordicsemi.kotlin.ble.client.mock

import no.nordicsemi.kotlin.ble.core.mock.BondingFailure

/**
 * The state of the bond between the phone and a simulated peripheral, as seen by the phone.
 *
 * @see PeripheralSpec.bondState
 */
sealed class MockBondState {

    /**
     * The peripheral is not bonded, and the bonding process has not been initiated.
     *
     * @param reason Why the peripheral is not bonded: the reason the last bonding failed, or
     * the bond was removed. `null` if there was no bonding yet.
     */
    data class None(val reason: Reason? = null) : MockBondState()

    /** The peripheral is not bonded yet, but the bonding process has been initiated. */
    data object Bonding : MockBondState()

    /** The peripheral is bonded. */
    data object Bonded : MockBondState()

    /** The reason why the peripheral is not bonded. */
    sealed interface Reason {

        /** The bonding failed. */
        data class Failed(val failure: BondingFailure) : Reason

        /** The bond was removed. It is only set when the phone removes the bond. */
        data object Removed : Reason
    }
}
