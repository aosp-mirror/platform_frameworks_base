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

import android.os.Parcelable
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.internal.util.ParcelableTestUtil
import com.android.internal.util.TestUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class ApfStatsTest {
    private fun <T: Parcelable> testParcel(obj: T, fieldCount: Int) {
        ParcelableTestUtil.assertFieldCountEquals(fieldCount, obj::class.java)
        TestUtils.assertParcelingIsLossless(obj)
    }

    @Test
    fun testBuilderAndParcel() {
        val apfStats = ApfStats.Builder()
                .setDurationMs(Long.MAX_VALUE)
                .setReceivedRas(1)
                .setMatchingRas(2)
                .setDroppedRas(3)
                .setZeroLifetimeRas(4)
                .setParseErrors(5)
                .setProgramUpdates(6)
                .setProgramUpdatesAll(7)
                .setProgramUpdatesAllowingMulticast(8)
                .setMaxProgramSize(9)
                .build()

        assertEquals(Long.MAX_VALUE, apfStats.durationMs)
        assertEquals(1, apfStats.receivedRas)
        assertEquals(2, apfStats.matchingRas)
        assertEquals(3, apfStats.droppedRas)
        assertEquals(4, apfStats.zeroLifetimeRas)
        assertEquals(5, apfStats.parseErrors)
        assertEquals(6, apfStats.programUpdates)
        assertEquals(7, apfStats.programUpdatesAll)
        assertEquals(8, apfStats.programUpdatesAllowingMulticast)
        assertEquals(9, apfStats.maxProgramSize)

        testParcel(apfStats, 10)
    }
}
