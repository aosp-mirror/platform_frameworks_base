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
class IpManagerEventTest {
    private fun <T: Parcelable> testParcel(obj: T, fieldCount: Int) {
        ParcelableTestUtil.assertFieldCountEquals(fieldCount, obj::class.java)
        TestUtils.assertParcelingIsLossless(obj)
    }

    @Test
    fun testConstructorAndParcel() {
        (IpManagerEvent.PROVISIONING_OK..IpManagerEvent.ERROR_INTERFACE_NOT_FOUND).forEach {
            val ipManagerEvent = IpManagerEvent(it, Long.MAX_VALUE)
            assertEquals(it, ipManagerEvent.eventType)
            assertEquals(Long.MAX_VALUE, ipManagerEvent.durationMs)

            testParcel(ipManagerEvent, 2)
        }
    }
}
