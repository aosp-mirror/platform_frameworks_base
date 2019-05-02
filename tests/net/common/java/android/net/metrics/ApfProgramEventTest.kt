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

package android.net.metrics;

import android.os.Parcelable
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.internal.util.ParcelableTestUtil
import com.android.internal.util.TestUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class ApfProgramEventTest {
    private fun <T: Parcelable> testParcel(obj: T, fieldCount: Int) {
        ParcelableTestUtil.assertFieldCountEquals(fieldCount, obj::class.java)
        TestUtils.assertParcelingIsLossless(obj)
    }

    private infix fun Int.hasFlag(flag: Int) = (this and (1 shl flag)) != 0

    @Test
    fun testBuilderAndParcel() {
        val apfProgramEvent = ApfProgramEvent.Builder()
                .setLifetime(1)
                .setActualLifetime(2)
                .setFilteredRas(3)
                .setCurrentRas(4)
                .setProgramLength(5)
                .setFlags(true, true)
                .build()

        assertEquals(1, apfProgramEvent.lifetime)
        assertEquals(2, apfProgramEvent.actualLifetime)
        assertEquals(3, apfProgramEvent.filteredRas)
        assertEquals(4, apfProgramEvent.currentRas)
        assertEquals(5, apfProgramEvent.programLength)
        assertEquals(ApfProgramEvent.flagsFor(true, true), apfProgramEvent.flags)

        testParcel(apfProgramEvent, 6)
    }

    @Test
    fun testFlagsFor() {
        var flags = ApfProgramEvent.flagsFor(false, false)
        assertFalse(flags hasFlag ApfProgramEvent.FLAG_HAS_IPV4_ADDRESS)
        assertFalse(flags hasFlag ApfProgramEvent.FLAG_MULTICAST_FILTER_ON)

        flags = ApfProgramEvent.flagsFor(true, false)
        assertTrue(flags hasFlag ApfProgramEvent.FLAG_HAS_IPV4_ADDRESS)
        assertFalse(flags hasFlag ApfProgramEvent.FLAG_MULTICAST_FILTER_ON)

        flags = ApfProgramEvent.flagsFor(false, true)
        assertFalse(flags hasFlag ApfProgramEvent.FLAG_HAS_IPV4_ADDRESS)
        assertTrue(flags hasFlag ApfProgramEvent.FLAG_MULTICAST_FILTER_ON)

        flags = ApfProgramEvent.flagsFor(true, true)
        assertTrue(flags hasFlag ApfProgramEvent.FLAG_HAS_IPV4_ADDRESS)
        assertTrue(flags hasFlag ApfProgramEvent.FLAG_MULTICAST_FILTER_ON)
    }
}
