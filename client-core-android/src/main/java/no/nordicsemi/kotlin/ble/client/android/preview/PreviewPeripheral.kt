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

package no.nordicsemi.kotlin.ble.client.android.preview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import no.nordicsemi.kotlin.ble.client.AnyRemoteService
import no.nordicsemi.kotlin.ble.client.ConnectionParametersChanged
import no.nordicsemi.kotlin.ble.client.ConnectionStateChanged
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.MtuChanged
import no.nordicsemi.kotlin.ble.client.PhyChanged
import no.nordicsemi.kotlin.ble.client.ReliableWriteCompleted
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.RemoteIncludedService
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.RssiRead
import no.nordicsemi.kotlin.ble.client.ServicesChanged
import no.nordicsemi.kotlin.ble.client.ServicesDiscovered
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.core.BondState
import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.PeripheralType
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.core.ServerScope
import no.nordicsemi.kotlin.ble.core.Service
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.core.android.preview.PreviewEnvironment
import no.nordicsemi.kotlin.ble.core.internal.CharacteristicDefinition
import no.nordicsemi.kotlin.ble.core.internal.DescriptorDefinition
import no.nordicsemi.kotlin.ble.core.internal.ServerScopeImpl
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.core.util.MergeResult
import no.nordicsemi.kotlin.ble.core.util.mergeIndexed
import no.nordicsemi.kotlin.log.Log
import org.jetbrains.annotations.Range
import kotlin.uuid.Uuid

/**
 * A stub implementation of [Peripheral.Executor] for Android.
 *
 * It does not depend on any Android API and can be used to preview the UI in the Compose Preview.
 *
 * The stub implementation provides some mocking functionality, for example [connect]
 * immediately changes the connection state to [ConnectionState.Connected] and [disconnect]
 * to [ConnectionState.Disconnected], etc.
 *
 * @param identifier The MAC address of the peripheral.
 * @param name An optional name of the peripheral.
 * @param type The type of the peripheral, defaults to [PeripheralType.LE].
 * @param initialState The initial connection state of the peripheral.
 * @param rssi The signal strength of the peripheral in dBm.
 * @param hasBondInformation `true` if the Android device has the bond information for the peripheral,
 * that is, if the peripheral is bonded to the device.
 */
private class StubExecutor(
    override val identifier: String,
    override val name: String?,
    override val type: PeripheralType,
    override val initialState: ConnectionState,
    override val initialServices: List<StubRemoteService>,
    private val rssi: Int,
    private val phy: PhyInUse,
    hasBondInformation: Boolean,
): Peripheral.Executor {
    override var logger: Log.Sink<Layer>? = Log.Sink.Null
    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<GattEvent> = _events.asSharedFlow()

    private val _bondState = MutableStateFlow(if (hasBondInformation) BondState.BONDED else BondState.NONE)
    override val bondState: StateFlow<BondState> = _bondState.asStateFlow()

    override val isClosed: Boolean
        get() = false

    override var isReliableWriteEnabled: Boolean = false

    override val environment: AndroidEnvironment = PreviewEnvironment()

    override suspend fun connect(
        autoConnect: Boolean,
        autoMtu: Boolean,
        opportunistic: Boolean,
    ) {
        _events.emit(ConnectionStateChanged(ConnectionState.Connected))
    }
    
    override suspend fun discoverServices(uuids: List<Uuid>): Boolean {
        _events.emit(ServicesDiscovered(initialServices))
        return true
    }

    override suspend fun createBond(): Boolean {
        _bondState.emit(BondState.BONDED)
        return true
    }

    override suspend fun removeBond(): Boolean {
        _bondState.emit(BondState.NONE)
        _events.emit(ConnectionStateChanged(ConnectionState.Disconnected(Reason.TerminateLocalHost)))
        return true
    }

    override suspend fun refreshCache(): Boolean {
        _events.emit(ServicesChanged)
        return true
    }

    override suspend fun requestConnectionPriority(priority: ConnectionPriority): Boolean {
        _events.emit(ConnectionParametersChanged(ConnectionParameters.Specified(15, 0, 0)))
        return true
    }

    override suspend fun requestMtu(mtu: @Range(from = 23, to = 517) Int): Boolean {
        _events.emit(MtuChanged(mtu))
        return true
    }

    override suspend fun requestPhy(txPhy: Phy, rxPhy: Phy, phyOptions: PhyOption): Boolean {
        _events.emit(PhyChanged(PhyInUse(txPhy, rxPhy)))
        return true
    }

    override suspend fun readPhy(): Boolean {
        _events.emit(PhyChanged(phy))
        return true
    }

    override suspend fun readRssi(): Boolean {
        _events.emit(RssiRead(rssi))
        return true
    }

    override fun beginReliableWrite(): Boolean {
        isReliableWriteEnabled = true
        return true
    }

    override suspend fun executeReliableWrite(): Boolean {
        isReliableWriteEnabled = false
        _events.emit(ReliableWriteCompleted(status = OperationStatus.Success))
        return true
    }

    override suspend fun abortReliableWrite(): Boolean {
        isReliableWriteEnabled = false
        _events.emit(ReliableWriteCompleted(status = OperationStatus.Success))
        return true
    }

    override suspend fun disconnect(reason: Reason): Boolean {
        _events.emit(ConnectionStateChanged(ConnectionState.Disconnected(reason)))
        return true
    }

    override fun close() {
        // Do nothing
    }
}

/**
 * A stub implementation of [RemoteService] for Android.
 *
 * This class is used to preview the UI in the Compose Preview.
 */
private class StubRemoteService(
    override val uuid: Uuid,
    override val instanceId: Int = 0,
    includedServices: List<ServiceDefinition> = emptyList(),
    characteristics: List<CharacteristicDefinition> = emptyList(),
): RemoteService() {

    override val characteristics: List<StubRemoteCharacteristic> = characteristics
        .map { cd ->
            StubRemoteCharacteristic(
                service = this,
                uuid = cd.uuid,
                instanceId = cd.instanceId,
                properties = cd.properties,
                permissions = cd.permissions,
                descriptors = cd.descriptors,
            )
        }

    override val includedServices: List<RemoteIncludedService> = includedServices
        .map { sd ->
            StubRemoteIncludedService(
                service = this,
                uuid = sd.uuid,
                instanceId = sd.instanceId,
                characteristics = sd.characteristics,
                includedServices = sd.includedServices,
            )
        }

    override fun toString(): String = uuid.toString()
}

/**
 * A stub implementation of [RemoteIncludedService] for Android.
 *
 * This class is used to preview the UI in the Compose Preview.
 */
private class StubRemoteIncludedService(
    override val service: AnyRemoteService,
    override val uuid: Uuid,
    override val instanceId: Int = 0,
    characteristics: List<CharacteristicDefinition> = emptyList(),
    includedServices: List<ServiceDefinition> = emptyList(),
): RemoteIncludedService {

    override val characteristics: List<RemoteCharacteristic> = characteristics
        .map { cd ->
            StubRemoteCharacteristic(
                service = this,
                uuid = cd.uuid,
                instanceId = cd.instanceId,
                properties = cd.properties,
                permissions = cd.permissions,
                descriptors = cd.descriptors,
            )
        }

    override val includedServices: List<RemoteIncludedService> = includedServices
        .map { sd ->
            StubRemoteIncludedService(
                service = this,
                uuid = sd.uuid,
                instanceId = sd.instanceId,
                characteristics = sd.characteristics,
                includedServices = sd.includedServices,
            )
        }

    override fun toString(): String = uuid.toString()
}

/**
 * A stub implementation of [RemoteCharacteristic] for Android.
 *
 * This class is used to preview the UI in the Compose Preview.
 */
private class StubRemoteCharacteristic(
    override val service: AnyRemoteService,
    override val uuid: Uuid,
    override val instanceId: Int,
    override val properties: Set<CharacteristicProperty> = emptySet(),
    private val permissions: Set<Permission>,
    descriptors: List<DescriptorDefinition> = emptyList(),
): RemoteCharacteristic {

    override val descriptors: List<RemoteDescriptor> = descriptors
        .map { dd ->
            StubRemoteDescriptor(
                characteristic = this,
                uuid = dd.uuid,
                instanceId = dd.instanceId,
                permissions = dd.permissions,
            )
        }

    private val _value = MutableStateFlow(byteArrayOf())

    private var _isNotifying = MutableStateFlow(false)
    override val isNotifying: StateFlow<Boolean>
        get() = _isNotifying.asStateFlow()

    override suspend fun setNotifying(enabled: Boolean) = when {
        owner == null -> throw InvalidAttributeException()
        isSubscribable() -> _isNotifying.update { enabled }
        else -> throw OperationFailedException(OperationStatus.SubscribeNotPermitted)
    }

    override suspend fun read(): ByteArray = when {
        owner == null -> throw InvalidAttributeException()
        isReadable() -> _value.value
        else -> throw OperationFailedException(OperationStatus.ReadNotPermitted)
    }

    override suspend fun write(data: ByteArray, writeType: WriteType) = when {
        owner == null -> throw InvalidAttributeException()
        isWritable() -> _value.update { data }
        else -> throw OperationFailedException(OperationStatus.WriteNotPermitted)
    }

    override suspend fun waitForValueChange(
        rawDataFilter: (ByteArray) -> Boolean,
        merge: suspend (ByteArray, ByteArray, Int) -> MergeResult,
        filter: (ByteArray) -> Boolean,
        trigger: suspend RemoteCharacteristic.() -> Unit,
    ): ByteArray = subscribe(trigger)
        .filter(rawDataFilter)
        .mergeIndexed(merge)
        .firstOrNull(filter)
        ?: throw InvalidAttributeException()

    override fun subscribe(onSubscription: suspend RemoteCharacteristic.() -> Unit): Flow<ByteArray> = when {
        owner == null -> throw InvalidAttributeException()
        isSubscribable() -> _value.filter { _isNotifying.value }.onStart { onSubscription() }
        else -> throw OperationFailedException(OperationStatus.SubscribeNotPermitted)
    }

    override fun toString(): String = uuid.toString()
}

/**
 * A stub implementation of [RemoteDescriptor] for Android.
 *
 * This class is used to preview the UI in the Compose Preview.
 */
private class StubRemoteDescriptor(
    override val characteristic: RemoteCharacteristic,
    override val uuid: Uuid,
    override val instanceId: Int,
    private val permissions: Set<Permission>,
): RemoteDescriptor {
    private var value: ByteArray = byteArrayOf()

    override suspend fun read(): ByteArray = when {
        owner == null -> throw InvalidAttributeException()
        isReadable() -> value
        else -> throw OperationFailedException(OperationStatus.ReadNotPermitted)
    }

    override suspend fun write(data: ByteArray) = when {
        owner == null -> throw InvalidAttributeException()
        isWritable() -> value = data
        else -> throw OperationFailedException(OperationStatus.WriteNotPermitted)
    }

    override fun toString(): String = uuid.toString()
}

/**
 * A preview implementation of [Peripheral] for Android.
 *
 * This class is used to preview the UI in the Compose Preview.
 *
 * @param scope The coroutine scope. This can be set to `rememberCoroutineScope()`.
 * @param address The MAC address of the peripheral.
 * @param name An optional name of the peripheral.
 * @param type The type of the peripheral, defaults to [PeripheralType.LE].
 * @param rssi The signal strength of the peripheral in dBm.
 * @param state The connection state of the peripheral.
 * @param services The list of fake services discovered on the peripheral.
 * @param hasBondInformation `true` if the Android device has the bond information for the peripheral,
 * that is, if the peripheral is bonded to the device. Defaults to `false`.
 */
open class PreviewPeripheral(
    scope: CoroutineScope,
    address: String = "00:11:22:33:44:55",
    name: String? = "Mock Device",
    type: PeripheralType = PeripheralType.LE,
    rssi: Int = -40, // dBm
    phy: PhyInUse = PhyInUse.PHY_LE_1M,
    state: ConnectionState = ConnectionState.Disconnected(),
    services: ServerScope.() -> Unit = {
        Service(Service.GENERIC_ACCESS_UUID) {
            Characteristic(
                uuid = Characteristic.DEVICE_NAME,
                property = CharacteristicProperty.READ,
                permission = Permission.READ,
            )
            Characteristic(
                uuid = Characteristic.APPEARANCE,
                property = CharacteristicProperty.READ,
                permission = Permission.READ,
            )
            Characteristic(
                uuid = Characteristic.PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS,
                property = CharacteristicProperty.READ,
                permission = Permission.READ,
            )
        }
        Service(Service.GENERIC_ATTRIBUTE_UUID) {
            Characteristic(
                uuid = Characteristic.SERVICE_CHANGED,
                property = CharacteristicProperty.INDICATE,
                permission = Permission.READ,
            )
        }
    },
    hasBondInformation: Boolean = false
): Peripheral(
    scope = scope,
    impl = StubExecutor(
        identifier = address,
        name = name,
        type = type,
        initialState = state,
        initialServices = ServerScopeImpl()
            .apply(services)
            .build()
            .map { sd ->
                StubRemoteService(
                    uuid = sd.uuid,
                    instanceId = sd.instanceId,
                    characteristics = sd.characteristics,
                    includedServices = sd.includedServices,
                )
            },
        rssi = rssi,
        phy = phy,
        hasBondInformation = hasBondInformation,
    )
) {
    override fun toString(): String {
        return name ?: address
    }
}