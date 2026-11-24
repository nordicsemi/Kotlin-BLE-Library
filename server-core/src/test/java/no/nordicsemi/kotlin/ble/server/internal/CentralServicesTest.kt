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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.server.WriteRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CentralServicesTest {

    @Test
    fun services_areEmptyBeforeSetup() = runTest {
        val fixture = Fixture(this)

        assertTrue(fixture.manager.services(fixture.central).value.isEmpty())
    }

    @Test
    fun services_haveTheSameStructureAsTheServicesOfTheServer() = runTest {
        val fixture = Fixture(this)
        val services = fixture.setup()

        val views = fixture.manager.services(fixture.central).value

        assertEquals(services.map { it.uuid }, views.map { it.uuid })
        assertEquals(services.single().characteristics.map { it.uuid }, views.single().characteristics.map { it.uuid })
        assertEquals(services.single().includedServices.map { it.uuid }, views.single().includedServices.map { it.uuid })
    }

    @Test
    fun services_areTheSameWhenAskedAgain() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()

        assertSame(fixture.manager.services(fixture.central), fixture.manager.services(fixture.central.identifier))
        assertSame(fixture.manager.services(fixture.central).value, fixture.manager.services(fixture.central).value)
    }

    @Test
    fun services_areReplacedWhenServerIsSetUpAgain() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()
        val before = fixture.manager.services(fixture.central).value

        val _ = fixture.setup()

        assertNotSame(before, fixture.manager.services(fixture.central).value)
    }

    @Test
    fun writes_areLimitedToTheCentral() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()
        val view = fixture.manager.services(fixture.central).value.single().characteristics.first { it.uuid == RW_UUID }
        val received = mutableListOf<WriteRequest>()
        backgroundScope.launch { view.writes.collect { received += it } }
        runCurrent()

        val _ = fixture.write(fixture.rw.instanceId, bytes(1), from = fixture.central)
        val _ = fixture.write(fixture.rw.instanceId, bytes(2), from = fixture.central2)
        val _ = fixture.write(fixture.rw.instanceId, bytes(3), from = fixture.central)

        assertContentEquals(listOf(1, 3), received.map { it.value.single().toInt() })
        assertTrue(received.all { it.central == fixture.central })
    }

    @Test
    fun descriptorWrites_areLimitedToTheCentral() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()
        val view = fixture.manager.services(fixture.central2).value.single().characteristics
            .first { it.uuid == RW_UUID }.descriptors.first { it.uuid == CUSTOM_DESCRIPTOR_UUID }
        val received = mutableListOf<WriteRequest>()
        backgroundScope.launch { view.writes.collect { received += it } }
        runCurrent()
        val descriptor = fixture.rw.descriptors.first { it.uuid == CUSTOM_DESCRIPTOR_UUID }

        val _ = fixture.write(descriptor.instanceId, bytes(1), from = fixture.central)
        val _ = fixture.write(descriptor.instanceId, bytes(2), from = fixture.central2)

        assertEquals(listOf(2), received.map { it.value.single().toInt() })
    }

    @Test
    fun subscribers_areLimitedToTheCentral() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()
        val view = fixture.manager.services(fixture.central).value.single().characteristics.first { it.uuid == NOTIFY_UUID }
        val other = fixture.manager.services(fixture.central2).value.single().characteristics.first { it.uuid == NOTIFY_UUID }

        fixture.subscribe(fixture.notify, from = fixture.central2)

        assertTrue(view.subscribers.value.isEmpty())
        assertEquals(setOf(fixture.central2), other.subscribers.value)
        assertEquals(setOf(fixture.central2), fixture.notify.subscribers.value)
    }

    @Test
    fun update_isSentToTheCentralOnly() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()
        fixture.subscribe(fixture.notify, from = fixture.central)
        fixture.subscribe(fixture.notify, from = fixture.central2)
        val view = fixture.manager.services(fixture.central2).value.single().characteristics.first { it.uuid == NOTIFY_UUID }

        view.update(bytes(5))

        assertEquals(listOf(fixture.central2), fixture.server.notifications.map { it.central })
    }

    @Test
    fun update_toAnotherCentralIsRejected() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()
        val view = fixture.manager.services(fixture.central).value.single().characteristics.first { it.uuid == NOTIFY_UUID }

        assertFailsWith<IllegalArgumentException> { view.update(bytes(1), to = fixture.central2) }
    }

    @Test
    fun values_areShared() = runTest {
        val fixture = Fixture(this)
        val _ = fixture.setup()
        val view = fixture.manager.services(fixture.central).value.single().characteristics.first { it.uuid == RW_UUID }

        view.setValue(bytes(7))

        assertContentEquals(bytes(7), fixture.rw.value.value)
        assertContentEquals(bytes(7), view.value.value)
    }
}
