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
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import no.nordicsemi.kotlin.ble.client.GattEvent
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteDescriptor
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.exception.ValueDoesNotMatchException
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.exception.BluetoothException
import no.nordicsemi.kotlin.ble.core.exception.SecurityException
import no.nordicsemi.kotlin.ble.core.internal.withCallSite

abstract class BaseRemoteDescriptor(
    parent: RemoteCharacteristic,
    private val events: SharedFlow<GattEvent>,
): RemoteDescriptor {
    final override val characteristic: RemoteCharacteristic = parent

    companion object {
        val ENABLE_NOTIFICATIONS_VALUE = byteArrayOf(0x01, 0x00)
        val ENABLE_INDICATIONS_VALUE = byteArrayOf(0x02, 0x00)
        val DISABLE_NOTIFICATIONS_VALUE = byteArrayOf(0x00, 0x00)
    }

    /**
     * A flag indicating whether reliable write is enabled.
     */
    protected val isReliableWriteEnabled: Boolean
        get() = owner?.executor?.isReliableWriteEnabled ?: false

    /** Cached restriction flag. */
    private val _isRestricted by lazy { super.isRestricted() }
    override fun isRestricted(): Boolean = _isRestricted

    /**
     * Executes the read operation specific to the implementation.
     *
     * This method should emit a [DescriptorRead] event to the [FlowCollector] upon
     * successful or failed completion of the read operation.
     *
     * This method is only called when the preconditions for reading the descriptor
     * value have been met (i.e., the descriptor is readable and not invalidated).
     *
     * @receiver The flow collector to emitting GATT events.
     * @throws OperationFailedException in case the request has failed.
     */
    abstract suspend fun FlowCollector<GattEvent>.executeRead()

    /**
     * Executes the write operation specific to the implementation.
     *
     * This method should emit a [DescriptorWrite] event to the [FlowCollector] upon
     * successful or failed completion of the write operation.
     *
     * This method is only called when the preconditions for writing the descriptor
     * value have been met (i.e., the descriptor is writable and not invalidated).
     *
     * @receiver The flow collector to emitting GATT events.
     * @throws OperationFailedException in case the request has failed.
     * @throws ValueDoesNotMatchException when the value reported by the peripheral
     * is not equal to the value written. This can only happen when *Long Write* is used
     * or the *Reliable Write* procedure is in progress.
     * @throws InvalidAttributeException when the descriptor has been invalidated.
     */
    abstract suspend fun FlowCollector<GattEvent>.executeWrite(data: ByteArray)

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

    final override suspend fun read(): ByteArray = withCallSite("read") {
        // Check whether the descriptor wasn't invalidated.
        val owner = requireNotNull(owner) {
            throw InvalidAttributeException()
        }

        // Check whether accessing the attribute is permitted.
        require(owner.executor.environment.isSystem || !isRestricted()) {
            throw SecurityException("Reading descriptor $uuid is not permitted")
        }

        // Verify that the descriptor can be read.
        require(isReadable()) {
            throw OperationFailedException(OperationStatus.ReadNotPermitted)
        }

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
                .filterIsInstance(DescriptorRead::class)
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

    final override suspend fun write(data: ByteArray) = withCallSite("write") {
        // Check whether the descriptor wasn't invalidated.
        val owner = requireNotNull(owner) {
            throw InvalidAttributeException()
        }

        // Check whether accessing the attribute is permitted.
        require(owner.executor.environment.isSystem || !isRestricted()) {
            throw SecurityException("Writing descriptor $uuid is not permitted")
        }

        // Verify that the descriptor can be written to.
        require(isWritable()) {
            throw OperationFailedException(OperationStatus.WriteNotPermitted)
        }

        OperationMutex.withLock {
            events
                .onSubscription {
                    try {
                        executeWrite(data)
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
                .filterIsInstance(DescriptorWrite::class)
                .firstOrNull { it.matches() }
                ?.let {
                    check(it.status.isSuccess) {
                        throw OperationFailedException(it.status)
                    }
                }
                ?: throw InvalidAttributeException()
        }
    }

    final override fun toString(): String = uuid.toString()
}