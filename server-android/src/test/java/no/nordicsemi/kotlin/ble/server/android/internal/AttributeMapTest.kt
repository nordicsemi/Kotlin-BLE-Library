/*
 * Copyright (c) 2026, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.kotlin.ble.server.android.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AttributeMapTest {

    /** Like the native attributes, it does not override equals. */
    private class Attribute(val name: String)

    @Test
    fun `attributes are mapped both ways`() {
        val map = AttributeMap<Attribute>()
        val service = Attribute("service")
        val characteristic = Attribute("characteristic")
        map.register(service, 1)
        map.register(characteristic, 2)

        assertEquals(1, map.idOf(service))
        assertEquals(2, map.idOf(characteristic))
        assertSame(service, map.attributeOf(1))
        assertSame(characteristic, map.attributeOf(2))
        assertEquals(2, map.size)
    }

    @Test
    fun `unknown attributes are not found`() {
        val map = AttributeMap<Attribute>()
        map.register(Attribute("a"), 1)

        assertNull(map.idOf(Attribute("a")))
        assertNull(map.attributeOf(5))
    }

    @Test
    fun `duplicate id is rejected`() {
        val map = AttributeMap<Attribute>()
        map.register(Attribute("a"), 1)

        assertFailsWith<IllegalArgumentException> { map.register(Attribute("b"), 1) }
    }

    @Test
    fun `same attribute can be registered again`() {
        val map = AttributeMap<Attribute>()
        val a = Attribute("a")
        map.register(a, 1)
        map.register(a, 1)

        assertEquals(1, map.size)
    }

    @Test
    fun `clear forgets everything`() {
        val map = AttributeMap<Attribute>()
        val a = Attribute("a")
        map.register(a, 1)
        map.clear()

        assertNull(map.idOf(a))
        assertNull(map.attributeOf(1))
        assertEquals(0, map.size)

        // The IDs can be used again.
        map.register(Attribute("b"), 1)
        assertEquals(1, map.size)
    }
}
