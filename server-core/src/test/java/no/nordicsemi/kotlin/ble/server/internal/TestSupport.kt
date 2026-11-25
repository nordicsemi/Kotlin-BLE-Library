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

package no.nordicsemi.kotlin.ble.server.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.server.Client
import no.nordicsemi.kotlin.ble.server.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.LocalDescriptor
import no.nordicsemi.kotlin.ble.server.LocalService
import no.nordicsemi.kotlin.log.Log
import kotlin.uuid.Uuid

/** A client used in tests. Clients with the same identifier are equal. */
class FakeClient(
    override val identifier: String,
    override var maximumUpdateValueLength: Int = 20,
): Client<String> {
    override var logger: Log.Sink<Layer>? = null

    override fun equals(other: Any?) = other is FakeClient && other.identifier == identifier
    override fun hashCode() = identifier.hashCode()
    override fun toString() = "FakeClient($identifier)"
}

/** A response sent using [GattServer.respond]. */
class Response(
    val client: FakeClient,
    val requestId: Int,
    val status: OperationStatus,
    val offset: Int,
    val value: ByteArray,
)

/** A notification or an indication sent using [GattServer.notify]. */
class Notification(
    val client: FakeClient,
    val attribute: Int,
    val value: ByteArray,
    val confirm: Boolean,
)

/** A [GattServer] that records calls and lets tests emit events. */
class FakeGattServer: GattServer<String, FakeClient> {
    override val state = MutableStateFlow(Manager.State.POWERED_ON)
    override val events = MutableSharedFlow<GattServerEvent<FakeClient>>(extraBufferCapacity = 256)

    val setServicesCalls = mutableListOf<List<ServiceDefinition>>()
    val responses = mutableListOf<Response>()
    val notifications = mutableListOf<Notification>()
    var closeCount = 0
        private set

    /** An exception to be thrown by [setServices]. */
    var setServicesError: Throwable? = null

    /** A hook called by [notify], before the notification is recorded. */
    var onNotify: suspend (Notification) -> Unit = {}

    /** A hook called by [setServices], after the services were recorded. */
    var setServicesHook: suspend () -> Unit = {}

    /** An exception to be thrown by the next call of [respond]. */
    var respondError: Throwable? = null

    override suspend fun setServices(services: List<ServiceDefinition>) {
        setServicesError?.let { throw it }
        setServicesCalls += services
        setServicesHook()
    }

    override fun respond(client: FakeClient, requestId: Int, status: OperationStatus, offset: Int, value: ByteArray) {
        respondError?.let {
            respondError = null
            throw it
        }
        responses += Response(client, requestId, status, offset, value)
    }

    override suspend fun notify(client: FakeClient, attribute: Int, value: ByteArray, confirm: Boolean) {
        val notification = Notification(client, attribute, value, confirm)
        onNotify(notification)
        notifications += notification
    }

    override fun close() {
        closeCount++
    }
}

val SERVICE_UUID = Uuid.parse("00001234-0000-1000-8000-00805f9b34fb")
val INCLUDED_UUID = Uuid.parse("00001235-0000-1000-8000-00805f9b34fb")
val RW_UUID = Uuid.parse("00002001-0000-1000-8000-00805f9b34fb")
val RO_UUID = Uuid.parse("00002002-0000-1000-8000-00805f9b34fb")
val WO_UUID = Uuid.parse("00002003-0000-1000-8000-00805f9b34fb")
val NOTIFY_UUID = Uuid.parse("00002004-0000-1000-8000-00805f9b34fb")
val INDICATE_UUID = Uuid.parse("00002005-0000-1000-8000-00805f9b34fb")
val BOTH_UUID = Uuid.parse("00002006-0000-1000-8000-00805f9b34fb")
val NONE_UUID = Uuid.parse("00002007-0000-1000-8000-00805f9b34fb")
val NOTIFY2_UUID = Uuid.parse("00002008-0000-1000-8000-00805f9b34fb")
val CUSTOM_DESCRIPTOR_UUID = Uuid.parse("00002a00-0000-1000-8000-00805f9b34fb")

/**
 * A manager with a server and a standard set of services:
 * - RW: read, write, write without response,
 * - RO: read,
 * - WO: write,
 * - NOTIFY: read, notify,
 * - INDICATE: indicate,
 * - BOTH: notify and indicate,
 * - NONE: no properties,
 * - NOTIFY2: notify,
 * - with a read-only CUD, and a descriptor with write permission on RW.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fixture(private val testScope: TestScope) {
    val server = FakeGattServer()
    val manager = LocalServerImpl(testScope.backgroundScope, server)
    val client = FakeClient("AA")
    val client2 = FakeClient("BB")
    private var requestId = 0

    lateinit var services: List<LocalService>

    suspend fun setup(): List<LocalService> {
        services = manager.setup {
            Service(SERVICE_UUID) {
                Characteristic(
                    RW_UUID,
                    CharacteristicProperty.READ and CharacteristicProperty.WRITE and
                            CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
                    Permission.READ and Permission.WRITE,
                ) {
                    CharacteristicUserDescriptionDescriptor("Some value")
                    Descriptor(CUSTOM_DESCRIPTOR_UUID, Permission.READ and Permission.WRITE)
                }
                Characteristic(RO_UUID, CharacteristicProperty.READ, Permission.READ)
                Characteristic(WO_UUID, CharacteristicProperty.WRITE, Permission.WRITE)
                Characteristic(NOTIFY_UUID, CharacteristicProperty.READ and CharacteristicProperty.NOTIFY, Permission.READ)
                Characteristic(INDICATE_UUID, CharacteristicProperty.INDICATE)
                Characteristic(BOTH_UUID, CharacteristicProperty.NOTIFY and CharacteristicProperty.INDICATE, Permission.READ)
                Characteristic(NONE_UUID, emptySet(), emptySet())
                Characteristic(NOTIFY2_UUID, CharacteristicProperty.NOTIFY)
                IncludedService(INCLUDED_UUID) {
                    Characteristic(RO_UUID, CharacteristicProperty.READ, Permission.READ)
                }
            }
        }
        return services
    }

    fun characteristic(uuid: Uuid): LocalCharacteristic =
        services.flatMap { it.characteristics }.first { it.uuid == uuid }

    fun cccd(uuid: Uuid): LocalDescriptor =
        characteristic(uuid).descriptors.first { it.isClientCharacteristicConfiguration }

    val rw get() = characteristic(RW_UUID)
    val ro get() = characteristic(RO_UUID)
    val wo get() = characteristic(WO_UUID)
    val notify get() = characteristic(NOTIFY_UUID)
    val indicate get() = characteristic(INDICATE_UUID)
    val both get() = characteristic(BOTH_UUID)

    /** Emits an event and lets the manager process it. */
    suspend fun emit(event: GattServerEvent<FakeClient>) {
        server.events.emit(event)
        testScope.testScheduler.runCurrent()
    }

    /** Lets the manager and handlers run. */
    fun run() = testScope.testScheduler.runCurrent()

    /** Sends a read request and returns the response. */
    suspend fun read(attribute: Int, offset: Int = 0, from: FakeClient = client): Response {
        emit(GattServerEvent.ReadRequested(from, ++requestId, attribute, offset))
        return server.responses.last()
    }

    /** Sends a write request and returns the response, or `null` if none was sent. */
    suspend fun write(
        attribute: Int,
        value: ByteArray,
        from: FakeClient = client,
        responseNeeded: Boolean = true,
        prepared: Boolean = false,
        offset: Int = 0,
    ): Response? {
        val before = server.responses.size
        emit(GattServerEvent.WriteRequested(from, ++requestId, attribute, offset, value, prepared, responseNeeded))
        return server.responses.drop(before).lastOrNull()
    }

    /** Sends an execute write request and returns the response. */
    suspend fun execute(execute: Boolean = true, from: FakeClient = client): Response {
        emit(GattServerEvent.ExecuteWrite(from, ++requestId, execute))
        return server.responses.last()
    }

    /** Writes the CCCD of the characteristic. */
    suspend fun writeCccd(characteristic: LocalCharacteristic, value: Int, from: FakeClient = client): Response =
        write(
            attribute = characteristic.descriptors.first { it.isClientCharacteristicConfiguration }.instanceId,
            value = byteArrayOf((value and 0xFF).toByte(), (value shr 8).toByte()),
            from = from,
        )!!

    /** Subscribes the client to notifications (1) or indications (2) of the characteristic. */
    suspend fun subscribe(characteristic: LocalCharacteristic, value: Int = 1, from: FakeClient = client) {
        val response = writeCccd(characteristic, value, from)
        check(response.status == OperationStatus.Success) { "Subscribing failed: ${response.status}" }
    }
}

fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
