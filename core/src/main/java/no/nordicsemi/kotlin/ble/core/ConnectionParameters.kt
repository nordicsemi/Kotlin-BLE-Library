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

package no.nordicsemi.kotlin.ble.core
import org.jetbrains.annotations.Range
import kotlin.time.Duration

/**
 * Bluetooth LE connection parameters.
 */
sealed class ConnectionParameters {

    companion object {
        /**
         * Creates unknown connection parameters.
         *
         * @return Unknown connection parameters.
         * @see ConnectionParameters.Unknown
         */
        operator fun invoke(): ConnectionParameters = Unknown

        /**
         * Creates a specified connection parameters.
         *
         * @param connectionInterval Connection interval in 1.25ms unit.
         *        Valid range is from 6 (7.5ms) to 3200 (4000ms).
         * @param latency Connection latency. Valid range is from 0 to 499.
         * @param supervisionTimeout Supervision timeout in 10ms unit.
         *        Valid range is from 10 (0.1s) to 3200 (32s)
         * @return Specified connection parameters.
         * @see ConnectionParameters.Specified
         */
        operator fun invoke(
            connectionInterval: Int,
            latency: Int,
            supervisionTimeout: Int,
        ) = Specified(
            connectionInterval,
            latency,
            supervisionTimeout,
        )
        /**
         * Creates a specified connection parameters.
         *
         * @param connectionInterval Connection interval.
         * @param latency Connection latency.
         * @param supervisionTimeout Supervision timeout.
         * @return Specified connection parameters.
         * @see ConnectionParameters.Specified
         */
        operator fun invoke(
            connectionInterval: Duration,
            latency: Int,
            supervisionTimeout: Duration,
        ) = Specified(
            connectionInterval,
            latency,
            supervisionTimeout,
        )
    }

    /**
     * Connection parameters are not known due to API limitations, but the device is connected.
     */
    data object Unknown : ConnectionParameters()

    /**
     * Bluetooth LE connection parameters.
     *
     * ### Connection Interval
     *
     * A Bluetooth LE device spends most of its time “sleeping” (hence the “Low Energy” in the name).
     * In a connection, this is accomplished by agreeing on a connection interval saying how often the
     * devices will communicate with each other. When they are done communicating, they will turn off
     * the radio, set a timer and go into idle mode, and when the timer times out, they will both wake
     * up and communicate again. The implementation of this is handled by the Bluetooth LE stack, but
     * it is up to your application to decide how often you want the devices to communicate by setting
     * the connection interval.
     *
     * ### Connection latency
     *
     * Peripheral latency allows the peripheral to skip waking up for a certain number of connection
     * events if it doesn't have any data to send. Usually, the connection interval is a strict tradeoff
     * between power consumption and low latency or delay in communication. If you want to reduce the
     * latency, but still keep a low power consumption, you can use peripheral latency.
     * This is particularly useful in HID (Human Interface Devices) applications, such as computer
     * mouse and keyboard applications, which usually don’t have any data to send, but when it has
     * data to send, we want to have very low latency. Using the peripheral latency option, we can
     * maintain low latency but reduce power consumption by remaining idle for several connection intervals.
     *
     * ### Supervision timeout
     *
     * When two devices are connected, they agree on a parameter that determines how long it should
     * take since the last packet was successfully received until the devices consider the connection lost.
     * This is called the supervision timeout. So if one of the devices is unexpectedly switched off,
     * runs out of battery, or if the devices are out of radio range, then this is the amount of time
     * it takes between successfully receiving the last packet before the connection is considered lost.
     *
     * @param connectionInterval Connection interval in 1.25ms unit.
     *        Valid range is from 6 (7.5ms) to 3200 (4000ms).
     * @param latency Connection latency. Valid range is from 0 to 499.
     * @param supervisionTimeout Supervision timeout in 10ms unit.
     *        Valid range is from 10 (0.1s) to 3200 (32s)
     */
    data class Specified(
        val connectionInterval: @Range(from = 6, to = 3200) Int,
        val latency: @Range(from = 0, to = 499) Int,
        val supervisionTimeout: @Range(from = 10, to = 3200) Int,
    ) : ConnectionParameters() {
        // @Range is JVM-only metadata. Keep the same contract enforced in common code.
        init {
            require(connectionInterval in 6..3200) {
                "Connection interval ($connectionInterval) must be in the range from 6 to 3200 (7.5 ms to 4000 ms)"
            }
            require(latency in 0..499) {
                "Connection latency ($latency) must be in the range from 0 to 499"
            }
            require(supervisionTimeout in 10..3200) {
                "Supervision timeout ($supervisionTimeout) must be in the range from 10 to 3200 (0.1 s to 32 s)"
            }
        }

        /**
         * Creates a connection parameters using [Duration] for interval and timeout.
         *
         * @param connectionInterval Connection interval.
         * @param latency Connection latency.
         * @param supervisionTimeout Supervision timeout.
         * @see ConnectionParameters.Specified
         */
        constructor(
            connectionInterval: Duration,
            latency: Int,
            supervisionTimeout: Duration,
        ): this(
            connectionInterval = (connectionInterval.inWholeMilliseconds * 100 / 125L).toInt(),
            latency = latency,
            supervisionTimeout = (supervisionTimeout.inWholeMilliseconds / 10L).toInt(),
        )

        /**
         * Returns the connection interval in milliseconds.
         */
        val connectionIntervalMillis: Long
            get() = connectionInterval * 125L / 100

        /**
         * Returns the supervision timeout in milliseconds.
         */
        val supervisionTimeoutMillis: Long
            get() = supervisionTimeout * 10L

        override fun toString(): String {
            return "Interval=$connectionInterval ($connectionIntervalMillis ms), Latency=$latency, Timeout=$supervisionTimeout ($supervisionTimeoutMillis ms)"
        }
    }
}
