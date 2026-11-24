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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateTest {

    @Test
    fun update_notifiesAllSubscribers() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify, 1, fixture.central)
        fixture.subscribe(fixture.notify, 1, fixture.central2)

        fixture.notify.update(bytes(1, 2, 3))

        val notifications = fixture.server.notifications
        assertEquals(2, notifications.size)
        assertEquals(setOf(fixture.central, fixture.central2), notifications.map { it.central }.toSet())
        notifications.forEach {
            assertContentEquals(bytes(1, 2, 3), it.value)
            assertEquals(fixture.notify.instanceId, it.attribute)
            assertFalse(it.confirm)
        }
    }

    @Test
    fun update_notifiesOnlyTheGivenCentral() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify, 1, fixture.central)
        fixture.subscribe(fixture.notify, 1, fixture.central2)

        fixture.manager.services(fixture.central2).value.single().characteristics.first { it.uuid == fixture.notify.uuid }.update(bytes(4))

        val notification = fixture.server.notifications.single()
        assertEquals(fixture.central2, notification.central)
    }

    @Test
    fun update_storesValue() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)

        fixture.notify.update(bytes(5, 6))

        assertContentEquals(bytes(5, 6), fixture.notify.value.value)
        assertContentEquals(bytes(5, 6), fixture.read(fixture.notify.instanceId).value)
    }

    @Test
    fun update_indicateOnlyUsesIndications() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.indicate, 2)

        fixture.indicate.update(bytes(1))

        assertTrue(fixture.server.notifications.single().confirm)
    }

    @Test
    fun update_notifyAndIndicateUsesNotification() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.both, 2)

        fixture.both.update(bytes(1))

        // NOTIFY takes precedence.
        assertFalse(fixture.server.notifications.single().confirm)
    }

    @Test
    fun update_noSubscribers_storesValueOnly() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        fixture.notify.update(bytes(1, 2))

        assertTrue(fixture.server.notifications.isEmpty())
        assertContentEquals(bytes(1, 2), fixture.notify.value.value)
    }

    @Test
    fun update_afterUnsubscribe_doesNotNotify() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)
        val _ = fixture.writeCccd(fixture.notify, 0)

        fixture.notify.update(bytes(1))

        assertTrue(fixture.server.notifications.isEmpty())
    }

    @Test
    fun update_afterDisconnect_doesNotNotify() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)
        fixture.emit(GattServerEvent.CentralDisconnected(fixture.central))

        fixture.notify.update(bytes(1))

        assertTrue(fixture.server.notifications.isEmpty())
    }

    @Test
    fun update_notSubscribedCentral_throws() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify, 1, fixture.central)

        assertFailsWith<IllegalStateException> { fixture.manager.services(fixture.central2).value.single().characteristics.first { it.uuid == fixture.notify.uuid }.update(bytes(1)) }
        assertTrue(fixture.server.notifications.isEmpty())
    }

    @Test
    fun update_unsupportedCharacteristic_throws() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }

        assertFailsWith<IllegalStateException> { fixture.ro.update(bytes(1)) }
        assertFailsWith<IllegalStateException> { fixture.manager.services(fixture.central).value.single().characteristics.first { it.uuid == fixture.rw.uuid }.update(bytes(1)) }
        assertTrue(fixture.ro.value.value.isEmpty())
    }

    @Test
    fun update_tooLongForCentral_throws() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)

        assertFailsWith<IllegalArgumentException> { fixture.notify.update(ByteArray(21)) }
        assertTrue(fixture.server.notifications.isEmpty())
        // The value is not changed.
        assertTrue(fixture.notify.value.value.isEmpty())
    }

    @Test
    fun update_maxLengthForCentral_accepted() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)

        fixture.notify.update(ByteArray(20))

        assertEquals(20, fixture.server.notifications.single().value.size)
    }

    @Test
    fun update_tooLongForOneOfCentrals_throwsAndDoesNotNotifyAny() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.central2.maximumUpdateValueLength = 10
        fixture.subscribe(fixture.notify, 1, fixture.central)
        fixture.subscribe(fixture.notify, 1, fixture.central2)

        assertFailsWith<IllegalArgumentException> { fixture.notify.update(ByteArray(15)) }
        assertTrue(fixture.server.notifications.isEmpty())

        // But it can be sent to the one with a larger MTU.
        fixture.manager.services(fixture.central).value.single().characteristics.first { it.uuid == fixture.notify.uuid }.update(ByteArray(15))
        assertEquals(fixture.central, fixture.server.notifications.single().central)
    }

    @Test
    fun update_largerThan512_throws() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.central.maximumUpdateValueLength = 1000
        fixture.subscribe(fixture.notify)

        assertFailsWith<IllegalArgumentException> { fixture.notify.update(ByteArray(513)) }
    }

    @Test
    fun update_usesCurrentMaximumUpdateValueLength() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify)
        fixture.central.maximumUpdateValueLength = 100

        fixture.notify.update(ByteArray(100))

        assertEquals(100, fixture.server.notifications.single().value.size)
    }

    @Test
    fun update_targetsAreNotifiedConcurrently() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify, 1, fixture.central)
        fixture.subscribe(fixture.notify, 1, fixture.central2)
        val gate = CompletableDeferred<Unit>()
        var started = 0
        fixture.server.onNotify = { started++; gate.await() }

        val job = launch { fixture.notify.update(bytes(1)) }
        testScheduler.runCurrent()

        // Both notifications are in progress, none completed yet.
        assertEquals(2, started)
        assertTrue(job.isActive)
        gate.complete(Unit)
        job.join()
        assertEquals(2, fixture.server.notifications.size)
    }

    @Test
    fun update_failure_isRethrownAfterAllAttempted() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        fixture.subscribe(fixture.notify, 1, fixture.central)
        fixture.subscribe(fixture.notify, 1, fixture.central2)
        fixture.server.onNotify = {
            if (it.central == fixture.central) throw IllegalStateException("Disconnected")
        }

        assertFailsWith<IllegalStateException> { fixture.notify.update(bytes(1)) }

        // The other central was notified.
        assertEquals(fixture.central2, fixture.server.notifications.single().central)
    }

    @Test
    fun update_onReplacedService_throws() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val old = fixture.notify
        fixture.subscribe(old)

        val _ = fixture.manager.setup { Service(SERVICE_UUID) }

        assertFailsWith<IllegalStateException> { old.update(bytes(1)) }
        assertTrue(fixture.server.notifications.isEmpty())
    }

    @Test
    fun update_onCharacteristicWithoutReadProperty_works() = runTest {
        val fixture = Fixture(this).apply { val _ = setup() }
        val notifyOnly = fixture.characteristic(NOTIFY2_UUID)
        fixture.subscribe(notifyOnly)

        notifyOnly.update(bytes(1))

        assertEquals(notifyOnly.instanceId, fixture.server.notifications.single().attribute)
    }
}
