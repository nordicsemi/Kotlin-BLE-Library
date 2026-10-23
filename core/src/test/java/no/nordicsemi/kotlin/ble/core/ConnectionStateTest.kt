package no.nordicsemi.kotlin.ble.core

import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ConnectionStateTest {

    @Test
    fun `only Connected state is connected`() {
        assertTrue(ConnectionState.Connected.isConnected)
        assertFalse(ConnectionState.Connecting.isConnected)
        assertFalse(ConnectionState.Disconnecting.isConnected)
        assertFalse(Disconnected().isConnected)
    }

    @Test
    fun `only Disconnected state is disconnected`() {
        assertTrue(Disconnected().isDisconnected)
        assertTrue(Disconnected(Reason.LinkLoss).isDisconnected)
        assertFalse(ConnectionState.Connected.isDisconnected)
        assertFalse(ConnectionState.Connecting.isDisconnected)
        assertFalse(ConnectionState.Disconnecting.isDisconnected)
    }

    @Test
    fun `Disconnected has no reason by default`() {
        assertNull(Disconnected().reason)
    }

    @Test
    fun `initial Disconnected state is not user initiated`() {
        assertFalse(Disconnected().isUserInitiated)
    }

    @Test
    fun `Success Cancelled and RequiredServiceNotFound are user initiated`() {
        assertTrue(Disconnected(Reason.Success).isUserInitiated)
        assertTrue(Disconnected(Reason.Cancelled).isUserInitiated)
        assertTrue(Disconnected(Reason.RequiredServiceNotFound).isUserInitiated)
    }

    @Test
    fun `link loss and errors are not user initiated`() {
        assertFalse(Disconnected(Reason.LinkLoss).isUserInitiated)
        assertFalse(Disconnected(Reason.TerminatePeerUser).isUserInitiated)
        assertFalse(Disconnected(Reason.UnacceptableConnectionParameters).isUserInitiated)
        assertFalse(Disconnected(Reason.InsufficientAuthentication).isUserInitiated)
        assertFalse(Disconnected(Reason.UnsupportedAddress).isUserInitiated)
        assertFalse(Disconnected(Reason.UnsupportedConfiguration).isUserInitiated)
        assertFalse(Disconnected(Reason.Timeout(30.seconds)).isUserInitiated)
        assertFalse(Disconnected(Reason.Unknown(133)).isUserInitiated)
    }

    @Test
    fun `reasons with different status or duration are not equal`() {
        assertNotEquals<Reason>(Reason.Unknown(133), Reason.Unknown(8))
        assertEquals<Reason>(Reason.Unknown(133), Reason.Unknown(133))
        assertNotEquals<Reason>(Reason.Timeout(1.seconds), Reason.Timeout(2.seconds))
    }
}
