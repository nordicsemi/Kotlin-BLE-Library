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

package no.nordicsemi.kotlin.ble.server.android.internal

import android.bluetooth.BluetoothDevice
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.core.ATT_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.server.android.Client
import no.nordicsemi.kotlin.ble.server.exception.ClientNotConnectedException
import no.nordicsemi.kotlin.log.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The time to wait for the link to be terminated after the disconnection was requested. */
private val DISCONNECT_TIMEOUT = 10.seconds

/** The size of the ATT opcode and the attribute handle in a notification. */
private const val NOTIFICATION_HEADER_SIZE = 3

/**
 * A native implementation of [Client] for Android.
 *
 * It is created by the [NativeGattServer] when a client connects, and its state is updated
 * from the callbacks of the server.
 *
 * @param device The native device.
 * @param server The server which the client is connected to.
 */
internal class NativeClient(
    val device: BluetoothDevice,
    private val server: NativeGattServer,
): Client {
    override var logger: Log.Sink<Layer>? = Log.Sink.Null
    override val identifier: String = device.address

    override val state: StateFlow<ConnectionState>
        field = MutableStateFlow<ConnectionState>(ConnectionState.Connected)

    override val mtu: StateFlow<Int?>
        field = MutableStateFlow<Int?>(ATT_MTU_DEFAULT)

    override val phy: StateFlow<PhyInUse?>
        field = MutableStateFlow<PhyInUse?>(null)

    override val connectionParameters: StateFlow<ConnectionParameters?>
        field = MutableStateFlow<ConnectionParameters?>(ConnectionParameters.Unknown)

    override val maximumUpdateValueLength: Int
        get() = (mtu.value ?: ATT_MTU_DEFAULT) - NOTIFICATION_HEADER_SIZE

    /** Whether the disconnection was requested by the app. */
    @Volatile
    private var disconnectRequested = false

    /** Only one request to change the PHY can be in flight. */
    private val phyLock = Mutex()

    /** The pending request to change the PHY, which is completed by `onPhyUpdate`. */
    @Volatile
    private var phyRequest: CompletableDeferred<PhyInUse>? = null

    override suspend fun setPreferredPhy(
        txPhy: Phy,
        rxPhy: Phy,
        phyOptions: PhyOption,
        timeout: Duration,
    ): PhyInUse {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw UnsupportedOperationException("Changing PHY requires Android 8 (Oreo) or newer")
        }
        if (!state.value.isConnected) {
            throw ClientNotConnectedException()
        }
        // The request enters the lock of the client, the stack is asked to change the PHY, and
        // the callback completes the request, the same way as the notifications are sent.
        return phyLock.withLock {
            val request = CompletableDeferred<PhyInUse>()
            phyRequest = request
            try {
                withTimeout(timeout) {
                    server.setPreferredPhy(this@NativeClient, txPhy, rxPhy, phyOptions)
                    request.await()
                }
            } finally {
                phyRequest = null
            }
        }
    }

    override suspend fun disconnect() {
        if (!state.value.isConnected) return
        disconnectRequested = true
        state.value = ConnectionState.Disconnecting
        try {
            server.cancelConnection(this)
        } catch (e: Exception) {
            // The request was not made, for example because the permission was revoked.
            disconnectRequested = false
            state.value = ConnectionState.Connected
            throw e
        }
        withTimeoutOrNull(DISCONNECT_TIMEOUT) {
            state.first { it.isDisconnected }
        } ?: logger?.warn(Layer.GAP) { "Timed out waiting for $identifier to disconnect" }
    }

    // Updates from the server callbacks.

    /** Called when the link state changed, but the client did not disconnect. */
    fun onStateChanged(newState: ConnectionState) {
        state.value = newState
    }

    /** Called when the client disconnected. */
    fun onDisconnected(status: Int) {
        val reason = status.toDisconnectionReason(userInitiated = disconnectRequested)
        state.value = ConnectionState.Disconnected(reason)
        mtu.value = null
        phy.value = null
        connectionParameters.value = null
        val exception = ClientNotConnectedException()
        phyRequest?.completeExceptionally(exception)
    }

    /** Called when the ATT MTU changed. */
    fun onMtuChanged(mtu: Int) {
        this.mtu.value = mtu
    }

    /** Called when the PHY was updated or read. */
    fun onPhyChanged(txPhy: Int, rxPhy: Int, isUpdate: Boolean) {
        val phyInUse = PhyInUse(txPhy.toPhy(), rxPhy.toPhy())
        phy.value = phyInUse
        if (isUpdate) {
            phyRequest?.complete(phyInUse)
        }
    }

    /** Called when the connection parameters changed. */
    fun onConnectionParametersChanged(interval: Int, latency: Int, timeout: Int) {
        connectionParameters.value = try {
            ConnectionParameters.Specified(interval, latency, timeout)
        } catch (_: IllegalArgumentException) {
            ConnectionParameters.Unknown
        }
    }

    override fun toString() = "Client(address=$identifier)"
}
