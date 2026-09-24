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

package no.nordicsemi.kotlin.ble.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.client.exception.ValueDoesNotMatchException
import no.nordicsemi.kotlin.ble.core.Characteristic
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.defaultWriteType
import no.nordicsemi.kotlin.ble.core.exception.BluetoothException
import no.nordicsemi.kotlin.ble.core.util.MergeResult
import no.nordicsemi.kotlin.ble.core.util.chunked
import no.nordicsemi.kotlin.ble.core.util.merge
import no.nordicsemi.kotlin.ble.core.util.mergeIndexed

/**
 * A GATT characteristic of a service on a remote connected peripheral device.
 *
 * The API allows to access the value of the characteristic.
 *
 * Depending on the properties of the characteristic, it may be possible to read, write,
 * subscribe for value changes, etc.
 */
interface RemoteCharacteristic: Characteristic<RemoteDescriptor> {
    override val service: AnyRemoteService
    override val owner: Peripheral<*, *>?
        get() = service.owner

    /**
     * Current state whether the characteristic is notifying or indicating.
     *
     * Use [subscribe] or [waitForValueChange] to subscribe for value changes, or
     * [setNotifying] to enable or disable notifications or indications manually.
     */
    val isNotifying: StateFlow<Boolean>

    /**
     * Enables notifications or indications, depending on the characteristic's properties.
     *
     * This method writes to the Client Characteristic Configuration Descriptor (CCCD)
     * belonging to this characteristic to enable or disable notifications or indications.
     *
     * Note: [subscribe] or [waitForValueChange] enable notifications automatically.
     *
     * ### Example
     *
     * This snippet shows how the notifications can be enabled and disabled synchronously:
     *
     * ```kotlin
     * remoteCharacteristic.setNotifying(true)
     * println("Notifications are enabled: ${remoteCharacteristic.isNotifying}") // -> true
     * ```
     *
     * ## Race Condition Warning
     *
     * Using `setNotifying(true)` can lead to a race condition where the peripheral sends a
     * notification before the app is ready to collect it, causing the first value to be missed.
     *
     * **It is strongly recommended to use [subscribe] or [waitForValueChange] instead.**
     * These Flow-based APIs guarantee that the listener is active *before* notifications are
     * enabled on the peripheral, preventing data loss.
     *
     * ### Example
     * ```kotlin
     * remoteCharacteristic
     *    .subscribe {
     *       // Here, the notifications are 100% enabled.
     *       println("Notifications are enabled: ${remoteCharacteristic.isNotifying}") // -> true
     *    }
     *    .catch {
     *       // Catch an exception if subscribe() failed.
     *    }
     *    .collect {
     *        // [...]
     *    }
     *
     * // The Flow is cold. Notifications are not yet enabled.
     * println("Notifications are enabled: ${remoteCharacteristic.isNotifying}") // -> false
     * ```
     * @param enabled True to enable notifications or indications, false to disable them.
     * @throws OperationFailedException if the operation failed.
     * @throws InvalidAttributeException if the characteristic has been invalidated due to
     * disconnection or service change event.
     * @throws SecurityException if accessing the characteristic is not permitted.
     * @throws BluetoothException if the implementation fails, see [BluetoothException.cause] for
     * a reason.
     */
    suspend fun setNotifying(enabled: Boolean)

    /**
     * Reads the value of the characteristic.
     *
     * This method suspends until the value is read from the peripheral. Long Read procedure
     * will be used automatically if the value is longer than the MTU.
     *
     * ### Example
     * ```kotlin
     * val value: ByteArray = remoteCharacteristic.read()
     * ```
     *
     * @return The value of the characteristic.
     * @throws OperationFailedException if the operation failed.
     * @throws InvalidAttributeException if the characteristic has been invalidated due to
     * disconnection or service change event.
     * @throws SecurityException if accessing the characteristic is not permitted.
     * @throws BluetoothException if the implementation fails, see [BluetoothException.cause] for
     * a reason.
     */
    suspend fun read(): ByteArray

    /**
     * Writes the value of the characteristic.
     *
     * If [data] is longer than [Peripheral.maximumWriteValueLength] for given [writeType],
     * the value will be trimmed. To write longer values, [chunked] them and write
     * subsequent chunks.
     *
     * ```kotlin
     * val data: ByteArray = // ...
     * val maxLength = peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE)
     * data.chunked(maxLength).forEach { chunk ->
     *     remoteCharacteristic.write(chunk, WriteType.WITHOUT_RESPONSE)
     * }
     * ```
     * @param data The data to be written.
     * @param writeType The write type to be used. By default, it is set to the characteristic's
     * default write type based on its properties.
     * @throws ValueDoesNotMatchException if the value was sent using *Long Write* or *Reliable Write*
     * procedure and the value replied back by the peripheral does not match the value written.
     * @throws OperationFailedException if the operation failed.
     * @throws InvalidAttributeException if the characteristic has been invalidated due to
     * disconnection or service change event.
     * @throws SecurityException if accessing the characteristic is not permitted.
     * @throws BluetoothException if the implementation fails, see [BluetoothException.cause] for
     * a reason.
     */
    suspend fun write(
        data: ByteArray,
        writeType: WriteType = properties.defaultWriteType ?: WriteType.WITH_RESPONSE
    )

    /**
     * Subscribes for notifications or indications of the characteristic.
     *
     * If not already enabled, this method enables notifications or indications automatically
     * on subscription, that is when a terminal operator (`collect`, `first`, etc.) is invoked on
     * the returned flow.
     *
     * [onSubscription] callback is invoked when the notifications or indications have been
     * enabled successfully.
     *
     * If higher-level packets are sent as multiple notifications or indications, they may be
     * merged using [merge] or [mergeIndexed] operator.
     *
     * ```kotlin
     * // The Completable Deferred allows to synchronously await until the notifications are enabled.
     * val deferred = CompletableDeferred<Unit>()
     *
     * // Enable notifications or indications and handle them.
     * remoteCharacteristic
     *    .subscribe {
     *        // Notifications are enabled and being collected.
     *        deferred.complete(Unit)
     *    }
     *    // Catch subscription errors, i.e. OperationFailedException(reason=Subscribe not permitted)
     *    .catch {
     *        deferred.completeExceptionally(it)
     *    }
     *    // If a packet is split into multiple notifications, merge them.
     *    .merge { accumulated, received ->
     *       // [...]
     *    }
     *    // Transform the response raw data to a meaningful value.
     *    .map { bytes -> parse(bytes) }
     *    .onEach { data ->
     *       // [...]
     *    }
     *    .launchIn(scope)
     * // Synchronously await until the notifications are enabled while still collecting them.
     * deferred.await()
     * ```
     * This way no notification or indication will be missed, and it is clear when the characteristic
     * is ready (connected, subscribed, triggered to send data).
     *
     * @param onSubscription An optional callback that is invoked when the notifications or
     * indications have been enabled successfully.
     * @return A flow emitting the raw data of notifications or indications sent from the
     * characteristic when the value changes. The flow completes when the characteristic
     * is invalidated, e.g., when the peripheral disconnects, or sends a Service Changed event.
     * @throws OperationFailedException if the operation failed.
     * @throws InvalidAttributeException if the characteristic has been invalidated due to
     * disconnection or service change event.
     * @throws SecurityException if accessing the characteristic or its descriptor is not permitted.
     * @throws BluetoothException if the implementation fails, see [BluetoothException.cause] for
     * a reason.
     * @see isNotifying
     * @see setNotifying
     * @see waitForValueChange
     * @see merge
     * @see mergeIndexed
     */
    fun subscribe(onSubscription: suspend RemoteCharacteristic.() -> Unit = {}): Flow<ByteArray>

    /**
     * Waits for the value of the characteristic to change.
     *
     * This method suspends until the value of the characteristic changes.
     * If the notifications or indications are not enabled, this method will enable them
     * automatically after subscribing for value changes.
     *
     * ```kotlin
     * val newValue = remoteCharacteristic.waitForValueChange(
     *   trigger = {
     *       // Write to the characteristic to request new data.
     *       write(byteArrayOf(0x01))
     *       // Note: You may also write to other characteristics or descriptors here:
     *       // anotherCharacteristic.write(byteArrayOf(0x02))
     *   },
     *   rawDataFilter = { data ->
     *       // Accept only packets starting with 0xAA.
     *       data.isNotEmpty() && data[0] == 0xAA.toByte()
     *   },
     *   merge = { accumulator, received, index ->
     *       // Assume the first byte indicates the total length of the message.
     *       val expectedLength = if (index == 0) received[1].toInt() else accumulator[1].toInt()
     *       val newAccumulator = accumulator + received
     *       if (newAccumulator.size - 2 < expectedLength) {
     *           // More data expected.
     *           MergeResult.Accumulate(newAccumulator)
     *       } else {
     *           // Full message received.
     *           MergeResult.Completed(newAccumulator.drop(2)) // Drop the header.
     *       }
     *   },
     *   filter = { merged ->
     *       // Accept only messages with a valid checksum.
     *       val checksum = merged.last()
     *       val calculated = merged.dropLast(1).fold(0.toByte()) { acc, byte -> acc + byte }
     *       checksum == calculated
     *   }
     * )
     * var response = parse(newValue)
     * ```
     *
     * @param rawDataFilter An optional filter function to evaluate the received notification or
     * indication. Accepted packets will be sent to merge [merge] operator for further processing.
     * By default, all received data is accepted.
     * @param merge Ao optional merge function to combine multiple received values into one.
     * This method can accumulate received values by returning [MergeResult.Accumulate] until a
     * complete message is formed, which is then returned as [MergeResult.Completed].
     * By default, each received value is treated as a complete message.
     * @param filter An optional filter function to evaluate the merged value. When the function
     * returns true, the value is returned from this method.
     * @return The received value of the characteristic.
     * @param trigger An optional trigger that will be executed once before waiting for the value change.
     * It can be used to perform an action that may cause the peripheral to send a notification or
     * indication, e.g., writing to the characteristic to request data. The [trigger] is executed
     * in the context of the [RemoteCharacteristic], so its methods and properties can be accessed
     * directly.
     * @throws OperationFailedException if the operation failed.
     * @throws InvalidAttributeException if the characteristic has been invalidated due to
     * disconnection or service change event.
     * @throws BluetoothException if the implementation fails, see [BluetoothException.cause] for
     * a reason.
     * @see subscribe
     * @see isNotifying
     * @see setNotifying
     */
    suspend fun waitForValueChange(
        rawDataFilter: ((ByteArray) -> Boolean) = { true },
        merge: suspend (accumulator: ByteArray, received: ByteArray, index: Int) -> MergeResult =
            { _, rec, _ -> MergeResult.Completed(rec) },
        filter: ((ByteArray) -> Boolean) = { true },
        trigger: suspend RemoteCharacteristic.() -> Unit = {},
    ): ByteArray
}