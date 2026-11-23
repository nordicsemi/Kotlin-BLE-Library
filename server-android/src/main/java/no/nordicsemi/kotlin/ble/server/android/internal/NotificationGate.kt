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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Serializes notifications and indications sent to a single central.
 *
 * Android allows only one notification per central to be in flight. The next one may be sent
 * after `onNotificationSent` was called, which for indications means that the confirmation was
 * received.
 */
internal class NotificationGate {
    private val mutex = Mutex()
    private val lock = Any()
    private var pending: CompletableDeferred<Int>? = null
    private var closeCause: Throwable? = null

    /**
     * Sends a notification and waits until [onSent] is called.
     *
     * Calls are queued in the order in which they were made.
     *
     * @param timeout The time to wait for [onSent].
     * @param start The function that initiates sending. It should throw if sending could not be
     * started.
     * @return The status reported to [onSent], or `null` if timed out.
     * @throws Throwable the cause given to [close], if the gate was closed before, or while
     * waiting.
     */
    suspend fun send(timeout: Duration, start: () -> Unit): Int? = mutex.withLock {
        val deferred = CompletableDeferred<Int>()
        synchronized(lock) {
            closeCause?.let { throw it }
            pending = deferred
        }
        try {
            start()
            withTimeoutOrNull(timeout) { deferred.await() }
        } finally {
            synchronized(lock) { pending = null }
        }
    }

    /** Completes the notification that is being sent. Does nothing if there is none. */
    fun onSent(status: Int) {
        synchronized(lock) { pending }?.complete(status)
    }

    /**
     * Fails the pending notification, and all that are queued, with the given [cause].
     */
    fun close(cause: Throwable) {
        synchronized(lock) {
            closeCause = cause
            pending
        }?.completeExceptionally(cause)
    }
}
