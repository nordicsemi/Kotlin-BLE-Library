package no.nordicsemi.kotlin.ble.client.android

import no.nordicsemi.kotlin.ble.core.AdvertisingDataFlag
import no.nordicsemi.kotlin.ble.core.AdvertisingDataType
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Additional tests of the advertising data parser, complementing [AdvertisingDataTest]. */
class AdvertisingDataParsingTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ad(type: Int, vararg data: Int) = bytes(data.size + 1, type, *data)

    private fun concat(vararg parts: ByteArray) = parts.fold(byteArrayOf()) { acc, part -> acc + part }

    // Empty and degenerate data

    @Test
    fun `empty data produces empty advertising data`() {
        val data = AdvertisingData(byteArrayOf())
        assertNull(data.flags)
        assertNull(data.name)
        assertNull(data.txPowerLevel)
        assertTrue(data.serviceUuids.isEmpty())
        assertTrue(data.serviceSolicitationUuids.isEmpty())
        assertTrue(data.serviceData.isEmpty())
        assertTrue(data.manufacturerData.isEmpty())
        assertTrue(data.adStructures.isEmpty())
    }

    @Test
    fun `data shorter than the smallest AD structure does not crash`() {
        val _ = AdvertisingData(bytes(0x02))
        val _ = AdvertisingData(bytes(0x02, 0x01))
    }

    @Test
    fun `zero padding after valid structures is ignored`() {
        val data = AdvertisingData(concat(ad(0x09, 0x41, 0x42), ByteArray(10)))
        assertEquals("AB", data.name)
    }

    @Test
    fun `truncated structure is ignored but previous ones are kept`() {
        val data = AdvertisingData(concat(ad(0x01, 0x06), bytes(0x0B, 0x09, 0x41, 0x42, 0x43)))
        assertEquals(setOf(AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE, AdvertisingDataFlag.BR_EDR_NOT_SUPPORTED), data.flags)
        assertNull(data.name)
    }

    @Test
    fun `unknown AD type is skipped and parsing continues`() {
        val data = AdvertisingData(concat(ad(0xA0, 1, 2, 3), ad(0x09, 0x41)))
        assertEquals("A", data.name)
    }

    // Flags

    @Test
    fun `flags only data is parsed`() {
        val data = AdvertisingData(ad(0x01, 0x06))
        assertEquals(
            setOf(AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE, AdvertisingDataFlag.BR_EDR_NOT_SUPPORTED),
            data.flags
        )
    }

    @Test
    fun `limited discoverable flag is parsed`() {
        assertEquals(
            setOf(AdvertisingDataFlag.LE_LIMITED_DISCOVERABLE_MODE),
            AdvertisingData(ad(0x01, 0x01)).flags
        )
    }

    @Test
    fun `all defined flags are parsed`() {
        assertEquals(AdvertisingDataFlag.entries.toSet(), AdvertisingData(ad(0x01, 0x1F)).flags)
    }

    @Test
    fun `reserved flag bits are ignored`() {
        assertEquals(
            setOf(AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE),
            AdvertisingData(ad(0x01, 0xE2)).flags
        )
    }

    // Names

    @Test
    fun `complete name wins over shortened name regardless of order`() {
        val completeFirst = AdvertisingData(concat(ad(0x09, 0x41, 0x42, 0x43), ad(0x08, 0x41)))
        val shortenedFirst = AdvertisingData(concat(ad(0x08, 0x41), ad(0x09, 0x41, 0x42, 0x43)))
        assertEquals("ABC", completeFirst.name)
        assertEquals("ABC", shortenedFirst.name)
        assertEquals("A", completeFirst.shortenedLocalName)
        assertEquals("ABC", shortenedFirst.completeLocalName)
    }

    // TX power

    @Test
    fun `negative TX power level is parsed as signed`() {
        assertEquals(-40, AdvertisingData(ad(0x0A, 0xD8)).txPowerLevel)
    }

    @Test
    fun `positive TX power level is parsed`() {
        assertEquals(8, AdvertisingData(ad(0x0A, 0x08)).txPowerLevel)
    }

    // Service UUIDs

    @Test
    fun `incomplete and complete lists of service UUIDs accumulate`() {
        val data = AdvertisingData(concat(ad(0x02, 0x0F, 0x18), ad(0x03, 0x0A, 0x18)))
        assertEquals(listOf(Uuid.fromShortUuid(0x180F), Uuid.fromShortUuid(0x180A)), data.serviceUuids)
    }

    @Test
    fun `16-bit and 128-bit service UUID lists are combined`() {
        val uuid128 = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val data = AdvertisingData(
            concat(ad(0x03, 0x0F, 0x18), bytes(17, 0x07), uuid128.toByteArray().reversedArray())
        )
        assertEquals(listOf(Uuid.fromShortUuid(0x180F), uuid128), data.serviceUuids)
    }

    // Service solicitation

    @Test
    fun `16-bit service solicitation UUIDs are parsed`() {
        val data = AdvertisingData(ad(0x14, 0x0F, 0x18, 0x0A, 0x18))
        assertEquals(listOf(Uuid.fromShortUuid(0x180F), Uuid.fromShortUuid(0x180A)), data.serviceSolicitationUuids)
        assertTrue(data.serviceUuids.isEmpty())
    }

    @Test
    fun `32-bit service solicitation UUIDs are parsed`() {
        val data = AdvertisingData(ad(0x1F, 0x78, 0x56, 0x34, 0x12))
        assertEquals(listOf(Uuid.fromShortUuid(0x12345678)), data.serviceSolicitationUuids)
    }

    @Test
    fun `128-bit service solicitation UUIDs are parsed`() {
        val uuid128 = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val data = AdvertisingData(concat(bytes(17, 0x15), uuid128.toByteArray().reversedArray()))
        assertEquals(listOf(uuid128), data.serviceSolicitationUuids)
    }

    // Multiple structures and raw storage

    @Test
    fun `raw data is kept unchanged`() {
        val raw = concat(ad(0x01, 0x06), ad(0x09, 0x41, 0x42))
        assertContentEquals(raw, AdvertisingData(raw).raw)
    }

    @Test
    fun `AD structures of the same type are all stored in order`() {
        val data = AdvertisingData(
            concat(
                ad(0xFF, 0x59, 0x00, 0x01),
                ad(0xFF, 0x4C, 0x00, 0x02),
            )
        )
        val structures = assertNotNull(data.adStructures[AdvertisingDataType.MANUFACTURER_SPECIFIC_DATA])
        assertEquals(2, structures.size)
        assertContentEquals(bytes(0x59, 0x00, 0x01), structures[0])
        assertContentEquals(bytes(0x4C, 0x00, 0x02), structures[1])
    }

    @Test
    fun `different companies in manufacturer data are all available`() {
        val data = AdvertisingData(
            concat(
                ad(0xFF, 0x59, 0x00, 0x01),
                ad(0xFF, 0x4C, 0x00, 0x02),
            )
        )
        assertContentEquals(bytes(0x01), data.manufacturerData[0x0059])
        assertContentEquals(bytes(0x02), data.manufacturerData[0x004C])
    }

    @Test
    fun `AD structures without dedicated property are stored by type`() {
        val data = AdvertisingData(concat(ad(0x19, 0x40, 0x03), ad(0x24, 0x17, 0x2F, 0x2F)))
        assertContentEquals(bytes(0x40, 0x03), data.adStructures[AdvertisingDataType.APPEARANCE]?.single())
        assertContentEquals(bytes(0x17, 0x2F, 0x2F), data.adStructures[AdvertisingDataType.URI]?.single())
    }

    @Test
    fun `unknown AD types are not stored`() {
        val data = AdvertisingData(ad(0xA0, 1, 2))
        assertTrue(data.adStructures.isEmpty())
    }

    @Test
    fun `flags structure is available in AD structures`() {
        val data = AdvertisingData(ad(0x01, 0x06))
        assertContentEquals(bytes(0x06), data.adStructures[AdvertisingDataType.FLAGS]?.single())
    }

    // Mesh

    @Test
    fun `mesh structures are parsed`() {
        val data = AdvertisingData(concat(ad(0x29, 1, 2), ad(0x2A, 3, 4), ad(0x2B, 5, 6)))
        assertContentEquals(bytes(1, 2), data.meshPbAdv)
        assertContentEquals(bytes(3, 4), data.meshMessage)
        assertContentEquals(bytes(5, 6), data.meshBeacon)
    }

    // Extended advertising data

    @Test
    fun `extended advertising data longer than 31 bytes is parsed`() {
        val longName = "N".repeat(60)
        val manufacturerPayload = IntArray(100) { it }
        val data = AdvertisingData(
            concat(
                ad(0x01, 0x06),
                ad(0x09, *longName.map { it.code }.toIntArray()),
                ad(0xFF, 0x59, 0x00, *manufacturerPayload),
            )
        )
        assertEquals(longName, data.name)
        assertEquals(100, data.manufacturerData[0x0059]?.size)
        assertEquals(99, data.manufacturerData[0x0059]?.last()?.toInt())
    }

    @Test
    fun `AD structure with maximum length of 255 is parsed`() {
        // Length 255 = 1 byte of type + 254 bytes of data.
        val name = "x".repeat(254)
        val data = AdvertisingData(ad(0x09, *name.map { it.code }.toIntArray()))
        assertEquals(name, data.name)
    }

    @Test
    fun `many structures in a long payload are all parsed`() {
        val parts = (0 until 40).map { ad(0xFF, it, 0x00, it) }
        val data = AdvertisingData(concat(*parts.toTypedArray()))
        assertEquals(40, data.manufacturerData.size)
        assertContentEquals(bytes(39), data.manufacturerData[39])
    }

    // Service data

    @Test
    fun `service data of different sizes can be combined`() {
        val uuid128 = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val data = AdvertisingData(
            concat(
                ad(0x16, 0x0F, 0x18, 0x64),
                ad(0x20, 0x78, 0x56, 0x34, 0x12, 0x01),
                bytes(18, 0x21), uuid128.toByteArray().reversedArray(), bytes(0x02),
            )
        )
        assertEquals(3, data.serviceData.size)
        assertContentEquals(bytes(0x64), data.serviceData[Uuid.fromShortUuid(0x180F)])
        assertContentEquals(bytes(0x01), data.serviceData[Uuid.fromShortUuid(0x12345678)])
        assertContentEquals(bytes(0x02), data.serviceData[uuid128])
    }
}
