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

package no.nordicsemi.kotlin.ble.client.android.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.internal.BaseRemoteDescriptor
import no.nordicsemi.kotlin.ble.client.internal.OperationEvent
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.log.Log
import kotlin.uuid.Uuid

internal class NativeRemoteDescriptor(
    parent: RemoteCharacteristic,
    private val gatt: BluetoothGatt,
    private val descriptor: BluetoothGattDescriptor,
    events: SharedFlow<GattEvent>,
): BaseRemoteDescriptor(parent, events) {
    override val uuid: Uuid = descriptor.uuid.toKotlinUuid
    // BluetoothGattDescriptor does not expose instance ID.
    // Instead, let's just use the index of the descriptor in the list of descriptors.
    // Note, that we can't use 'parent.descriptors.indexOf(descriptor)' here,
    // as at the time of creation of the descriptor it is not yet added to parent's list.
    override val instanceId: Int = descriptor.characteristic.descriptors.indexOf(descriptor)

    override suspend fun FlowCollector<GattEvent>.executeRead() {
       owner?.logger?.log(Layer.GATT, Log.Level.DEBUG, owner?.identifier.toString(), null) {
            "gatt.readDescriptor(${descriptor.uuid})"
        }
        val success = gatt.readDescriptor(descriptor)
        check(success) {
            owner?.logger?.log(Layer.GATT, Log.Level.DEBUG, owner?.identifier.toString(), null) {
                "Reading descriptor failed"
            }
            throw OperationFailedException(OperationStatus.RequestFailed)
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun FlowCollector<GattEvent>.executeWrite(data: ByteArray) {
        owner?.logger?.log(Layer.GATT, Log.Level.DEBUG, owner?.identifier.toString(), null) {
            "gatt.writeDescriptor(${descriptor.uuid}, value=0x${data.toHexString()})"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(descriptor, data)

            if (result != BluetoothStatusCodes.SUCCESS) {
                owner?.logger?.log(Layer.GATT, Log.Level.DEBUG, owner?.identifier.toString(), null) {
                    "Writing descriptor failed with status $result"
                }
            }

            @SuppressLint("SwitchIntDef")
            when (result) {
                BluetoothStatusCodes.SUCCESS -> { /* no-op */ }

                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY ->
                    throw OperationFailedException(OperationStatus.Busy)

                9, /* BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND */
                28 /* BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED */ ->
                    throw InvalidAttributeException()

                else ->
                    throw OperationFailedException(OperationStatus.UnknownError(result))
            }
        } else {
            descriptor.value = data
            // There was a bug on an early versions of Android, where the descriptor
            // was written using the write type of the parent characteristic.
            // Instead, descriptors can only be written using WRITE_TYPE_DEFAULT.
            descriptor.characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val success = gatt.writeDescriptor(descriptor)
            check(success) {
                owner?.logger?.log(Layer.GATT, Log.Level.DEBUG, owner?.identifier.toString(), null) {
                    "Writing descriptor failed"
                }
                throw OperationFailedException(OperationStatus.RequestFailed)
            }
        }
    }

    override fun OperationEvent.matches(): Boolean = subject == descriptor
}