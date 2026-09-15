/*
 * Copyright (c) 2025, Nordic Semiconductor
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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import no.nordicsemi.kotlin.ble.client.AnyRemoteService
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.exception.ValueDoesNotMatchException
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.exception.BluetoothException
import no.nordicsemi.kotlin.ble.core.exception.SecurityException
import no.nordicsemi.kotlin.ble.core.internal.CallSiteException
import no.nordicsemi.kotlin.ble.core.internal.withCallSite
import no.nordicsemi.kotlin.ble.core.util.MergeResult
import no.nordicsemi.kotlin.ble.core.util.mergeIndexed

abstract class BaseRemoteCharacteristic(
    parent: AnyRemoteService,
    private val events: SharedFlow<GattEvent>,
): RemoteCharacteristic {
    final override val service: AnyRemoteService = parent

    /**
     * A flag indicating whether reliable write is enabled.
     */
    protected val isReliableWriteEnabled: Boolean
        get() = owner?.executor?.isReliableWriteEnabled ?: false

    /** Cached restriction flag. */
    private val _isRestricted by lazy { super.isRestricted() }
    override fun isRestricted(): Boolean = _isRestricted

    abstract fun setCharacteristicNotification(enabled: Boolean)

    /**
     * Executes the read operation specific to the implementation.
     *
     * This method should emit a [CharacteristicRead] event to the [FlowCollector] upon
     * successful or failed completion of the read operation.
     *
     * This method is only called when the preconditions for reading the characteristic
     * value have been met (i.e., the characteristic is readable and not invalidated).
     *
     * @receiver The flow collector to emitting GATT events.
     * @throws OperationFailedException in case the request has failed.
     */
    abstract suspend fun FlowCollector<GattEvent>.executeRead()

    /**
     * Executes the write operation specific to the implementation.
     *
     * This method should emit a [CharacteristicWrite] event to the [FlowCollector] upon
     * successful or failed completion of the write operation.
     *
     * This method is only called when the preconditions for writing the characteristic
     * value have been met (i.e., the characteristic is writable and not invalidated).
     *
     * @receiver The flow collector to emitting GATT events.
     * @throws OperationFailedException in case the request has failed.
     * @throws ValueDoesNotMatchException when the value reported by the peripheral
     * is not equal to the value written. This can only happen when *Long Write* is used
     * or the *Reliable Write* procedure is in progress.
     * @throws InvalidAttributeException when the characteristic has been invalidated.
     */
    abstract suspend fun FlowCollector<GattEvent>.executeWrite(data: ByteArray, writeType: WriteType)

    /**
     * Checks whether the event matches this descriptor.
     *
     * If there are multiple descriptors read or written at the same time, this method should
     * differentiate them using the reference, UUID, instance ID or other means.
     *
     * @receiver The received GATT event.
     * @return `true` if the event matches this descriptor, `false` otherwise.
     */
    abstract fun OperationEvent.matches(): Boolean

    /** A flag indicating whether notifications or indications are enabled.  */
    private var _isNotifying: Boolean = false
    final override val isNotifying: Boolean
        get() = owner != null && _isNotifying

    final override suspend fun setNotifying(enabled: Boolean) = withCallSite("setNotifying") {
        // Check whether the characteristic wasn't invalidated.
        val owner = requireNotNull(owner) {
            throw InvalidAttributeException()
        }

        // Check whether accessing the attribute is permitted.
        require(owner.executor.environment.isSystem || !isRestricted()) {
            throw SecurityException("Subscribing to value changes from characteristic $uuid is not permitted")
        }

        // If the current state of notifications is the same as the requested state, return.
        if (enabled == isNotifying)
            return@withCallSite

        // Verify that the characteristic can be subscribed to.
        require(isSubscribable()) {
            throw OperationFailedException(OperationStatus.SubscribeNotPermitted)
        }

        // Check if the CCCD descriptor exists.
        val cccd = descriptors.cccd()
            ?: throw OperationFailedException(OperationStatus.SubscribeNotPermitted)

        // Enable handling of notifications or indications locally.
        try {
            setCharacteristicNotification(enabled)
        } catch (e: OperationFailedException) {
            throw e
        } catch (e: Exception) {
            throw BluetoothException(e)
        }

        // Enable notifications or indications by writing to the CCCD descriptor.
        val value = when {
            // Note: Notify has priority over Indicate, if both are supported.
            //       This is inline with the iOS native behavior.
            enabled && CharacteristicProperty.NOTIFY in properties -> BaseRemoteDescriptor.ENABLE_NOTIFICATIONS_VALUE
            enabled -> BaseRemoteDescriptor.ENABLE_INDICATIONS_VALUE
            else -> BaseRemoteDescriptor.DISABLE_NOTIFICATIONS_VALUE
        }
        cccd.write(value)
        _isNotifying = enabled
    }

    final override suspend fun read(): ByteArray = withCallSite("read") {
        // Check whether the characteristic wasn't invalidated.
        val owner = requireNotNull(owner) {
            throw InvalidAttributeException()
        }

        // Check whether accessing the attribute is permitted.
        require(owner.executor.environment.isSystem || !isRestricted()) {
            throw SecurityException("Reading characteristic $uuid is not permitted")
        }

        // Verify that the characteristic can be read.
        require(isReadable()) {
            throw OperationFailedException(OperationStatus.ReadNotPermitted)
        }

        // Read the characteristic value and await the result.
        OperationMutex.withLock {
            events
                .onSubscription {
                    try {
                        executeRead()
                    } catch (e: CancellationException) {
                        // We MUST rethrow CancellationException.
                        throw e
                    } catch (e: OperationFailedException) {
                        // This is thrown when the write request failed before it was sent.
                        throw e
                    } catch (e: InvalidAttributeException) {
                        // Thrown when the services have been invalidated.
                        throw e
                    } catch (e: SecurityException) {
                        throw e
                    } catch (e: BluetoothException) {
                        throw e
                    } catch (_: IllegalStateException) {
                        // Thrown when mock implementation checks for connection parameters.
                        // If that fails, the attribute was invalidated.
                        throw InvalidAttributeException()
                    } catch (e: Exception) {
                        // This is any other exception, i.e. SecurityException, etc.
                        throw BluetoothException(e)
                    }
                }
                .takeWhile { !it.isServiceInvalidatedEvent }
                .filterIsInstance(CharacteristicRead::class)
                .firstOrNull { it.matches() }
                ?.let {
                    when (it.status) {
                        OperationStatus.Success -> it.value
                        else -> throw OperationFailedException(it.status)
                    }
                }
                ?: throw InvalidAttributeException()
        }
    }

    final override suspend fun write(data: ByteArray, writeType: WriteType) = withCallSite("write") {
        // Check whether the characteristic wasn't invalidated.
        val owner = requireNotNull(owner) {
            throw InvalidAttributeException()
        }

        // Check whether accessing the attribute is permitted.
        require(owner.executor.environment.isSystem || !isRestricted()) {
            throw SecurityException("Writing characteristic $uuid is not permitted")
        }

        // Verify that the characteristic can be written.
        require(isWritable()) {
            throw OperationFailedException(OperationStatus.WriteNotPermitted)
        }

        // Write the characteristic value and await the result.
        OperationMutex.withLock {
            events
                .onSubscription {
                    try {
                        executeWrite(data, writeType)
                    } catch (e: CancellationException) {
                        // We MUST rethrow CancellationException.
                        throw e
                    } catch (e: ValueDoesNotMatchException) {
                        // This exception is thrown when during Long Write or Reliable Write.
                        throw e
                    } catch (e: OperationFailedException) {
                        // This is thrown when the write request failed before it was sent.
                        throw e
                    } catch (e: InvalidAttributeException) {
                        // Thrown when the services have been invalidated.
                        throw e
                    } catch (e: SecurityException) {
                        throw e
                    } catch (e: BluetoothException) {
                        throw e
                    } catch (_: IllegalStateException) {
                        // Thrown when mock implementation checks for connection parameters.
                        // If that fails, the attribute was invalidated.
                        throw InvalidAttributeException()
                    } catch (e: Exception) {
                        // This is any other exception, i.e. SecurityException, etc.
                        throw BluetoothException(e)
                    }
                }
                .takeWhile { !it.isServiceInvalidatedEvent }
                .filterIsInstance(CharacteristicWrite::class)
                .firstOrNull { it.matches() }
                ?.let {
                    check(it.status == OperationStatus.Success) {
                        throw OperationFailedException(it.status)
                    }
                }
                ?: throw InvalidAttributeException()
        }
    }

    override suspend fun waitForValueChange(
        rawDataFilter: (ByteArray) -> Boolean,
        merge: suspend (ByteArray, ByteArray, Int) -> MergeResult,
        filter: (ByteArray) -> Boolean,
        trigger: suspend RemoteCharacteristic.() -> Unit,
    ): ByteArray = withCallSite("waitForValueChange") {
        subscribe(trigger)
            .filter(rawDataFilter)
            .mergeIndexed(merge)
            .firstOrNull(filter)
            ?: throw InvalidAttributeException()
    }

    override fun subscribe(
        onSubscription: suspend RemoteCharacteristic.() -> Unit
    ): Flow<ByteArray> {
        // Check whether the characteristic wasn't invalidated.
        val owner = requireNotNull(owner) {
            throw InvalidAttributeException()
        }

        // Check whether accessing the attribute is permitted.
        require(owner.executor.environment.isSystem || !isRestricted()) {
            throw SecurityException("Subscribing to value changes from characteristic $uuid is not permitted")
        }

        // Verify that the characteristic can be subscribed to.
        require(isSubscribable()) {
            throw OperationFailedException(OperationStatus.SubscribeNotPermitted)
        }

        // Captured immediately, on the caller's thread, before the flow below is even built.
        val calledHere = CallSiteException("subscribe() was called here")

        return events
            .onSubscription {
                // Captured when collection of the flow actually starts, which may happen later
                // and on a different coroutine than the one that called subscribe().
                val collectedHere = CallSiteException("subscribe() Flow started being collected here")
                try {
                    // First, make sure the notifications or indications are enabled.
                    setNotifying(true)
                    // Then, invoke the user callback.
                    onSubscription()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e.addSuppressed(calledHere)
                    e.addSuppressed(collectedHere)
                    throw e
                }
            }
            .takeWhile { !it.isServiceInvalidatedEvent }
            .filterIsInstance(CharacteristicChanged::class)
            .filter { isNotifying && it.matches() }
            .map { it.value }
    }

    final override fun toString(): String = uuid.toString()
}

private fun List<RemoteDescriptor>.cccd() =
    firstOrNull { it.isClientCharacteristicConfiguration }