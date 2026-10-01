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

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import no.nordicsemi.kotlin.ble.client.android.AdvertisingData
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.android.exception.ScanningFailedToStartException
import no.nordicsemi.kotlin.ble.core.BondState
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.PeripheralType
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.WriteType
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import android.bluetooth.le.ScanResult as NativeScanResult

internal fun Int.toBondState(): BondState = when (this) {
    BluetoothDevice.BOND_BONDED -> BondState.BONDED
    BluetoothDevice.BOND_BONDING -> BondState.BONDING
    else -> BondState.NONE
}

internal fun Int.toConnectionState(status: Int, reason: ConnectionState.Disconnected.Reason?): ConnectionState = when (this) {
    BluetoothGatt.STATE_CONNECTED -> ConnectionState.Connected
    BluetoothGatt.STATE_CONNECTING -> ConnectionState.Connecting
    BluetoothGatt.STATE_DISCONNECTED -> ConnectionState.Disconnected(reason ?: status.toDisconnectionReason())
    BluetoothGatt.STATE_DISCONNECTING -> ConnectionState.Disconnecting
    else -> ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.Unknown(this))
}

// Source: https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Bluetooth/system/stack/include/gatt_api.h
// Source: https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Bluetooth/system/stack/include/hci_error_code.h
private fun Int.toDisconnectionReason(): ConnectionState.Disconnected.Reason = when (this) {
    BluetoothGatt.GATT_SUCCESS -> ConnectionState.Disconnected.Reason.Success
    0x05 /* = 5, HCI_ERR_AUTH_FAILURE (GATT_INSUFFICIENT_AUTHENTICATION) */ -> ConnectionState.Disconnected.Reason.InsufficientAuthentication
    0x08 /* = 8, HCI_ERR_CONNECTION_TOUT (GATT_CONN_TIMEOUT) */,
    0x22 /* = 34, HCI_ERR_LMP_RESPONSE_TIMEOUT (GATT_CONN_LMP_TIMEOUT) */,
    0x93 /* = 147, GATT_CONNECTION_TIMEOUT, BluetoothGatt.GATT_CONNECTION_TIMEOUT, API 35+ */ -> ConnectionState.Disconnected.Reason.LinkLoss
    0x13 /* = 19, HCI_ERR_PEER_USER (GATT_CONN_TERMINATE_PEER_USER) */,
    0x15 /* = 21, HCI_ERR_REMOTE_POWER_OFF */ -> ConnectionState.Disconnected.Reason.TerminatePeerUser
    0x16 /* = 22, HCI_ERR_CONN_CAUSE_LOCAL_HOST (GATT_CONN_TERMINATE_LOCAL_HOST) */ -> ConnectionState.Disconnected.Reason.TerminateLocalHost
 /* 0x101 = 257, BTA_GATT_CONN_NONE, BluetoothGatt.GATT_FAILURE -> Unknown */
    else -> ConnectionState.Disconnected.Reason.Unknown(this)
}

internal fun Int.toPeripheralType(): PeripheralType = when (this) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> PeripheralType.CLASSIC
    BluetoothDevice.DEVICE_TYPE_DUAL -> PeripheralType.DUAL
    BluetoothDevice.DEVICE_TYPE_LE -> PeripheralType.LE
    else -> PeripheralType.UNKNOWN
}

internal fun Int.errorCodeToReason(): ScanningFailedToStartException.Reason = when (this) {
    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> ScanningFailedToStartException.Reason.AlreadyStarted
    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> ScanningFailedToStartException.Reason.ApplicationRegistrationFailed
    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> ScanningFailedToStartException.Reason.FeatureUnsupported
    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> ScanningFailedToStartException.Reason.InternalError
    ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> ScanningFailedToStartException.Reason.OutOfResources
    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> ScanningFailedToStartException.Reason.ScanningTooFrequently
    else -> ScanningFailedToStartException.Reason.Unknown(this)
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal fun NativeScanResult.toScanResult(peripheral: (device: BluetoothDevice, name: String?) -> Peripheral): ScanResult? {
    val scanRecord = scanRecord ?: return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val deviceName = try { device.name } catch (_: SecurityException) { null }
        ScanResult(
            peripheral = peripheral(device, scanRecord.deviceName ?: deviceName),
            advertisingData = AdvertisingData(raw = scanRecord.bytes),
            isConnectable = isConnectable,
            rssi = rssi,
            txPowerLevel =
                if (txPower != NativeScanResult.TX_POWER_NOT_PRESENT)
                    txPower
                else
                    if (scanRecord.txPowerLevel != Int.MIN_VALUE)
                        scanRecord.txPowerLevel
                    else
                        null,
            primaryPhy = primaryPhy.toPrimaryPhy(),
            secondaryPhy = secondaryPhy.toPhy(),
            timestamp = timestampNanos.elapsedRealtimeNanosToInstant()
        )
    } else {
        ScanResult(
            peripheral = peripheral(device, scanRecord.deviceName ?: device.name),
            advertisingData = AdvertisingData(raw = scanRecord.bytes),
            isConnectable = null, // Unknown
            rssi = rssi,
            txPowerLevel =
                if (scanRecord.txPowerLevel != Int.MIN_VALUE)
                    scanRecord.txPowerLevel
                else
                    null,
            primaryPhy = PrimaryPhy.PHY_LE_1M,
            secondaryPhy = null, // Not used
            timestamp = timestampNanos.elapsedRealtimeNanosToInstant()
        )
    }
}

internal fun ByteArray.toScanResult(rssi: Int, peripheral: (name: String?) -> Peripheral): ScanResult {
    val data = AdvertisingData(raw = this)
    return ScanResult(
        peripheral = peripheral(data.name),
        isConnectable = null, // Unknown
        advertisingData = data,
        rssi = rssi,
        txPowerLevel = null, // Unknown
        primaryPhy = PrimaryPhy.PHY_LE_1M,
        secondaryPhy = null, // Not used
        timestamp = Clock.System.now()
    )
}

/**
 * Converts the time since boot, as reported by [NativeScanResult.getTimestampNanos],
 * to wall-clock time.
 *
 * The time since boot is not affected by changes of the system time, so the age of the scan
 * result is calculated first and subtracted from the current time.
 */
private fun Long.elapsedRealtimeNanosToInstant(): Instant {
    val age = (SystemClock.elapsedRealtimeNanos() - this).nanoseconds
    return Clock.System.now() - age
}

private fun Int.toPrimaryPhy(): PrimaryPhy = when (this) {
    3 /* BluetoothDevice.PHY_LE_CODED */ -> PrimaryPhy.PHY_LE_CODED
    else -> PrimaryPhy.PHY_LE_1M
}

internal fun Int.toPhy(): Phy = when (this) {
    2 /* BluetoothDevice.PHY_LE_2M */ -> Phy.PHY_LE_2M
    3 /* BluetoothDevice.PHY_LE_CODED */ -> Phy.PHY_LE_CODED
    5 /* BluetoothDevice.PHY_LE_HDT */ -> Phy.PHY_LE_HDT
    else -> Phy.PHY_LE_1M
}

internal fun Phy.toPhy(): Int = when (this) {
    Phy.PHY_LE_2M -> 2 /* BluetoothDevice.PHY_LE_2M */
    Phy.PHY_LE_CODED -> 3 /* BluetoothDevice.PHY_LE_CODED */
    Phy.PHY_LE_HDT -> 5 /* BluetoothDevice.PHY_LE_HDT */
    else -> 1 /* BluetoothDevice.PHY_LE_1M */
}

internal fun PhyOption.toOption(): Int = when (this) {
    PhyOption.NO_PREFERRED -> 0 /* BluetoothDevice.PHY_OPTION_NO_PREFERRED */
    PhyOption.S2 -> 1 /* BluetoothDevice.PHY_OPTION_S2 */
    PhyOption.S8 -> 2 /* BluetoothDevice.PHY_OPTION_S8 */
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal fun ConnectionPriority.toPriority() = when (this) {
    ConnectionPriority.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
    ConnectionPriority.HIGH -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
    ConnectionPriority.LOW_POWER -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
    ConnectionPriority.DIGITAL_CAR_KEY ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            BluetoothGatt.CONNECTION_PRIORITY_DCK
        else
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED
}

// Source: https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Bluetooth/system/stack/include/gatt_api.h
internal fun Int.toOperationStatus(): OperationStatus = when (this) {
 /* 0x00 = 0 */ BluetoothGatt.GATT_SUCCESS -> OperationStatus.Success
    0x01 /* = 1, GATT_INVALID_HANDLE */ -> OperationStatus.InvalidHandle
 /* 0x02 = 2, GATT_READ_NOT_PERMIT */ BluetoothGatt.GATT_READ_NOT_PERMITTED -> OperationStatus.ReadNotPermitted
 /* 0x03 = 3, GATT_WRITE_NOT_PERMIT */ BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> OperationStatus.WriteNotPermitted
    0x04 /* = 4, GATT_INVALID_PDU */ -> OperationStatus.InvalidPdu
 /* 0x05 = 5, GATT_INSUF_AUTHENTICATION */ BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> OperationStatus.InsufficientAuthentication
 /* 0x06 = 6, GATT_REQ_NOT_SUPPORTED */ BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> OperationStatus.RequestNotSupported
 /* 0x07 = 7, GATT_INVALID_OFFSET */ BluetoothGatt.GATT_INVALID_OFFSET -> OperationStatus.InvalidOffset
 /* 0x08 = 8, GATT_INSUF_AUTHORIZATION */ BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION -> OperationStatus.InsufficientAuthorization
    0x09 /* = 9,  GATT_PREPARE_Q_FULL */ -> OperationStatus.PrepareQueueFull
    0x0A /* = 10, GATT_NOT_FOUND */ -> OperationStatus.AttributeNotFound
    0x0B /* = 11, GATT_NOT_LONG */ -> OperationStatus.AttributeNotLong
    0x0C /* = 12, GATT_INSUF_KEY_SIZE */ -> OperationStatus.EncryptionKeyTooShort
 /* 0x0D = 13, GATT_INVALID_ATTR_LEN */ BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> OperationStatus.InvalidAttributeLength
    0x0E /* = 14, GATT_ERR_UNLIKELY */ -> OperationStatus.UnlikelyError
 /* 0x0F = 15, GATT_INSUF_ENCRYPTION */ BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> OperationStatus.InsufficientEncryption
 /* 0x10 = 16, GATT_UNSUPPORT_GRP_TYPE -> UnknownError */
    0x11 /* = 17, GATT_INSUF_RESOURCE */ -> OperationStatus.InsufficientResources
 /* 0x12 = 18, GATT_DATABASE_OUT_OF_SYNC -> UnknownError */
    0x13 /* = 19, GATT_VALUE_NOT_ALLOWED */ -> OperationStatus.ValueNotAllowed

    // Codes 0x80-0x9F are Application Errors, some of which are actually used:
 /* 0x80 = 128, GATT_NO_RESOURCES
    0x81 = 129, GATT_INTERNAL_ERROR
    0x82 = 130, GATT_WRONG_STATE
    0x83 = 131, GATT_DB_FULL
    0x84 = 132, GATT_BUSY */
    0x85 /* = 133, GATT_ERROR */ -> OperationStatus.GattError
 /* 0x86 = 134, GATT_CMD_STARTED
    0x87 = 135, GATT_ILLEGAL_PARAMETER
    0x88 = 136, GATT_PENDING
    0x89 = 137, GATT_AUTH_FAIL
    0x8A = 138, ????????????,
    0x8B = 139, GATT_INVALID_CFG
    0x8C = 140, GATT_SERVICE_STARTED
    0x8D = 141, GATT_ENCRYPED_NO_MITM
    0x8E = 142, GATT_NOT_ENCRYPTED
    0x8F = 143, GATT_CONGESTED */ BluetoothGatt.GATT_CONNECTION_CONGESTED -> OperationStatus.ConnectionCongested
 /* 0x90 = 144, GATT_DUP_REG
    0x91 = 145, GATT_ALREADY_OPEN
    0x92 = 146, GATT_CANCEL */
    0x93 /* = 147, GATT_CONNECTION_TIMEOUT (BluetoothGatt.GATT_CONNECTION_TIMEOUT, API 35+) */ -> OperationStatus.ConnectionTimeout
    in 0x80..0x9F -> OperationStatus.ApplicationError(this)

    // Codes 0xE0-0xFF are Profile Errors:
    0xFC -> OperationStatus.WriteRequestRejected
    0xFD -> OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured
    0xFE -> OperationStatus.ProcedureAlreadyInProgress
    0xFF -> OperationStatus.OutOfRange
    in 0xE0..0xFF -> OperationStatus.ProfileError(this)

    // Report other codes as UnknownError:
    else -> OperationStatus.UnknownError(this)
}

internal fun WriteType.toInt() = when (this) {
    WriteType.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    WriteType.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    WriteType.SIGNED -> BluetoothGattCharacteristic.WRITE_TYPE_SIGNED
}

internal val UUID.toKotlinUuid: Uuid
    get() = Uuid.fromLongs(mostSignificantBits, leastSignificantBits)