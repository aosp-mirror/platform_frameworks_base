/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.connectivity

import android.net.INetworkOfferCallback
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import kotlin.test.assertFalse
import kotlin.test.assertTrue

const val POLICY_NONE = 0L

@RunWith(AndroidJUnit4::class)
@SmallTest
class NetworkOfferTest {
    val mockCallback = mock(INetworkOfferCallback::class.java)

    @Test
    fun testOfferNeededUnneeded() {
        val score = FullScore(50, POLICY_NONE)
        val offer = NetworkOffer(score, NetworkCapabilities.Builder().build(), mockCallback,
                1 /* providerId */)
        val request1 = mock(NetworkRequest::class.java)
        val request2 = mock(NetworkRequest::class.java)
        offer.onNetworkNeeded(request1)
        verify(mockCallback).onNetworkNeeded(eq(request1))
        assertTrue(offer.neededFor(request1))
        assertFalse(offer.neededFor(request2))

        offer.onNetworkNeeded(request2)
        verify(mockCallback).onNetworkNeeded(eq(request2))
        assertTrue(offer.neededFor(request1))
        assertTrue(offer.neededFor(request2))

        // Note that the framework never calls onNetworkNeeded multiple times with the same
        // request without calling onNetworkUnneeded first. It would be incorrect usage and the
        // behavior would be undefined, so there is nothing to test.

        offer.onNetworkUnneeded(request1)
        verify(mockCallback).onNetworkUnneeded(eq(request1))
        assertFalse(offer.neededFor(request1))
        assertTrue(offer.neededFor(request2))

        offer.onNetworkUnneeded(request2)
        verify(mockCallback).onNetworkUnneeded(eq(request2))
        assertFalse(offer.neededFor(request1))
        assertFalse(offer.neededFor(request2))
    }
}
