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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.server.exception.ClientNotConnectedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationGateTest {

    @Test
    fun `send returns the reported status`() = runTest {
        val gate = NotificationGate()
        var started = 0

        val result = async { gate.send(10.seconds) { started++ } }
        runCurrent()
        assertEquals(1, started)

        gate.onSent(0)
        assertEquals(0, result.await())
    }

    @Test
    fun `failed status is returned`() = runTest {
        val gate = NotificationGate()

        val result = async { gate.send(10.seconds) { } }
        runCurrent()
        gate.onSent(133)

        assertEquals(133, result.await())
    }

    @Test
    fun `notifications are sent one at a time in order`() = runTest {
        val gate = NotificationGate()
        val started = mutableListOf<Int>()

        val first = async { gate.send(10.seconds) { started += 1 } }
        val second = async { gate.send(10.seconds) { started += 2 } }
        val third = async { gate.send(10.seconds) { started += 3 } }
        runCurrent()
        assertEquals(listOf(1), started)

        gate.onSent(0)
        runCurrent()
        assertEquals(listOf(1, 2), started)

        gate.onSent(0)
        runCurrent()
        assertEquals(listOf(1, 2, 3), started)

        gate.onSent(0)
        assertEquals(listOf(0, 0, 0), listOf(first.await(), second.await(), third.await()))
    }

    @Test
    fun `send times out`() = runTest {
        val gate = NotificationGate()

        val result = async { gate.send(5.seconds) { } }
        runCurrent()
        advanceTimeBy(6.seconds)

        assertNull(result.await())
    }

    @Test
    fun `queue continues after timeout`() = runTest {
        val gate = NotificationGate()

        val first = async { gate.send(5.seconds) { } }
        val second = async { gate.send(5.seconds) { } }
        runCurrent()
        advanceTimeBy(6.seconds)
        assertNull(first.await())

        runCurrent()
        gate.onSent(0)
        assertEquals(0, second.await())
    }

    @Test
    fun `exception from start is thrown and does not block the queue`() = runTest {
        val gate = NotificationGate()

        assertFailsWith<IllegalStateException> {
            gate.send(10.seconds) { throw IllegalStateException() }
        }

        val result = async { gate.send(10.seconds) { } }
        runCurrent()
        gate.onSent(0)
        assertEquals(0, result.await())
    }

    @Test
    fun `close fails the pending and queued notifications`() = runTest {
        val gate = NotificationGate()

        val first = async { runCatching { gate.send(10.seconds) { } } }
        val second = async { runCatching { gate.send(10.seconds) { } } }
        runCurrent()

        gate.close(ClientNotConnectedException())

        assertTrue(first.await().exceptionOrNull() is ClientNotConnectedException)
        assertTrue(second.await().exceptionOrNull() is ClientNotConnectedException)
    }

    @Test
    fun `send after close fails`() = runTest {
        val gate = NotificationGate()
        gate.close(ClientNotConnectedException())

        assertFailsWith<ClientNotConnectedException> { gate.send(10.seconds) { } }
    }

    @Test
    fun `status without pending notification is ignored`() = runTest {
        val gate = NotificationGate()
        gate.onSent(0)

        val result = async { gate.send(10.seconds) { } }
        runCurrent()
        gate.onSent(7)
        assertEquals(7, result.await())
    }
}
