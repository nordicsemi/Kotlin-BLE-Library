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

package no.nordicsemi.kotlin.ble.client.android.mock

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheralServer
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.SimulatedCentral
import no.nordicsemi.kotlin.ble.core.ServerScope
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.ble.server.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.LocalDescriptor
import no.nordicsemi.kotlin.ble.server.LocalService
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Test support for the Hosted*Test classes: peripherals with a hosted GATT server.

internal const val HOSTED_ADDRESS = "AA:BB:CC:DD:EE:02"

internal val HOSTED_SERVICE_UUID: Uuid = Uuid.fromShortUuid(0xB000)
internal val HOSTED_CHAR_UUID: Uuid = Uuid.fromShortUuid(0xB001)
internal val HOSTED_CHAR_2_UUID: Uuid = Uuid.fromShortUuid(0xB002)
internal val HOSTED_DESCRIPTOR_UUID: Uuid = Uuid.fromShortUuid(0xB0D1)

/**
 * A simulated peripheral with a hosted GATT server, together with the mock central manager that
 * sees it.
 */
internal class HostedFixture(
    val spec: PeripheralSpec<String>,
    val manager: MockCentralManager,
    val server: MockPeripheralServer,
    val services: List<LocalService<SimulatedCentral>>,
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
    suspend fun remote(
        uuid: Uuid = HOSTED_CHAR_UUID,
        serviceUuid: Uuid = HOSTED_SERVICE_UUID,
    ): RemoteCharacteristic = discover()
        .first { it.uuid == serviceUuid }
        .characteristics
        .first { it.uuid == uuid }

    /** Discovers services and returns the first descriptor with the given UUID. */
    suspend fun remoteDescriptor(
        uuid: Uuid = HOSTED_DESCRIPTOR_UUID,
        characteristicUuid: Uuid = HOSTED_CHAR_UUID,
    ): RemoteDescriptor = remote(characteristicUuid)
        .descriptors
        .first { it.uuid == uuid }

    /** The local characteristic hosted by the server. */
    fun local(
        uuid: Uuid = HOSTED_CHAR_UUID,
        serviceUuid: Uuid = HOSTED_SERVICE_UUID,
    ): LocalCharacteristic<SimulatedCentral> = services
        .first { it.uuid == serviceUuid }
        .characteristics
        .first { it.uuid == uuid }

    /** The local descriptor hosted by the server. */
    fun localDescriptor(
        uuid: Uuid = HOSTED_DESCRIPTOR_UUID,
        characteristicUuid: Uuid = HOSTED_CHAR_UUID,
    ): LocalDescriptor<SimulatedCentral> = local(characteristicUuid)
        .descriptors
        .first { it.uuid == uuid }
}

/**
 * Creates a connectable simulated peripheral with a hosted GATT server, defined in [services].
 *
 * The server runs in the background scope of the test, with the mock central manager.
 */
internal suspend fun TestScope.hostedFixture(
    maxAttMtu: Int = 23,
    bonded: Boolean = false,
    environment: MockAndroidEnvironment = LatestApi(),
    eventHandler: PeripheralSpecEventHandler = object : PeripheralSpecEventHandler {},
    services: ServerScope.() -> Unit,
): HostedFixture {
    val server = MockPeripheralServer(backgroundScope)
    val localServices = server.setup(services)
    val spec = PeripheralSpec.simulatePeripheral(
        identifier = HOSTED_ADDRESS,
        // Android assumes PUBLIC address type for devices that weren't scanned.
        addressType = AddressType.PUBLIC,
    ) {
        advertising {
            CompleteLocalName("Hosted")
        }
        connectable(
            name = "Hosted",
            maxAttMtu = maxAttMtu,
            isBonded = bonded,
            isServiceCached = bonded,
            eventHandler = eventHandler,
            server = server,
        )
    }
    val manager = CentralManager.mock(environment = environment, scope = backgroundScope)
        .apply { simulatePeripherals(listOf(spec)) }
    return HostedFixture(spec, manager, server, localServices)
}

/** Lets the simulation work that is already due run, see [gattSettle]. */
internal suspend fun TestScope.hostedSettle() {
    advanceTimeBy(1.seconds)
    runCurrent()
}
