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
import android.os.Build
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4

import com.android.testutils.assertParcelSane
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo

import java.lang.IllegalStateException

import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(AndroidJUnit4::class)
@SmallTest
class MatchAllNetworkSpecifierTest {
    @Rule @JvmField
    val ignoreRule: DevSdkIgnoreRule = DevSdkIgnoreRule()

    private val specifier = MatchAllNetworkSpecifier()
    private val discoverySession = Mockito.mock(DiscoverySession::class.java)
    private val peerHandle = Mockito.mock(PeerHandle::class.java)
    private val wifiAwareNetworkSpecifier = WifiAwareNetworkSpecifier.Builder(discoverySession,
            peerHandle).build()

    @Test
    fun testParcel() {
        assertParcelSane(MatchAllNetworkSpecifier(), 0)
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    @IgnoreAfter(Build.VERSION_CODES.R)
    // Only run this test on Android R.
    // The method - satisfiedBy() has changed to canBeSatisfiedBy() starting from Android R, so the
    // method - canBeSatisfiedBy() cannot be found when running this test on Android Q.
    fun testCanBeSatisfiedBy_OnlyForR() {
        // MatchAllNetworkSpecifier didn't follow its parent class to change the satisfiedBy() to
        // canBeSatisfiedBy(), so if a caller calls MatchAllNetworkSpecifier#canBeSatisfiedBy(), the
        // NetworkSpecifier#canBeSatisfiedBy() will be called actually, and false will be returned.
        // Although it's not meeting the expectation, the behavior still needs to be verified.
        assertFalse(specifier.canBeSatisfiedBy(wifiAwareNetworkSpecifier))
    }

    @Test(expected = IllegalStateException::class)
    @IgnoreUpTo(Build.VERSION_CODES.R)
    fun testCanBeSatisfiedBy() {
        specifier.canBeSatisfiedBy(wifiAwareNetworkSpecifier)
    }
}
