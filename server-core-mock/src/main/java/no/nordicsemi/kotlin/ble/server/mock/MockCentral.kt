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

package no.nordicsemi.kotlin.ble.server.mock

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.kotlin.ble.core.ATT_MTU_DEFAULT
import no.nordicsemi.kotlin.ble.core.ATT_MTU_MAX
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.server.Central
import no.nordicsemi.kotlin.ble.server.internal.GattServerEvent
import no.nordicsemi.kotlin.log.Log
import kotlin.uuid.Uuid

/**
 * A simulated central device, which talks to a [MockGattServer].
 *
 * It behaves like a real GATT client: it can read and write attributes using single requests, or
 * using the long read, long write and reliable write procedures, which are fragmented according to
 * the current MTU. It can enable and disable notifications and indications by writing the Client
 * Characteristic Configuration descriptor (the descriptor itself is handled by the server logic).
 *
 * All operations complete immediately, without any delays, when the server responds. They work
 * with the virtual time of `runTest`. Attributes are identified by their `instanceId`; use
 * [findCharacteristic] and similar methods to find them by UUIDs.
 *
 * A central has to be connected using [connect] before sending requests. Requests are executed one
 * at a time, like in a real ATT bearer.
 *
 * This class is meant to be extended by platform-specific mock centrals, which also implement
 * the platform-specific [Central] interface.
 *
 * @param ID The type of the central identifier.
 * @param C The type of the central as seen by the GATT server. The simulated central **must** be
 * an instance of this type, as it is given to the GATT server in events.
 * @property identifier The central identifier.
 * @property server The GATT server to connect to.
 * @property maxMtu The maximum ATT MTU supported by the simulated central (and the simulated local
 * device), 23 to 517.
 */
abstract class MockCentral<ID: Any, C: Central<ID>>(
    final override val identifier: ID,
    protected val server: MockGattServer<ID, C>,
    val maxMtu: Int = ATT_MTU_MAX,
): Central<ID> {

    init {
        require(maxMtu in ATT_MTU_DEFAULT..ATT_MTU_MAX) {
            "The maximum MTU ($maxMtu) must be in the range from $ATT_MTU_DEFAULT to $ATT_MTU_MAX"
        }
    }

    override var logger: Log.Sink<Layer>? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())

    /** The connection state of the simulated central. Initially disconnected. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _attMtu = MutableStateFlow(ATT_MTU_DEFAULT)

    /**
     * The current ATT MTU. It is 23 after connecting, until changed using [exchangeMtu].
     * The value is retained after disconnecting.
     */
    val attMtu: StateFlow<Int> = _attMtu.asStateFlow()

    override val maximumUpdateValueLength: Int
        get() = _attMtu.value - 3

    private val _updates = MutableSharedFlow<MockUpdate>(extraBufferCapacity = UPDATES_BUFFER_CAPACITY)

    /**
     * Notifications and indications received by the simulated central.
     *
     * A hot flow with no replay: collect it before triggering the update. Indications are
     * confirmed automatically.
     */
    val updates: SharedFlow<MockUpdate> = _updates.asSharedFlow()

    private val procedureLock = Mutex()

    /**
     * Returns this central as seen by the GATT server.
     */
    @Suppress("UNCHECKED_CAST")
    val asCentral: C
        get() = this as C

    /** `true` if the central is connected. */
    val isConnected: Boolean
        get() = _connectionState.value.isConnected

    // ---- Connection ----

    /**
     * Connects to the GATT server. The MTU is reset to 23.
     *
     * @throws IllegalStateException if already connected, or if the server is closed.
     */
    fun connect() {
        check(!isConnected) { "Already connected" }
        server.attach(this)
        _attMtu.value = ATT_MTU_DEFAULT
        _connectionState.value = ConnectionState.Connected
        onConnected()
    }

    /**
     * Disconnects from the GATT server, as if the central terminated the connection.
     *
     * Emits [GattServerEvent.CentralDisconnected]. Does nothing if not connected.
     */
    open suspend fun disconnect() {
        disconnect(ConnectionState.Disconnected.Reason.TerminatePeerUser)
    }

    /**
     * Disconnects from the GATT server with the given reason.
     *
     * Emits [GattServerEvent.CentralDisconnected]. Requests that were waiting for a response fail.
     * Does nothing if not connected.
     *
     * @param reason The reason of disconnection.
     */
    suspend fun disconnect(reason: ConnectionState.Disconnected.Reason) {
        if (markDisconnected(reason)) {
            server.emit(GattServerEvent.CentralDisconnected(asCentral))
        }
    }

    /**
     * Simulates losing the link, for example when the central went out of range.
     */
    suspend fun simulateLinkLoss() = disconnect(ConnectionState.Disconnected.Reason.LinkLoss)

    internal fun terminate(reason: ConnectionState.Disconnected.Reason) {
        if (markDisconnected(reason)) {
            server.tryEmit(GattServerEvent.CentralDisconnected(asCentral))
        }
    }

    private fun markDisconnected(reason: ConnectionState.Disconnected.Reason): Boolean {
        if (!isConnected) return false
        _connectionState.value = ConnectionState.Disconnected(reason)
        server.detach(this)
        onDisconnected()
        return true
    }

    /**
     * Called after the central connected. Platform-specific subclasses can set up their state here.
     */
    protected open fun onConnected() {}

    /**
     * Called after the central disconnected.
     */
    protected open fun onDisconnected() {}

    /**
     * Called after the ATT MTU changed.
     *
     * @param mtu The new MTU.
     */
    protected open fun onMtuChanged(mtu: Int) {}

    /**
     * Simulates the MTU exchange procedure, initiated by the central.
     *
     * @param requested The MTU requested by the central. The result is limited to [maxMtu].
     * @return The new MTU, which is the lower of the requested MTU and [maxMtu], at least 23.
     * @throws IllegalStateException if the central is not connected.
     */
    fun exchangeMtu(requested: Int): Int {
        check(isConnected) { "Not connected" }
        val mtu = requested.coerceIn(ATT_MTU_DEFAULT, maxMtu)
        _attMtu.value = mtu
        onMtuChanged(mtu)
        return mtu
    }

    // ---- Attribute lookup ----

    /**
     * Finds a service by UUID, see [MockGattServer.findService].
     */
    fun findService(uuid: Uuid, index: Int = 0): Int? = server.findService(uuid, index)

    /**
     * Finds a characteristic by UUID, see [MockGattServer.findCharacteristic].
     *
     * @return The `instanceId` of the characteristic, or `null` if not found.
     */
    fun findCharacteristic(serviceUuid: Uuid, uuid: Uuid): Int? = server.findCharacteristic(serviceUuid, uuid)

    /**
     * Finds a descriptor by UUID, see [MockGattServer.findDescriptor].
     *
     * @return The `instanceId` of the descriptor, or `null` if not found.
     */
    fun findDescriptor(serviceUuid: Uuid, characteristicUuid: Uuid, uuid: Uuid): Int? =
        server.findDescriptor(serviceUuid, characteristicUuid, uuid)

    // ---- Read ----

    /**
     * Sends a single Read Request (offset 0).
     *
     * The response contains at most `mtu - 1` bytes, the rest of the value is truncated. Use
     * [readLong] to read longer values.
     *
     * @param attribute The `instanceId` of the characteristic or descriptor.
     * @throws IllegalStateException if the central is not connected.
     */
    suspend fun read(attribute: Int): ReadResult = procedure {
        readRequest(attribute, offset = 0)
    }

    /**
     * Sends a single Read Blob Request.
     *
     * @param attribute The `instanceId` of the characteristic or descriptor.
     * @param offset The offset from which the value is read.
     * @throws IllegalStateException if the central is not connected.
     */
    suspend fun readBlob(attribute: Int, offset: Int): ReadResult = procedure {
        readRequest(attribute, offset)
    }

    /**
     * Executes the Long Read procedure: reads the value using a Read Request followed by
     * Read Blob Requests, until a response shorter than `mtu - 1` bytes is received.
     *
     * @param attribute The `instanceId` of the characteristic or descriptor.
     * @return The whole value, or the first error.
     * @throws IllegalStateException if the central is not connected.
     */
    suspend fun readLong(attribute: Int): ReadResult = procedure {
        var value = ByteArray(0)
        var complete = false
        var failure: ReadResult.Failure? = null
        while (!complete && failure == null) {
            when (val result = readRequest(attribute, offset = value.size)) {
                is ReadResult.Failure -> failure = result
                is ReadResult.Success -> {
                    value += result.value
                    complete = result.value.size < _attMtu.value - 1
                    // An attribute value is not longer than 512 bytes. If the server returned
                    // more, it must be ignoring the offset.
                    if (!complete && value.size > MAX_ATTRIBUTE_LENGTH) {
                        failure = ReadResult.Failure(OperationStatus.InvalidAttributeLength)
                    }
                }
            }
        }
        failure ?: ReadResult.Success(value)
    }

    private suspend fun readRequest(attribute: Int, offset: Int): ReadResult {
        val response = server.request(this) { requestId ->
            GattServerEvent.ReadRequested(asCentral, requestId, attribute, offset)
        }
        return if (response.status.isSuccess) {
            ReadResult.Success(response.value.copyOf(minOf(response.value.size, _attMtu.value - 1)))
        } else {
            ReadResult.Failure(response.status)
        }
    }

    // ---- Write ----

    /**
     * Writes the value of a characteristic or a descriptor.
     *
     * A write command (without response) is sent as a single request and completes immediately.
     * A write request is sent as a single Write Request if the value fits in `mtu - 3`
     * bytes, otherwise [writeLong] is used.
     *
     * @param attribute The `instanceId` of the characteristic or descriptor.
     * @param value The value to write.
     * @param withResponse `true` for a write request, `false` for a write command.
     * @throws IllegalStateException if the central is not connected.
     * @throws IllegalArgumentException if [withResponse] is `false` and the value does not fit in
     * `mtu - 3` bytes.
     */
    suspend fun write(attribute: Int, value: ByteArray, withResponse: Boolean = true): WriteResult {
        val maxLength = _attMtu.value - 3
        return if (withResponse && value.size > maxLength) {
            writeLong(attribute, value)
        } else {
            procedure {
                require(withResponse || value.size <= maxLength) {
                    "A write command can carry at most $maxLength bytes with the current MTU"
                }
                writeRequest(attribute, 0, value, prepared = false, withResponse = withResponse)
            }
        }
    }

    /**
     * Executes the Long Write procedure: sends the value in Prepare Write Requests, each carrying
     * at most `mtu - 5` bytes, and executes them. If any of them fails, the queue is cancelled.
     *
     * @param attribute The `instanceId` of the characteristic or descriptor.
     * @param value The value to write.
     * @return The result of the Execute Write, or the first error.
     * @throws IllegalStateException if the central is not connected.
     */
    suspend fun writeLong(attribute: Int, value: ByteArray): WriteResult = procedure {
        val result = prepareWrites(attribute, value)
        if (result is WriteResult.Failure) {
            val _ = executeWriteRequest(execute = false)
            result
        } else {
            executeWriteRequest(execute = true)
        }
    }

    /**
     * Starts a Reliable Write procedure, in which one or more values are queued using
     * [ReliableWrite.write], and then executed, or cancelled.
     *
     * @throws IllegalStateException if the central is not connected.
     */
    fun beginReliableWrite(): ReliableWrite {
        check(isConnected) { "Not connected" }
        return ReliableWrite()
    }

    /**
     * Executes the Reliable Write procedure.
     *
     * The write requests done in the [block] are queued and executed together when it returns. If any of
     * them failed, or the block threw an exception, they are cancelled.
     *
     * @param block The block queuing write requests.
     * @return The result of the Execute Write, or the first error of a queued write request.
     * @throws IllegalStateException if the central is not connected.
     */
    suspend fun reliableWrite(block: suspend ReliableWrite.() -> Unit): WriteResult {
        val write = beginReliableWrite()
        try {
            write.block()
        } catch (e: Throwable) {
            write.cancel()
            throw e
        }
        return write.execute()
    }

    /**
     * A Reliable Write procedure in progress. Values are queued on the server using Prepare
     * Write Requests, and taken into effect only after [execute].
     */
    inner class ReliableWrite internal constructor() {
        private var failure: WriteResult.Failure? = null
        private var finished = false

        /**
         * Queues a value using Prepare Write Requests, each carrying at most `mtu - 5` bytes.
         *
         * @param attribute The `instanceId` of the characteristic or descriptor.
         * @param value The value to write.
         * @return The result of the last Prepare Write.
         */
        suspend fun write(attribute: Int, value: ByteArray): WriteResult = procedure {
            check(!finished) { "The reliable write has already finished" }
            val result = prepareWrites(attribute, value)
            if (result is WriteResult.Failure && failure == null) {
                failure = result
            }
            result
        }

        /**
         * Executes the queued write requests. If any of the previous ones failed, the queue is
         * cancelled instead, and the first error is returned.
         */
        suspend fun execute(): WriteResult = procedure {
            check(!finished) { "The reliable write has already finished" }
            finished = true
            val error = failure
            if (error != null) {
                val _ = executeWriteRequest(execute = false)
                error
            } else {
                executeWriteRequest(execute = true)
            }
        }

        /**
         * Cancels the queued write requests. Nothing is written.
         */
        @IgnorableReturnValue
        suspend fun cancel(): WriteResult = procedure {
            if (finished) return@procedure WriteResult.Success
            finished = true
            executeWriteRequest(execute = false)
        }
    }

    private suspend fun prepareWrites(attribute: Int, value: ByteArray): WriteResult {
        val chunkSize = _attMtu.value - 5
        var offset = 0
        do {
            val end = minOf(offset + chunkSize, value.size)
            val result = writeRequest(
                attribute = attribute,
                offset = offset,
                value = value.copyOfRange(offset, end),
                prepared = true,
                withResponse = true,
            )
            if (result is WriteResult.Failure) return result
            offset = end
        } while (offset < value.size)
        return WriteResult.Success
    }

    private suspend fun writeRequest(
        attribute: Int,
        offset: Int,
        value: ByteArray,
        prepared: Boolean,
        withResponse: Boolean,
    ): WriteResult {
        if (!withResponse) {
            server.emit(
                GattServerEvent.WriteRequested(asCentral, server.newRequestId(), attribute, offset, value.copyOf(), prepared, false)
            )
            return WriteResult.Success
        }
        val response = server.request(this) { requestId ->
            GattServerEvent.WriteRequested(asCentral, requestId, attribute, offset, value.copyOf(), prepared, true)
        }
        return response.status.toWriteResult()
    }

    private suspend fun executeWriteRequest(execute: Boolean): WriteResult {
        val response = server.request(this) { requestId ->
            GattServerEvent.ExecuteWrite(asCentral, requestId, execute)
        }
        return response.status.toWriteResult()
    }

    private fun OperationStatus.toWriteResult() = if (isSuccess) WriteResult.Success else WriteResult.Failure(this)

    // ---- Subscriptions ----

    /**
     * Enables notifications or indications of a characteristic by writing its Client Characteristic
     * Configuration descriptor.
     *
     * @param characteristic The `instanceId` of the characteristic.
     * @param indications `true` to enable indications, `false` to enable notifications.
     * @return The result of the write operation, or [OperationStatus.SubscribeNotPermitted] failure if the
     * characteristic has no Client Characteristic Configuration descriptor.
     * @throws IllegalStateException if the central is not connected.
     */
    suspend fun subscribe(characteristic: Int, indications: Boolean = false): WriteResult {
        val cccd = server.findCccd(characteristic)
            ?: return WriteResult.Failure(OperationStatus.SubscribeNotPermitted)
        return write(cccd, byteArrayOf(if (indications) 0x02 else 0x01, 0x00), withResponse = true)
    }

    /**
     * Disables notifications and indications of a characteristic by writing its Client
     * Characteristic Configuration descriptor.
     *
     * @param characteristic The `instanceId` of the characteristic.
     * @return The result of the write operation, or [OperationStatus.SubscribeNotPermitted] failure if the
     * characteristic has no Client Characteristic Configuration descriptor.
     * @throws IllegalStateException if the central is not connected.
     */
    suspend fun unsubscribe(characteristic: Int): WriteResult {
        val cccd = server.findCccd(characteristic)
            ?: return WriteResult.Failure(OperationStatus.SubscribeNotPermitted)
        return write(cccd, byteArrayOf(0x00, 0x00), withResponse = true)
    }

    internal suspend fun deliver(update: MockUpdate) {
        _updates.emit(update)
    }

    private suspend fun <T> procedure(block: suspend () -> T): T {
        check(isConnected) { "Not connected" }
        return procedureLock.withLock {
            check(isConnected) { "Not connected" }
            block()
        }
    }

    override fun toString() = "MockCentral(identifier=$identifier, state=${_connectionState.value}, mtu=${_attMtu.value})"

    private companion object {
        const val UPDATES_BUFFER_CAPACITY = 256
        const val MAX_ATTRIBUTE_LENGTH = 512
    }
}
