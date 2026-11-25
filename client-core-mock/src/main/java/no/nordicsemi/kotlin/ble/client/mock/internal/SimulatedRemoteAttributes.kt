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


package no.nordicsemi.kotlin.ble.client.mock.internal

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import no.nordicsemi.kotlin.ble.client.AnyRemoteService
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.RemoteIncludedService
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.internal.BaseRemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.internal.BaseRemoteDescriptor
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicRead
import no.nordicsemi.kotlin.ble.client.internal.CharacteristicWrite
import no.nordicsemi.kotlin.ble.client.internal.DescriptorRead
import no.nordicsemi.kotlin.ble.client.internal.DescriptorWrite
import no.nordicsemi.kotlin.ble.client.internal.OperationEvent
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.internal.CharacteristicDefinition
import no.nordicsemi.kotlin.ble.core.internal.DescriptorDefinition
import no.nordicsemi.kotlin.ble.core.internal.ServiceDefinition
import no.nordicsemi.kotlin.ble.server.mock.MockClient
import no.nordicsemi.kotlin.ble.server.mock.ReadResult
import no.nordicsemi.kotlin.ble.server.mock.WriteResult
import kotlin.uuid.Uuid

/*
 * The remote attributes of a GATT server that is used by a simulated client, see [MockClient].
 *
 * They implement the same API as the attributes of a peripheral, but the requests are sent to
 * the GATT server using the simulated client. All requests complete immediately: they do not
 * simulate the duration of the transfer.
 */

/** A service of the GATT server of the other device. */
internal class SimulatedRemoteService(
    client: MockClient<String>,
    service: ServiceDefinition,
    events: SharedFlow<GattEvent>,
): RemoteService() {
    override val uuid: Uuid = service.uuid
    override val instanceId: Int = service.instanceId

    override val characteristics: List<RemoteCharacteristic> = service.characteristics
        .map { SimulatedRemoteCharacteristic(this, client, it, events) }
    override val includedServices: List<RemoteIncludedService> = service.includedServices
        .map { SimulatedRemoteIncludedService(this, client, it, events) }

    override fun toString(): String = uuid.toString()
}

/** An included service of the GATT server of the other device. */
internal class SimulatedRemoteIncludedService(
    parent: AnyRemoteService,
    client: MockClient<String>,
    service: ServiceDefinition,
    events: SharedFlow<GattEvent>,
): RemoteIncludedService {
    override val service: AnyRemoteService = parent
    override val uuid: Uuid = service.uuid
    override val instanceId: Int = service.instanceId

    override val characteristics: List<RemoteCharacteristic> = service.characteristics
        .map { SimulatedRemoteCharacteristic(this, client, it, events) }
    override val includedServices: List<RemoteIncludedService> = service.includedServices
        .map { SimulatedRemoteIncludedService(this, client, it, events) }

    override fun toString(): String = uuid.toString()
}

/** A characteristic of the GATT server of the other device. */
internal class SimulatedRemoteCharacteristic(
    parent: AnyRemoteService,
    private val client: MockClient<String>,
    characteristic: CharacteristicDefinition,
    events: SharedFlow<GattEvent>,
): BaseRemoteCharacteristic(parent, events) {
    override val uuid: Uuid = characteristic.uuid
    override val instanceId: Int = characteristic.instanceId
    override val properties: Set<CharacteristicProperty> = characteristic.properties
    override val descriptors: List<RemoteDescriptor> = characteristic.descriptors
        .map { SimulatedRemoteDescriptor(this, client, it, events) }

    override fun setCharacteristicNotification(enabled: Boolean) {
        // no-op: the notifications are enabled by writing the Client Characteristic Configuration.
    }

    override suspend fun FlowCollector<GattEvent>.executeRead() {
        check(client.isConnected) { "Not connected" }

        val (value, status) = when (val result = client.readLong(instanceId)) {
            is ReadResult.Success -> result.value to OperationStatus.Success
            is ReadResult.Failure -> byteArrayOf() to result.status
        }
        emit(CharacteristicRead(this@SimulatedRemoteCharacteristic, value, status))
    }

    override suspend fun FlowCollector<GattEvent>.executeWrite(data: ByteArray, writeType: WriteType) {
        check(client.isConnected) { "Not connected" }

        // Bluetooth Core Specification 6.2, Vol 3 (Host), Part F (ATT), 3.2.9. Long attribute values:
        // "The maximum length of an attribute value shall be 512 octets."
        val truncated = data.take(512).toByteArray()
        val mtu = client.attMtu.value
        val status = when (writeType) {
            WriteType.WITH_RESPONSE -> {
                // Long Write is used automatically when the data does not fit in a single request.
                val result = if (truncated.size > mtu - 3) {
                    client.writeLong(instanceId, truncated)
                } else {
                    client.write(instanceId, truncated, withResponse = true)
                }
                result.status()
            }
            // There is no response to a write command.
            WriteType.WITHOUT_RESPONSE,
            WriteType.SIGNED -> {
                val extra = if (writeType == WriteType.SIGNED) 12 else 3
                val result = client.write(instanceId, truncated.take(mtu - extra).toByteArray(), withResponse = false)
                result.status()
            }
        }
        emit(CharacteristicWrite(this@SimulatedRemoteCharacteristic, status))
    }

    override fun OperationEvent.matches(): Boolean = when (subject) {
        // The subject of read and write events is the characteristic itself.
        is SimulatedRemoteCharacteristic -> subject == this@SimulatedRemoteCharacteristic
        // The subject of notifications is the instance ID of the characteristic.
        is Int -> subject == instanceId
        else -> error("Unknown subject type: ${subject::class}")
    }
}

/** A descriptor of the GATT server of the other device. */
internal class SimulatedRemoteDescriptor(
    parent: RemoteCharacteristic,
    private val client: MockClient<String>,
    descriptor: DescriptorDefinition,
    events: SharedFlow<GattEvent>,
): BaseRemoteDescriptor(parent, events) {
    override val uuid: Uuid = descriptor.uuid
    override val instanceId: Int = descriptor.instanceId

    override suspend fun FlowCollector<GattEvent>.executeRead() {
        check(client.isConnected) { "Not connected" }

        val (value, status) = when (val result = client.readLong(instanceId)) {
            is ReadResult.Success -> result.value to OperationStatus.Success
            is ReadResult.Failure -> byteArrayOf() to result.status
        }
        emit(DescriptorRead(this@SimulatedRemoteDescriptor, value, status))
    }

    override suspend fun FlowCollector<GattEvent>.executeWrite(data: ByteArray) {
        check(client.isConnected) { "Not connected" }

        val truncated = data.take(512).toByteArray()
        val result = if (truncated.size > client.attMtu.value - 3) {
            client.writeLong(instanceId, truncated)
        } else {
            client.write(instanceId, truncated, withResponse = true)
        }
        emit(DescriptorWrite(this@SimulatedRemoteDescriptor, result.status()))
    }

    override fun OperationEvent.matches(): Boolean = subject == this@SimulatedRemoteDescriptor
}

private fun WriteResult.status(): OperationStatus = when (this) {
    WriteResult.Success -> OperationStatus.Success
    is WriteResult.Failure -> status
}
