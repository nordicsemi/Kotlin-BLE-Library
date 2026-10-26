package no.nordicsemi.kotlin.ble.client

import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.PhyInUse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GattEventTest {

    @Test
    fun `transition to Disconnected is a disconnection event`() {
        val event = ConnectionStateChanged(ConnectionState.Disconnected(ConnectionState.Disconnected.Reason.LinkLoss))
        assertTrue(event.disconnected)
        assertTrue(event.isDisconnectionEvent)
    }

    @Test
    fun `transition to initial Disconnected state is a disconnection event`() {
        assertTrue(ConnectionStateChanged(ConnectionState.Disconnected()).isDisconnectionEvent)
    }

    @Test
    fun `transitions to other states are not disconnection events`() {
        listOf(
            ConnectionState.Connecting,
            ConnectionState.Connected,
            ConnectionState.Disconnecting,
        ).forEach {
            assertFalse(ConnectionStateChanged(it).disconnected, "$it")
            assertFalse(ConnectionStateChanged(it).isDisconnectionEvent, "$it")
        }
    }

    @Test
    fun `disconnection invalidates services`() {
        assertTrue(ConnectionStateChanged(ConnectionState.Disconnected()).isServiceInvalidatedEvent)
    }

    @Test
    fun `ServicesChanged invalidates services`() {
        assertTrue(ServicesChanged.isServiceInvalidatedEvent)
        assertFalse(ServicesChanged.isDisconnectionEvent)
    }

    @Test
    fun `other events neither disconnect nor invalidate services`() {
        listOf(
            ConnectionStateChanged(ConnectionState.Connected),
            ServicesDiscovered(emptyList()),
            ServiceDiscoveryFailed(RemoteServices.Failed.Reason.EmptyResult),
            RssiRead(-60),
            MtuChanged(247),
            PhyChanged(PhyInUse.PHY_LE_1M),
            ConnectionParametersChanged(ConnectionParameters.Unknown),
            ReliableWriteCompleted(OperationStatus.Success),
        ).forEach {
            assertFalse(it.isDisconnectionEvent, "$it")
            assertFalse(it.isServiceInvalidatedEvent, "$it")
        }
    }

    @Test
    fun `service discovery results are service discovery completion events`() {
        assertIs<ServiceDiscoveryCompleted>(ServicesDiscovered(emptyList()))
        assertIs<ServiceDiscoveryCompleted>(ServiceDiscoveryFailed(RemoteServices.Failed.Reason.InternalError))
    }

    @Test
    fun `events with same payload are equal`() {
        assertEquals(MtuChanged(247), MtuChanged(247))
        assertEquals(RssiRead(-60), RssiRead(-60))
    }
}
