/*
 * Copyright (c) 2024, Nordic Semiconductor
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

package no.nordicsemi.kotlin.ble.client.android.mock

import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests of the mock Android environments.
 */
class MockAndroidEnvironmentTest {

    @Test
    fun `environment of Android 4_3 can be created`() {
        assertEquals(23, MockAndroidEnvironment.Api18().maxAttMtu)
    }

    @Test
    fun `environment of Android 4_4 can be created`() {
        assertEquals(23, MockAndroidEnvironment.Api19().maxAttMtu)
    }

    @Test
    fun `environment of Android 5 and newer supports the highest MTU`() {
        assertEquals(517, MockAndroidEnvironment.Api21().maxAttMtu)
        assertEquals(517, MockAndroidEnvironment.Api31().maxAttMtu)
    }

    @Test
    fun `android does not request the MTU automatically`() {
        // Android 17 can, but the library requests the MTU itself, before it reports the connection.
        assertFalse(MockAndroidEnvironment.Api31().automaticallyRequestsMtu)
        assertFalse(MockAndroidEnvironment.Api36().automaticallyRequestsMtu)
        assertFalse(MockAndroidEnvironment.Api37().automaticallyRequestsMtu)
    }
}

