/*
 * Copyright (c) 2024, Nordic Semiconductor
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

package no.nordicsemi.kotlin.ble.client.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nordicsemi.kotlin.ble.client.CentralManager
import no.nordicsemi.kotlin.ble.client.Peripheral
import no.nordicsemi.kotlin.ble.client.ScanResult
import no.nordicsemi.kotlin.ble.core.Environment
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.core.exception.ManagerClosedException

/**
 * Base implementation of [CentralManager].
 *
 * @param ID The type of the peripheral identifier.
 * @param P The type of the peripheral.
 * @param EX The type of the peripheral executor.
 * @param F The type of the scan filter scope.
 * @param SR Scan result type.
 * @property scope The coroutine scope.
 */
abstract class CentralManagerImpl<
    ID: Any,
    P: Peripheral<ID, EX>,
    EX: Peripheral.Executor<ID>,
    F: CentralManager.ScanFilterScope,
    SR: ScanResult<*, *>,
>(
    protected val scope: CoroutineScope,
    private val environment: Environment,
): CentralManager<ID, P, EX, F, SR> {
    /**
     * The scope of the jobs of this manager, e.g. closing the peripherals.
     *
     * It is a child of [scope], so that it is cancelled when [scope] is canceled, but it can be
     * cancelled on its own, without cancelling [scope], when the manager is closed.
     */
    protected val internalScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    init {
        // Make sure the Central Manager gets closed when its scope gets canceled.
        // This coroutine gets cancelled when close() is called or when the scope gets canceled.
        internalScope.launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { close() } }
        }
    }

    /**
     * Guards [managedPeripherals].
     *
     * Peripherals are looked up and added from scan callbacks, which may be called on a different
     * thread than the one the application uses the manager on (for example, the Binder thread
     * on Android 4.3 and 4.4), and from the application itself.
     */
    private val peripheralsLock = Any()

    /**
     * A list of peripherals managed by this Central Manager instance.
     *
     * Always access it while holding [peripheralsLock].
     */
    private val managedPeripherals = mutableMapOf<ID, P>()

    /**
     * Returns a snapshot of the peripherals managed by this Central Manager instance.
     */
    protected fun managedPeripherals(): List<P> = synchronized(peripheralsLock) {
        managedPeripherals.values.toList()
    }

    /**
     * Adds the given peripheral to the peripherals managed by this Central Manager instance,
     * replacing the one with the same ID, if any.
     */
    protected fun addPeripheral(id: ID, peripheral: P) {
        synchronized(peripheralsLock) {
            managedPeripherals[id] = peripheral
        }
    }

    /**
     * Checks whether the given peripheral was obtained using this instance
     * of the Central Manager.
     */
    protected fun checkPeripheral(peripheral: P) {
        // The same instance is required. Peripherals are equal when they have the same ID,
        // so a peripheral obtained using another Central Manager would pass equals().
        val isManaged = synchronized(peripheralsLock) {
            managedPeripherals.values.any { it === peripheral }
        }
        require(isManaged) {
            "$peripheral was not obtained using this Central Manager instance"
        }
    }

    /**
     * Returns the [Peripheral] object associated with given [id].
     *
     * If the Central Manager engine does not have a matching peripheral, the factory method
     * is called to create it. This method is thread-safe: for each ID only one peripheral is
     * created, also when called from several threads at the same time.
     *
     * @param id The peripheral ID.
     * @param factory A lambda that should return a new peripheral instance for the given ID.
     * @return The peripheral.
     */
    protected fun peripheral(id: ID, factory: (ID) -> P): P = synchronized(peripheralsLock) {
        managedPeripherals.getOrPut(id) {
            factory(id).also { newPeripheral ->
                // Make sure the new peripheral is closed when the manager gets closed or
                // the scope gets canceled.
                state
                    .filter { it != Manager.State.POWERED_ON }
                    .onEach {
                        // Close the peripheral when the manager is closed.
                        newPeripheral.forceClose()
                    }
                    .onCompletion {
                        // Close the peripheral when the scope is canceled.
                        newPeripheral.forceClose()
                    }
                    .launchIn(internalScope)
            }
        }
    }

    /**
     * Flag indicating if the central manager is open.
     *
     * This is set to false when [close] is called.
     */
    protected var isOpen = true
        private set

    /**
     * Checks if the central manager is open, otherwise throws [ManagerClosedException].
     */
    protected fun ensureOpen() {
        require(isOpen) { throw ManagerClosedException() }
    }

    // Implementation

    override fun close() {
        isOpen = false

        // Close all the peripherals obtained from this manager, which also disconnects them.
        // Closing a peripheral that is already closed has no effect.
        managedPeripherals().forEach { peripheral ->
            try {
                peripheral.forceClose()
            } catch (_: Exception) {
                // Make sure that the other peripherals are closed.
            }
        }

        // Cancel the jobs of this manager. The scope given to the manager is not affected.
        internalScope.cancel()
    }
}