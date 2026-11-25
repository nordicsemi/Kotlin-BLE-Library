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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.Profile
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheralServer
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import no.nordicsemi.kotlin.ble.server.LocalService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val LOCAL_SERVICE_UUID = Uuid.parse("00001802-0000-1000-8000-00805f9b34fb")
private val LOCAL_CHARACTERISTIC_UUID = Uuid.parse("00002a06-0000-1000-8000-00805f9b34fb")

/** A profile with a remote service and a local one. */
private class RemoteAndLocalProfile : Profile.WithLocalServices(
    requiredServiceUuids = listOf(GATT_SERVICE_UUID),
    localServiceUuids = listOf(LOCAL_SERVICE_UUID),
    name = "Test",
) {
    var remote: List<RemoteService>? = null
    var local: List<LocalService>? = null
    var initialized = false
    var unsupported = false
    var reject = false

    override fun prepare(
        target: no.nordicsemi.kotlin.ble.client.Peripheral<*, *>,
        remoteServices: List<RemoteService>,
        localServices: List<LocalService>,
    ) {
        require(!reject) { "Rejected" }
        remote = remoteServices
        local = localServices
    }

    override suspend fun CoroutineScope.initialize(target: no.nordicsemi.kotlin.ble.client.Peripheral<*, *>) {
        initialized = true
    }

    override suspend fun unsupported(target: no.nordicsemi.kotlin.ble.client.Peripheral<*, *>) {
        unsupported = true
    }
}

class ProfileTest {
    private val readable = setOf(CharacteristicProperty.READ)
    private val readPermission = setOf(Permission.READ)

    /** Defines the local service. */
    private suspend fun MockPeripheralServer.defineService() {
        val _ = setup {
            Service(LOCAL_SERVICE_UUID) {
                Characteristic(
                    LOCAL_CHARACTERISTIC_UUID,
                    CharacteristicProperty.WRITE_WITHOUT_RESPONSE and CharacteristicProperty.WRITE,
                    Permission.WRITE,
                )
            }
        }
    }

    /** A central manager with the given server, and a peripheral obtained from it. */
    private data class Setup(val manager: MockCentralManager, val peripheral: Peripheral)

    private fun TestScope.setUp(server: MockPeripheralServer?): Setup {
        val fixture = gattSingleCharacteristic(properties = readable, permissions = readPermission)
        val manager = CentralManager.mock(environment = LatestApi(), scope = backgroundScope, server = server)
            .apply { simulatePeripherals(listOf(fixture.spec)) }
        return Setup(manager, manager.getPeripheralById(fixture.spec.identifier)!!)
    }

    @Test
    fun `profile starts when the remote and local services are available`() = runTest {
        withTimeout(10.seconds) {
            val server = MockPeripheralServer(backgroundScope).apply { defineService() }
            val (manager, peripheral) = setUp(server)
            val profile = RemoteAndLocalProfile()
            peripheral.profile(backgroundScope, profile)

            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()

            assertTrue(profile.initialized)
            assertEquals(listOf(GATT_SERVICE_UUID), profile.remote?.map { it.uuid })
            assertEquals(listOf(LOCAL_SERVICE_UUID), profile.local?.map { it.uuid })
        }
    }

    @Test
    fun `profile waits for the local service`() = runTest {
        withTimeout(10.seconds) {
            val server = MockPeripheralServer(backgroundScope)
            val (manager, peripheral) = setUp(server)
            val profile = RemoteAndLocalProfile()
            peripheral.profile(backgroundScope, profile)

            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()
            assertFalse(profile.initialized)

            server.defineService()
            gattSettle()
            assertTrue(profile.initialized)
        }
    }

    @Test
    fun `profile rejecting the services is reported as unsupported`() = runTest {
        withTimeout(10.seconds) {
            val server = MockPeripheralServer(backgroundScope).apply { defineService() }
            val (manager, peripheral) = setUp(server)
            val profile = RemoteAndLocalProfile().apply { reject = true }
            peripheral.profile(backgroundScope, profile, required = false)

            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()

            assertFalse(profile.initialized)
            assertTrue(profile.unsupported)
            assertTrue(peripheral.isConnected)
        }
    }

    @Test
    fun `profile uses the server created by the manager`() = runTest {
        withTimeout(10.seconds) {
            val (manager, peripheral) = setUp(server = null)
            val profile = RemoteAndLocalProfile()
            peripheral.profile(backgroundScope, profile)

            manager.connect(peripheral, CentralManager.ConnectionOptions.Direct())
            gattSettle()
            assertFalse(profile.initialized)

            val _ = manager.mockServer.setup {
                Service(LOCAL_SERVICE_UUID) {
                    Characteristic(
                        LOCAL_CHARACTERISTIC_UUID,
                        CharacteristicProperty.WRITE_WITHOUT_RESPONSE and CharacteristicProperty.WRITE,
                        Permission.WRITE,
                    )
                }
            }
            gattSettle()
            assertTrue(profile.initialized)
        }
    }
}
