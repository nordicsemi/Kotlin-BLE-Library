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
package no.nordicsemi.kotlin.ble.server.android.mock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.ble.server.android.LocalCharacteristic
import no.nordicsemi.kotlin.ble.server.android.LocalService
import no.nordicsemi.kotlin.ble.server.android.LocalServer
import no.nordicsemi.kotlin.ble.server.mock.MockUpdate
import kotlin.uuid.Uuid

val SERVICE_UUID = Uuid.parse("0000aaa0-0000-1000-8000-00805f9b34fb")
val RW_UUID = Uuid.parse("0000aaa1-0000-1000-8000-00805f9b34fb")
val NOTIFY_UUID = Uuid.parse("0000aaa2-0000-1000-8000-00805f9b34fb")
val INDICATE_UUID = Uuid.parse("0000aaa3-0000-1000-8000-00805f9b34fb")

fun TestScope.mockManager(
    environment: MockAndroidEnvironment = MockAndroidEnvironment.Api31(),
): MockLocalServer = LocalServer.mock(environment, backgroundScope)

/** Defines a service with a read/write, a notifying and an indicating characteristic. */
suspend fun MockLocalServer.defineServices(): LocalService = setup {
    Service(SERVICE_UUID) {
        Characteristic(
            RW_UUID,
            CharacteristicProperty.READ and CharacteristicProperty.WRITE and CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
            Permission.READ and Permission.WRITE,
        )
        Characteristic(
            NOTIFY_UUID,
            CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
            Permission.READ,
        )
        Characteristic(
            INDICATE_UUID,
            CharacteristicProperty.INDICATE,
            Permission.READ,
        )
    }
}.first()

fun LocalService.characteristic(uuid: Uuid): LocalCharacteristic = characteristics.first { it.uuid == uuid }

/** Collects the updates received by a central. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.collectUpdates(central: MockAndroidCentral): List<MockUpdate> {
    val list = mutableListOf<MockUpdate>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        central.updates.collect { list += it }
    }
    return list
}

fun bytes(size: Int, start: Int = 0) = ByteArray(size) { (start + it).toByte() }
