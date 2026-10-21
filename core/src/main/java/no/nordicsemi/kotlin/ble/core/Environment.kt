/*
 * Copyright (c) 2025, Nordic Semiconductor
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
 * A base interface for local environment.
 *
 * The environment is used to capabilities of the local device, native or mock.
 *
 * @property deviceName The name of the local device.
 * @property isBluetoothSupported Whether Bluetooth is supported.
 * @property isBluetoothEnabled Whether Bluetooth is enabled.
 * @property isScanningOnLeCodedPhySupported Whether the device can scan for Bluetooth LE devices
 * advertising on LE Coded PHY as Primary PHY.
 * @property reportsConnectionParameters Whether the device reports connection parameters.
 * @property automaticallyRequestsMtu Whether the system requests the maximum MTU itself when
 * the connection is established (e.g. iOS). If `true`, the library does not request the MTU before
 * it reports the peripheral as connected.
 * @property allowsBondRemoval Whether the device provides API to remove bond information from local host.
 * @property reportsConnectableFlag Whether the device provides information whether a scanned
 * Bluetooth LE advertising packet is connectable.
 * @property isSystem Whether the app is running in a privileged environment and has access to
 * restricted features.
 * @property repeatsRequestsAfterBonding Whether the system repeats a request to a protected
 * attribute after it bonded with the peripheral, instead of failing it with Insufficient
 * Authentication (when `false`, the client is expected to repeat the request).
 * @property rejectsAutoConnectToUnbondedPrivateAddress Whether the system reports an error when
 * asked to connect in the background (auto connect) to a device that is not bonded and uses
 * a Resolvable Private Address. When `false`, the request is accepted, but the connection
 * is never established.
 */
interface Environment {
    val deviceName: String
    val isBluetoothSupported: Boolean
    val isBluetoothEnabled: Boolean
    val isScanningOnLeCodedPhySupported: Boolean
    val reportsConnectionParameters: Boolean
    val automaticallyRequestsMtu: Boolean
    val allowsBondRemoval: Boolean
    val reportsConnectableFlag: Boolean
    val isSystem: Boolean
    val repeatsRequestsAfterBonding: Boolean
    val rejectsAutoConnectToUnbondedPrivateAddress: Boolean
}