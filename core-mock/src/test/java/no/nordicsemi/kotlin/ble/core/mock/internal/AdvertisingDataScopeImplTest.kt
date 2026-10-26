package no.nordicsemi.kotlin.ble.core.mock.internal

import no.nordicsemi.kotlin.ble.core.AdvertisingDataFlag
import no.nordicsemi.kotlin.ble.core.mock.AdvertisingDataScope
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AdvertisingDataScopeImplTest {

    private fun scope(txPowerLevel: Int = -7, block: AdvertisingDataScope.() -> Unit) =
        AdvertisingDataScopeImpl(txPowerLevel).apply(block)

    @Test
    fun `new scope is empty`() {
        assertTrue(AdvertisingDataScopeImpl(0).isEmpty)
    }

    @Test
    fun `scope with a name is not empty`() {
        assertFalse(scope { CompleteLocalName("Dev") }.isEmpty)
        assertFalse(scope { ShortenedLocalName("Dev") }.isEmpty)
    }

    @Test
    fun `scope with a service UUID is not empty`() {
        assertFalse(scope { ServiceUuid(0x180F) }.isEmpty)
    }

    @Test
    fun `scope with manufacturer data is not empty`() {
        assertFalse(scope { ManufacturerData(0x0059, byteArrayOf(1)) }.isEmpty)
    }

    @Test
    fun `scope with only TX power is not empty`() {
        assertFalse(scope { IncludeTxPowerLevel() }.isEmpty)
    }

    @Test
    fun `TX power level is not advertised unless requested`() {
        assertNull(scope(-12) { CompleteLocalName("Dev") }.build().txPowerLevel)
    }

    @Test
    fun `TX power level of the advertising set is advertised when requested`() {
        assertEquals(-12, scope(-12) { CompleteLocalName("Dev"); IncludeTxPowerLevel() }.build().txPowerLevel)
    }

    @Test
    fun `service UUIDs accumulate`() {
        val data = scope {
            ServiceUuid(0x180F)
            ServiceUuid(Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        }.build()
        assertEquals(2, data.serviceUuids?.size)
        assertEquals(Uuid.fromShortUuid(0x180F), data.serviceUuids?.first())
    }

    @Test
    fun `service solicitation UUIDs accumulate`() {
        val data = scope {
            ServiceSolicitationUuid(0x180F)
            ServiceSolicitationUuid(0x180A)
        }.build()
        assertEquals(
            listOf(Uuid.fromShortUuid(0x180F), Uuid.fromShortUuid(0x180A)),
            data.serviceSolicitationUuids
        )
    }

    @Test
    fun `flags from vararg are advertised`() {
        val data = scope {
            Flags(AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE, AdvertisingDataFlag.BR_EDR_NOT_SUPPORTED)
            CompleteLocalName("Dev")
        }.build()
        assertEquals(
            setOf(AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE, AdvertisingDataFlag.BR_EDR_NOT_SUPPORTED),
            data.flags
        )
    }

    @Test
    fun `flags from bitfield are advertised`() {
        val data = scope {
            Flags(0x06)
            CompleteLocalName("Dev")
        }.build()
        assertEquals(
            setOf(AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE, AdvertisingDataFlag.BR_EDR_NOT_SUPPORTED),
            data.flags
        )
    }

    @Test
    fun `last Flags call wins`() {
        val data = scope {
            Flags(AdvertisingDataFlag.LE_LIMITED_DISCOVERABLE_MODE)
            Flags(AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE)
            CompleteLocalName("Dev")
        }.build()
        assertEquals(setOf(AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE), data.flags)
    }

    @Test
    fun `service data with short UUID is stored under the expanded UUID`() {
        val data = scope { ServiceData(0x180F, byteArrayOf(0x64)) }.build()
        assertContentEquals(byteArrayOf(0x64), data.serviceData?.get(Uuid.fromShortUuid(0x180F)))
    }

    @Test
    fun `manufacturer data for different companies accumulates`() {
        val data = scope {
            ManufacturerData(0x0059, byteArrayOf(1))
            ManufacturerData(0x004C, byteArrayOf(2))
        }.build()
        assertEquals(setOf(0x0059, 0x004C), data.manufacturerData?.keys)
    }

    @Test
    fun `manufacturer data company ID boundaries are accepted`() {
        val _ = scope { ManufacturerData(0x0000, byteArrayOf(1)) }
        val _ = scope { ManufacturerData(0xFFFF, byteArrayOf(1)) }
    }

    @Test
    fun `manufacturer data company ID above 16 bits is rejected`() {
        assertFailsWith<IllegalArgumentException> { scope { ManufacturerData(0x10000, byteArrayOf(1)) } }
    }

    @Test
    fun `manufacturer data negative company ID is rejected`() {
        assertFailsWith<IllegalArgumentException> { scope { ManufacturerData(-1, byteArrayOf(1)) } }
    }

    @Test
    fun `built definition raw data contains the name`() {
        val data = scope { CompleteLocalName("AB") }.build()
        assertContentEquals(byteArrayOf(0x03, 0x09, 0x41, 0x42), data.raw)
    }
}
