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

/**
 * At the bottom of the Bluetooth LE stack is the physical layer (PHY).
 * PHY refers to the physical layer radio specifications that govern the operation of the
 * Bluetooth LE radio. This layer defines different modulation and coding schemes adopted by
 * Bluetooth LE radio transmitters that affect things like the throughput of the radio.
 * This in turn changes the battery consumption of the device or the range of the connection.
 *
 * ### LE 1M PHY
 * LE 1M PHY, or 1 Megabit PHY, is the classic PHY supported by all Bluetooth LE devices.
 * As its name implies, 1M PHY uses 1 megabit per second.
 *
 * When initiating a connection between two Bluetooth LE devices, this is the mode that will be used,
 * to begin with. Then the peers can request another mode if both devices support it.
 *
 * ### LE 2M PHY
 * 2 Megabit PHY is a new mode introduced in Bluetooth v5.0. As the name implies, it effectively
 * doubles the data rate to 2 megabit per second, or 2 Mbps. Since the data is transmitted at a
 * higher data rate (faster), the radio needs to stay on for less time, decreasing battery usage.
 * The downside is the decrease in receiver sensitivity which translates to less communication range.
 *
 * ### LE Coded PHY
 * While LE 2M PHY exists for users willing to sacrifice range for increased data rate, coded PHY
 * was introduced to serve applications where users can achieve longer communication range by
 * sacrificing data rate. Coded PHY uses coding schemes to correct packet errors more effectively,
 * which also means that a single bit is represented by more than 1 symbol. Coded PHY uses 2 modes,
 * S=2 and S=8. In the S=2 mode, 2 symbols represent 1 bit, therefore the data rate is 500 kbps.
 * While in the S=8 mode, 8 symbols are used to represent a bit and the data rate becomes 125 kbps.
 *
 * ### LE HDT PHY
 * The HDT PHY (Higher Data Throughput Physical Layer) in Bluetooth Low Energy increases maximum
 * data rates from 2 Mbps up to 7.5 Mbps, delivering nearly 4x higher capacity through advanced
 * QAM/PSK modulations and forward error correction. Comparing to LE 2M PHY, even with the slowest
 * speed of 2 Mbps it delivers 5 dB higher link budget, resulting in 70% increased range.
 */
enum class Phy {

    /**
     * Bluetooth LE 1M PHY.
     *
     * Used to refer to LE 1M Physical Channel for advertising, scanning or connection.
     *
     * This is the default PHY used for connection, used in Bluetooth 4.0 and later.
     */
    PHY_LE_1M,

    /**
     * Bluetooth LE 2M PHY.
     *
     * Used to refer to LE 2M Physical Channel for advertising, scanning or connection.
     *
     * This PHY is available in Bluetooth 5.0 and later and can be used to send data at
     * 2 Mbps, which is twice the speed of the 1M PHY.
     */
    PHY_LE_2M,

    /**
     * Bluetooth LE Coded PHY.
     *
     * Used to refer to LE Coded Physical Channel for advertising, scanning or connection.
     *
     * This PHY is available in Bluetooth 5.0 and later and can be used to send data at
     * 500 kbps or 125 kbps, depending on the coding scheme used.
     * The range of the connection can be increased depending on the coding scheme.
     */
    PHY_LE_CODED,

    /**
     * Bluetooth LE HDT (High Data Throughput) PHY.
     *
     * All Bluetooth HDT bit-rates (2, 3, 4, 6, and 7.5 Mbps) operate at a 2 megasymbol-per-second
     * signaling rate, leveraging these complex modulation techniques to carry extra information
     * per symbol.
     */
    PHY_LE_HDT;

    /**
     * Returns the nominal bit rate in bits per second for this PHY, in bits per second.
     */
    fun rate(): Int = when (this) {
        PHY_LE_1M -> 1_000_000
        PHY_LE_2M -> 2_000_000
        PHY_LE_CODED -> 125_000 // Lowest rate for coded PHY
        PHY_LE_HDT -> 2_000_000 // Lowest rate for HDT PHY
    }

    override fun toString(): String = when (this) {
        PHY_LE_1M -> "LE 1M"
        PHY_LE_2M -> "LE 2M"
        PHY_LE_CODED -> "LE Coded"
        PHY_LE_HDT -> "LE HDT"
    }
}

infix fun Phy.and(phy: Phy): List<Phy> = listOf(this, phy)

infix fun List<Phy>.and(phy: Phy): List<Phy> = this + phy