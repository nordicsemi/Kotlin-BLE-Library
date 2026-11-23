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

package no.nordicsemi.kotlin.ble.client.android.internal

import android.bluetooth.BluetoothDevice
import no.nordicsemi.kotlin.ble.core.Phy
import kotlin.test.Test
import kotlin.test.assertEquals

class MapperTest {

    @Test
    fun `PHY is mapped to a mask for setPreferredPhy`() {
        // BluetoothGatt.setPreferredPhy() expects a bitwise OR of PHY_LE_*_MASK values,
        // which are different from PHY_LE_* values, e.g. PHY_LE_CODED = 3, but the mask is 4.
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK, Phy.PHY_LE_1M.toPhyMask())
        assertEquals(BluetoothDevice.PHY_LE_2M_MASK, Phy.PHY_LE_2M.toPhyMask())
        assertEquals(BluetoothDevice.PHY_LE_CODED_MASK, Phy.PHY_LE_CODED.toPhyMask())
        assertEquals(BluetoothDevice.PHY_LE_HDT_MASK, Phy.PHY_LE_HDT.toPhyMask())
    }

    @Test
    fun `PHY masks are distinct single bits`() {
        val masks = Phy.entries.map { it.toPhyMask() }
        assertEquals(masks.size, masks.toSet().size)
        masks.forEach { assertEquals(1, Integer.bitCount(it)) }
    }

    @Test
    fun `PHY reported by callbacks is mapped from value, not mask`() {
        assertEquals(Phy.PHY_LE_1M, BluetoothDevice.PHY_LE_1M.toPhy())
        assertEquals(Phy.PHY_LE_2M, BluetoothDevice.PHY_LE_2M.toPhy())
        assertEquals(Phy.PHY_LE_CODED, BluetoothDevice.PHY_LE_CODED.toPhy())
        assertEquals(Phy.PHY_LE_HDT, BluetoothDevice.PHY_LE_HDT.toPhy())
    }
}
