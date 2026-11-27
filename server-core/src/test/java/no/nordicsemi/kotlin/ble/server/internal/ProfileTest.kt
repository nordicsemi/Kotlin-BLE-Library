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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.Profile
import no.nordicsemi.kotlin.ble.server.LocalServer
import no.nordicsemi.kotlin.ble.server.LocalService
import no.nordicsemi.kotlin.ble.server.profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import no.nordicsemi.kotlin.ble.server.LocalProfile as ServerProfile

private class LocalProfile(
    uuid: Uuid,
): Profile<LocalServer<String>, Nothing, LocalService>(
    localServiceUuids = listOf(uuid),
) {
    var prepared = 0
    var services: List<LocalService>? = null
    var initialized = 0
    var cancelled = 0
    var unsupported = 0
    var reject = false

    override fun prepare(
        target: LocalServer<String>,
        remoteServices: List<Nothing>,
        localServices: List<LocalService>,
    ) {
        require(!reject)
        prepared++
        services = localServices
    }

    override suspend fun CoroutineScope.initialize(target: LocalServer<String>) {
        initialized++
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            cancelled++
        }
    }

    override suspend fun unsupported(target: LocalServer<String>) {
        unsupported++
    }
}

class ProfileTest {

    @Test
    fun `profile waits for the services`() = runTest {
        val fixture = Fixture(this)
        val profile = LocalProfile(SERVICE_UUID)
        fixture.manager.profile(backgroundScope, profile)
        runCurrent()
        assertEquals(0, profile.initialized)

        val _ = fixture.setup()
        runCurrent()

        assertEquals(1, profile.initialized)
        assertEquals(listOf(SERVICE_UUID), profile.services?.map { it.uuid })
    }

    @Test
    fun `profile is restarted when the services are replaced`() = runTest {
        val fixture = Fixture(this)
        val profile = LocalProfile(SERVICE_UUID)
        val _ = fixture.setup()
        fixture.manager.profile(backgroundScope, profile)
        runCurrent()
        assertEquals(1, profile.initialized)

        val _ = fixture.setup()
        runCurrent()

        assertEquals(2, profile.initialized)
        assertEquals(1, profile.cancelled)
    }

    @Test
    fun `profile is not started without its service`() = runTest {
        val fixture = Fixture(this)
        val profile = LocalProfile(Uuid.random())
        val _ = fixture.setup()
        fixture.manager.profile(backgroundScope, profile)
        runCurrent()

        assertEquals(0, profile.initialized)
        assertEquals(0, profile.unsupported)
    }

    @Test
    fun `profile rejecting the services is unsupported`() = runTest {
        val fixture = Fixture(this)
        val profile = LocalProfile(SERVICE_UUID).apply { reject = true }
        val _ = fixture.setup()
        fixture.manager.profile(backgroundScope, profile)
        runCurrent()

        assertEquals(0, profile.initialized)
        assertEquals(1, profile.unsupported)
    }

    @Test
    fun `profile is cancelled with the job`() = runTest {
        val fixture = Fixture(this)
        val profile = LocalProfile(SERVICE_UUID)
        val _ = fixture.setup()
        val job = fixture.manager.profile(backgroundScope, profile)
        runCurrent()
        assertTrue(job.isActive)

        job.cancel()
        runCurrent()

        assertFalse(job.isActive)
        assertEquals(1, profile.cancelled)
    }

    private class SimpleProfile(var reject: Boolean = false): ServerProfile.Simple(SERVICE_UUID) {
        var characteristicUuid: Uuid? = null
        var initialized = 0
        var unsupported = 0

        override fun prepare(server: LocalServer<*>, service: LocalService) {
            // Throws NoSuchElementException when missing, which rejects the service.
            characteristicUuid = service.characteristics.first { it.uuid == if (reject) NONE_UUID else RW_UUID }.uuid
            if (reject) throw NoSuchElementException()
        }

        override suspend fun CoroutineScope.initialize(target: LocalServer<*>) {
            initialized++
        }

        override suspend fun unsupported(target: LocalServer<*>) {
            unsupported++
        }
    }

    private class Multi: ServerProfile.MultiService(listOf(SERVICE_UUID)) {
        var services: List<LocalService>? = null

        override fun prepare(server: LocalServer<*>, services: List<LocalService>) {
            this.services = services
        }

        override suspend fun CoroutineScope.initialize(target: LocalServer<*>) {}
    }

    @Test
    fun `simple profile is given its service`() = runTest {
        val fixture = Fixture(this)
        val profile = SimpleProfile()
        fixture.manager.profile(backgroundScope, profile)
        runCurrent()
        assertEquals(0, profile.initialized)

        val _ = fixture.setup()
        runCurrent()

        assertEquals(1, profile.initialized)
        assertEquals(RW_UUID, profile.characteristicUuid)
    }

    @Test
    fun `simple profile rejecting the service is unsupported`() = runTest {
        val fixture = Fixture(this)
        val profile = SimpleProfile(reject = true)
        val _ = fixture.setup()
        fixture.manager.profile(backgroundScope, profile)
        runCurrent()

        assertEquals(0, profile.initialized)
        assertEquals(1, profile.unsupported)
    }

    @Test
    fun `multi service profile is given its services`() = runTest {
        val fixture = Fixture(this)
        val profile = Multi()
        assertNull(profile.services)
        val _ = fixture.setup()
        fixture.manager.profile(backgroundScope, profile)
        runCurrent()

        assertEquals(listOf(SERVICE_UUID), profile.services?.map { it.uuid })
    }
}
