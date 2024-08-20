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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.testutils.assertEntryEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

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