/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net

import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4

import com.android.testutils.assertParcelSane

import java.lang.IllegalStateException

import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(AndroidJUnit4::class)
@SmallTest
class MatchAllNetworkSpecifierTest {
    @Test
    fun testParcel() {
        assertParcelSane(MatchAllNetworkSpecifier(), 0)
    }

    @Test(expected = IllegalStateException::class)
    fun testCanBeSatisfiedBy() {
        val specifier = MatchAllNetworkSpecifier()
        val discoverySession = Mockito.mock(DiscoverySession::class.java)
        val peerHandle = Mockito.mock(PeerHandle::class.java)
        val wifiAwareNetworkSpecifier = WifiAwareNetworkSpecifier.Builder(discoverySession,
                peerHandle).build()
        specifier.canBeSatisfiedBy(wifiAwareNetworkSpecifier)
    }
}
