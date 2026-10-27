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

@file:Suppress("unused")

package no.nordicsemi.kotlin.ble.client.android.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Build
import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import no.nordicsemi.kotlin.ble.client.ConnectionParametersChanged
import no.nordicsemi.kotlin.ble.client.ConnectionStateChanged
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.MtuChanged
import no.nordicsemi.kotlin.ble.client.PhyChanged
import no.nordicsemi.kotlin.ble.client.ReliableWriteCompleted
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.RssiRead
import no.nordicsemi.kotlin.ble.client.ServiceDiscoveryFailed
import no.nordicsemi.kotlin.ble.client.ServicesChanged
import no.nordicsemi.kotlin.ble.client.ServicesDiscovered
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicChanged
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicRead
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicWrite
import no.nordicsemi.kotlin.ble.client.internal.DescriptorRead
import no.nordicsemi.kotlin.ble.client.internal.DescriptorWrite
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.log.Log

internal class NativeGattCallback(
    override val identifier: String,
): BluetoothGattCallback(), Log.IdentifiableEmitter<String> {
    var logger: Log.Sink<Layer>? = null

    /** Called when the device connects, before the event is emitted. */
    var onConnected: (() -> Unit)? = null
    val events: SharedFlow<GattEvent>
        field = MutableSharedFlow<GattEvent>(extraBufferCapacity = 64)

    /**
     * A requested disconnection reason.
     *
     * Older Android versions don't return status=8 (GATT_CONN_TIMEOUT) when the connection
     * drops due to a link loss. By checking whether it was the user who requested disconnection
     * we can improve the status.
     */
    var disconnectReason: ConnectionState.Disconnected.Reason? = null

    /** Whether the link is up, as reported by the last connection state change. */
    private var isConnected: Boolean = false

    /**
     * A flag set when the bond information was removed, or bonding failed, while the link was up.
     *
     * Android terminates the link then, but Android 17 reports it with status 147
     * (`GATT_CONNECTION_TIMEOUT`), which would be mapped to link loss, although the link was
     * terminated by the phone (confirmed with an HCI snoop log on Pixel 7).
     */
    private var isBondLostWhileConnected: Boolean = false

    /**
     * A flag set when the service discovery was complete.
     *
     * This is only used on Android 8-11 which do not have `onServiceChanged` callback, but
     * do report decreased connection interval during re-discovery.
     */
    private var isServiceDiscoveryComplete: Boolean = false

    // Handling connection state updates

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        logger?.debug(Layer.GAP) { "onConnectionStateChange(newState=$newState, status=$status)" }

        val betterStatus = when {
            disconnectReason != null -> status
            newState != BluetoothGatt.STATE_DISCONNECTED -> status
            // The phone terminates the link after the bond was lost (e.g. the pairing was cancelled),
            // but the status doesn't say that: Pixel 7 with Android 17 reports 147 (connection
            // timeout), and Pixel 4 with Android 12 reports 0 (success).
            isBondLostWhileConnected && (status == BluetoothGatt.GATT_SUCCESS || status == 0x93 /* GATT_CONNECTION_TIMEOUT */) -> 0x16 /* GATT_CONN_TERMINATE_LOCAL_HOST */
            // Pixel 4 with Android 12 does return status 0 when link to a device is lost.
            // Newer versions (Pixel 7 with Android 16) report status 8 (timeout) in the same case.
            status == BluetoothGatt.GATT_SUCCESS -> 0x08 /* GATT_CONN_TIMEOUT */
            else -> status
        }

        isConnected = newState == BluetoothGatt.STATE_CONNECTED
        isServiceDiscoveryComplete = false
        isBondLostWhileConnected = false
        if (isConnected) {
            onConnected?.invoke()
        }
        events.tryEmit(ConnectionStateChanged(newState.toConnectionState(betterStatus, disconnectReason)))
    }

    /** Called when the bond state changed to None. */
    fun onBondLost() {
        isBondLostWhileConnected = isConnected
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        logger?.debug(Layer.GATT) { "onServicesDiscovered(status=$status)" }
        isServiceDiscoveryComplete = true
        if (status != BluetoothGatt.GATT_SUCCESS) {
            events.tryEmit(ServiceDiscoveryFailed(RemoteServices.Failed.Reason.Unknown(status)))
            return
        }
        // Expect status to be GATT_SUCCESS (0) and at least 2 services:
        // - Generic Access
        // - Generic Attribute
        val services = gatt.services
        if (services.size < 2) {
            logger?.warn(Layer.GATT) { "Services discovery returned ${services.size} services (>= 2 expected)" }
            events.tryEmit(ServiceDiscoveryFailed(RemoteServices.Failed.Reason.EmptyResult))
            return
        }
        val remoteServices = services
            // For some reason, secondary services are not only listed as included services
            // under the root service, but also on the main list. Skip them.
            // They will be added as IncludedService inside the NativeRemoteService.
            .filter { it.type == BluetoothGattService.SERVICE_TYPE_PRIMARY }
            // Map each primary BluetoothGattService to NativeRemoteService.
            .map { NativeRemoteService(gatt, it, events) }
        events.tryEmit(ServicesDiscovered(remoteServices))
    }

    override fun onServiceChanged(gatt: BluetoothGatt) {
        logger?.debug(Layer.GATT) { "onServiceChanged()" }
        events.tryEmit(ServicesChanged)
    }

    // Handling value changes.

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        logger?.debug(Layer.GATT) { "onCharacteristicChanged(${characteristic.uuid}, value=0x${value.toHexString() })" }
        events.tryEmit(CharacteristicChanged(characteristic, value))
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        logger?.debug(Layer.GATT) { "onCharacteristicRead(${characteristic.uuid}, value=0x${value.toHexString()}, status=$status)" }
        events.tryEmit(CharacteristicRead(characteristic, value, status.toOperationStatus()))
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        logger?.debug(Layer.GATT) { "onCharacteristicWrite(${characteristic.uuid}, status=$status)" }
        events.tryEmit(CharacteristicWrite(characteristic, status.toOperationStatus()))
    }

    override fun onDescriptorRead(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
        value: ByteArray
    ) {
        logger?.debug(Layer.GATT) { "onDescriptorRead(${descriptor.uuid}, value=0x${value.toHexString()}, status=$status)" }
        events.tryEmit(DescriptorRead(descriptor, value, status.toOperationStatus()))
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        logger?.debug(Layer.GATT) { "onDescriptorWrite(${descriptor.uuid}, status=$status)" }
        events.tryEmit(DescriptorWrite(descriptor, status.toOperationStatus()))
    }

    // Note, this is called when Reliable Write was executed or aborted.
    // There is no way to distinguish between the two without keeping state.
    override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
        logger?.debug(Layer.GATT) { "onReliableWriteCompleted(status=$status)" }
        events.tryEmit(ReliableWriteCompleted(status.toOperationStatus()))
    }

    // Handling connection parameter updates

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        logger?.debug(Layer.GATT) { "onMtuChanged(mtu=$mtu, status=$status)" }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger?.warn(Layer.GATT) { "MTU request failed with status $status" }
            // no return, event must be emitted
        }
        events.tryEmit(MtuChanged(mtu))
    }

    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
        logger?.debug(Layer.LINK) { "onReadRemoteRssi(rssi=$rssi, status=$status)" }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger?.warn(Layer.LINK) { "RSSI request failed with status $status" }
            // no return, event must be emitted
        }
        events.tryEmit(RssiRead(rssi))
    }

    override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
        logger?.debug(Layer.PHY) { "onPhyUpdate(tx=$txPhy, rx=$rxPhy, status=$status)" }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger?.warn(Layer.PHY) { "PHY update failed with status $status" }
            // no return, event must be emitted
        }
        val phyInUse = PhyInUse(txPhy.toPhy(), rxPhy.toPhy())
        events.tryEmit(PhyChanged(phyInUse))
    }

    override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
        logger?.debug(Layer.PHY) { "onPhyRead(tx=$txPhy, rx=$rxPhy, status=$status)" }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger?.warn(Layer.PHY) { "Reading PHY failed with status $status" }
            // no return, event must be emitted
        }
        val phyInUse = PhyInUse(txPhy.toPhy(), rxPhy.toPhy())
        events.tryEmit(PhyChanged(phyInUse))
    }

    // Note:
    // The base method is hidden in BluetoothGattCallback using @hide.
    // It was added in Android 8 (Oreo) and it is possible to override it, but due to its hidden
    // nature, it cannot use `override`.
    @Keep
    /* override */ fun onConnectionUpdated(gatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
        logger?.debug(Layer.LINK) { "onConnectionUpdated(interval=$interval, latency=$latency, timeout=$timeout, status=$status)" }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger?.warn(Layer.LINK) { "Connection update failed with status $status" }
            // no return, event must be emitted
        }
        val newParameters = ConnectionParameters.Specified(interval, latency, timeout)
        events.tryEmit(ConnectionParametersChanged(newParameters))

        // Android starts reporting Service Changed event starting from API 31 (S).
        // However, since API 26 it was possible to detect it by listening to connection
        // parameters update, which decreased the interval to 7.5 ms during service discovery.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            isServiceDiscoveryComplete && interval == 6 /* 7.5 ms */  &&
            // Don't invalidate services while bonding.
            // There may be an ongoing request that triggered bonding.
            gatt.device.bondState != BluetoothDevice.BOND_BONDING) {
            logger?.warn(Layer.SMP) { "Inferred ongoing Service Changed procedure" }
            onServiceChanged(gatt)
        }
    }

    /**
     * This method is called on Android versions prior to Android Oreo where there is no callback
     * for when the connection parameters are updated.
     *
     * It reports [ConnectionParameters.Unknown].
     */
    fun onConnectionUpdated() {
        logger?.debug(Layer.LINK) { "onConnectionUpdated(unknown)" }
        val newParameters = ConnectionParameters.Unknown
        events.tryEmit(ConnectionParametersChanged(newParameters))
    }

    // Backward compatibility

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        onCharacteristicChanged(gatt, characteristic, characteristic.value?.clone() ?: byteArrayOf())
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        onCharacteristicRead(gatt, characteristic, characteristic.value?.clone() ?: byteArrayOf(), status)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onDescriptorRead(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        onDescriptorRead(gatt, descriptor, status, descriptor.value?.clone() ?: byteArrayOf())
    }
}