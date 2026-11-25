package no.nordicsemi.kotlin.ble.client.exception

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.exception.BluetoothException
import no.nordicsemi.kotlin.ble.core.exception.GattException
import no.nordicsemi.kotlin.ble.core.internal.CallSiteException
import no.nordicsemi.kotlin.ble.core.internal.withCallSite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExceptionsTest {

    @Test
    fun `ConnectionFailedException keeps reason and mentions it in message`() {
        val exception = ConnectionFailedException(Reason.LinkLoss)
        assertEquals(Reason.LinkLoss, exception.reason)
        assertTrue(exception.message!!.contains(Reason.LinkLoss.toString()))
    }

    @Test
    fun `OperationFailedException keeps status and uses it as message`() {
        val exception = OperationFailedException(OperationStatus.InsufficientAuthentication)
        assertEquals(OperationStatus.InsufficientAuthentication, exception.reason)
        assertEquals("Insufficient Authentication", exception.message)
    }

    @Test
    fun `OperationFailedException keeps cause`() {
        val cause = RuntimeException("cause")
        assertSame(cause, OperationFailedException(OperationStatus.Busy, cause).cause)
    }

    @Test
    fun `GATT exceptions are GattException`() {
        assertIs<GattException>(OperationFailedException(OperationStatus.Busy))
        assertIs<GattException>(InvalidAttributeException())
        assertIs<GattException>(ValueDoesNotMatchException())
    }

    @Test
    fun `connection related exceptions are BluetoothException`() {
        assertIs<BluetoothException>(ConnectionFailedException(Reason.Cancelled))
        assertIs<BluetoothException>(PeripheralNotConnectedException())
        assertIs<BluetoothException>(ScanningException())
    }

    @Test
    fun `BluetoothException is an IllegalStateException`() {
        assertIs<IllegalStateException>(BluetoothException())
    }

    @Test
    fun `ScanningException has default message and accepts custom one`() {
        assertEquals("Scanning failed", ScanningException().message)
        assertEquals("custom", ScanningException("custom").message)
    }

    @Test
    fun `withCallSite adds call site as suppressed exception`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            withCallSite("connect") { throw IllegalStateException("boom") }
        }
        val suppressed = failure.suppressedExceptions.single()
        assertIs<CallSiteException>(suppressed)
        assertEquals("connect() was called here", suppressed.message)
    }

    @Test
    fun `withCallSite returns the block result`() = runTest {
        assertEquals(42, withCallSite("read") { 42 })
    }

    @Test
    fun `withCallSite does not decorate cancellation`() = runTest {
        val failure = assertFailsWith<CancellationException> {
            withCallSite("connect") { throw CancellationException("cancelled") }
        }
        assertTrue(failure.suppressedExceptions.isEmpty())
    }
}
