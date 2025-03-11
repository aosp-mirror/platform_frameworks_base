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
import android.net.NetworkStats.DEFAULT_NETWORK_YES
import android.net.NetworkStats.METERED_NO
import android.net.NetworkStats.ROAMING_NO
import android.net.NetworkStats.SET_DEFAULT
import android.net.NetworkStats.TAG_NONE
import android.net.NetworkTemplate
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.testutils.assertNetworkStatsEquals
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkStatsAccumulatorTest {

    @Test
    fun hasEqualParameters_differentParameters_returnsFalse() {
        val wifiTemplate = NetworkTemplate.Builder(NetworkTemplate.MATCH_WIFI).build()
        val mobileTemplate = NetworkTemplate.Builder(NetworkTemplate.MATCH_MOBILE).build()

        val snapshot = NetworkStatsAccumulator(wifiTemplate, false, 0, 0)

        assertThat(snapshot.hasEqualParameters(mobileTemplate, false)).isFalse()
        assertThat(snapshot.hasEqualParameters(wifiTemplate, true)).isFalse()
    }

    @Test
    fun hasSameParameters_equalParameters_returnsTrue() {
        val wifiTemplate1 = NetworkTemplate.Builder(NetworkTemplate.MATCH_WIFI).build()
        val wifiTemplate2 = NetworkTemplate.Builder(NetworkTemplate.MATCH_WIFI).build()

        val snapshot = NetworkStatsAccumulator(wifiTemplate1, false, 0, 0)

        assertThat(snapshot.hasEqualParameters(wifiTemplate1, false)).isTrue()
        assertThat(snapshot.hasEqualParameters(wifiTemplate2, false)).isTrue()
    }

    @Test
    fun queryStats_lessThanOneBucketFromSnapshotEndTime_returnsAllStats() {
        val snapshot = NetworkStatsAccumulator(TEMPLATE, false, 200, 1000)

        // Accumulator has data until 1000 (= 0), and its end-point is still in the history period.
        // Current time is less than one bucket away from snapshot end-point: 1050 - 1000 < 200
        val stats = snapshot.queryStats(1050, FakeStats(500, 1050, 1))

        // After the query at 1050, accumulator should have 1 * (1050 - 1000) = 50 bytes.
        assertNetworkStatsEquals(stats, networkStatsWithBytes(50))
    }

    @Test
    fun queryStats_oneBucketFromSnapshotEndTime_returnsCumulativeStats() {
        val snapshot = NetworkStatsAccumulator(TEMPLATE, false, 200, 1000)

        // Accumulator has data until 1000 (= 0), and its end-point is still in the history period.
        // Current time is one bucket away from snapshot end-point: 1250 - 1000 > 200
        val stats = snapshot.queryStats(1250, FakeStats(550, 1250, 2))

        // After the query at 1250, accumulator should have 2 * (1250 - 1000) = 500 bytes.
        assertNetworkStatsEquals(stats, networkStatsWithBytes(500))
    }

    @Test
    fun queryStats_twoBucketsFromSnapshotEndTime_returnsCumulativeStats() {
        val snapshot = NetworkStatsAccumulator(TEMPLATE, false, 200, 1000)

        // Accumulator has data until 1000 (= 0), and its end-point is in the history period.
        // Current time is two buckets away from snapshot end-point: 1450 - 1000 > 2*200
        val stats = snapshot.queryStats(1450, FakeStats(600, 1450, 3))

        // After the query at 1450, accumulator should have 3 * (1450 - 1000) = 1350 bytes.
        assertNetworkStatsEquals(stats, networkStatsWithBytes(1350))
    }

    @Test
    fun queryStats_manyBucketsFromSnapshotEndTime_returnsCumulativeStats() {
        val snapshot = NetworkStatsAccumulator(TEMPLATE, false, 200, 1000)

        // Accumulator has data until 1000 (= 0), and its end-point is still in the history period.
        // Current time is many buckets away from snapshot end-point
        val stats = snapshot.queryStats(6100, FakeStats(900, 6100, 1))

        // After the query at 6100, accumulator should have 1 * (6100 - 1000) = 5100 bytes.
        assertNetworkStatsEquals(stats, networkStatsWithBytes(5100))
    }

    @Test
    fun queryStats_multipleQueriesAndSameHistoryWindow_returnsCumulativeStats() {
        val snapshot = NetworkStatsAccumulator(TEMPLATE, false, 200, 1000)

        // Accumulator is queried within the history period, whose starting point stays the same.
        // After each query, accumulator should contain bytes from the initial end-point until now.
        val stats1 = snapshot.queryStats(5100, FakeStats(900, 5100, 1))
        val stats2 = snapshot.queryStats(10100, FakeStats(900, 10100, 1))
        val stats3 = snapshot.queryStats(15100, FakeStats(900, 15100, 1))

        assertNetworkStatsEquals(stats1, networkStatsWithBytes(4100))
        assertNetworkStatsEquals(stats2, networkStatsWithBytes(9100))
        assertNetworkStatsEquals(stats3, networkStatsWithBytes(14100))
    }

    @Test
    fun queryStats_multipleQueriesAndSlidingHistoryWindow_returnsCumulativeStats() {
        val snapshot = NetworkStatsAccumulator(TEMPLATE, false, 200, 1000)

        // Accumulator is queried within the history period, whose starting point is moving.
        // After each query, accumulator should contain bytes from the initial end-point until now.
        val stats1 = snapshot.queryStats(5100, FakeStats(900, 5100, 1))
        val stats2 = snapshot.queryStats(10100, FakeStats(4000, 10100, 1))
        val stats3 = snapshot.queryStats(15100, FakeStats(7000, 15100, 1))

        assertNetworkStatsEquals(stats1, networkStatsWithBytes(4100))
        assertNetworkStatsEquals(stats2, networkStatsWithBytes(9100))
        assertNetworkStatsEquals(stats3, networkStatsWithBytes(14100))
    }

    @Test
    fun queryStats_withSnapshotEndTimeBeforeHistoryStart_addsOnlyStatsWithinHistory() {
        val snapshot = NetworkStatsAccumulator(TEMPLATE, false, 200, 1900)

        // Accumulator has data until 1000 (= 0), but its end-point is not in the history period.
        // After the query, accumulator should add only those bytes that are covered by the history.
        val stats = snapshot.queryStats(2700, FakeStats(2200, 2700, 1))

        assertNetworkStatsEquals(stats, networkStatsWithBytes(500))
    }

    /**
     * Simulates equally distributed traffic stats persisted over a set period of time.
     */
    private class FakeStats(
        val historyStartMillis: Long, val currentTimeMillis: Long, val bytesPerMilli: Long
    ) : NetworkStatsAccumulator.StatsQueryFunction {

        override fun queryNetworkStats(
            template: NetworkTemplate, includeTags: Boolean, startTime: Long, endTime: Long
        ): NetworkStats {
            val overlap = overlap(startTime, endTime, historyStartMillis, currentTimeMillis)
            return networkStatsWithBytes(overlap * bytesPerMilli)
        }
    }

    companion object {

        private val TEMPLATE = NetworkTemplate.Builder(NetworkTemplate.MATCH_WIFI).build()

        fun networkStatsWithBytes(bytes: Long): NetworkStats {
            val stats = NetworkStats(0, 1).addEntry(
                NetworkStats.Entry(
                    null,
                    0,
                    SET_DEFAULT,
                    TAG_NONE,
                    METERED_NO,
                    ROAMING_NO,
                    DEFAULT_NETWORK_YES,
                    bytes,
                    bytes / 100,
                    bytes,
                    bytes / 100,
                    0
                )
            )
            return stats
        }

        fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long {
            return maxOf(0L, minOf(aEnd, bEnd) - maxOf(aStart, bStart))
        }
    }
}