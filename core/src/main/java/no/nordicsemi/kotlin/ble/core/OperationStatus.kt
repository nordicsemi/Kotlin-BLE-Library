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

/**
 * GATT operation status.
 *
 * Some of the status codes are defined by the Bluetooth Specification, others are
 * used only in the library to indicate specific conditions.
 *
 * Read more in Bluetooth Specification, Vol 3 (Host), Part F (ATT), 3.4.1.1 ATT_ERROR_RSP:
 * [link](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/attribute-protocol--att-.html#UUID-eefd3e8d-9b16-3af8-1fb4-fa90f52262e8).
 */
sealed class OperationStatus(
    open val code: Int,
) {
    /** A GATT operation completed successfully. */
    object Success: OperationStatus(0x00)
    /** The attribute handle given was not valid on this server. */
    object InvalidHandle: OperationStatus(0x01)
    /**GATT read operation is not permitted. */
    object ReadNotPermitted: OperationStatus(0x02)
    /** GATT write operation is not permitted. */
    object WriteNotPermitted: OperationStatus(0x03)
    /** The attribute PDU was invalid. */
    object InvalidPdu: OperationStatus(0x04)
    /** Insufficient authentication for a given operation. */
    object InsufficientAuthentication: OperationStatus(0x05)
    /** The given request is not supported. */
    object RequestNotSupported: OperationStatus(0x06)
    /** A read or write operation was requested with an invalid offset. */
    object InvalidOffset: OperationStatus(0x07)
    /**Insufficient authorization for a given operation. */
    object InsufficientAuthorization: OperationStatus(0x08)
    /** The prepare write queue is full. */
    object PrepareQueueFull: OperationStatus(0x09)
    /** No attribute found within the given attribute handle range. */
    object AttributeNotFound: OperationStatus(0x0A)
    /** The attribute cannot be read using the *Long Read* procedure. */
    object AttributeNotLong: OperationStatus(0x0B)
    /** The Encryption Key Size used for encrypting this link is too short. */
    object EncryptionKeyTooShort: OperationStatus(0x0C)
    /** A write operation exceeds the maximum length of the attribute. */
    object InvalidAttributeLength: OperationStatus(0x0D)
    /** The attribute request that was requested has encountered an error that was unlikely, and therefore could not be completed as requested. */
    object UnlikelyError: OperationStatus(0x0E)
    /** Insufficient encryption for a given operation. */
    object InsufficientEncryption: OperationStatus(0x0F)
    /** Insufficient Resources to complete the request. */
    object InsufficientResources: OperationStatus(0x10)
    /** The attribute parameter value was not allowed. */
    object ValueNotAllowed: OperationStatus(0x13)

    /**
     * A generic application level error.
     *
     * Application Errors have [code]s in range 0x80 - 0x9F.
     */
    open class ApplicationError(override val code: Int): OperationStatus(code)
    /** A remote device connection is congested. */
    object ConnectionCongested: ApplicationError(0x8F)
    /** Most generic GATT error code. */
    object GattError: ApplicationError(0x85) // 133
    /**
     * GATT connection timed out, likely due to the remote device being out of range or not
     * advertising as connectable.
     */
    object ConnectionTimeout: ApplicationError(0x93)

    /**
     * Common Profile and Service Error Codes.
     *
     * Profile and Service Errors have [code]s in range 0xE0 – 0xFF.
     *
     * Those are defined in Core Specification Supplement, Volume 1, Part B.
     */
    open class ProfileError(override val code: Int): OperationStatus(code)
    /** Write Request Rejected. */
    object WriteRequestRejected: ProfileError(0xFC)
    /** Client Characteristic Configuration Descriptor Improperly Configured. */
    object ClientCharacteristicConfigurationDescriptorImproperlyConfigured: ProfileError(0xFD)
    /** Procedure Already in Progress. */
    object ProcedureAlreadyInProgress: ProfileError(0xFE)
    /** Out of Range. */
    object OutOfRange: ProfileError(0xFF)

    /** Unknown error. */
    data class UnknownError(override val code: Int): OperationStatus(code) {
        // A data class generates its own toString(), which would hide the description of
        // OperationStatus.
        override fun toString() = super.toString()
    }

    // These errors are mapped from non-Bluetooth status codes.

    /** An unknown internal error occurred when executing the operation. */
    object RequestFailed: OperationStatus(-1)
    /** Device is busy. */
    object Busy: OperationStatus(-2)
    /** The characteristic does not support subscribing for value change. */
    object SubscribeNotPermitted: OperationStatus(-3)

    val isSuccess
        get() = this is Success

    override fun toString() = when (this) {
        Success -> "Success"
        InvalidHandle -> "Invalid Handle"
        ReadNotPermitted -> "Read Not Permitted"
        WriteNotPermitted -> "Write Not Permitted"
        InvalidPdu -> "Invalid PDU"
        InsufficientAuthentication -> "Insufficient Authentication"
        RequestNotSupported -> "Request Not Supported"
        InvalidOffset -> "Invalid Offset"
        InsufficientAuthorization -> "Insufficient Authorization"
        PrepareQueueFull -> "Prepare Queue Full"
        AttributeNotFound -> "Attribute Not Found"
        AttributeNotLong -> "Attribute Not Long"
        EncryptionKeyTooShort -> "Encryption Key Too Short"
        InvalidAttributeLength -> "Invalid Attribute Length"
        UnlikelyError -> "Unlikely Error"
        InsufficientEncryption -> "Insufficient Encryption"
        InsufficientResources -> "Insufficient Resources"
        ValueNotAllowed -> "Value Not Allowed"
        ConnectionCongested -> "Connection Congested"
        GattError -> "Gatt Error"
        ConnectionTimeout -> "Connection Timeout"
        is ApplicationError -> "Application Error (code: ${code.toHexCode()})"
        WriteRequestRejected -> "Write Request Rejected"
        ClientCharacteristicConfigurationDescriptorImproperlyConfigured -> "Client Characteristic Configuration Descriptor Improperly Configured"
        ProcedureAlreadyInProgress -> "Procedure Already In Progress"
        OutOfRange -> "Out of Range"
        is ProfileError -> "Profile Error (code: ${code.toHexCode()})"
        is UnknownError -> "Unknown Error (code: ${code.toHexCode()})"
        RequestFailed -> "Request Failed"
        Busy -> "Busy"
        SubscribeNotPermitted -> "Subscribe Not Permitted"
    }
}

/** Formats the status code as hexadecimal, with at least two digits, e.g. `0x0A` or `0x93`. */
private fun Int.toHexCode() = "0x" + toUInt().toString(16).uppercase().padStart(2, '0')
