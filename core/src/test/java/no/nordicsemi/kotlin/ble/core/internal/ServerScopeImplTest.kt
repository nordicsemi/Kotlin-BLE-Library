package no.nordicsemi.kotlin.ble.core.internal

import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Descriptor
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.ServerScope
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.core.util.fromShortUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ServerScopeImplTest {

    private fun build(block: ServerScope.() -> Unit): List<ServiceDefinition> =
        ServerScopeImpl().apply(block).build()

    private fun ServiceDefinition.allHandles(): List<Int> =
        listOf(instanceId) +
        characteristics.flatMap { c -> listOf(c.instanceId) + c.descriptors.map { it.instanceId } } +
        includedServices.flatMap { it.allHandles() }

    // Structure

    @Test
    fun `empty scope builds no services`() {
        assertTrue(build { }.isEmpty())
    }

    @Test
    fun `services are built in declaration order`() {
        val services = build {
            Service(0x1800)
            Service(0x180F)
            Service(0x180A)
        }
        assertEquals(
            listOf(Uuid.fromShortUuid(0x1800), Uuid.fromShortUuid(0x180F), Uuid.fromShortUuid(0x180A)),
            services.map { it.uuid }
        )
    }

    @Test
    fun `characteristics keep properties permissions and order`() {
        val services = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ, Permission.READ)
                Characteristic(
                    0x2A1A,
                    CharacteristicProperty.WRITE and CharacteristicProperty.READ,
                    Permission.READ and Permission.WRITE
                )
            }
        }
        val characteristics = services.single().characteristics
        assertEquals(2, characteristics.size)
        assertEquals(Uuid.fromShortUuid(0x2A19), characteristics[0].uuid)
        assertEquals(setOf(CharacteristicProperty.READ), characteristics[0].properties)
        assertEquals(setOf(Permission.READ), characteristics[0].permissions)
        assertEquals(setOf(CharacteristicProperty.WRITE, CharacteristicProperty.READ), characteristics[1].properties)
        assertEquals(setOf(Permission.READ, Permission.WRITE), characteristics[1].permissions)
    }

    @Test
    fun `included services are nested in their parent`() {
        val services = build {
            Service(0x180F) {
                IncludedService(0x180A) {
                    Characteristic(0x2A29, CharacteristicProperty.READ)
                }
            }
        }
        val included = services.single().includedServices.single()
        assertEquals(Uuid.fromShortUuid(0x180A), included.uuid)
        assertEquals(1, included.characteristics.size)
        assertTrue(services.single().characteristics.isEmpty())
    }

    @Test
    fun `services with the same UUID are both kept`() {
        val services = build {
            Service(0x180F)
            Service(0x180F)
        }
        assertEquals(2, services.size)
    }

    // Handles

    @Test
    fun `handles are unique across services characteristics and descriptors`() {
        val services = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ and CharacteristicProperty.NOTIFY) {
                    CharacteristicUserDescriptionDescriptor("Battery")
                }
                Characteristic(0x2A1A, CharacteristicProperty.READ)
                IncludedService(0x180A) {
                    Characteristic(0x2A29, CharacteristicProperty.READ)
                }
            }
            Service(0x1801) {
                Characteristic(0x2A05, CharacteristicProperty.INDICATE)
            }
        }
        val handles = services.flatMap { it.allHandles() }
        assertEquals(handles.size, handles.toSet().size, "Handles: $handles")
    }

    @Test
    fun `handles increase in declaration order`() {
        val services = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ) {
                    Descriptor(0x2910, Permission.READ)
                    Descriptor(0x2911, Permission.READ)
                }
                Characteristic(0x2A1A, CharacteristicProperty.READ)
            }
        }
        val service = services.single()
        val first = service.characteristics[0]
        val second = service.characteristics[1]
        assertTrue(service.instanceId < first.instanceId)
        assertTrue(first.instanceId < first.descriptors[0].instanceId)
        assertTrue(first.descriptors[0].instanceId < first.descriptors[1].instanceId)
        assertTrue(first.descriptors[1].instanceId < second.instanceId)
    }

    @Test
    fun `no attribute uses reserved handle 0`() {
        // ATT handle 0x0000 is reserved and must not be used.
        val services = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ)
            }
        }
        assertTrue(services.flatMap { it.allHandles() }.all { it > 0 })
    }

    @Test
    fun `Characteristic returns its handle`() {
        var handle = -1
        val services = build {
            Service(0x180F) {
                handle = Characteristic(0x2A19, CharacteristicProperty.READ)
            }
        }
        assertEquals(services.single().characteristics.single().instanceId, handle)
    }

    // Descriptors

    @Test
    fun `descriptors keep declaration order and permissions`() {
        val services = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ) {
                    Descriptor(0x2910, Permission.READ)
                    Descriptor(0x2911, Permission.READ and Permission.WRITE)
                }
            }
        }
        val descriptors = services.single().characteristics.single().descriptors
        assertEquals(listOf(Uuid.fromShortUuid(0x2910), Uuid.fromShortUuid(0x2911)), descriptors.map { it.uuid })
        assertEquals(setOf(Permission.READ, Permission.WRITE), descriptors[1].permissions)
    }

    @Test
    fun `CCCD is added automatically for notifying characteristic`() {
        val characteristic = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.NOTIFY)
            }
        }.single().characteristics.single()
        val cccd = characteristic.descriptors.single { it.uuid == Descriptor.CLIENT_CHAR_CONF_UUID }
        assertIs<CCCD>(cccd)
        assertFalse(cccd.enabled)
    }

    @Test
    fun `CCCD is added automatically for indicating characteristic`() {
        val characteristic = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.INDICATE)
            }
        }.single().characteristics.single()
        assertNotNull(characteristic.descriptors.singleOrNull { it.uuid == Descriptor.CLIENT_CHAR_CONF_UUID })
    }

    @Test
    fun `CCCD is readable and writable`() {
        val characteristic = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.NOTIFY)
            }
        }.single().characteristics.single()
        assertEquals(setOf(Permission.READ, Permission.WRITE), characteristic.descriptors.single().permissions)
    }

    @Test
    fun `CCCD is not added to characteristic that cannot notify or indicate`() {
        val characteristic = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ and CharacteristicProperty.WRITE)
            }
        }.single().characteristics.single()
        assertTrue(characteristic.descriptors.isEmpty())
    }

    @Test
    fun `explicit CCCD is not duplicated`() {
        val characteristic = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.NOTIFY) {
                    ClientCharacteristicConfigurationDescriptor(enabled = true)
                }
            }
        }.single().characteristics.single()
        val cccd = characteristic.descriptors.single()
        assertIs<CCCD>(cccd)
        assertTrue(cccd.enabled)
    }

    @Test
    fun `CCCD is readable and writable by default`() {
        val cccd = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.NOTIFY)
            }
        }.single().characteristics.single().descriptors.single()
        assertEquals(Permission.READ and Permission.WRITE, cccd.permissions)
    }

    @Test
    fun `explicit CCCD can require encryption`() {
        val cccd = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.NOTIFY) {
                    ClientCharacteristicConfigurationDescriptor(
                        permissions = Permission.READ and Permission.WRITE_ENCRYPTED,
                    )
                }
            }
        }.single().characteristics.single().descriptors.single()
        assertEquals(Permission.READ and Permission.WRITE_ENCRYPTED, cccd.permissions)
    }

    @Test
    fun `adding CCCD twice fails`() {
        assertFailsWith<IllegalArgumentException> {
            build {
                Service(0x180F) {
                    Characteristic(0x2A19, CharacteristicProperty.NOTIFY) {
                        ClientCharacteristicConfigurationDescriptor()
                        ClientCharacteristicConfigurationDescriptor()
                    }
                }
            }
        }
    }

    @Test
    fun `CCCD cannot be added as a generic descriptor`() {
        assertFailsWith<IllegalArgumentException> {
            build {
                Service(0x180F) {
                    Characteristic(0x2A19, CharacteristicProperty.NOTIFY) {
                        Descriptor(Descriptor.CLIENT_CHAR_CONF_UUID, Permission.READ)
                    }
                }
            }
        }
    }

    @Test
    fun `CUD cannot be added as a generic descriptor`() {
        assertFailsWith<IllegalArgumentException> {
            build {
                Service(0x180F) {
                    Characteristic(0x2A19, CharacteristicProperty.READ) {
                        Descriptor(Descriptor.CHAR_USER_DESC_UUID, Permission.READ)
                    }
                }
            }
        }
    }

    @Test
    fun `adding CUD twice fails`() {
        assertFailsWith<IllegalArgumentException> {
            build {
                Service(0x180F) {
                    Characteristic(0x2A19, CharacteristicProperty.READ) {
                        CharacteristicUserDescriptionDescriptor("A")
                        CharacteristicUserDescriptionDescriptor("B")
                    }
                }
            }
        }
    }

    @Test
    fun `adding CEPD twice fails`() {
        assertFailsWith<IllegalArgumentException> {
            build {
                Service(0x180F) {
                    Characteristic(0x2A19, CharacteristicProperty.READ) {
                        CharacteristicExtendedPropertiesDescriptor()
                        CharacteristicExtendedPropertiesDescriptor()
                    }
                }
            }
        }
    }

    @Test
    fun `read-only CUD is read-only and does not add extended properties`() {
        val characteristic = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ) {
                    CharacteristicUserDescriptionDescriptor("Battery", writable = false)
                }
            }
        }.single().characteristics.single()
        val cud = characteristic.descriptors.single()
        assertIs<CUD>(cud)
        assertEquals(setOf(Permission.READ), cud.permissions)
        assertFalse(CharacteristicProperty.EXTENDED_PROPERTIES in characteristic.properties)
    }

    @Test
    fun `writable CUD adds CEPD with writable auxiliaries and extended properties flag`() {
        val characteristic = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ) {
                    CharacteristicUserDescriptionDescriptor("Battery", writable = true)
                }
            }
        }.single().characteristics.single()
        val cepd = characteristic.descriptors.filterIsInstance<CEPD>().single()
        assertTrue(cepd.writableAuxiliaries)
        assertTrue(CharacteristicProperty.EXTENDED_PROPERTIES in characteristic.properties)
    }

    @Test
    fun `explicit CEPD requires extended properties flag in characteristic properties`() {
        // Bluetooth Core Spec: the Characteristic Extended Properties descriptor exists
        // if and only if the Extended Properties bit is set in the characteristic properties.
        val characteristic = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ) {
                    CharacteristicExtendedPropertiesDescriptor(reliableWrite = true)
                }
            }
        }.single().characteristics.single()
        assertTrue(CharacteristicProperty.EXTENDED_PROPERTIES in characteristic.properties)
    }

    @Test
    fun `CEPD is readable only`() {
        val characteristic = build {
            Service(0x180F) {
                Characteristic(0x2A19, CharacteristicProperty.READ) {
                    CharacteristicExtendedPropertiesDescriptor(reliableWrite = true)
                }
            }
        }.single().characteristics.single()
        assertEquals(setOf(Permission.READ), characteristic.descriptors.single().permissions)
    }

    @Test
    fun `original properties set is not modified by builder`() {
        val properties = setOf(CharacteristicProperty.READ)
        val _ = build {
            Service(0x180F) {
                Characteristic(0x2A19, properties, emptySet()) {
                    CharacteristicUserDescriptionDescriptor("Battery", writable = true)
                }
            }
        }
        assertEquals(setOf(CharacteristicProperty.READ), properties)
    }
}
