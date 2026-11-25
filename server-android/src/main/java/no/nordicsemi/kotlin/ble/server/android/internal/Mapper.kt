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

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyOption
import java.util.UUID
import kotlin.uuid.Uuid

/** The status sent in a response when the request cannot be represented as an ATT error. */
private const val GATT_ERR_UNLIKELY = 0x0E

/** Converts the Kotlin UUID to the Java one. */
internal fun Uuid.toJavaUuid(): UUID = toLongs { mostSignificantBits, leastSignificantBits ->
    UUID(mostSignificantBits, leastSignificantBits)
}

/** Converts the set of permissions to a bit field of `BluetoothGattCharacteristic.PERMISSION_*`. */
internal fun Set<Permission>.toNativePermissions(): Int = fold(0) { mask, permission ->
    mask or when (permission) {
        Permission.READ -> BluetoothGattCharacteristic.PERMISSION_READ
        Permission.READ_ENCRYPTED -> BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        Permission.READ_ENCRYPTED_MITM -> BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM
        Permission.WRITE -> BluetoothGattCharacteristic.PERMISSION_WRITE
        Permission.WRITE_ENCRYPTED -> BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        Permission.WRITE_ENCRYPTED_MITM -> BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM
        Permission.WRITE_SIGNED -> BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED
        Permission.WRITE_SIGNED_MITM -> BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM
    }
}

/**
 * Converts the set of properties to a bit field of `BluetoothGattCharacteristic.PROPERTY_*`.
 */
internal fun Set<CharacteristicProperty>.toNativeProperties(): Int = fold(0) { mask, property ->
    mask or when (property) {
        CharacteristicProperty.BROADCAST -> BluetoothGattCharacteristic.PROPERTY_BROADCAST
        CharacteristicProperty.READ -> BluetoothGattCharacteristic.PROPERTY_READ
        CharacteristicProperty.WRITE_WITHOUT_RESPONSE -> BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        CharacteristicProperty.WRITE -> BluetoothGattCharacteristic.PROPERTY_WRITE
        CharacteristicProperty.NOTIFY -> BluetoothGattCharacteristic.PROPERTY_NOTIFY
        CharacteristicProperty.INDICATE -> BluetoothGattCharacteristic.PROPERTY_INDICATE
        CharacteristicProperty.SIGNED_WRITE -> BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE
        CharacteristicProperty.EXTENDED_PROPERTIES -> BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS
    }
}

/**
 * Converts the status to the code sent in a response to a request.
 *
 * Only values from 0 to 255 can be sent over the air. Non-Bluetooth statuses, which are negative,
 * are sent as *Unlikely Error*.
 */
internal fun OperationStatus.toNativeStatus(): Int =
    if (code in 0..0xFF) code else GATT_ERR_UNLIKELY

// Source: https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Bluetooth/system/stack/include/gatt_api.h
internal fun Int.toOperationStatus(): OperationStatus = when (this) {
    BluetoothGatt.GATT_SUCCESS -> OperationStatus.Success
    0x01 /* GATT_INVALID_HANDLE */ -> OperationStatus.InvalidHandle
    BluetoothGatt.GATT_READ_NOT_PERMITTED -> OperationStatus.ReadNotPermitted
    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> OperationStatus.WriteNotPermitted
    0x04 /* GATT_INVALID_PDU */ -> OperationStatus.InvalidPdu
    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> OperationStatus.InsufficientAuthentication
    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> OperationStatus.RequestNotSupported
    BluetoothGatt.GATT_INVALID_OFFSET -> OperationStatus.InvalidOffset
    0x08 /* GATT_INSUFFICIENT_AUTHORIZATION */ -> OperationStatus.InsufficientAuthorization
    0x09 /* GATT_PREPARE_Q_FULL */ -> OperationStatus.PrepareQueueFull
    0x0A /* GATT_NOT_FOUND */ -> OperationStatus.AttributeNotFound
    0x0B /* GATT_NOT_LONG */ -> OperationStatus.AttributeNotLong
    0x0C /* GATT_INSUF_KEY_SIZE */ -> OperationStatus.EncryptionKeyTooShort
    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> OperationStatus.InvalidAttributeLength
    0x0E /* GATT_ERR_UNLIKELY */ -> OperationStatus.UnlikelyError
    BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> OperationStatus.InsufficientEncryption
    0x11 /* GATT_INSUF_RESOURCE */ -> OperationStatus.InsufficientResources
    0x13 /* GATT_VALUE_NOT_ALLOWED */ -> OperationStatus.ValueNotAllowed
    0x85 /* GATT_ERROR */ -> OperationStatus.GattError
    BluetoothGatt.GATT_CONNECTION_CONGESTED -> OperationStatus.ConnectionCongested
    0x93 /* GATT_CONNECTION_TIMEOUT */ -> OperationStatus.ConnectionTimeout
    in 0x80..0x9F -> OperationStatus.ApplicationError(this)
    0xFC -> OperationStatus.WriteRequestRejected
    0xFD -> OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured
    0xFE -> OperationStatus.ProcedureAlreadyInProgress
    0xFF -> OperationStatus.OutOfRange
    in 0xE0..0xFF -> OperationStatus.ProfileError(this)
    else -> OperationStatus.UnknownError(this)
}

/**
 * Converts the connection state and the status reported by `onConnectionStateChange`.
 *
 * @param status The status reported with the new state.
 * @param userInitiated Whether the disconnection was requested by the app.
 */
internal fun Int.toConnectionState(status: Int, userInitiated: Boolean): ConnectionState = when (this) {
    BluetoothGatt.STATE_CONNECTED -> ConnectionState.Connected
    BluetoothGatt.STATE_CONNECTING -> ConnectionState.Connecting
    BluetoothGatt.STATE_DISCONNECTING -> ConnectionState.Disconnecting
    else -> ConnectionState.Disconnected(status.toDisconnectionReason(userInitiated))
}

// Source: https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Bluetooth/system/stack/include/hci_error_code.h
internal fun Int.toDisconnectionReason(userInitiated: Boolean): ConnectionState.Disconnected.Reason = when {
    userInitiated && (this == BluetoothGatt.GATT_SUCCESS || this == 0x16) ->
        ConnectionState.Disconnected.Reason.Success
    // The GATT server is not told why the link was terminated when the status is 0. Most often
    // it is the client that terminates the connection. A link loss is reported by Android
    // as a timeout, when it's reported correctly.
    this == BluetoothGatt.GATT_SUCCESS -> ConnectionState.Disconnected.Reason.TerminatePeerUser
    this == 0x05 /* HCI_ERR_AUTH_FAILURE */ -> ConnectionState.Disconnected.Reason.InsufficientAuthentication
    this == 0x08 /* HCI_ERR_CONNECTION_TOUT */ ||
    this == 0x22 /* HCI_ERR_LMP_RESPONSE_TIMEOUT */ ||
    this == 0x93 /* GATT_CONNECTION_TIMEOUT */ -> ConnectionState.Disconnected.Reason.LinkLoss
    this == 0x13 /* HCI_ERR_PEER_USER */ ||
    this == 0x15 /* HCI_ERR_REMOTE_POWER_OFF */ -> ConnectionState.Disconnected.Reason.TerminatePeerUser
    this == 0x3B /* HCI_ERR_UNACCEPT_CONN_INTERVAL */ -> ConnectionState.Disconnected.Reason.UnacceptableConnectionParameters
    this == 0x16 /* HCI_ERR_CONN_CAUSE_LOCAL_HOST */ -> ConnectionState.Disconnected.Reason.TerminateLocalHost
    else -> ConnectionState.Disconnected.Reason.Unknown(this)
}

/** Converts the PHY reported by `onPhyUpdate` and `onPhyRead`, `BluetoothDevice.PHY_LE_*`. */
internal fun Int.toPhy(): Phy = when (this) {
    2 /* BluetoothDevice.PHY_LE_2M */ -> Phy.PHY_LE_2M
    3 /* BluetoothDevice.PHY_LE_CODED */ -> Phy.PHY_LE_CODED
    5 /* BluetoothDevice.PHY_LE_HDT */ -> Phy.PHY_LE_HDT
    else -> Phy.PHY_LE_1M
}

/**
 * Converts the PHY to the mask used when requesting PHY change, `BluetoothDevice.PHY_LE_*_MASK`.
 *
 * Note, that the masks differ from the values reported in callbacks.
 */
internal fun Phy.toPhyMask(): Int = when (this) {
    Phy.PHY_LE_1M -> 1 /* BluetoothDevice.PHY_LE_1M_MASK */
    Phy.PHY_LE_2M -> 2 /* BluetoothDevice.PHY_LE_2M_MASK */
    Phy.PHY_LE_CODED -> 4 /* BluetoothDevice.PHY_LE_CODED_MASK */
    Phy.PHY_LE_HDT -> 16 /* BluetoothDevice.PHY_LE_HDT_MASK */
}

/** Converts the PHY option to `BluetoothDevice.PHY_OPTION_*`. */
internal fun PhyOption.toOption(): Int = when (this) {
    PhyOption.NO_PREFERRED -> 0 /* BluetoothDevice.PHY_OPTION_NO_PREFERRED */
    PhyOption.S2 -> 1 /* BluetoothDevice.PHY_OPTION_S2 */
    PhyOption.S8 -> 2 /* BluetoothDevice.PHY_OPTION_S8 */
}
