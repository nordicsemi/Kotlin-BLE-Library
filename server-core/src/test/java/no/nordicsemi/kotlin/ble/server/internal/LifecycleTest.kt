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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifecycleTest {

    @Test
    fun state_delegatesToServer() = runTest {
        val fixture = Fixture(this)

        assertEquals(Manager.State.POWERED_ON, fixture.manager.state.value)
        fixture.server.state.value = Manager.State.POWERED_OFF
        assertEquals(Manager.State.POWERED_OFF, fixture.manager.state.value)
    }

    @Test
    fun close_closesServer() = runTest {
        val fixture = Fixture(this)

        fixture.manager.close()

        assertEquals(1, fixture.server.closeCount)
    }

    @Test
    fun close_isIdempotent() = runTest {
        val fixture = Fixture(this)

        fixture.manager.close()
        fixture.manager.close()
        testScheduler.advanceUntilIdle()

        assertEquals(1, fixture.server.closeCount)
    }

    @Test
    fun close_invalidatesServices() = runTest {
        val fixture = Fixture(this)
        val services = fixture.setup()

        fixture.manager.close()

        assertNull(services[0].manager)
        assertNull(services[0].includedServices[0].manager)
        assertTrue(fixture.manager.services.value.isEmpty())
    }

    @Test
    fun close_clearsSubscribers() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)
        val notify = fixture.notify

        fixture.manager.close()

        assertTrue(notify.subscribers.value.isEmpty())
    }

    @Test
    fun close_stopsProcessingEvents() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val rw = fixture.rw
        fixture.manager.close()

        fixture.emit(GattServerEvent.ReadRequested(fixture.central, 1, rw.instanceId, 0))
        fixture.emit(GattServerEvent.WriteRequested(fixture.central, 2, rw.instanceId, 0, bytes(1), false, true))

        assertTrue(fixture.server.responses.isEmpty())
        assertTrue(rw.value.value.isEmpty())
    }

    @Test
    fun close_update_throws() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)
        val notify = fixture.notify
        fixture.manager.close()

        assertFailsWith<IllegalStateException> { notify.update(bytes(1)) }
    }

    @Test
    fun close_setupThrows() = runTest {
        val fixture = Fixture(this)
        fixture.manager.close()

        assertFailsWith<ManagerClosedException> { fixture.manager.setup { Service(SERVICE_UUID) } }
    }

    @Test
    fun setup_closedWhileSettingServices_throws() = runTest {
        val fixture = Fixture(this)
        val manager = fixture.manager
        // Close the manager from within the platform call.
        fixture.server.setServicesHook = { manager.close() }

        assertFailsWith<ManagerClosedException> { manager.setup { Service(SERVICE_UUID) } }
        assertTrue(manager.services.value.isEmpty())
    }

    @Test
    fun scopeCancelled_closesManager() = runTest {
        val job = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        val server = FakeGattServer()
        val manager = LocalServerImpl(scope, server)
        val services = manager.setup { Service(SERVICE_UUID) }
        testScheduler.runCurrent()

        scope.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(1, server.closeCount)
        assertNull(services[0].manager)
        assertFailsWith<ManagerClosedException> { manager.setup { Service(SERVICE_UUID) } }
    }

    @Test
    fun close_doesNotCancelTheParentScope() = runTest {
        val job = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        val manager = LocalServerImpl(scope, FakeGattServer())

        manager.close()
        testScheduler.advanceUntilIdle()

        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun events_areProcessedInOrder() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val order = mutableListOf<Int>()
        fixture.ro.onRead {
            kotlinx.coroutines.delay(100)
            order += 1
            no.nordicsemi.kotlin.ble.server.ReadResponse.Success(bytes(1))
        }
        fixture.rw.onWrite {
            order += 2
            no.nordicsemi.kotlin.ble.server.WriteResponse.Success
        }

        // The write is sent while the first request is still being handled.
        fixture.server.events.emit(GattServerEvent.ReadRequested(fixture.central, 1, fixture.ro.instanceId, 0))
        fixture.server.events.emit(
            GattServerEvent.WriteRequested(fixture.central, 2, fixture.rw.instanceId, 0, bytes(1), false, true)
        )
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()

        assertEquals(listOf(1, 2), order)
        assertEquals(listOf(1, 2), fixture.server.responses.map { it.requestId })
    }

    @Test
    fun eventsBeforeSetup_areRejected() = runTest {
        val fixture = Fixture(this)

        val response = fixture.write(1, bytes(1))

        assertEquals(OperationStatus.InvalidHandle, response?.status)
    }

    @Test
    fun serverRespondFailure_doesNotKillEventLoop() = runTest {
        val server = FakeGattServer()
        val manager = LocalServerImpl(backgroundScope, server)
        val central = FakeCentral("AA")
        server.respondError = IllegalStateException("Disconnected")

        server.events.emit(GattServerEvent.ReadRequested(central, 1, 1, 0))
        server.events.emit(GattServerEvent.ReadRequested(central, 2, 1, 0))
        testScheduler.runCurrent()

        assertEquals(listOf(2), server.responses.map { it.requestId })
        manager.close()
    }
}
