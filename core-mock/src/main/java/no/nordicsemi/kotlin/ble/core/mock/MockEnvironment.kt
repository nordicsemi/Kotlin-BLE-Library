/*
 * Copyright (c) 2025, Nordic Semiconductor
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

import no.nordicsemi.kotlin.ble.core.Environment
import org.jetbrains.annotations.Range

/**
 * A mock environment that can be used to test the behavior of the Central Manager.
 *
 * @property maxAttMtu The maximum supported ATT MTU. This defaults to 23 bytes and can be
 * extended to 517 bytes (512 bytes for the payload, 2 octets for handle number, 2 for the offset
 * and 1 for the op code).
 * @property maxLlMtu The maximum supported Link Layer MTU. This defaults to 27 bytes and can be
 * extended to 251 with Data Length Extension (DLE).
 * @property isLe2MPhySupported Whether the LE 2M PHY is supported.
 * @property isLeCodedPhySupported Whether the LE Coded PHY is supported.
 */
interface MockEnvironment : Environment {
    val maxAttMtu: @Range(from = 23, to = 517) Int
    val maxLlMtu: @Range(from = 27, to = 251) Int
    val isLe2MPhySupported: Boolean
    val isLeCodedPhySupported: Boolean
}
