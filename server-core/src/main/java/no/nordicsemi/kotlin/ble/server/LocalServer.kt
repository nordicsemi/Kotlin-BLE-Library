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

@file:Suppress("unused")

package no.nordicsemi.kotlin.ble.server

import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.ServerScope

/**
 * The GATT server of this device, which serves the connected centrals.
 *
 * Define the services using [setup]. They are shared by all centrals: each request carries the
 * [Central] that sent it. Advertising is not a part of the local server, use the advertiser module.
 *
 * Sample code:
 * ```kotlin
 * val services = localServer.setup {
 *     Service(SERVICE_UUID) {
 *         Characteristic(LED_UUID, CharacteristicProperty.READ and CharacteristicProperty.WRITE,
 *                        Permission.READ and Permission.WRITE)
 *         Characteristic(BUTTON_UUID, CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
 *                        Permission.READ)
 *     }
 * }
 * val led = services[0].characteristics.first { it.uuid == LED_UUID }
 * led.onWrite { if (value.size == 1) WriteResponse.Success else WriteResponse.Failure(OperationStatus.InvalidAttributeLength) }
 * led.writes.onEach { println("LED: ${it.value.first()}") }.launchIn(scope)
 * ```
 *
 * @param ID The type of the central identifier.
 * @param C The type of the central.
 */
interface LocalServer<ID: Any, C: Central<ID>>: Manager {

    /**
     * The services currently hosted by the local GATT server.
     */
    val services: StateFlow<List<LocalService<C>>>

    /**
     * Defines the services of the local GATT server using the [ServerScope] DSL and makes them
     * available to centrals.
     *
     * Calling it again replaces the previous services, which are then invalidated
     * ([AnyLocalService.manager] becomes `null`).
     *
     * Handlers and collectors of [LocalCharacteristic.writes] should be set up right after this
     * returns. Until then, reads return an empty value and writes are accepted and stored.
     *
     * @param builder The services definition.
     * @return The services that have been added.
     * @throws no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException if the manager is closed.
     * @throws SecurityException if the required permission is denied.
     * @throws no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException if Bluetooth is
     * not available.
     */
    suspend fun setup(builder: ServerScope.() -> Unit): List<LocalService<C>>

    companion object Factory
}
