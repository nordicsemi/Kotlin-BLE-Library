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

import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.Cancelled
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.Success
import kotlin.time.Duration

/**
 * Connection state of a Bluetooth LE peripheral.
 */
sealed class ConnectionState {

    /**
     * The client is trying to connect to the peripheral.
     *
     * When connected, the state will change to [Connected].
     *
     * On timeout or cancellation, the state will change to [Disconnected] with the correct reason.
     */
    data object Connecting: ConnectionState()

    /**
     * The peripheral is connected.
     *
     * When the connection terminates, the state will change to [Disconnected]
     * or [Connecting] if the client will try to reconnect.
     */
    data object Connected: ConnectionState()

    /**
     * Disconnection has been initiated.
     *
     * This state is set when the `disconnect()` method is called on the peripheral.
     *
     * This is followed by [Disconnected] state with reason [Success][Disconnected.Reason.Success].
     */
    data object Disconnecting: ConnectionState()

    /**
     * Device has disconnected.
     *
     * If the [reason] is null, it means that no connection attempt was made. This is
     * the initial state of the peripheral.
     *
     * Note, that it doesn't mean that the peripheral is not connected, it may be connected
     * using another [Manager].
     *
     * @param reason Reason of disconnection or `null` if the connection was never initiated.
     * This is the initial state of the peripheral.
     */
    data class Disconnected(val reason: Reason? = null): ConnectionState() {

        /** Reason of disconnection. */
        sealed class Reason {
            /** The disconnection was initiated by the user.  */
            data object Success: Reason()
            /**
             * Unknown error.
             *
             * Errors that may be reported as Unknown:
             * * 0x101 (257) = GATT Failure (`BluetoothGatt.GATT_FAILURE`)
             *
             * See [gatt_api.h](https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Bluetooth/system/stack/include/gatt_api.h),
             * [hci_error_code.h](https://android.googlesource.com/platform/system/bt/+/refs/heads/main%5E/stack/include/hci_error_code.h).
             *
             * @property status The status code returned by the Bluetooth stack.
             */
            data class Unknown(val status: Int): Reason()
            /** The local device initiated disconnection.  */
            data object TerminateLocalHost: Reason()
            /** The remote device initiated graceful disconnection. */
            data object TerminatePeerUser: Reason()
            /**
             * Insufficient authentication.
             *
             * This error is returned when the Android device tries to enable security on a
             * device that had its bond information removed. Remove bond information on the phone
             * and retry.
             */
            data object InsufficientAuthentication: Reason()
            /** The device got out of range or has turned off. */
            data object LinkLoss: Reason()
            /** Connection attempt was canceled.  */
            data object Cancelled: Reason()
            /**
             * Connection attempt was aborted due to an unsupported address.
             *
             * Resolvable Private Address (RPA) can rotate, causing address to "expire" in the
             * background connection list. RPA is allowed for direct connection, as such request
             * times out after a short period of time.
             *
             * See: [Source code](https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/Bluetooth/system/stack/gatt/gatt_api.cc;l=1498)
             */
            data object UnsupportedAddress: Reason()
            /**
             * Connection attempt was aborted due PHY negotiations failure.
             *
             * Some phones fail to respond properly to a PHY request sent immediately after establishing
             * the connection (i.e. Samsung S8 with Android 9 responds with Instant from the past).
             * The peripheral drops the connection when a valid response is not received before
             * the timeout. A solution may be to remove the PHY LE2M request from the peripheral side,
             * i.e. by setting `CONFIG_BT_CTLR_PHY_2M=n` in Zephyr.
             *
             * This bug seems to be non-existent on other Android devices we tested.
             */
            data object UnsupportedConfiguration: Reason()
            /**
             * The connection attempt timed out.
             *
             * The device might have reboot, is out of range, turned off  or doesn't respond
             * for another reason.
             *
             * @property duration The duration of the timeout.
             */
            data class Timeout(val duration: Duration): Reason()
            /**
             * The disconnection happened because (at least) one of the required services was
             * not found on the device.
             */
            data object RequiredServiceNotFound: Reason()
        }

        /**
         * A quick check whether the disconnection was initiated by the user.
         *
         * This returns `false` in the initial state, that is before the connection attempt was started.
         *
         * `True` is returned if `disconnect()` method was called, which happens
         * with the `reason` is `Success`, `Cancelled` or `RequiredServiceNotFound`.
         */
        val isUserInitiated: Boolean
            get() = reason is Success || reason is Cancelled || reason is Reason.RequiredServiceNotFound
    }

    /** Whether the connection is open. */
    val isConnected: Boolean
        get() = this is Connected

    /** Whether the connection terminated. */
    val isDisconnected: Boolean
        get() = this is Disconnected
}
