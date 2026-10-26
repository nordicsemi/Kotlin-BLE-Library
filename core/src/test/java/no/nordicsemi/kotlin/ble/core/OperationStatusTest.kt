package no.nordicsemi.kotlin.ble.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OperationStatusTest {

    @Test
    fun `ATT error codes match the Bluetooth Core Specification`() {
        assertEquals(0x00, OperationStatus.Success.code)
        assertEquals(0x01, OperationStatus.InvalidHandle.code)
        assertEquals(0x02, OperationStatus.ReadNotPermitted.code)
        assertEquals(0x03, OperationStatus.WriteNotPermitted.code)
        assertEquals(0x04, OperationStatus.InvalidPdu.code)
        assertEquals(0x05, OperationStatus.InsufficientAuthentication.code)
        assertEquals(0x06, OperationStatus.RequestNotSupported.code)
        assertEquals(0x07, OperationStatus.InvalidOffset.code)
        assertEquals(0x08, OperationStatus.InsufficientAuthorization.code)
        assertEquals(0x09, OperationStatus.PrepareQueueFull.code)
        assertEquals(0x0A, OperationStatus.AttributeNotFound.code)
        assertEquals(0x0B, OperationStatus.AttributeNotLong.code)
        assertEquals(0x0C, OperationStatus.EncryptionKeyTooShort.code)
        assertEquals(0x0D, OperationStatus.InvalidAttributeLength.code)
        assertEquals(0x0E, OperationStatus.UnlikelyError.code)
        assertEquals(0x0F, OperationStatus.InsufficientEncryption.code)
        assertEquals(0x10, OperationStatus.InsufficientResources.code)
        assertEquals(0x13, OperationStatus.ValueNotAllowed.code)
    }

    @Test
    fun `common profile and service error codes match the Core Specification Supplement`() {
        assertEquals(0xFC, OperationStatus.WriteRequestRejected.code)
        assertEquals(0xFD, OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured.code)
        assertEquals(0xFE, OperationStatus.ProcedureAlreadyInProgress.code)
        assertEquals(0xFF, OperationStatus.OutOfRange.code)
    }

    @Test
    fun `Android specific codes are in the application error range`() {
        assertEquals(133, OperationStatus.GattError.code)
        assertEquals(0x8F, OperationStatus.ConnectionCongested.code)
        assertEquals(0x93, OperationStatus.ConnectionTimeout.code)
        assertIs<OperationStatus.ApplicationError>(OperationStatus.GattError)
        assertIs<OperationStatus.ApplicationError>(OperationStatus.ConnectionCongested)
        assertIs<OperationStatus.ApplicationError>(OperationStatus.ConnectionTimeout)
    }

    @Test
    fun `statuses mapped from non-Bluetooth codes have negative codes`() {
        assertTrue(OperationStatus.RequestFailed.code < 0)
        assertTrue(OperationStatus.Busy.code < 0)
        assertTrue(OperationStatus.SubscribeNotPermitted.code < 0)
    }

    @Test
    fun `only Success is a success`() {
        assertTrue(OperationStatus.Success.isSuccess)
        assertFalse(OperationStatus.InvalidHandle.isSuccess)
        assertFalse(OperationStatus.GattError.isSuccess)
        assertFalse(OperationStatus.UnknownError(0).isSuccess)
        assertFalse(OperationStatus.RequestFailed.isSuccess)
    }

    @Test
    fun `UnknownError keeps the code and is equal for the same code`() {
        assertEquals(0x42, OperationStatus.UnknownError(0x42).code)
        assertEquals(OperationStatus.UnknownError(0x42), OperationStatus.UnknownError(0x42))
    }

    @Test
    fun `known statuses have readable and distinct descriptions`() {
        val statuses = listOf(
            OperationStatus.Success,
            OperationStatus.InvalidHandle,
            OperationStatus.ReadNotPermitted,
            OperationStatus.WriteNotPermitted,
            OperationStatus.InvalidPdu,
            OperationStatus.InsufficientAuthentication,
            OperationStatus.RequestNotSupported,
            OperationStatus.InvalidOffset,
            OperationStatus.InsufficientAuthorization,
            OperationStatus.PrepareQueueFull,
            OperationStatus.AttributeNotFound,
            OperationStatus.AttributeNotLong,
            OperationStatus.EncryptionKeyTooShort,
            OperationStatus.InvalidAttributeLength,
            OperationStatus.UnlikelyError,
            OperationStatus.InsufficientEncryption,
            OperationStatus.InsufficientResources,
            OperationStatus.ValueNotAllowed,
            OperationStatus.ConnectionCongested,
            OperationStatus.GattError,
            OperationStatus.ConnectionTimeout,
            OperationStatus.WriteRequestRejected,
            OperationStatus.ClientCharacteristicConfigurationDescriptorImproperlyConfigured,
            OperationStatus.ProcedureAlreadyInProgress,
            OperationStatus.OutOfRange,
            OperationStatus.RequestFailed,
            OperationStatus.Busy,
            OperationStatus.SubscribeNotPermitted,
        )
        val descriptions = statuses.map { it.toString() }
        assertTrue(descriptions.all { it.isNotBlank() })
        assertEquals(descriptions.size, descriptions.toSet().size)
    }

    @Test
    fun `unknown application error is described as application error`() {
        assertTrue(OperationStatus.ApplicationError(0x80).toString().startsWith("Application Error"))
    }

    @Test
    fun `unknown profile error is described as profile error`() {
        assertTrue(OperationStatus.ProfileError(0xE0).toString().startsWith("Profile Error"))
    }

    @Test
    fun `unknown error is described as unknown error`() {
        assertTrue(OperationStatus.UnknownError(0x77).toString().startsWith("Unknown Error"))
    }

    @Test
    fun `unknown errors with the same code are equal`() {
        assertEquals(OperationStatus.UnknownError(0x77), OperationStatus.UnknownError(0x77))
        assertNotEquals<OperationStatus>(OperationStatus.UnknownError(0x77), OperationStatus.UnknownError(0x78))
    }

    @Test
    fun `description of an unknown error contains its code in hex`() {
        assertTrue(OperationStatus.UnknownError(0xAB).toString().contains("ab", ignoreCase = true))
    }
}
