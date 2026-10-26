package no.nordicsemi.kotlin.ble.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AdvertisingSetParametersTest {

    // Legacy defaults

    @Test
    fun `legacy parameters are discoverable when connectable by default`() {
        assertTrue(LegacyAdvertisingSetParameters(connectable = true).discoverable)
        assertFalse(LegacyAdvertisingSetParameters(connectable = false).discoverable)
    }

    @Test
    fun `legacy parameters use medium interval and TX power by default`() {
        val params = LegacyAdvertisingSetParameters(connectable = true)
        assertEquals(AdvertisingInterval.MEDIUM, params.interval)
        assertEquals(TxPowerLevel.MEDIUM, params.txPowerLevel)
    }

    @Test
    fun `advertising interval presets are within the allowed range`() {
        listOf(AdvertisingInterval.LOW, AdvertisingInterval.MEDIUM, AdvertisingInterval.HIGH).forEach {
            val _ = LegacyAdvertisingSetParameters(connectable = true, interval = it)
        }
    }

    @Test
    fun `TX power level presets are valid and ordered`() {
        assertTrue(TxPowerLevel.ULTRA_LOW < TxPowerLevel.LOW)
        assertTrue(TxPowerLevel.LOW < TxPowerLevel.MEDIUM)
        assertTrue(TxPowerLevel.MEDIUM < TxPowerLevel.HIGH)
        listOf(TxPowerLevel.ULTRA_LOW, TxPowerLevel.LOW, TxPowerLevel.MEDIUM, TxPowerLevel.HIGH).forEach {
            val _ = LegacyAdvertisingSetParameters(connectable = true, txPowerLevel = it)
        }
    }

    // Interval validation

    @Test
    fun `interval boundaries of 20 ms and 10_24 s are accepted`() {
        val _ = LegacyAdvertisingSetParameters(connectable = true, interval = 20.milliseconds)
        val _ = LegacyAdvertisingSetParameters(connectable = true, interval = 10240.milliseconds)
    }

    @Test
    fun `interval shorter than 20 ms is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LegacyAdvertisingSetParameters(connectable = true, interval = 19999.microseconds)
        }
    }

    @Test
    fun `interval longer than 10_24 s is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LegacyAdvertisingSetParameters(connectable = true, interval = 10241.milliseconds)
        }
    }

    @Test
    fun `zero and negative interval are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LegacyAdvertisingSetParameters(connectable = true, interval = 0.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            LegacyAdvertisingSetParameters(connectable = true, interval = (-1).seconds)
        }
    }

    // TX power validation

    @Test
    fun `TX power boundaries of -127 and 10 dBm are accepted`() {
        val _ = LegacyAdvertisingSetParameters(connectable = true, txPowerLevel = -127)
        val _ = LegacyAdvertisingSetParameters(connectable = true, txPowerLevel = 10)
    }

    @Test
    fun `TX power below -127 dBm is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LegacyAdvertisingSetParameters(connectable = true, txPowerLevel = -128)
        }
    }

    @Test
    fun `TX power above 10 dBm is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LegacyAdvertisingSetParameters(connectable = true, txPowerLevel = 11)
        }
    }

    // Bluetooth 5

    @Test
    fun `Bluetooth 5 parameters have 1M PHYs and no extra features by default`() {
        val params = Bluetooth5AdvertisingSetParameters(connectable = true)
        assertEquals(PrimaryPhy.PHY_LE_1M, params.primaryPhy)
        assertEquals(Phy.PHY_LE_1M, params.secondaryPhy)
        assertFalse(params.anonymous)
        assertFalse(params.scannable)
        assertFalse(params.includeTxPowerLevel)
    }

    @Test
    fun `Bluetooth 5 parameters validate interval and TX power as well`() {
        assertFailsWith<IllegalArgumentException> {
            Bluetooth5AdvertisingSetParameters(connectable = true, interval = 10.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            Bluetooth5AdvertisingSetParameters(connectable = true, txPowerLevel = 20)
        }
    }

    @Test
    fun `anonymous advertising cannot be connectable`() {
        assertFailsWith<IllegalArgumentException> {
            Bluetooth5AdvertisingSetParameters(connectable = true, anonymous = true)
        }
    }

    @Test
    fun `anonymous advertising cannot be scannable`() {
        assertFailsWith<IllegalArgumentException> {
            Bluetooth5AdvertisingSetParameters(connectable = false, scannable = true, anonymous = true)
        }
    }

    @Test
    fun `anonymous non-connectable non-scannable advertising is allowed`() {
        val params = Bluetooth5AdvertisingSetParameters(connectable = false, anonymous = true)
        assertTrue(params.anonymous)
    }

    @Test
    fun `HDT PHY cannot be used as secondary PHY`() {
        assertFailsWith<IllegalArgumentException> {
            Bluetooth5AdvertisingSetParameters(connectable = true, secondaryPhy = Phy.PHY_LE_HDT)
        }
    }

    @Test
    fun `1M 2M and Coded PHYs can be used as secondary PHY`() {
        listOf(Phy.PHY_LE_1M, Phy.PHY_LE_2M, Phy.PHY_LE_CODED).forEach {
            val _ = Bluetooth5AdvertisingSetParameters(connectable = true, secondaryPhy = it)
        }
    }

    @Test
    fun `Coded PHY can be used as primary PHY`() {
        val params = Bluetooth5AdvertisingSetParameters(connectable = true, primaryPhy = PrimaryPhy.PHY_LE_CODED)
        assertEquals(PrimaryPhy.PHY_LE_CODED, params.primaryPhy)
    }
}
