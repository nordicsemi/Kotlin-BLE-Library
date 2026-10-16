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

package no.nordicsemi.kotlin.ble.client.android.mock

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.BondResult
import no.nordicsemi.kotlin.ble.client.mock.ConnectionResult
import no.nordicsemi.kotlin.ble.client.mock.MockServerScope
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.PrepareWriteResponse
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.client.mock.ServiceDiscoveryResult
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteDescriptor
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.uuid.Uuid

// Test support for the Gatt*Test classes. Everything here is prefixed with "Gatt" / "GATT_".

internal const val GATT_ADDRESS = "AA:BB:CC:DD:EE:01"

internal val GATT_SERVICE_UUID: Uuid = Uuid.fromShortUuid(0xA000)
internal val GATT_CHAR_UUID: Uuid = Uuid.fromShortUuid(0xA001)
internal val GATT_CHAR_2_UUID: Uuid = Uuid.fromShortUuid(0xA002)
internal val GATT_DESCRIPTOR_UUID: Uuid = Uuid.fromShortUuid(0xA0D1)

/**
 * A configurable and recording event handler of the simulated peripheral.
 *
 * Handlers are lambdas that tests may replace; every request is also recorded.
 */
internal class GattHandler : PeripheralSpecEventHandler {
    var readHandler: (MockRemoteCharacteristic) -> ReadResponse =
        { ReadResponse.Success(byteArrayOf()) }
    var descriptorReadHandler: (MockRemoteDescriptor) -> ReadResponse =
        { ReadResponse.Success(byteArrayOf()) }
    var writeHandler: (MockRemoteCharacteristic, ByteArray) -> WriteResponse =
        { _, _ -> WriteResponse.Success }
    var commandHandler: (MockRemoteCharacteristic, ByteArray) -> Unit = { _, _ -> }
    var descriptorWriteHandler: (MockRemoteDescriptor, ByteArray) -> WriteResponse =
        { _, _ -> WriteResponse.Success }
    var prepareHandler: (MockRemoteCharacteristic, ByteArray) -> PrepareWriteResponse =
        { _, value -> PrepareWriteResponse.Success(value) }
    var descriptorPrepareHandler: (MockRemoteDescriptor, ByteArray) -> PrepareWriteResponse =
        { _, value -> PrepareWriteResponse.Success(value) }
    var executeHandler: (Boolean) -> WriteResponse = { WriteResponse.Success }
    var discoveryResult: ServiceDiscoveryResult = ServiceDiscoveryResult.Success
    var bondResult: BondResult = BondResult.Accept

    val reads = mutableListOf<Uuid>()
    val descriptorReads = mutableListOf<Uuid>()
    val writeRequests = mutableListOf<ByteArray>()
    val writeCommands = mutableListOf<ByteArray>()
    val prepareWrites = mutableListOf<ByteArray>()
    val descriptorWrites = mutableListOf<Pair<Uuid, ByteArray>>()
    val descriptorPrepareWrites = mutableListOf<Pair<Uuid, ByteArray>>()
    val executeWrites = mutableListOf<Boolean>()
    val discoveryRequests = mutableListOf<List<Uuid>>()
    var bondRequests = 0

    override fun onConnectionRequest(phy: PrimaryPhy) = ConnectionResult.Accept

    override fun onBondRequest(): BondResult {
        bondRequests++
        return bondResult
    }

    override fun onServiceDiscoveryRequest(uuids: List<Uuid>): ServiceDiscoveryResult {
        discoveryRequests += uuids
        return discoveryResult
    }

    override fun onReadRequest(characteristic: MockRemoteCharacteristic): ReadResponse {
        reads += characteristic.uuid
        return readHandler(characteristic)
    }

    override fun onReadRequest(descriptor: MockRemoteDescriptor): ReadResponse {
        descriptorReads += descriptor.uuid
        return descriptorReadHandler(descriptor)
    }

    override fun onWriteRequest(characteristic: MockRemoteCharacteristic, value: ByteArray): WriteResponse {
        writeRequests += value
        return writeHandler(characteristic, value)
    }

    override fun onWriteCommand(characteristic: MockRemoteCharacteristic, value: ByteArray) {
        writeCommands += value
        commandHandler(characteristic, value)
    }

    override fun onPrepareWriteRequest(characteristic: MockRemoteCharacteristic, value: ByteArray): PrepareWriteResponse {
        prepareWrites += value
        return prepareHandler(characteristic, value)
    }

    override fun onWriteRequest(descriptor: MockRemoteDescriptor, value: ByteArray): WriteResponse {
        descriptorWrites += descriptor.uuid to value
        return descriptorWriteHandler(descriptor, value)
    }

    override fun onPrepareWriteRequest(descriptor: MockRemoteDescriptor, value: ByteArray): PrepareWriteResponse {
        descriptorPrepareWrites += descriptor.uuid to value
        return descriptorPrepareHandler(descriptor, value)
    }

    override fun onExecuteWriteRequest(execute: Boolean): WriteResponse {
        executeWrites += execute
        return executeHandler(execute)
    }
}

/** A simulated peripheral together with the mock central manager that sees it. */
internal class GattFixture(
    val spec: PeripheralSpec<String>,
    val manager: MockCentralManager,
    val handler: GattHandler,
) {
    lateinit var peripheral: Peripheral

    /** Connects to the peripheral (or reconnects, using a fresh [Peripheral] instance). */
    @IgnorableReturnValue
    suspend fun connect(): Peripheral {
        peripheral = manager.getPeripheralById(spec.identifier)!!
        manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
        return peripheral
    }

    /** Connects (if needed) and returns the discovered services. */
    suspend fun discover(): List<RemoteService> {
        if (!::peripheral.isInitialized || !peripheral.isConnected) connect()
        return peripheral.services()
            .filterIsInstance<RemoteServices.Discovered>()
            .first()
            .services
    }

    /** Discovers services and returns the first characteristic with the given UUID. */
    suspend fun characteristic(
        uuid: Uuid = GATT_CHAR_UUID,
        serviceUuid: Uuid = GATT_SERVICE_UUID,
    ): RemoteCharacteristic = discover()
        .first { it.uuid == serviceUuid }
        .characteristics
        .first { it.uuid == uuid }

    /** Discovers services and returns the first descriptor with the given UUID. */
    suspend fun descriptor(
        uuid: Uuid = GATT_DESCRIPTOR_UUID,
        characteristicUuid: Uuid = GATT_CHAR_UUID,
    ): RemoteDescriptor = characteristic(characteristicUuid)
        .descriptors
        .first { it.uuid == uuid }
}

/**
 * Creates a connectable simulated peripheral. With a default [maxAttMtu] of 23 the largest
 * single-packet write is 20 bytes (MTU - 3) and the largest single-packet read is 22 (MTU - 1).
 *
 * If [bonded], the peripheral is already bonded and has its services cached. [peripheralHasBond]
 * tells whether the peripheral stores the bond too.
 */
internal fun TestScope.gattFixture(
    handler: GattHandler = GattHandler(),
    maxAttMtu: Int = 23,
    bonded: Boolean = false,
    peripheralHasBond: Boolean = bonded,
    environment: MockAndroidEnvironment = LatestApi(),
    services: MockServerScope.() -> Unit,
): GattFixture {
    val spec = PeripheralSpec.simulatePeripheral(
        identifier = GATT_ADDRESS,
        // Android assumes PUBLIC address type for devices that weren't scanned.
        addressType = AddressType.PUBLIC,
    ) {
        advertising {
            CompleteLocalName("Gatt")
        }
        // Note:
        // The below are NOT THE SAME METHOD with different isBonded value.
        // The first one makes the device known (scanned before), while the other not.
        // DO NOT MERGE it into a single call with isBonded = bonded.
        if (bonded || peripheralHasBond) {
            connectable(
                name = "Gatt",
                maxAttMtu = maxAttMtu,
                isBonded = bonded,
                peripheralHasBond = peripheralHasBond,
                eventHandler = handler,
                cachedServices = services,
            )
        } else {
            connectable(
                name = "Gatt",
                maxAttMtu = maxAttMtu,
                eventHandler = handler,
                services = services,
            )
        }
    }
    val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        .apply { simulatePeripherals(listOf(spec)) }
    return GattFixture(spec, manager, handler)
}

/** Creates a peripheral with a single service with a single characteristic. */
internal fun TestScope.gattSingleCharacteristic(
    handler: GattHandler = GattHandler(),
    maxAttMtu: Int = 23,
    bonded: Boolean = false,
    peripheralHasBond: Boolean = bonded,
    environment: MockAndroidEnvironment = LatestApi(),
    properties: Set<no.nordicsemi.kotlin.ble.core.CharacteristicProperty>,
    permissions: Set<no.nordicsemi.kotlin.ble.core.Permission>,
): GattFixture = gattFixture(handler, maxAttMtu, bonded, peripheralHasBond, environment) {
    Service(GATT_SERVICE_UUID) {
        Characteristic(GATT_CHAR_UUID, properties, permissions)
    }
}

/** Returns [size] bytes with predictable content. */
internal fun gattBytes(size: Int, seed: Int = 0): ByteArray = ByteArray(size) { (it + seed).toByte() }


/**
 * Lets the simulation work that is already due run.
 *
 * Not `advanceUntilIdle()`: the simulated peripheral keeps advertising forever, so the scheduler
 * is never idle and virtual time would run away.
 */
internal suspend fun TestScope.gattSettle() {
    advanceTimeBy(1.seconds)
    runCurrent()
}
