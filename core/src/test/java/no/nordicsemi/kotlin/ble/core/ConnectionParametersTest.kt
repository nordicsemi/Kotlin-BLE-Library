package no.nordicsemi.kotlin.ble.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionParametersTest {

    // Factory

    @Test
    fun `invoke without arguments returns Unknown`() {
        assertEquals(ConnectionParameters.Unknown, ConnectionParameters())
    }

    @Test
    fun `invoke with raw values returns Specified with the same values`() {
        val params = ConnectionParameters(24, 2, 400)
        assertIs<ConnectionParameters.Specified>(params)
        assertEquals(24, params.connectionInterval)
        assertEquals(2, params.latency)
        assertEquals(400, params.supervisionTimeout)
    }

    // Validation of raw values

    @Test
    fun `connection interval boundaries are accepted`() {
        val _ = ConnectionParameters.Specified(6, 0, 400)
        val _ = ConnectionParameters.Specified(3200, 0, 3200)
    }

    @Test
    fun `connection interval below 6 is rejected`() {
        assertFailsWith<IllegalArgumentException> { ConnectionParameters.Specified(5, 0, 400) }
    }

    @Test
    fun `connection interval above 3200 is rejected`() {
        assertFailsWith<IllegalArgumentException> { ConnectionParameters.Specified(3201, 0, 400) }
    }

    @Test
    fun `latency boundaries are accepted`() {
        val _ = ConnectionParameters.Specified(24, 0, 400)
        val _ = ConnectionParameters.Specified(24, 499, 400)
    }

    @Test
    fun `negative latency is rejected`() {
        assertFailsWith<IllegalArgumentException> { ConnectionParameters.Specified(24, -1, 400) }
    }

    @Test
    fun `latency above 499 is rejected`() {
        assertFailsWith<IllegalArgumentException> { ConnectionParameters.Specified(24, 500, 400) }
    }

    @Test
    fun `supervision timeout boundaries are accepted`() {
        val _ = ConnectionParameters.Specified(24, 0, 10)
        val _ = ConnectionParameters.Specified(24, 0, 3200)
    }

    @Test
    fun `supervision timeout below 10 is rejected`() {
        assertFailsWith<IllegalArgumentException> { ConnectionParameters.Specified(24, 0, 9) }
    }

    @Test
    fun `supervision timeout above 3200 is rejected`() {
        assertFailsWith<IllegalArgumentException> { ConnectionParameters.Specified(24, 0, 3201) }
    }

    // Unit conversions

    @Test
    fun `connection interval is expressed in 1_25 ms units`() {
        assertEquals(30L, ConnectionParameters.Specified(24, 0, 400).connectionIntervalMillis)
        assertEquals(4000L, ConnectionParameters.Specified(3200, 0, 3200).connectionIntervalMillis)
    }

    @Test
    fun `supervision timeout is expressed in 10 ms units`() {
        assertEquals(4000L, ConnectionParameters.Specified(24, 0, 400).supervisionTimeoutMillis)
        assertEquals(100L, ConnectionParameters.Specified(24, 0, 10).supervisionTimeoutMillis)
    }

    @Test
    fun `Duration constructor converts milliseconds to protocol units`() {
        val params = ConnectionParameters.Specified(30.milliseconds, 0, 4.seconds)
        assertEquals(24, params.connectionInterval)
        assertEquals(400, params.supervisionTimeout)
    }

    @Test
    fun `Duration constructor round trips with millis properties`() {
        val params = ConnectionParameters.Specified(50.milliseconds, 3, 6.seconds)
        assertEquals(50L, params.connectionIntervalMillis)
        assertEquals(6000L, params.supervisionTimeoutMillis)
    }

    @Test
    fun `Duration invoke on companion creates Specified`() {
        val params = ConnectionParameters(30.milliseconds, 1, 2.seconds)
        assertEquals(ConnectionParameters.Specified(24, 1, 200), params)
    }

    @Test
    fun `Duration constructor maps the shortest interval of 7_5 ms to 6 units`() {
        // 7.5 ms is the lowest interval allowed by the Bluetooth specification.
        val params = ConnectionParameters.Specified(7.5.milliseconds, 0, 1.seconds)
        assertEquals(6, params.connectionInterval)
    }

    @Test
    fun `Duration constructor converts intervals that are not a multiple of 1_25 ms rounding down`() {
        // 11 ms is 8.8 units.
        assertEquals(8, ConnectionParameters.Specified(11.milliseconds, 0, 1.seconds).connectionInterval)
        // 7.4 ms is 5.92 units, which is rounded down to 5, below the shortest interval.
        assertFailsWith<IllegalArgumentException> {
            ConnectionParameters.Specified(7.4.milliseconds, 0, 1.seconds)
        }
    }

    @Test
    fun `Duration constructor maps the longest interval of 4 s to 3200 units`() {
        val params = ConnectionParameters.Specified(4000.milliseconds, 0, 32.seconds)
        assertEquals(3200, params.connectionInterval)
        assertEquals(3200, params.supervisionTimeout)
    }

    @Test
    fun `Duration constructor rejects intervals shorter than 7_5 ms`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionParameters.Specified(5.milliseconds, 0, 1.seconds)
        }
    }

    @Test
    fun `Duration constructor rejects supervision timeouts shorter than 100 ms`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionParameters.Specified(30.milliseconds, 0, 90.milliseconds)
        }
    }

    // Equality

    @Test
    fun `Specified with equal values are equal`() {
        assertEquals(ConnectionParameters.Specified(24, 0, 400), ConnectionParameters.Specified(24, 0, 400))
    }

    @Test
    fun `Specified differs from Unknown`() {
        assertNotEquals<ConnectionParameters>(ConnectionParameters.Unknown, ConnectionParameters.Specified(24, 0, 400))
    }

    @Test
    fun `toString mentions values in protocol units and milliseconds`() {
        val text = ConnectionParameters.Specified(24, 0, 400).toString()
        assertEquals("Interval=24 (30 ms), Latency=0, Timeout=400 (4000 ms)", text)
    }
}
