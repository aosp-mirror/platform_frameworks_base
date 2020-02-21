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

package android.net

import android.net.NetworkScore.Metrics.BANDWIDTH_UNKNOWN
import android.net.NetworkScore.POLICY_DEFAULT_SUBSCRIPTION
import android.net.NetworkScore.POLICY_IGNORE_ON_WIFI
import android.net.NetworkScore.RANGE_MEDIUM
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.testutils.assertParcelSane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_SCORE = 80

@RunWith(AndroidJUnit4::class)
@SmallTest
class NetworkScoreTest {
    @Test
    fun testParcelNetworkScore() {
        val defaultCap = NetworkCapabilities()
        val builder = NetworkScore.Builder().setLegacyScore(TEST_SCORE)
        assertEquals(TEST_SCORE, builder.build().getLegacyScore())
        assertParcelSane(builder.build(), 7)

        builder.addPolicy(POLICY_IGNORE_ON_WIFI)
                .addPolicy(POLICY_DEFAULT_SUBSCRIPTION)
                .setLinkLayerMetrics(NetworkScore.Metrics(44 /* latency */,
                        380 /* downlinkBandwidth */, BANDWIDTH_UNKNOWN /* uplinkBandwidth */))
                .setEndToEndMetrics(NetworkScore.Metrics(11 /* latency */,
                        BANDWIDTH_UNKNOWN /* downlinkBandwidth */, 100_000 /* uplinkBandwidth */))
                .setRange(NetworkScore.RANGE_MEDIUM)
        assertParcelSane(builder.build(), 7)
        builder.clearPolicy(POLICY_IGNORE_ON_WIFI)
        val ns = builder.build()
        assertParcelSane(ns, 7)
        assertFalse(ns.hasPolicy(POLICY_IGNORE_ON_WIFI))
        assertTrue(ns.hasPolicy(POLICY_DEFAULT_SUBSCRIPTION))

        val exitingNs = ns.withExiting(true)
        assertNotEquals(ns.isExiting, exitingNs.isExiting)
        assertNotEquals(ns, exitingNs)
        assertParcelSane(exitingNs, 7)
    }

    @Test
    fun testEqualsNetworkScore() {
        val builder1 = NetworkScore.Builder()
        val builder2 = NetworkScore.Builder()
        assertTrue(builder1.build().equals(builder2.build()))
        assertEquals(builder1.build().hashCode(), builder2.build().hashCode())

        builder1.setLegacyScore(TEST_SCORE)
        assertFalse(builder1.build().equals(builder2.build()))
        assertNotEquals(builder1.hashCode(), builder2.hashCode())
        builder2.setLegacyScore(TEST_SCORE)
        assertTrue(builder1.build().equals(builder2.build()))
        assertEquals(builder1.build().hashCode(), builder2.build().hashCode())
    }

    @Test
    fun testBuilderEquals() {
        val ns = NetworkScore.Builder()
                .addPolicy(POLICY_IGNORE_ON_WIFI)
                .addPolicy(POLICY_DEFAULT_SUBSCRIPTION)
                .setExiting(true)
                .setEndToEndMetrics(NetworkScore.Metrics(145, 2500, 1430))
                .setRange(RANGE_MEDIUM)
                .setSignalStrength(400)
                .build()
        assertEquals(ns, NetworkScore.Builder(ns).build())
    }
}
