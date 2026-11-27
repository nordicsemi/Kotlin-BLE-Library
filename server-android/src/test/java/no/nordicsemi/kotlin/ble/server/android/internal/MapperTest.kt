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

import android.bluetooth.BluetoothGattCharacteristic
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.core.and
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class MapperTest {

    @Test
    fun `uuid is converted to java uuid`() {
        val uuid = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        assertEquals(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), uuid.toJavaUuid())
    }

    @Test
    fun `empty permissions`() {
        assertEquals(0, emptySet<Permission>().toNativePermissions())
    }

    @Test
    fun `each permission has its own bit`() {
        val masks = Permission.entries.map { setOf(it).toNativePermissions() }
        assertEquals(Permission.entries.size, masks.toSet().size)
        masks.forEach { assert(it != 0) }
    }

    @Test
    fun `permissions are mapped to native values`() {
        assertEquals(BluetoothGattCharacteristic.PERMISSION_READ, setOf(Permission.READ).toNativePermissions())
        assertEquals(
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
            (Permission.READ and Permission.WRITE).toNativePermissions(),
        )
        assertEquals(
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM or
                    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            (Permission.READ_ENCRYPTED_MITM and Permission.WRITE_ENCRYPTED).toNativePermissions(),
        )
        assertEquals(
            BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED or
                    BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM,
            (Permission.WRITE_SIGNED and Permission.WRITE_SIGNED_MITM).toNativePermissions(),
        )
    }

    @Test
    fun `each property has its own bit`() {
        val masks = CharacteristicProperty.entries.map { setOf(it).toNativeProperties() }
        assertEquals(CharacteristicProperty.entries.size, masks.toSet().size)
    }

    @Test
    fun `properties are mapped to native values`() {
        assertEquals(0, emptySet<CharacteristicProperty>().toNativeProperties())
        assertEquals(
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            (CharacteristicProperty.READ and CharacteristicProperty.NOTIFY).toNativeProperties(),
        )
        assertEquals(
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
            setOf(
                CharacteristicProperty.WRITE,
                CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
                CharacteristicProperty.INDICATE,
            ).toNativeProperties(),
        )
        assertEquals(
            BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS,
            setOf(CharacteristicProperty.EXTENDED_PROPERTIES).toNativeProperties(),
        )
    }

    @Test
    fun `status codes are sent as is`() {
        assertEquals(0, OperationStatus.Success.toNativeStatus())
        assertEquals(0x02, OperationStatus.ReadNotPermitted.toNativeStatus())
        assertEquals(0x80, OperationStatus.ApplicationError(0x80).toNativeStatus())
        assertEquals(0xFF, OperationStatus.OutOfRange.toNativeStatus())
    }

    @Test
    fun `non-bluetooth statuses are sent as unlikely error`() {
        assertEquals(0x0E, OperationStatus.RequestFailed.toNativeStatus())
        assertEquals(0x0E, OperationStatus.Busy.toNativeStatus())
        assertEquals(0x0E, OperationStatus.UnknownError(0x1234).toNativeStatus())
    }

    @Test
    fun `native status is mapped to operation status`() {
        assertEquals(OperationStatus.Success, 0.toOperationStatus())
        assertEquals(OperationStatus.InvalidHandle, 1.toOperationStatus())
        assertEquals(OperationStatus.InsufficientEncryption, 0x0F.toOperationStatus())
        assertEquals(OperationStatus.GattError, 133.toOperationStatus())
        assertEquals(OperationStatus.ConnectionCongested, 143.toOperationStatus())
        assertEquals(OperationStatus.ConnectionTimeout, 147.toOperationStatus())
        assertEquals(0x81, assertIs<OperationStatus.ApplicationError>(0x81.toOperationStatus()).code)
        assertEquals(0xE5, assertIs<OperationStatus.ProfileError>(0xE5.toOperationStatus()).code)
        assertEquals(OperationStatus.UnknownError(257), 257.toOperationStatus())
    }

    @Test
    fun `status survives round trip`() {
        listOf(
            OperationStatus.Success,
            OperationStatus.InvalidOffset,
            OperationStatus.InvalidAttributeLength,
            OperationStatus.ValueNotAllowed,
            OperationStatus.WriteRequestRejected,
        ).forEach {
            assertEquals(it, it.toNativeStatus().toOperationStatus())
        }
    }

    @Test
    fun `disconnection reasons`() {
        assertEquals(Reason.Success, 0.toDisconnectionReason(userInitiated = true))
        assertEquals(Reason.Success, 0x16.toDisconnectionReason(userInitiated = true))
        assertEquals(Reason.TerminatePeerUser, 0.toDisconnectionReason(userInitiated = false))
        assertEquals(Reason.TerminatePeerUser, 0x13.toDisconnectionReason(userInitiated = false))
        assertEquals(Reason.LinkLoss, 0x08.toDisconnectionReason(userInitiated = false))
        assertEquals(Reason.LinkLoss, 0x22.toDisconnectionReason(userInitiated = false))
        assertEquals(Reason.TerminateLocalHost, 0x16.toDisconnectionReason(userInitiated = false))
        assertEquals(Reason.InsufficientAuthentication, 0x05.toDisconnectionReason(userInitiated = false))
        assertEquals(Reason.Unknown(133), 133.toDisconnectionReason(userInitiated = false))
    }

    @Test
    fun `connection state is mapped`() {
        assertEquals(ConnectionState.Connected, 2.toConnectionState(0, false))
        assertEquals(ConnectionState.Connecting, 1.toConnectionState(0, false))
        assertEquals(ConnectionState.Disconnecting, 3.toConnectionState(0, false))
        assertEquals(
            ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.LinkLoss),
            0.toConnectionState(8, false),
        )
    }

    @Test
    fun `phy callback values`() {
        assertEquals(Phy.PHY_LE_1M, 1.toPhy())
        assertEquals(Phy.PHY_LE_2M, 2.toPhy())
        assertEquals(Phy.PHY_LE_CODED, 3.toPhy())
    }

    @Test
    fun `phy masks differ from phy values`() {
        assertEquals(1, Phy.PHY_LE_1M.toPhyMask())
        assertEquals(2, Phy.PHY_LE_2M.toPhyMask())
        assertEquals(4, Phy.PHY_LE_CODED.toPhyMask())
    }

    @Test
    fun `phy options`() {
        assertEquals(0, PhyOption.NO_PREFERRED.toOption())
        assertEquals(1, PhyOption.S2.toOption())
        assertEquals(2, PhyOption.S8.toOption())
    }
}
