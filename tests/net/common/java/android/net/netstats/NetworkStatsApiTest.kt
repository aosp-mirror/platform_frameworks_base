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

package android.net.netstats

import android.net.NetworkStats
import android.net.NetworkStats.DEFAULT_NETWORK_NO
import android.net.NetworkStats.DEFAULT_NETWORK_YES
import android.net.NetworkStats.Entry
import android.net.NetworkStats.IFACE_VT
import android.net.NetworkStats.METERED_NO
import android.net.NetworkStats.METERED_YES
import android.net.NetworkStats.ROAMING_NO
import android.net.NetworkStats.ROAMING_YES
import android.net.NetworkStats.SET_DEFAULT
import android.net.NetworkStats.SET_FOREGROUND
import android.net.NetworkStats.TAG_NONE
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.assertFieldCountEquals
import com.android.testutils.assertNetworkStatsEquals
import com.android.testutils.assertParcelingIsLossless
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals

@RunWith(JUnit4::class)
@SmallTest
class NetworkStatsApiTest {
    @Rule
    @JvmField
    val ignoreRule = DevSdkIgnoreRule()

    private val testStatsEmpty = NetworkStats(0L, 0)

    // stats1 and stats2 will have some entries with common keys, which are expected to
    // be merged if performing add on these 2 stats.
    private val testStats1 = NetworkStats(0L, 0)
            // Entries which only appear in set1.
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, 20, 3, 57, 40, 3))
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_YES, DEFAULT_NETWORK_NO, 31, 7, 24, 5, 8))
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_YES, ROAMING_NO, DEFAULT_NETWORK_NO, 25, 3, 47, 8, 2))
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_FOREGROUND, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 37, 52, 1, 10, 4))
            // Entries which are common for set1 and set2.
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 101, 2, 103, 4, 5))
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, 0x80,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 17, 2, 11, 1, 0))
            .addEntry(Entry(TEST_IFACE, TEST_UID2, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 40, 1, 0, 0, 8))
            .addEntry(Entry(IFACE_VT, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 3, 1, 6, 2, 0))

    private val testStats2 = NetworkStats(0L, 0)
            // Entries which are common for set1 and set2.
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, 0x80,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 3, 15, 2, 31, 1))
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_FOREGROUND, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 13, 61, 10, 1, 45))
            .addEntry(Entry(TEST_IFACE, TEST_UID2, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 11, 2, 3, 4, 7))
            .addEntry(Entry(IFACE_VT, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 4, 3, 2, 1, 0))
            // Entry which only appears in set2.
            .addEntry(Entry(IFACE_VT, TEST_UID2, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 2, 3, 7, 8, 0))

    // This is a result of adding stats1 and stats2, while the merging of common key items is
    // subject to test later, this should not be initialized with for a loop to add stats1
    // and stats2 above.
    private val testStats3 = NetworkStats(0L, 9)
            // Entries which are unique either in stats1 or stats2.
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 101, 2, 103, 4, 5))
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_YES, DEFAULT_NETWORK_NO, 31, 7, 24, 5, 8))
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_YES, ROAMING_NO, DEFAULT_NETWORK_NO, 25, 3, 47, 8, 2))
            .addEntry(Entry(IFACE_VT, TEST_UID2, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 2, 3, 7, 8, 0))
            // Entries which are common for stats1 and stats2 are being merged.
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, 20, 3, 57, 40, 3))
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, 0x80,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 20, 17, 13, 32, 1))
            .addEntry(Entry(TEST_IFACE, TEST_UID1, SET_FOREGROUND, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 50, 113, 11, 11, 49))
            .addEntry(Entry(TEST_IFACE, TEST_UID2, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 51, 3, 3, 4, 15))
            .addEntry(Entry(IFACE_VT, TEST_UID1, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 7, 4, 8, 3, 0))

    companion object {
        private const val TEST_IFACE = "test0"
        private const val TEST_UID1 = 1001
        private const val TEST_UID2 = 1002
    }

    @Before
    fun setUp() {
        assertEquals(8, testStats1.size())
        assertEquals(5, testStats2.size())
        assertEquals(9, testStats3.size())
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testAddEntry() {
        val expectedEntriesInStats2 = arrayOf(
                Entry(TEST_IFACE, TEST_UID1, SET_DEFAULT, 0x80,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 3, 15, 2, 31, 1),
                Entry(TEST_IFACE, TEST_UID1, SET_FOREGROUND, TAG_NONE,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 13, 61, 10, 1, 45),
                Entry(TEST_IFACE, TEST_UID2, SET_DEFAULT, TAG_NONE,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 11, 2, 3, 4, 7),
                Entry(IFACE_VT, TEST_UID1, SET_DEFAULT, TAG_NONE,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 4, 3, 2, 1, 0),
                Entry(IFACE_VT, TEST_UID2, SET_DEFAULT, TAG_NONE,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 2, 3, 7, 8, 0))

        // While testStats* are already initialized with addEntry, verify content added
        // matches expectation.
        for (i in expectedEntriesInStats2.indices) {
            val entry = testStats2.getValues(i, null)
            assertEquals(expectedEntriesInStats2[i], entry)
        }

        // Verify entry updated with addEntry.
        val stats = testStats2.addEntry(Entry(IFACE_VT, TEST_UID1, SET_DEFAULT, TAG_NONE,
                METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 12, -5, 7, 0, 9))
        assertEquals(Entry(IFACE_VT, TEST_UID1, SET_DEFAULT, TAG_NONE,
                METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 16, -2, 9, 1, 9),
                stats.getValues(3, null))
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testAdd() {
        var stats = NetworkStats(0L, 0)
        assertNetworkStatsEquals(testStatsEmpty, stats)
        stats = stats.add(testStats2)
        assertNetworkStatsEquals(testStats2, stats)
        stats = stats.add(testStats1)
        // EMPTY + STATS2 + STATS1 = STATS3
        assertNetworkStatsEquals(testStats3, stats)
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testParcelUnparcel() {
        assertParcelingIsLossless(testStatsEmpty)
        assertParcelingIsLossless(testStats1)
        assertParcelingIsLossless(testStats2)
        assertFieldCountEquals(15, NetworkStats::class.java)
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testDescribeContents() {
        assertEquals(0, testStatsEmpty.describeContents())
        assertEquals(0, testStats1.describeContents())
        assertEquals(0, testStats2.describeContents())
        assertEquals(0, testStats3.describeContents())
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testSubtract() {
        // STATS3 - STATS2 = STATS1
        assertNetworkStatsEquals(testStats1, testStats3.subtract(testStats2))
        // STATS3 - STATS1 = STATS2
        assertNetworkStatsEquals(testStats2, testStats3.subtract(testStats1))
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testMethodsDontModifyReceiver() {
        listOf(testStatsEmpty, testStats1, testStats2, testStats3).forEach {
            val origStats = it.clone()
            it.addEntry(Entry(TEST_IFACE, TEST_UID1, SET_FOREGROUND, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 13, 61, 10, 1, 45))
            it.add(testStats3)
            it.subtract(testStats1)
            assertNetworkStatsEquals(origStats, it)
        }
    }
}