/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.metrics

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.testutils.assertParcelSane
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

private const val NO_LIFETIME: Long = -1L

@RunWith(AndroidJUnit4::class)
@SmallTest
class RaEventTest {
    @Test
    fun testConstructorAndParcel() {
        var raEvent = RaEvent.Builder().build()
        assertEquals(NO_LIFETIME, raEvent.routerLifetime)
        assertEquals(NO_LIFETIME, raEvent.prefixValidLifetime)
        assertEquals(NO_LIFETIME, raEvent.prefixPreferredLifetime)
        assertEquals(NO_LIFETIME, raEvent.routeInfoLifetime)
        assertEquals(NO_LIFETIME, raEvent.rdnssLifetime)
        assertEquals(NO_LIFETIME, raEvent.dnsslLifetime)

        raEvent = RaEvent.Builder()
                .updateRouterLifetime(1)
                .updatePrefixValidLifetime(2)
                .updatePrefixPreferredLifetime(3)
                .updateRouteInfoLifetime(4)
                .updateRdnssLifetime(5)
                .updateDnsslLifetime(6)
                .build()
        assertEquals(1, raEvent.routerLifetime)
        assertEquals(2, raEvent.prefixValidLifetime)
        assertEquals(3, raEvent.prefixPreferredLifetime)
        assertEquals(4, raEvent.routeInfoLifetime)
        assertEquals(5, raEvent.rdnssLifetime)
        assertEquals(6, raEvent.dnsslLifetime)

        raEvent = RaEvent.Builder()
                .updateRouterLifetime(Long.MIN_VALUE)
                .updateRouterLifetime(Long.MAX_VALUE)
                .build()
        assertEquals(Long.MIN_VALUE, raEvent.routerLifetime)

        raEvent = RaEvent(1, 2, 3, 4, 5, 6)
        assertEquals(1, raEvent.routerLifetime)
        assertEquals(2, raEvent.prefixValidLifetime)
        assertEquals(3, raEvent.prefixPreferredLifetime)
        assertEquals(4, raEvent.routeInfoLifetime)
        assertEquals(5, raEvent.rdnssLifetime)
        assertEquals(6, raEvent.dnsslLifetime)

        assertParcelSane(raEvent, 6)
    }
}
