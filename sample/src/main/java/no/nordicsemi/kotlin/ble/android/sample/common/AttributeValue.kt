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

package no.nordicsemi.kotlin.ble.android.sample.common

import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.core.WriteType

/**
 * The last values exchanged with a characteristic or a descriptor.
 *
 * Remote attributes don't hold a value, so the sample app records it itself.
 *
 * @property received The last value read from the attribute or received in a notification
 * or indication.
 * @property notificationCount Number of notifications or indications received since
 * subscribing, or 0 if the last [received] value was read.
 * @property sent The last value written to the attribute.
 * @property error The last error, cleared on the next successful operation.
 */
data class AttributeValue(
    val received: Timestamped<ByteArray>? = null,
    val notificationCount: Int = 0,
    val sent: Timestamped<ByteArray>? = null,
    val error: Timestamped<String>? = null,
)

/**
 * A value with the time, in milliseconds since epoch, when it was received or sent.
 */
data class Timestamped<T>(
    val value: T,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * An operation requested from the UI on a characteristic or a descriptor.
 */
sealed interface AttributeAction {
    data class Read(val characteristic: RemoteCharacteristic) : AttributeAction
    data class Write(
        val characteristic: RemoteCharacteristic,
        val value: ByteArray,
        val writeType: WriteType,
    ) : AttributeAction
    data class ToggleNotifications(val characteristic: RemoteCharacteristic) : AttributeAction
    data class ReadDescriptor(val descriptor: RemoteDescriptor) : AttributeAction
}
