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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.core.log.Layer
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment
import no.nordicsemi.kotlin.ble.server.android.Client
import no.nordicsemi.kotlin.ble.server.android.LocalServer
import no.nordicsemi.kotlin.ble.server.internal.LocalServerImpl
import no.nordicsemi.kotlin.log.Log

/**
 * A native implementation of [LocalServer] for Android.
 *
 * All the logic is in the [LocalServerImpl]. This class adds the list of connected clients
 * and passes the logger to the [NativeGattServer].
 *
 * @param scope The coroutine scope.
 * @param nativeServer The native GATT server.
 */
internal class NativeLocalServerImpl private constructor(
    scope: CoroutineScope,
    private val nativeServer: NativeGattServer,
): LocalServerImpl<String, Client>(scope, nativeServer), LocalServer {

    /**
     * Creates the manager.
     *
     * @param scope The coroutine scope.
     * @param environment Native Android environment object.
     */
    constructor(
        scope: CoroutineScope,
        environment: NativeAndroidEnvironment,
    ): this(scope, NativeGattServer(scope, environment))

    override var logger: Log.Sink<Layer>? = Log.Sink.Null
        set(value) {
            field = value
            nativeServer.logger = value
        }

    override val connectedClients: StateFlow<List<Client>>
        get() = nativeServer.connectedClients

    init {
        // The setter is not called for the initial value.
        nativeServer.logger = logger
    }
}
