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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyOption
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment
import no.nordicsemi.kotlin.ble.server.android.Client
import no.nordicsemi.kotlin.ble.server.exception.ClientNotConnectedException
import no.nordicsemi.kotlin.ble.server.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.server.internal.GattServer
import no.nordicsemi.kotlin.ble.server.internal.GattServerEvent
import no.nordicsemi.kotlin.log.Log
import kotlin.time.Duration.Companion.seconds

/** The number of events that can wait to be processed. No event may be lost. */
private const val EVENT_BUFFER_CAPACITY = 1024

/** The time to wait for the stack to confirm that a service was added. */
private val SERVICE_ADDED_TIMEOUT = 10.seconds

/**
 * The time to wait for a notification to be sent, or an indication confirmed.
 *
 * The ATT transaction timeout is 30 seconds, after which the link is terminated.
 */
private val NOTIFICATION_TIMEOUT = 35.seconds

/** `GATT_INVALID_HANDLE`, sent when a request targets an attribute that is not known. */
private const val GATT_INVALID_HANDLE = 0x01

/** `HCI_ERR_CONN_CAUSE_LOCAL_HOST`, the reason of disconnection when the server was closed. */
private const val GATT_CONN_TERMINATE_LOCAL_HOST = 0x16

/**
 * A native implementation of [GattServer] for Android, built on the [BluetoothGattServer].
 *
 * The class only translates between the native callbacks and [GattServerEvent]s. The logic is
 * implemented in the `LocalServerImpl`.
 *
 * @param scope The coroutine scope.
 * @param environment Native Android environment object.
 */
@SuppressLint("MissingPermission")
internal class NativeGattServer(
    scope: CoroutineScope,
    private val environment: NativeAndroidEnvironment,
): GattServer<String, Client>, Log.Emitter {

    /** The logger. It is also set to the clients. */
    var logger: Log.Sink<Layer>? = Log.Sink.Null
        set(value) {
            field = value
            synchronized(clients) { clients.values.toList() }.forEach { it.logger = value }
        }

    override val state: StateFlow<Manager.State> = environment.bluetoothState

    override val events: SharedFlow<GattServerEvent<Client>>
        field = MutableSharedFlow<GattServerEvent<Client>>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /** The clients that are currently connected. */
    val connectedClients: StateFlow<List<Client>>
        field = MutableStateFlow<List<Client>>(emptyList())

    /** The native server, or `null` if it was not opened yet, or Bluetooth was turned off. */
    @Volatile
    private var server: BluetoothGattServer? = null

    @Volatile
    private var isClosed = false

    /** Maps the native services, characteristics and descriptors to IDs of their definitions. */
    private val attributes = AttributeMap<Any>()

    /** Guards setting the services. */
    private val servicesMutex = Mutex()

    /** Connected clients, by their addresses. Guarded by itself. */
    private val clients = LinkedHashMap<String, NativeClient>()

    /** The pending [BluetoothGattServer.addService], waiting for `onServiceAdded`. */
    @Volatile
    private var serviceAdded: CompletableDeferred<Int>? = null

    /**
     * Guards setting the value of a characteristic and notifying with it before Android 13.
     *
     * The characteristics are shared by all clients and the value is a part of their state.
     */
    private val valueLock = Any()

    init {
        // When Bluetooth is turned off, the native server stops working. All connections are lost.
        scope.launch {
            state.collect {
                if (it != Manager.State.POWERED_ON) {
                    onBluetoothUnavailable()
                }
            }
        }
    }

    // Requirements

    private fun ensureOpen() {
        if (isClosed) {
            throw ManagerClosedException()
        }
    }

    /**
     * Checks whether the BLUETOOTH_CONNECT permission is granted.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is denied.
     */
    private fun checkConnectPermission() {
        if (environment.requiresBluetoothRuntimePermissions && !environment.isBluetoothConnectPermissionGranted) {
            throw SecurityException("BLUETOOTH_CONNECT permission not granted")
        }
    }

    /** Opens the native server, if it is not open yet. */
    private fun openServer(): BluetoothGattServer = synchronized(this) {
        server ?: run {
            val manager = environment.bluetoothManager ?: throw BluetoothUnavailableException()
            logger?.d(Layer.GATT) { "bluetoothManager.openGattServer()" }
            manager.openGattServer(environment.applicationContext, callback)
                ?.also { server = it }
                ?: throw BluetoothUnavailableException()
        }
    }

    // Services

    override suspend fun setServices(services: List<ServiceDefinition>) {
        ensureOpen()

        // Ensure Bluetooth is enabled.
        if (state.value != Manager.State.POWERED_ON) {
            throw BluetoothUnavailableException()
        }

        // Verify the BLUETOOTH_CONNECT permission is granted (Android 12+).
        checkConnectPermission()

        servicesMutex.withLock {
            val server = openServer()

            // Remove the previous services. This does nothing for a new server.
            logger?.d(Layer.GATT) { "server.clearServices()" }
            server.clearServices()
            attributes.clear()

            try {
                // The services have to be added one by one. The next one can be added after
                // the stack confirmed the previous one.
                services.forEach { definition ->
                    server.addService(definition, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                }
            } catch (e: Throwable) {
                // Do not leave the server with some of the services only.
                logger?.w(Layer.GATT) { "Adding services failed: $e" }
                if (this.server === server) {
                    server.clearServices()
                }
                attributes.clear()
                throw e
            }
        }
    }

    /**
     * Creates the native service from the definition and adds it to the server.
     *
     * Services included in the service are added first, as secondary services, and are then
     * included in the new one.
     *
     * @return The native service that was added.
     */
    @IgnorableReturnValue
    private suspend fun BluetoothGattServer.addService(
        definition: ServiceDefinition,
        type: Int,
    ): BluetoothGattService {
        val service = BluetoothGattService(definition.uuid.toJavaUuid(), type)
        attributes.register(service, definition.instanceId)

        definition.characteristics.forEach { characteristicDefinition ->
            val characteristic = BluetoothGattCharacteristic(
                characteristicDefinition.uuid.toJavaUuid(),
                characteristicDefinition.properties.toNativeProperties(),
                characteristicDefinition.permissions.toNativePermissions(),
            )
            attributes.register(characteristic, characteristicDefinition.instanceId)

            characteristicDefinition.descriptors.forEach { descriptorDefinition ->
                val descriptor = BluetoothGattDescriptor(
                    descriptorDefinition.uuid.toJavaUuid(),
                    descriptorDefinition.permissions.toNativePermissions(),
                )
                attributes.register(descriptor, descriptorDefinition.instanceId)
                characteristic.addDescriptor(descriptor)
            }
            service.addCharacteristic(characteristic)
        }

        definition.includedServices.forEach { includedDefinition ->
            val secondary = addService(includedDefinition, BluetoothGattService.SERVICE_TYPE_SECONDARY)
            service.addService(secondary)
        }

        val added = CompletableDeferred<Int>()
        serviceAdded = added
        try {
            logger?.d(Layer.GATT) { "server.addService(${service.uuid})" }
            if (!addService(service)) {
                throw OperationFailedException(OperationStatus.RequestFailed)
            }
            val status = withTimeoutOrNull(SERVICE_ADDED_TIMEOUT) { added.await() }
                ?: throw OperationFailedException(OperationStatus.RequestFailed)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw OperationFailedException(status.toOperationStatus())
            }
        } finally {
            serviceAdded = null
        }
        return service
    }

    // Responses and notifications

    override fun respond(
        client: Client,
        requestId: Int,
        status: OperationStatus,
        offset: Int,
        value: ByteArray,
    ) {
        val device = client.asNative().device
        val server = server
        if (server == null) {
            logger?.w(Layer.GATT) { "Cannot respond to $device, the server is not open" }
            return
        }
        logger?.d(Layer.GATT) { "server.sendResponse(device=$device, requestId=$requestId, status=${status.toNativeStatus()}, offset=$offset, value=0x${value.toHexString()})" }
        val result = server.sendResponse(
            device,
            requestId,
            status.toNativeStatus(),
            offset,
            value.takeIf { it.isNotEmpty() },
        )
        if (!result) {
            logger?.w(Layer.GATT) { "Response to request $requestId from $device was not sent" }
        }
    }

    override suspend fun notify(
        client: Client,
        attribute: Int,
        value: ByteArray,
        confirm: Boolean,
    ) {
        ensureOpen()
        val nativeClient = client.asNative()
        val characteristic = attributes.attributeOf(attribute) as? BluetoothGattCharacteristic
            ?: throw IllegalArgumentException("Attribute $attribute is not a known characteristic")
        if (!nativeClient.state.value.isConnected) {
            throw ClientNotConnectedException()
        }

        // Only one notification can be in flight per client. The next ones are queued.
        val status = nativeClient.notifications.send(NOTIFICATION_TIMEOUT) {
            // The server may have been closed while the notification was queued.
            val server = server ?: throw ClientNotConnectedException()
            if (!nativeClient.state.value.isConnected) {
                throw ClientNotConnectedException()
            }
            logger?.d(Layer.GATT) { "server.notifyCharacteristicChanged(device=${nativeClient.device}, characteristic=${characteristic.uuid}, confirm=$confirm, value=0x${value.toHexString()})" }
            server.sendNotification(nativeClient.device, characteristic, confirm, value)
        } ?: throw OperationFailedException(OperationStatus.ConnectionTimeout)

        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw OperationFailedException(status.toOperationStatus())
        }
    }

    /**
     * Sends the notification using the API available on the running Android version.
     *
     * @throws ClientNotConnectedException if the client is not connected.
     * @throws OperationFailedException if the notification could not be sent.
     */
    @Suppress("DEPRECATION")
    private fun BluetoothGattServer.sendNotification(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        confirm: Boolean,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = notifyCharacteristicChanged(device, characteristic, confirm, value)
            when (result) {
                0 /* BluetoothStatusCodes.SUCCESS */ -> return
                4 /* BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED */ -> throw ClientNotConnectedException()
                200 /* BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY */ -> throw OperationFailedException(OperationStatus.Busy)
                else -> throw OperationFailedException(OperationStatus.RequestFailed)
            }
        } else {
            // The value is a part of the characteristic, which is shared by all clients.
            // The native call reads it synchronously, so the lock needs to be held for that time only.
            val result = synchronized(valueLock) {
                characteristic.value = value
                notifyCharacteristicChanged(device, characteristic, confirm)
            }
            if (!result) {
                throw OperationFailedException(OperationStatus.RequestFailed)
            }
        }
    }

    // Client operations

    /** Requests changing the PHY of the connection. The result is reported in `onPhyUpdate`. */
    @RequiresApi(Build.VERSION_CODES.O)
    fun setPreferredPhy(client: NativeClient, txPhy: Phy, rxPhy: Phy, phyOptions: PhyOption) {
        val server = server ?: throw ClientNotConnectedException()
        logger?.d(Layer.PHY) { "server.setPreferredPhy(device=${client.device}, tx=${txPhy.toPhyMask()}, rx=${rxPhy.toPhyMask()}, options=${phyOptions.toOption()})" }
        server.setPreferredPhy(client.device, txPhy.toPhyMask(), rxPhy.toPhyMask(), phyOptions.toOption())
    }

    /** Terminates the connection with the client. */
    fun cancelConnection(client: NativeClient) {
        val server = server ?: return
        logger?.d(Layer.GAP) { "server.cancelConnection(${client.device})" }
        server.cancelConnection(client.device)
    }

    private fun Client.asNative(): NativeClient =
        this as? NativeClient
            ?: throw IllegalArgumentException("$this was not created by the native GATT server")

    // Lifecycle

    override fun close() {
        if (isClosed) return
        isClosed = true

        // The clients are disconnected, but the callbacks may not be called after the server
        // is closed.
        dropAllClients()
        serviceAdded?.completeExceptionally(ManagerClosedException())

        val server = synchronized(this) { server.also { server = null } }
        if (server != null) {
            logger?.d(Layer.GATT) { "server.close()" }
            server.close()
        }
        attributes.clear()
    }

    /** Called when Bluetooth was turned off, or is not available. */
    private fun onBluetoothUnavailable() {
        val server = synchronized(this) { server.also { server = null } } ?: return
        logger?.w(Layer.GAP) { "Bluetooth is not available, closing the server" }
        dropAllClients()
        serviceAdded?.completeExceptionally(BluetoothUnavailableException())
        try {
            server.close()
        } catch (e: Exception) {
            logger?.w(Layer.GATT) { "Failed to close the server: $e" }
        }
        attributes.clear()
    }

    // Clients

    /**
     * Returns the client for the given device, creating it, if it was not known.
     *
     * Android reports connections of all clients, but a request may also come from a client
     * that was connected before the server was opened.
     */
    @IgnorableReturnValue
    private fun clientFor(device: BluetoothDevice): NativeClient {
        var isNew = false
        val client = synchronized(clients) {
            clients[device.address] ?: NativeClient(device, this).also {
                isNew = true
                it.logger = logger
                clients[device.address] = it
                connectedClients.value = clients.values.toList()
            }
        }
        if (isNew) {
            logger?.i(Layer.GAP) { "Client $device connected" }
            // Android does not report the PHY when a client connects.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                readPhy(device)
            }
        }
        return client
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun readPhy(device: BluetoothDevice) {
        try {
            logger?.d(Layer.PHY) { "server.readPhy($device)" }
            server?.readPhy(device)
        } catch (e: Exception) {
            logger?.w(Layer.PHY) { "Failed to read the PHY of $device: $e" }
        }
    }

    /** Removes the client and reports that it disconnected. */
    private fun dropClient(device: BluetoothDevice, status: Int) {
        val client = synchronized(clients) {
            clients.remove(device.address)?.also {
                connectedClients.value = clients.values.toList()
            }
        } ?: return
        logger?.i(Layer.GAP) { "Client $device disconnected (status: $status)" }
        client.onDisconnected(status)
        emit(GattServerEvent.ClientDisconnected(client))
    }

    private fun dropAllClients() {
        synchronized(clients) { clients.values.map { it.device } }.forEach {
            dropClient(it, GATT_CONN_TERMINATE_LOCAL_HOST)
        }
    }

    private fun existingClient(device: BluetoothDevice): NativeClient? =
        synchronized(clients) { clients[device.address] }

    // Events

    private fun emit(event: GattServerEvent<Client>) {
        if (!events.tryEmit(event)) {
            logger?.e(Layer.GATT) { "Event $event was lost" }
        }
    }

    /** Rejects a request for an attribute that is not known. */
    private fun rejectUnknownAttribute(device: BluetoothDevice, requestId: Int, offset: Int) {
        logger?.w(Layer.GATT) { "Request $requestId from $device targets an unknown attribute" }
        server?.sendResponse(device, requestId, GATT_INVALID_HANDLE, offset, null)
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            logger?.d(Layer.GAP) { "onConnectionStateChange(device=$device, newState=$newState, status=$status)" }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> clientFor(device)
                BluetoothGatt.STATE_DISCONNECTED -> dropClient(device, status)
                else -> existingClient(device)?.onStateChanged(newState.toConnectionState(status, userInitiated = false))
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            logger?.d(Layer.GATT) { "onServiceAdded(${service.uuid}, status=$status)" }
            serviceAdded?.complete(status)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            logger?.d(Layer.GATT) { "onCharacteristicReadRequest(device=$device, requestId=$requestId, characteristic=${characteristic.uuid}, offset=$offset)" }
            val client = clientFor(device)
            val id = attributes.idOf(characteristic)
                ?: return rejectUnknownAttribute(device, requestId, offset)
            emit(GattServerEvent.ReadRequested(client, requestId, id, offset))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            logger?.d(Layer.GATT) { "onCharacteristicWriteRequest(device=$device, requestId=$requestId, characteristic=${characteristic.uuid}, prepared=$preparedWrite, responseNeeded=$responseNeeded, offset=$offset, value=0x${value?.toHexString()})" }
            val client = clientFor(device)
            val id = attributes.idOf(characteristic)
                ?: return if (responseNeeded) rejectUnknownAttribute(device, requestId, offset) else Unit
            emit(GattServerEvent.WriteRequested(client, requestId, id, offset, value ?: byteArrayOf(), preparedWrite, responseNeeded))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            logger?.d(Layer.GATT) { "onDescriptorReadRequest(device=$device, requestId=$requestId, descriptor=${descriptor.uuid}, offset=$offset)" }
            val client = clientFor(device)
            val id = attributes.idOf(descriptor)
                ?: return rejectUnknownAttribute(device, requestId, offset)
            emit(GattServerEvent.ReadRequested(client, requestId, id, offset))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            logger?.d(Layer.GATT) { "onDescriptorWriteRequest(device=$device, requestId=$requestId, descriptor=${descriptor.uuid}, prepared=$preparedWrite, responseNeeded=$responseNeeded, offset=$offset, value=0x${value?.toHexString()})" }
            val client = clientFor(device)
            val id = attributes.idOf(descriptor)
                ?: return if (responseNeeded) rejectUnknownAttribute(device, requestId, offset) else Unit
            emit(GattServerEvent.WriteRequested(client, requestId, id, offset, value ?: byteArrayOf(), preparedWrite, responseNeeded))
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            logger?.d(Layer.GATT) { "onExecuteWrite(device=$device, requestId=$requestId, execute=$execute)" }
            emit(GattServerEvent.ExecuteWrite(clientFor(device), requestId, execute))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            logger?.d(Layer.GATT) { "onNotificationSent(device=$device, status=$status)" }
            existingClient(device)?.notifications?.onSent(status)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            logger?.d(Layer.GATT) { "onMtuChanged(device=$device, mtu=$mtu)" }
            clientFor(device).onMtuChanged(mtu)
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            logger?.d(Layer.PHY) { "onPhyUpdate(device=$device, tx=$txPhy, rx=$rxPhy, status=$status)" }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger?.w(Layer.PHY) { "PHY update failed with status $status" }
                // no return, the request must be completed
            }
            existingClient(device)?.onPhyChanged(txPhy, rxPhy, isUpdate = true)
        }

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            logger?.d(Layer.PHY) { "onPhyRead(device=$device, tx=$txPhy, rx=$rxPhy, status=$status)" }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger?.w(Layer.PHY) { "Reading PHY failed with status $status" }
                return
            }
            existingClient(device)?.onPhyChanged(txPhy, rxPhy, isUpdate = false)
        }

        // Note:
        // The base method is hidden in BluetoothGattServerCallback using @hide.
        // It was added in Android 8 (Oreo) and it is possible to override it, but due to its hidden
        // nature, it cannot use `override`.
        @Keep
        /* override */ fun onConnectionUpdated(
            device: BluetoothDevice,
            interval: Int,
            latency: Int,
            timeout: Int,
            status: Int,
        ) {
            logger?.d(Layer.LINK) { "onConnectionUpdated(device=$device, interval=$interval, latency=$latency, timeout=$timeout, status=$status)" }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger?.w(Layer.LINK) { "Connection update failed with status $status" }
                // no return, the parameters are still valid
            }
            existingClient(device)?.onConnectionParametersChanged(interval, latency, timeout)
        }
    }
}
