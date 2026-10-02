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

package no.nordicsemi.kotlin.ble.client.android

/**
 * Class representing available connection priorities. Selecting a specific mode will affect
 * two parameters: latency and power consumption.
 */
enum class ConnectionPriority {

    /**
     * Connection parameter update - Use the connection parameters recommended by the
     * Bluetooth SIG. This is the default value if no connection parameter update
     * is requested.
     *
     * * Interval: 30 - 50 ms,
     * * Latency: 0,
     * * Supervision timeout: 5 sec (Android 8+) or 20 sec (before).
     *
     * @see <a href="https://android.googlesource.com/platform/packages/modules/Bluetooth/+/673c5903c4a920510c371af26e5870857a584ead%5E!">commit 673c5903c4a920510c371af26e5870857a584ead</a>
     */
    BALANCED,

    /**
     * Connection parameter update - Request a high priority, low latency connection.
     * An application should only request high priority connection parameters to transfer
     * large amounts of data over LE quickly. Once the transfer is complete, the application
     * should request [BALANCED] connection parameters
     * to reduce energy use.
     *
     * * Interval: 11.25 - 15 ms (Android 6+) or 7.5 - 10 ms (Android 4.3 - 5.1),
     * * Latency: 0,
     * * Supervision timeout: 5 sec (Android 8+) or 20 sec (before).
     *
     * @see <a href="https://android.googlesource.com/platform/packages/modules/Bluetooth/+/4bc7c7e877c9d18f2781229c553b6144f9fd7236%5E%21/">commit 4bc7c7e877c9d18f2781229c553b6144f9fd7236</a>
     * @see <a href="https://android.googlesource.com/platform/packages/modules/Bluetooth/+/673c5903c4a920510c371af26e5870857a584ead%5E!">commit 673c5903c4a920510c371af26e5870857a584ead</a>
     */
    HIGH,

    /**
     * Connection parameter update - Request low power, reduced data rate connection parameters.
     *
     * * Interval: 100 - 125 ms,
     * * Latency: 2,
     * * Supervision timeout: 5 sec (Android 8+) or 20 sec (before).
     *
     * @see <a href="https://android.googlesource.com/platform/packages/modules/Bluetooth/+/673c5903c4a920510c371af26e5870857a584ead%5E!">commit 673c5903c4a920510c371af26e5870857a584ead</a>
     */
    LOW_POWER,

    /**
     * Connection parameter update - Request the priority preferred for Digital Car Key for a lower
     * latency connection. This connection parameter will consume more power than
     * [BALANCED], so it is recommended that apps do not use this
     * unless it specifically fits their use case.
     *
     * * Interval: 30 ms,
     * * Latency: 0,
     * * Supervision timeout: 5 sec.
     */
    DIGITAL_CAR_KEY,
}
