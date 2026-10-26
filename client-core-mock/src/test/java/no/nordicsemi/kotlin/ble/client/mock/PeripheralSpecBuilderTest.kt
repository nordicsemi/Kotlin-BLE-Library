package no.nordicsemi.kotlin.ble.client.mock

import no.nordicsemi.kotlin.ble.core.Bluetooth5AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.PeripheralType
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests of [PeripheralSpec] builder: defaults, validation and derived properties. */
class PeripheralSpecBuilderTest {

    private val handler = object : PeripheralSpecEventHandler {}

    /**
     * Creates a connectable peripheral, for tests in which the way it is created is not the subject.
     *
     * A bonded peripheral is also known and has its services cached. [block] is applied after
     * the peripheral is made connectable.
     */
    private fun connectableSpec(
        proximity: Proximity = Proximity.IMMEDIATE,
        identifier: String = "AA:BB:CC:DD:EE:01",
        addressType: AddressType = AddressType.RANDOM_STATIC,
        isBonded: Boolean = false,
        isPhyCodedSupported: Boolean = false,
        block: PeripheralSpec.Builder<String>.() -> Unit = {},
    ) = PeripheralSpec.simulatePeripheral(identifier, addressType, proximity = proximity) {
        if (isBonded) {
            connectable(
                name = "Device",
                isPhyCodedSupported = isPhyCodedSupported,
                isBonded = true,
                eventHandler = handler,
                cachedServices = { },
            )
        } else {
            connectable(
                name = "Device",
                isPhyCodedSupported = isPhyCodedSupported,
                eventHandler = handler,
                services = { },
            )
        }
        block()
    }

    // Basic properties

    @Test
    fun `spec keeps identifier address type peripheral type and proximity`() {
        val spec = PeripheralSpec.simulatePeripheral(
            identifier = "id",
            addressType = AddressType.PUBLIC,
            type = PeripheralType.DUAL,
            proximity = Proximity.FAR,
        )
        assertEquals("id", spec.identifier)
        assertEquals(AddressType.PUBLIC, spec.addressType)
        assertEquals(PeripheralType.DUAL, spec.type)
        assertEquals(Proximity.FAR, spec.proximity)
    }

    @Test
    fun `spec without builder is not connectable`() {
        val spec = PeripheralSpec.simulatePeripheral("id")
        assertNull(spec.name)
        assertNull(spec.maxAttMtu)
        assertNull(spec.maxLlMtu)
        assertNull(spec.eventHandler)
        assertFalse(spec.isConnected)
        assertFalse(spec.isKnown)
        assertFalse(spec.isBonded)
        assertNull(spec.advertisingSets)
    }

    @Test
    fun `allowForRetrieval makes the peripheral known`() {
        val spec = PeripheralSpec.simulatePeripheral("id") { allowForRetrieval() }
        assertTrue(spec.isKnown)
    }

    @Test
    fun `simulateCaching makes the peripheral known`() {
        val spec = PeripheralSpec.simulatePeripheral("id")
        spec.simulateCaching()
        assertTrue(spec.isKnown)
    }

    // connectable()

    @Test
    fun `connectable peripheral has default parameters`() {
        val spec = connectableSpec()
        assertEquals("Device", spec.name)
        assertEquals(0x0000, spec.appearance)
        assertEquals(25..40, spec.preferredConnectionInterval)
        assertEquals(0, spec.preferredSlaveLatency)
        assertEquals(400, spec.preferredSupervisionTimeout)
        assertEquals(23, spec.maxAttMtu)
        assertEquals(27, spec.maxLlMtu)
        assertEquals(setOf(Phy.PHY_LE_1M), spec.supportedPhy)
    }

    @Test
    fun `connectable peripheral is not connected nor known by default`() {
        val spec = connectableSpec()
        assertFalse(spec.isConnected)
        assertFalse(spec.isKnown)
        assertFalse(spec.isBonded)
    }

    @Test
    fun `connectable peripheral keeps custom parameters`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connectable(
                name = "Custom",
                appearance = 0x0340,
                preferredConnectionInterval = 12..24,
                preferredSlaveLatency = 4,
                preferredSupervisionTimeout = 200,
                maxAttMtu = 247,
                maxL2capMtu = 251,
                eventHandler = handler,
                services = { },
            )
        }
        assertEquals(0x0340, spec.appearance)
        assertEquals(12..24, spec.preferredConnectionInterval)
        assertEquals(4, spec.preferredSlaveLatency)
        assertEquals(200, spec.preferredSupervisionTimeout)
        assertEquals(247, spec.maxAttMtu)
        assertEquals(251, spec.maxLlMtu)
    }

    @Test
    fun `PHY LE 2M and Coded support can be enabled`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connectable(
                name = "Phy",
                isPhyLe2MSupported = true,
                isPhyCodedSupported = true,
                eventHandler = handler,
                services = { },
            )
        }
        assertEquals(setOf(Phy.PHY_LE_1M, Phy.PHY_LE_2M, Phy.PHY_LE_CODED), spec.supportedPhy)
    }

    @Test
    fun `connectable cannot be configured twice`() {
        assertFailsWith<IllegalArgumentException> {
            PeripheralSpec.simulatePeripheral("id") {
                connectable(name = "A", eventHandler = handler, services = { })
                connectable(name = "B", eventHandler = handler, services = { })
            }
        }
    }

    @Test
    fun `connectable with cached services is known and keeps bond state`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connectable(name = "Cached", isBonded = true, eventHandler = handler, cachedServices = { })
        }
        assertTrue(spec.isKnown)
        assertTrue(spec.isBonded)
        assertFalse(spec.isConnected)
    }

    @Test
    fun `connected peripheral is connected when in range`() {
        val spec = PeripheralSpec.simulatePeripheral("id", proximity = Proximity.NEAR) {
            connected(name = "Connected", eventHandler = handler, cachedServices = { })
        }
        assertTrue(spec.isConnected)
        assertTrue(spec.isKnown)
    }

    @Test
    fun `connected peripheral is not connected when out of range`() {
        val spec = PeripheralSpec.simulatePeripheral("id", proximity = Proximity.OUT_OF_RANGE) {
            connected(name = "Connected", eventHandler = handler, cachedServices = { })
        }
        assertFalse(spec.isConnected)
    }

    @Test
    fun `high speed parameters are applied`() {
        val spec = PeripheralSpec.simulatePeripheral("id") { highSpeed() }
        assertEquals(498, spec.maxAttMtu)
        assertEquals(251, spec.maxLlMtu)
        assertTrue(Phy.PHY_LE_2M in spec.supportedPhy)
    }

    @Test
    fun `high speed does not reduce the MTU of already configured peripheral`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connectable(name = "A", maxAttMtu = 517, maxL2capMtu = 251, eventHandler = handler, services = { })
            highSpeed()
        }
        assertEquals(517, spec.maxAttMtu)
    }

    // Validation

    private fun assertRejected(
        interval: IntRange = 25..40,
        latency: Int = 0,
        timeout: Int = 400,
        attMtu: Int = 23,
        llMtu: Int = 27,
    ) {
        assertFailsWith<IllegalArgumentException> {
            PeripheralSpec.simulatePeripheral("id") {
                connectable(
                    name = "Invalid",
                    preferredConnectionInterval = interval,
                    preferredSlaveLatency = latency,
                    preferredSupervisionTimeout = timeout,
                    maxAttMtu = attMtu,
                    maxL2capMtu = llMtu,
                    eventHandler = handler,
                    services = { },
                )
            }
        }
    }

    @Test
    fun `connection interval out of range is rejected`() {
        assertRejected(interval = 5..40)
        assertRejected(interval = 25..3201)
    }

    @Test
    fun `latency out of range is rejected`() {
        assertRejected(latency = -1)
        assertRejected(latency = 500)
    }

    @Test
    fun `supervision timeout out of range is rejected`() {
        assertRejected(timeout = 9)
        assertRejected(timeout = 3201)
    }

    @Test
    fun `ATT MTU out of range is rejected`() {
        assertRejected(attMtu = 22)
        assertRejected(attMtu = 518)
    }

    @Test
    fun `LL MTU out of range is rejected`() {
        assertRejected(llMtu = 26)
        assertRejected(llMtu = 252)
    }

    @Test
    fun `boundary values are accepted`() {
        val _ = PeripheralSpec.simulatePeripheral("id") {
            connectable(
                name = "Boundary",
                preferredConnectionInterval = 6..3200,
                preferredSlaveLatency = 499,
                preferredSupervisionTimeout = 3200,
                maxAttMtu = 517,
                maxL2capMtu = 251,
                eventHandler = handler,
                services = { },
            )
        }
        val _ = PeripheralSpec.simulatePeripheral("id") {
            connectable(
                name = "Boundary",
                preferredConnectionInterval = 6..6,
                preferredSlaveLatency = 0,
                preferredSupervisionTimeout = 10,
                maxAttMtu = 23,
                maxL2capMtu = 27,
                eventHandler = handler,
                services = { },
            )
        }
    }

    // Advertising

    @Test
    fun `advertising sets accumulate in declaration order`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            advertising { CompleteLocalName("One") }
            advertising { CompleteLocalName("Two") }
        }
        val sets = assertNotNull(spec.advertisingSets)
        assertEquals(2, sets.size)
        assertEquals("One", sets[0].advertisingData.completeLocalName)
        assertEquals("Two", sets[1].advertisingData.completeLocalName)
    }

    @Test
    fun `advertising set with max events has infinite timeout`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            advertising(maxAdvertisingEvents = 10) { CompleteLocalName("One") }
        }
        val set = assertNotNull(spec.advertisingSets).single()
        assertEquals(10, set.maxAdvertisingEvents)
        assertEquals(kotlin.time.Duration.INFINITE, set.timeout)
    }

    @Test
    fun `advertising set with timeout has unlimited events`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            advertising(timeout = kotlin.time.Duration.parse("5s")) { CompleteLocalName("One") }
        }
        val set = assertNotNull(spec.advertisingSets).single()
        assertEquals(Int.MAX_VALUE, set.maxAdvertisingEvents)
    }

    @Test
    fun `advertised TX power level comes from the advertising parameters`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            advertising(parameters = LegacyAdvertisingSetParameters(connectable = true, txPowerLevel = -15)) {
                CompleteLocalName("One")
                IncludeTxPowerLevel()
            }
        }
        assertEquals(-15, assertNotNull(spec.advertisingSets).single().advertisingData.txPowerLevel)
    }

    @Test
    fun `secondary and coded PHYs of Bluetooth 5 advertising are added to supported PHYs`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            advertising(
                parameters = Bluetooth5AdvertisingSetParameters(
                    connectable = true,
                    primaryPhy = PrimaryPhy.PHY_LE_CODED,
                    secondaryPhy = Phy.PHY_LE_2M,
                )
            ) { CompleteLocalName("One") }
        }
        assertTrue(Phy.PHY_LE_CODED in spec.supportedPhy)
        assertTrue(Phy.PHY_LE_2M in spec.supportedPhy)
        assertTrue(Phy.PHY_LE_1M in spec.supportedPhy)
    }

    @Test
    fun `legacy advertising does not add PHYs`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            advertising { CompleteLocalName("One") }
        }
        assertEquals(setOf(Phy.PHY_LE_1M), spec.supportedPhy)
    }

    // MAC change

    @Test
    fun `MAC change of disconnected peripheral changes address and forgets bond`() {
        val spec = connectableSpec(
            identifier = "old",
            addressType = AddressType.RANDOM_PRIVATE_RESOLVABLE,
            isBonded = true,
        )
        spec.simulateMacChange("new", AddressType.RANDOM_PRIVATE_RESOLVABLE)
        assertEquals("new", spec.identifier)
        assertFalse(spec.isKnown)
        assertFalse(spec.isBonded)
    }

    @Test
    fun `MAC change is ignored while connected`() {
        val spec = connectableSpec()
        spec.simulateConnection()
        assertTrue(spec.isConnected)
        spec.simulateMacChange("new", AddressType.PUBLIC)
        assertEquals("AA:BB:CC:DD:EE:01", spec.identifier)
    }

    // Connection simulation

    @Test
    fun `simulated connection uses preferred connection parameters`() {
        val spec = connectableSpec()
        spec.simulateConnection()
        assertTrue(spec.isConnected)
        assertEquals(ConnectionParameters.Specified(25, 0, 400), spec.connectionParameters)
    }

    @Test
    fun `simulated connection negotiates MTU not larger than the maximum`() {
        val spec = connectableSpec { highSpeed() }
        spec.simulateConnection(mtu = 1000)
        assertEquals(498, spec.mtu)
    }

    @Test
    fun `simulated connection MTU is never lower than the default`() {
        val spec = connectableSpec { highSpeed() }
        spec.simulateConnection(mtu = 5)
        assertEquals(23, spec.mtu)
    }

    @Test
    fun `simulated connection on a PHY that is not supported is rejected`() {
        val spec = connectableSpec()
        assertFailsWith<IllegalArgumentException> {
            spec.simulateConnection(phy = PrimaryPhy.PHY_LE_CODED)
        }
        assertFalse(spec.isConnected)
    }

    @Test
    fun `simulated connection on a PHY that is not supported does not reach the event handler`() {
        var connectionRequests = 0
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connectable(
                name = "Device",
                eventHandler = object : PeripheralSpecEventHandler {
                    override fun onConnectionRequest(peripheral: MockPeripheral, phy: PrimaryPhy): ConnectionResult {
                        connectionRequests++
                        return ConnectionResult.Accept
                    }
                },
                services = { },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            spec.simulateConnection(phy = PrimaryPhy.PHY_LE_CODED)
        }
        assertEquals(0, connectionRequests)
    }

    @Test
    fun `simulated connection on a PHY that is not supported is rejected also when already connected`() {
        val spec = connectableSpec()
        spec.simulateConnection()
        assertFailsWith<IllegalArgumentException> {
            spec.simulateConnection(phy = PrimaryPhy.PHY_LE_CODED)
        }
    }

    @Test
    fun `simulated connection uses requested PHY when supported`() {
        val spec = connectableSpec(isPhyCodedSupported = true)
        spec.simulateConnection(phy = PrimaryPhy.PHY_LE_CODED)
        assertEquals(PhyInUse(Phy.PHY_LE_CODED, Phy.PHY_LE_CODED), spec.phy)
    }

    @Test
    fun `simulated disconnection clears the connection state`() {
        val spec = connectableSpec()
        spec.simulateConnection()
        spec.simulateDisconnection()
        assertFalse(spec.isConnected)
        assertNull(spec.mtu)
        assertNull(spec.phy)
        assertNull(spec.connectionParameters)
    }

    @Test
    fun `simulated connection of non connectable peripheral does nothing`() {
        val spec = PeripheralSpec.simulatePeripheral("id")
        spec.simulateConnection()
        assertFalse(spec.isConnected)
    }

    @Test
    fun `proximity can be changed`() {
        val spec = connectableSpec()
        spec.simulateProximityChange(Proximity.FAR)
        assertEquals(Proximity.FAR, spec.proximity)
    }

    // connected(activePhy)

    @Test
    fun `connected peripheral uses the active PHY of the connection`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connected(
                name = "Device",
                isPhyLe2MSupported = true,
                activePhy = PhyInUse(Phy.PHY_LE_2M, Phy.PHY_LE_2M),
                eventHandler = handler,
                cachedServices = { },
            )
        }
        assertTrue(spec.isConnected)
        assertEquals(PhyInUse(Phy.PHY_LE_2M, Phy.PHY_LE_2M), spec.phy)
    }

    @Test
    fun `connected peripheral uses LE 1M by default`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connected(name = "Device", eventHandler = handler, cachedServices = { })
        }
        assertEquals(PhyInUse.PHY_LE_1M, spec.phy)
    }

    @Test
    fun `connected peripheral can use different TX and RX PHYs`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connected(
                name = "Device",
                isPhyLe2MSupported = true,
                activePhy = PhyInUse(txPhy = Phy.PHY_LE_2M, rxPhy = Phy.PHY_LE_1M),
                eventHandler = handler,
                cachedServices = { },
            )
        }
        assertEquals(PhyInUse(Phy.PHY_LE_2M, Phy.PHY_LE_1M), spec.phy)
    }

    @Test
    fun `connected peripheral has the default state of a new connection`() {
        val spec = PeripheralSpec.simulatePeripheral("id") {
            connected(name = "Device", eventHandler = handler, cachedServices = { })
        }
        assertEquals(23, spec.mtu)
        assertEquals(27, spec.llMtu)
        assertNotNull(spec.connectionParameters)
    }

    @Test
    fun `active PHY not supported by the peripheral is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PeripheralSpec.simulatePeripheral("id") {
                connected(
                    name = "Device",
                    activePhy = PhyInUse(Phy.PHY_LE_2M, Phy.PHY_LE_2M),
                    eventHandler = handler,
                    cachedServices = { },
                )
            }
        }
    }

    @Test
    fun `active PHY is rejected when only the RX PHY is not supported`() {
        assertFailsWith<IllegalArgumentException> {
            PeripheralSpec.simulatePeripheral("id") {
                connected(
                    name = "Device",
                    isPhyLe2MSupported = true,
                    activePhy = PhyInUse(txPhy = Phy.PHY_LE_2M, rxPhy = Phy.PHY_LE_CODED),
                    eventHandler = handler,
                    cachedServices = { },
                )
            }
        }
    }

    @Test
    fun `active PHY is validated also when the peripheral is out of range`() {
        assertFailsWith<IllegalArgumentException> {
            PeripheralSpec.simulatePeripheral("id", proximity = Proximity.OUT_OF_RANGE) {
                connected(
                    name = "Device",
                    activePhy = PhyInUse(Phy.PHY_LE_CODED, Phy.PHY_LE_CODED),
                    eventHandler = handler,
                    cachedServices = { },
                )
            }
        }
    }

    @Test
    fun `peripheral that is out of range is not connected`() {
        val spec = PeripheralSpec.simulatePeripheral("id", proximity = Proximity.OUT_OF_RANGE) {
            connected(name = "Device", eventHandler = handler, cachedServices = { })
        }
        assertFalse(spec.isConnected)
        assertNull(spec.phy)
        assertNull(spec.mtu)
    }
}
