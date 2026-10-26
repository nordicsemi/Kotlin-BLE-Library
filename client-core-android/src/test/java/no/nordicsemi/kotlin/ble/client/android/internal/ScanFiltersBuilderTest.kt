package no.nordicsemi.kotlin.ble.client.android.internal

import no.nordicsemi.kotlin.ble.core.AdvertisingDataType
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests of how the scan filter DSL ([ConjunctionFilter] and [DisjunctionFilter]) is translated
 * into a list of [ScanFilter]s. Filters in the list are alternatives (OR), criteria inside
 * a single [ScanFilter] must all be satisfied (AND).
 */
class ScanFiltersBuilderTest {

    private val serviceA = Uuid.fromShortUuid(0x180F)
    private val serviceB = Uuid.fromShortUuid(0x180A)
    private val serviceC = Uuid.fromShortUuid(0x180D)

    private fun filters(block: ConjunctionFilter.() -> Unit): List<ScanFilter>? =
        ConjunctionFilter().apply(block).filters

    // Conjunction

    @Test
    fun `empty filter produces no filters`() {
        assertNull(filters { })
    }

    @Test
    fun `single criterion produces a single filter`() {
        val result = assertNotNull(filters { Name("Nordic") })
        assertEquals(1, result.size)
        assertEquals("Nordic", result[0].name)
    }

    @Test
    fun `all criteria are placed in a single filter`() {
        val result = assertNotNull(filters {
            Name("Nordic")
            Address("01:23:45:67:89:AB")
            ServiceUuid(serviceA)
            ServiceSolicitationUuid(serviceB)
            ServiceData(serviceC, byteArrayOf(1))
            ManufacturerData(0x0059, byteArrayOf(2))
            Custom(AdvertisingDataType.APPEARANCE)
        })
        assertEquals(1, result.size)
        val filter = result.single()
        assertEquals("Nordic", filter.name)
        assertEquals("01:23:45:67:89:AB", filter.address)
        assertEquals(serviceA, filter.serviceUuid?.uuid)
        assertEquals(serviceB, filter.serviceSolicitationUuid?.uuid)
        assertEquals(serviceC, filter.serviceData?.uuid)
        assertEquals(0x0059, filter.manufacturerData?.companyId)
        assertEquals(AdvertisingDataType.APPEARANCE, filter.customAdvertisingData?.type)
    }

    @Test
    fun `setting the same criterion twice overrides the previous one`() {
        val result = assertNotNull(filters {
            Name("first")
            Name("second")
        })
        assertEquals("second", result.single().name)
    }

    @Test
    fun `name regex is stored separately from exact name`() {
        val filter = assertNotNull(filters { Name(Regex("Nor.*")) }).single()
        assertNull(filter.name)
        assertEquals("Nor.*", filter.nameRegex?.pattern)
    }

    @Test
    fun `service UUID mask is stored`() {
        val mask = Uuid.parse("ffff0000-0000-0000-0000-000000000000")
        val filter = assertNotNull(filters { ServiceUuid(serviceA, mask) }).single()
        assertEquals(mask, filter.serviceUuid?.mask)
    }

    @Test
    fun `manufacturer data without data matches any data of the company`() {
        val filter = assertNotNull(filters { ManufacturerData(0x0059) }).single()
        assertEquals(0x0059, filter.manufacturerData?.companyId)
        assertEquals(0, filter.manufacturerData?.data?.size)
        assertNull(filter.manufacturerData?.mask)
    }

    @Test
    fun `custom filter without data keeps data and mask null`() {
        val filter = assertNotNull(filters { Custom(AdvertisingDataType.URI) }).single()
        assertNull(filter.customAdvertisingData?.data)
        assertNull(filter.customAdvertisingData?.mask)
    }

    @Test
    fun `custom filter keeps data and mask`() {
        val filter = assertNotNull(filters {
            Custom(AdvertisingDataType.APPEARANCE, byteArrayOf(1, 2), byteArrayOf(0xFF.toByte(), 0x00))
        }).single()
        assertContentEquals(byteArrayOf(1, 2), filter.customAdvertisingData?.data)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x00), filter.customAdvertisingData?.mask)
    }

    // Disjunction

    @Test
    fun `Any produces one filter per alternative`() {
        val result = assertNotNull(filters {
            Any {
                Name("a")
                Name("b")
                Name("c")
            }
        })
        assertEquals(listOf("a", "b", "c"), result.map { it.name })
    }

    @Test
    fun `empty Any produces no filters`() {
        assertNull(filters { Any { } })
    }

    @Test
    fun `empty Any does not affect other criteria`() {
        val result = assertNotNull(filters {
            Name("Nordic")
            Any { }
        })
        assertEquals(1, result.size)
        assertEquals("Nordic", result.single().name)
    }

    @Test
    fun `criterion outside Any is combined with every alternative inside Any`() {
        val result = assertNotNull(filters {
            Name("Nordic")
            Any {
                ServiceUuid(serviceA)
                ServiceUuid(serviceB)
            }
        })
        assertEquals(2, result.size)
        assertTrue(result.all { it.name == "Nordic" })
        assertEquals(listOf(serviceA, serviceB), result.map { it.serviceUuid?.uuid })
    }

    @Test
    fun `criterion declared after Any is combined with every alternative as well`() {
        val result = assertNotNull(filters {
            Any {
                ServiceUuid(serviceA)
                ServiceUuid(serviceB)
            }
            Name("Nordic")
        })
        assertEquals(2, result.size)
        assertTrue(result.all { it.name == "Nordic" })
    }

    @Test
    fun `two Any blocks produce cross product of alternatives`() {
        val result = assertNotNull(filters {
            Any {
                Name("a")
                Name("b")
            }
            Any {
                ServiceUuid(serviceA)
                ServiceUuid(serviceB)
            }
        })
        assertEquals(4, result.size)
        val pairs = result.map { it.name to it.serviceUuid?.uuid }.toSet()
        assertEquals(
            setOf("a" to serviceA, "a" to serviceB, "b" to serviceA, "b" to serviceB),
            pairs
        )
    }

    @Test
    fun `All inside Any groups criteria into a single alternative`() {
        val result = assertNotNull(filters {
            Any {
                All {
                    ServiceUuid(serviceA)
                    ServiceData(serviceA, byteArrayOf(1))
                }
                ManufacturerData(0x0059, byteArrayOf(2))
            }
        })
        assertEquals(2, result.size)
        assertNotNull(result[0].serviceUuid)
        assertNotNull(result[0].serviceData)
        assertNull(result[0].manufacturerData)
        assertNotNull(result[1].manufacturerData)
        assertNull(result[1].serviceUuid)
    }

    @Test
    fun `nested Any inside Any is flattened`() {
        val result = assertNotNull(filters {
            Any {
                Name("a")
                Any {
                    Name("b")
                    Name("c")
                }
            }
        })
        assertEquals(listOf("a", "b", "c"), result.map { it.name })
    }

    @Test
    fun `All at top level behaves like plain criteria`() {
        val result = assertNotNull(filters {
            All {
                Name("Nordic")
                ServiceUuid(serviceA)
            }
        })
        assertEquals(1, result.size)
        assertEquals("Nordic", result.single().name)
        assertEquals(serviceA, result.single().serviceUuid?.uuid)
    }

    @Test
    fun `Any rejects service data with mask of different length`() {
        assertFailsWith<IllegalArgumentException> {
            filters { Any { ServiceData(serviceA, byteArrayOf(1, 2), byteArrayOf(0xFF.toByte())) } }
        }
    }

    @Test
    fun `Any rejects manufacturer data with mask of different length`() {
        assertFailsWith<IllegalArgumentException> {
            filters { Any { ManufacturerData(0x0059, byteArrayOf(1, 2), byteArrayOf(0xFF.toByte())) } }
        }
    }

    @Test
    fun `Any rejects custom data with mask of different length`() {
        assertFailsWith<IllegalArgumentException> {
            filters {
                Any { Custom(AdvertisingDataType.APPEARANCE, byteArrayOf(1, 2), byteArrayOf(0xFF.toByte())) }
            }
        }
    }

    // ScanFilter

    @Test
    fun `merged filter prefers own criteria and takes missing from other`() {
        val first = ScanFilter().apply { name = "first" }
        val second = ScanFilter().apply {
            name = "second"
            address = "01:23:45:67:89:AB"
        }
        val merged = first.merged(second)
        assertEquals("first", merged.name)
        assertEquals("01:23:45:67:89:AB", merged.address)
    }

    @Test
    fun `merging does not modify the original filters`() {
        val first = ScanFilter().apply { name = "first" }
        val second = ScanFilter().apply { address = "01:23:45:67:89:AB" }
        val _ = first.merged(second)
        assertNull(first.address)
        assertNull(second.name)
    }

    @Test
    fun `ScanFilter toString lists criteria`() {
        val text = ScanFilter().apply { name = "Nordic" }.toString()
        assertTrue(text.contains("Nordic"), text)
    }
}
