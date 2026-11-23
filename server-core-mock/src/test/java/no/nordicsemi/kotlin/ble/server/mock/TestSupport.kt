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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.core.internal.ServerScopeImpl
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.server.internal.GattServerEvent
import kotlin.uuid.Uuid

val SERVICE_UUID = Uuid.parse("00001800-0000-1000-8000-00805f9b34fb")
val INCLUDED_SERVICE_UUID = Uuid.parse("00001801-0000-1000-8000-00805f9b34fb")
val READ_WRITE_UUID = Uuid.parse("00002a00-0000-1000-8000-00805f9b34fb")
val NOTIFY_UUID = Uuid.parse("00002a01-0000-1000-8000-00805f9b34fb")
val DESCRIPTOR_UUID = Uuid.parse("00002a02-0000-1000-8000-00805f9b34fb")

/** A central type used in tests, which is its own GATT server-side representation. */
class TestCentral(
    identifier: String,
    server: MockGattServer<String, TestCentral>,
    maxMtu: Int = 517,
): MockCentral<String, TestCentral>(identifier, server, maxMtu)

typealias TestServer = MockGattServer<String, TestCentral>

fun buildServices(): List<ServiceDefinition> = ServerScopeImpl()
    .apply {
        Service(SERVICE_UUID) {
            Characteristic(
                READ_WRITE_UUID,
                CharacteristicProperty.READ and CharacteristicProperty.WRITE,
                Permission.READ and Permission.WRITE,
            ) {
                Descriptor(DESCRIPTOR_UUID, Permission.READ)
            }
            Characteristic(
                NOTIFY_UUID,
                CharacteristicProperty.READ and CharacteristicProperty.NOTIFY and CharacteristicProperty.INDICATE,
                Permission.READ,
            )
            IncludedService(INCLUDED_SERVICE_UUID) {
                Characteristic(Uuid.parse("00002a03-0000-1000-8000-00805f9b34fb"), CharacteristicProperty.READ, Permission.READ)
            }
        }
    }
    .build()

/**
 * A minimal fake of the server logic: stores values, serves reads with offsets, queues prepared
 * writes per central, executes them, and records all events.
 *
 * Attributes can be made to fail using [failing].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeApp(server: TestServer, scope: TestScope) {
    val values = mutableMapOf<Int, ByteArray>()
    val events = mutableListOf<GattServerEvent<TestCentral>>()
    val failing = mutableMapOf<Int, OperationStatus>()
    private val queues = mutableMapOf<TestCentral, MutableList<GattServerEvent.WriteRequested<TestCentral>>>()

    val reads get() = events.filterIsInstance<GattServerEvent.ReadRequested<TestCentral>>()
    val writes get() = events.filterIsInstance<GattServerEvent.WriteRequested<TestCentral>>()
    val executes get() = events.filterIsInstance<GattServerEvent.ExecuteWrite<TestCentral>>()
    val disconnects get() = events.filterIsInstance<GattServerEvent.CentralDisconnected<TestCentral>>()

    init {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            server.events.collect { event ->
                events += event
                when (event) {
                    is GattServerEvent.ReadRequested -> {
                        val error = failing[event.attribute]
                        if (error != null) {
                            server.respond(event.central, event.requestId, error, event.offset, ByteArray(0))
                        } else {
                            val value = values[event.attribute] ?: ByteArray(0)
                            server.respond(
                                event.central, event.requestId, OperationStatus.Success, event.offset,
                                value.copyOfRange(minOf(event.offset, value.size), value.size),
                            )
                        }
                    }
                    is GattServerEvent.WriteRequested -> {
                        val error = failing[event.attribute]
                        when {
                            error != null && event.responseNeeded ->
                                server.respond(event.central, event.requestId, error, event.offset, ByteArray(0))
                            event.prepared -> {
                                queues.getOrPut(event.central) { mutableListOf() } += event
                                server.respond(event.central, event.requestId, OperationStatus.Success, event.offset, event.value)
                            }
                            else -> {
                                if (error == null) values[event.attribute] = event.value
                                if (event.responseNeeded) {
                                    server.respond(event.central, event.requestId, OperationStatus.Success, 0, ByteArray(0))
                                }
                            }
                        }
                    }
                    is GattServerEvent.ExecuteWrite -> {
                        val queue = queues.remove(event.central).orEmpty()
                        if (event.execute) {
                            queue.groupBy { it.attribute }.forEach { (attribute, parts) ->
                                values[attribute] = parts.sortedBy { it.offset }
                                    .fold(ByteArray(0)) { acc, part -> acc + part.value }
                            }
                        }
                        server.respond(event.central, event.requestId, OperationStatus.Success, 0, ByteArray(0))
                    }
                    is GattServerEvent.SubscriptionChanged -> Unit
                    is GattServerEvent.CentralDisconnected -> queues.remove(event.central)
                }
            }
        }
    }
}

/** Collects the updates received by a central. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.collectUpdates(central: MockCentral<*, *>): List<MockUpdate> {
    val list = mutableListOf<MockUpdate>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        central.updates.collect { list += it }
    }
    return list
}

fun bytes(size: Int, start: Int = 0) = ByteArray(size) { (start + it).toByte() }
