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

package no.nordicsemi.kotlin.ble.core.mock

/**
 * The reason why bonding of a simulated peripheral failed.
 *
 * The client is told the matching reason, and the phone terminates the link a few seconds later.
 * A peripheral that terminates the link itself after a failed bonding can be simulated by calling
 * `PeripheralSpec.simulateDisconnection()`.
 *
 * Note: Most phones report all the failures, except [REJECTED] on some, with the same reason
 * (the library reports it as `AuthenticationFailed`), and the exact reason differs between
 * Android versions. Use [REJECTED] to emulate a real refusal of the peripheral. The other failures
 * allow testing how the app reacts to the other reasons, which may be reported by phones in the future.
 *
 * [CANCELLED] and [WRONG_PIN] only make sense for pairing that requires user interaction.
 * [AUTH_FAILED] is the same as [WRONG_PIN], but reads better when the simulated peripheral
 * does not use a PIN.
 */
enum class BondingFailure {
    /** The peripheral refused to pair, e.g. it does not support bonding. This is the default. */
    REJECTED,
    /** The user cancelled the pairing, e.g. by tapping Cancel on the pairing dialog. */
    CANCELLED,
    /** The user entered a wrong PIN. It is reported in the same way as [AUTH_FAILED]. */
    WRONG_PIN,
    /**
     * The authentication failed, e.g. the keys did not match. It is reported in the same way
     * as [WRONG_PIN], and can be used when the simulated peripheral does not use a PIN.
     */
    AUTH_FAILED,
    /** The pairing was not completed in time, e.g. the user ignored the pairing dialog. */
    TIMED_OUT,
}
