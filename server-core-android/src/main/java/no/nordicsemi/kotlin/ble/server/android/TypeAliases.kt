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

/** A primary service of the local GATT server, for Android centrals. */
typealias LocalService = no.nordicsemi.kotlin.ble.server.LocalService<Central>

/** A service included in another service of the local GATT server, for Android centrals. */
typealias LocalIncludedService = no.nordicsemi.kotlin.ble.server.LocalIncludedService<Central>

/** A characteristic of the local GATT server, for Android centrals. */
typealias LocalCharacteristic = no.nordicsemi.kotlin.ble.server.LocalCharacteristic<Central>

/** A descriptor of the local GATT server, for Android centrals. */
typealias LocalDescriptor = no.nordicsemi.kotlin.ble.server.LocalDescriptor<Central>

/** A request to read an attribute of the local GATT server, sent by an Android central. */
typealias ReadRequest = no.nordicsemi.kotlin.ble.server.ReadRequest<Central>

/** A request to write an attribute of the local GATT server, sent by an Android central. */
typealias WriteRequest = no.nordicsemi.kotlin.ble.server.WriteRequest<Central>
