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
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.android.BondState
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority
import no.nordicsemi.kotlin.ble.client.android.EncryptionState
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.core.ATT_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.PeripheralType
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment
import no.nordicsemi.kotlin.log.Log
import org.jetbrains.annotations.Range
import java.lang.reflect.InvocationTargetException
import kotlin.uuid.Uuid

/**
 * A native implementation of [Peripheral.Executor] for Android.
 *
 * This class uses the Android Bluetooth API to connect to the device.
 *
 * @param environment The native Android environment.
 * @param bluetoothDevice The Bluetooth device to connect to.
 * @param name The name of the device from the advertisement data.
 */
internal class NativeExecutor(
    override val environment: NativeAndroidEnvironment,
    private val bluetoothDevice: BluetoothDevice,
    name: String?,
): Peripheral.Executor {
    override var logger: Log.Sink<Layer>? = Log.Sink.Null
        set(value) {
            field = value
            gattCallback.logger = value
        }
    override val identifier: String = bluetoothDevice.address
    override val type: PeripheralType = try {
        // This may throw Security Exception if Bluetooth Connect permission isn't granted.
        bluetoothDevice.type.toPeripheralType()
    } catch (_: SecurityException) {
        PeripheralType.UNKNOWN
    }
    override val name: String? = try {
        // This may throw Security Exception if Bluetooth Connect permission isn't granted.
        bluetoothDevice.name ?: name
    } catch (_: SecurityException) {
        name
    }
    override val initialState: ConnectionState = ConnectionState.Disconnected()
    override val initialServices: List<RemoteService> = emptyList()

    /**
     * The [BluetoothGatt] object used to communicate with the physical device.
     *
     * This is set to `null` when the connection is closed. If the peripheral was connected
     * using [auto connect][CentralManager.ConnectionOptions.AutoConnect], the [gatt] object
     * is set until [disconnect] is called.
     */
    private var gatt: BluetoothGatt? = null

    /**
     * The [NativeGattCallback] receives callbacks from the [BluetoothGatt] and emits them
     * as [GattEvent] to [events].
     */
    private val gattCallback: NativeGattCallback = NativeGattCallback(identifier)
        .also {
            it.logger = logger
            it.onConnected = ::readEncryptionState
        }

    /** The current bond state. */
    override val bondState: StateFlow<BondState>
        field = MutableStateFlow(bluetoothDevice.bondState.toBondState())

    /**
     * This method is called when the bond state of the device changes.
     */
    internal fun onBondStateChanged(state: BondState) {
        if (state is BondState.None) {
            gattCallback.onBondLost()
        }
        bondState.value = state
    }

    /**
     * The current state of the encryption of the link.
     *
     * It is reported from Android 16, when the broadcasts became public.
     */
    override val encryptionState: StateFlow<EncryptionState>
        field = MutableStateFlow<EncryptionState>(EncryptionState.Unknown)

    /**
     * This method is called when the state of the encryption of the link changes.
     */
    internal fun onEncryptionStateChanged(state: EncryptionState) {
        encryptionState.value = state
    }

    /**
     * Reads the current encryption of the link, when the device connects.
     *
     * The broadcasts are sent only when the encryption changes. If the device reconnects while
     * the previous link is still up (the system keeps it for a few seconds after the GATT was
     * closed), the link is already encrypted, there is no broadcast, and the state would stay
     * unknown, or stale. The status is available since Android 16 QPR2 (API 36.1).
     */
    @SuppressLint("NewApi", "MissingPermission")
    private fun readEncryptionState() {
        if (!isBaklava1OrNewer) return
        val state = try {
            val status = bluetoothDevice.getEncryptionStatus(BluetoothDevice.TRANSPORT_LE)
            when (val algorithm = status?.algorithm) {
                // The status is not available, e.g. the device is not connected.
                null -> EncryptionState.Unknown
                BluetoothDevice.ENCRYPTION_ALGORITHM_NONE -> EncryptionState.NotEncrypted()
                else -> EncryptionState.Encrypted(
                    algorithm = algorithm.toEncryptionAlgorithm(),
                    keySize = status.keySize.takeIf { it > 0 },
                )
            }
        } catch (_: SecurityException) {
            EncryptionState.Unknown
        }
        encryptionState.value = state
    }

    // Implementation

    override val events: SharedFlow<GattEvent>
        get() = gattCallback.events

    override val isClosed: Boolean
        get() = gatt == null

    override var isReliableWriteEnabled: Boolean = false

    override suspend fun connect(
        autoConnect: Boolean,
        autoMtu: Boolean,
        opportunistic: Boolean,
    ) {
        // On retry the previous GATT object may not be null and must be closed.
        gatt?.let {
            logger?.d(Layer.GAP) { "gatt.close()" }
            it.close()
        }
        logger?.d(Layer.GAP) { "device.connectGatt(autoConnect=$autoConnect)" }
        // Note: Instead of relying on BluetoothGattConnectionSettings.setAutomaticMtuEnabled
        //       the MTU is requested explicitly after connection. See client/Peripheral -> connect().
        gatt = bluetoothDevice.connect(environment.applicationContext, autoConnect, autoMtu, opportunistic, gattCallback)
    }

    override suspend fun discoverServices(uuids: List<Uuid>): Boolean {
        gatt?.let { gatt ->
            logger?.d(Layer.GATT) { "gatt.discoverServices()" }
            val result = gatt.discoverServices()
            if (!result) {
                throw OperationFailedException(OperationStatus.RequestFailed)
            }
            return true
        }
        return false
    }

    override suspend fun createBond(): Boolean {
        // `createBond` was hidden in Android 4.3, but available using reflection.
        // https://android.googlesource.com/platform/frameworks/base/+/android-4.3_r1/core/java/android/bluetooth/BluetoothDevice.java
        // It was exposed in Android 4.4. KitKat.
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            logger?.d(Layer.SMP) { "device.createBond()" }
            bluetoothDevice.createBond()
        } else {
            try {
                logger?.d(Layer.SMP) { "device.createBond() (hidden)" }
                val method = BluetoothDevice::class.java.getMethod("createBond")
                method.invoke(bluetoothDevice) as Boolean
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                throw OperationFailedException(OperationStatus.RequestNotSupported, e)
            }
        }
        if (!result) {
            throw OperationFailedException(OperationStatus.RequestFailed)
        }
        return true
    }

    override suspend fun removeBond(): Boolean {
        // Note: Since Android 17 bond removal requires BLUETOOTH_PRIVILEGED permission.
        //       This API will try to do this anyway, as the library may be used in apps with this
        //       permission.
        // Change:
        // https://cs.android.com/android/_/android/platform/packages/modules/Bluetooth/+/f4c525723297ede881a618bb325f4b78a9babb1c
        val result = try {
            logger?.d(Layer.SMP) { "device.removeBond() (hidden)" }
            val method = BluetoothDevice::class.java.getMethod("removeBond")
            method.invoke(bluetoothDevice) as Boolean
        } catch (e: SecurityException) {
            throw e
        } catch (e: InvocationTargetException) {
            // Android 17+ throws SecurityException if BLUETOOTH_PRIVILEGED permission is not granted.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && e.targetException is SecurityException) {
                throw e.targetException
            }
            throw OperationFailedException(OperationStatus.RequestNotSupported, e)
        } catch (e: Exception) {
            throw OperationFailedException(OperationStatus.RequestNotSupported, e)
        }
        if (!result) {
            throw OperationFailedException(OperationStatus.RequestFailed)
        }
        // Android terminates the link when the bond information is removed, and the bond state
        // is reported some 100 ms later, after the disconnection, so mark the bond as lost now.
        gattCallback.onBondLost()
        return true
    }

    override suspend fun refreshCache(): Boolean {
        gatt?.let { gatt ->
            val result = try {
                logger?.d(Layer.GATT) { "gatt.refresh() (hidden)" }
                val method = BluetoothGatt::class.java.getMethod("refresh")
                method.invoke(gatt) as Boolean
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                throw OperationFailedException(OperationStatus.RequestNotSupported, e)
            }
            if (!result) {
                throw OperationFailedException(OperationStatus.RequestFailed)
            }
            // There is no callback for services invalidated.
            gattCallback.onServiceChanged(gatt)
            return true
        }
        return false
    }

    override suspend fun requestConnectionPriority(priority: ConnectionPriority): Boolean {
        gatt?.let { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                logger?.d(Layer.LINK) { "gatt.requestConnectionPriority(${priority.toPriority()})" }
                val result = gatt.requestConnectionPriority(priority.toPriority())
                if (!result) {
                    throw OperationFailedException(OperationStatus.RequestFailed)
                }
            }

            // Prior to Android Oreo there is no callback for connection parameters change.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                gattCallback.onConnectionUpdated()
            }
            return true
        }
        return false
    }

    override suspend fun requestMtu(mtu: @Range(from = 23, to = 517) Int): Boolean {
        gatt?.let { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                logger?.d(Layer.GATT) { "gatt.requestMtu($mtu)" }
                val result = gatt.requestMtu(mtu)
                if (!result) {
                    throw OperationFailedException(OperationStatus.RequestFailed)
                }
            } else {
                gattCallback.onMtuChanged(gatt, ATT_MTU_DEFAULT, BluetoothGatt.GATT_SUCCESS)
            }
            return true
        }
        return false
    }

    override suspend fun requestPhy(txPhy: Phy, rxPhy: Phy, phyOptions: PhyOption): Boolean {
        gatt?.let { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                logger?.d(Layer.PHY) { "gatt.setPreferredPhy(tx=${txPhy.toPhy()}, rx=${rxPhy.toPhy()}, options=${phyOptions.toOption()})" }
                gatt.setPreferredPhy(txPhy.toPhy(), rxPhy.toPhy(), phyOptions.toOption())
            } else {
                @SuppressLint("WrongConstant")
                gattCallback.onPhyUpdate(gatt,
                    1 /* BluetoothDevice.PHY_LE_1M */,
                    1 /* BluetoothDevice.PHY_LE_1M */,
                    BluetoothGatt.GATT_SUCCESS)
            }
            return true
        }
        return false
    }

    override suspend fun readPhy(): Boolean {
        gatt?.let { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                logger?.d(Layer.PHY) { "gatt.readPhy()" }
                gatt.readPhy()
            } else {
                @SuppressLint("WrongConstant")
                gattCallback.onPhyRead(gatt,
                    1 /* BluetoothDevice.PHY_LE_1M */,
                    1 /* BluetoothDevice.PHY_LE_1M */,
                    BluetoothGatt.GATT_SUCCESS)
            }
            return true
        }
        return false
    }

    override fun beginReliableWrite(): Boolean {
        gatt?.let { gatt ->
            logger?.d(Layer.GATT) { "gatt.beginReliableWrite()" }
            val result = gatt.beginReliableWrite()
                .also { isReliableWriteEnabled = it }
            if (!result) {
                throw OperationFailedException(OperationStatus.RequestFailed)
            }
            return true
        }
        return false
    }

    override suspend fun executeReliableWrite(): Boolean {
        gatt?.let { gatt ->
            logger?.d(Layer.GATT) { "gatt.executeReliableWrite()" }
            val result = gatt.executeReliableWrite()
            if (!result) {
                throw OperationFailedException(OperationStatus.RequestFailed)
            }
            return true
        }
        return false
    }

    override suspend fun abortReliableWrite(): Boolean {
        gatt?.let { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                logger?.d(Layer.GATT) { "gatt.abortReliableWrite()" }
                gatt.abortReliableWrite()
            } else {
                logger?.d(Layer.GATT) { "gatt.abortReliableWrite(device)" }
                @Suppress("DEPRECATION")
                gatt.abortReliableWrite(gatt.device)
            }
            return true
        }
        return false
    }

    override suspend fun readRssi(): Boolean {
        gatt?.let { gatt ->
            logger?.d(Layer.LINK) { "gatt.readRemoteRssi()" }
            val result = gatt.readRemoteRssi()
            if (!result) {
                throw OperationFailedException(OperationStatus.RequestFailed)
            }
            return true
        }
        return false
    }

    override suspend fun disconnect(reason: Reason): Boolean {
        gatt?.let { gatt ->
            gattCallback.disconnectReason = reason
            logger?.d(Layer.GAP) { "gatt.disconnect()" }
            gatt.disconnect()
            return true
        }
        return false
    }

    override fun close() {
        gatt?.let { gatt ->
            this.gatt = null
            gattCallback.disconnectReason = null
            try {
                logger?.d(Layer.GAP) { "gatt.disconnect()" }
                gatt.disconnect()
            } catch (_: Exception) {
                // Ignore
            }
            try {
                logger?.d(Layer.GAP) { "gatt.close()" }
                gatt.close()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }
}