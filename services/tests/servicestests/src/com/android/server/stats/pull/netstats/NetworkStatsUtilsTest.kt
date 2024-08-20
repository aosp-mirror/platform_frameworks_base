/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.stats.pull.netstats

import android.net.NetworkStats
import android.net.NetworkStats.DEFAULT_NETWORK_NO
import android.net.NetworkStats.DEFAULT_NETWORK_YES
import android.net.NetworkStats.Entry
import android.net.NetworkStats.METERED_NO
import android.net.NetworkStats.ROAMING_NO
import android.net.NetworkStats.ROAMING_YES
import android.net.NetworkStats.SET_DEFAULT
import android.net.NetworkStats.TAG_NONE
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.testutils.assertEntryEquals
import com.android.testutils.assertNetworkStatsEquals
import com.android.testutils.makePublicStatsFromAndroidNetStats
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Build/Install/Run:
 * atest FrameworksServicesTests:NetworkStatsUtilsTest
 */
@RunWith(AndroidJUnit4::class)
class NetworkStatsUtilsTest {

    @Test
    fun testBucketToEntry() {
        val bucket = makeMockBucket(android.app.usage.NetworkStats.Bucket.UID_ALL,
                android.app.usage.NetworkStats.Bucket.TAG_NONE,
                android.app.usage.NetworkStats.Bucket.STATE_DEFAULT,
                android.app.usage.NetworkStats.Bucket.METERED_YES,
                android.app.usage.NetworkStats.Bucket.ROAMING_NO,
                android.app.usage.NetworkStats.Bucket.DEFAULT_NETWORK_ALL, 1024, 8, 2048, 12)
        val entry = NetworkStatsUtils.fromBucket(bucket)
        val expectedEntry = NetworkStats.Entry(null /* IFACE_ALL */, NetworkStats.UID_ALL,
                NetworkStats.SET_DEFAULT, NetworkStats.TAG_NONE, NetworkStats.METERED_YES,
                NetworkStats.ROAMING_NO, NetworkStats.DEFAULT_NETWORK_ALL, 1024, 8, 2048, 12,
                0 /* operations */)

        assertEntryEquals(expectedEntry, entry)
    }

    @Test
    fun testPublicStatsToAndroidNetStats() {
        val uid1 = 10001
        val uid2 = 10002
        val testIface = "wlan0"
        val testAndroidNetStats = NetworkStats(0L, 3)
                .addEntry(Entry(testIface, uid1, SET_DEFAULT, TAG_NONE,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, 20, 3, 57, 40, 3))
                .addEntry(Entry(
                        testIface, uid2, SET_DEFAULT, TAG_NONE,
                        METERED_NO, ROAMING_YES, DEFAULT_NETWORK_NO, 2, 7, 2, 5, 7))
                .addEntry(Entry(testIface, uid2, SET_DEFAULT, TAG_NONE,
                        METERED_NO, ROAMING_YES, DEFAULT_NETWORK_NO, 4, 5, 3, 1, 8))
        val publicStats: android.app.usage.NetworkStats =
                makePublicStatsFromAndroidNetStats(testAndroidNetStats)
        val androidNetStats: NetworkStats =
                NetworkStatsUtils.fromPublicNetworkStats(publicStats)

        // 1. The public `NetworkStats` class does not include interface information.
        //    Interface details must be removed and items with duplicated
        //    keys need to be merged before making any comparisons.
        // 2. The public `NetworkStats` class lacks an operations field.
        //    Thus, the information will not be preserved during the conversion.
        val expectedStats = NetworkStats(0L, 2)
                .addEntry(Entry(null, uid1, SET_DEFAULT, TAG_NONE,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, 20, 3, 57, 40, 0))
                .addEntry(Entry(null, uid2, SET_DEFAULT, TAG_NONE,
                        METERED_NO, ROAMING_YES, DEFAULT_NETWORK_NO, 6, 12, 5, 6, 0))
        assertNetworkStatsEquals(expectedStats, androidNetStats)
    }

    private fun makeMockBucket(
            uid: Int,
            tag: Int,
            state: Int,
            metered: Int,
            roaming: Int,
            defaultNetwork: Int,
            rxBytes: Long,
            rxPackets: Long,
            txBytes: Long,
            txPackets: Long
    ): android.app.usage.NetworkStats.Bucket {
        val ret: android.app.usage.NetworkStats.Bucket =
                mock(android.app.usage.NetworkStats.Bucket::class.java)
        doReturn(uid).`when`(ret).getUid()
        doReturn(tag).`when`(ret).getTag()
        doReturn(state).`when`(ret).getState()
        doReturn(metered).`when`(ret).getMetered()
        doReturn(roaming).`when`(ret).getRoaming()
        doReturn(defaultNetwork).`when`(ret).getDefaultNetworkStatus()
        doReturn(rxBytes).`when`(ret).getRxBytes()
        doReturn(rxPackets).`when`(ret).getRxPackets()
        doReturn(txBytes).`when`(ret).getTxBytes()
        doReturn(txPackets).`when`(ret).getTxPackets()
        return ret
    }
}