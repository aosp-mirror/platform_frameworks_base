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
 * limitations under the License
 */

package com.android.server

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.server.NetIdManager.MIN_NET_ID
import com.android.testutils.ExceptionUtils.ThrowingRunnable
import com.android.testutils.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@SmallTest
class NetIdManagerTest {
    @Test
    fun testReserveReleaseNetId() {
        val manager = NetIdManager(MIN_NET_ID + 4)
        assertEquals(MIN_NET_ID, manager.reserveNetId())
        assertEquals(MIN_NET_ID + 1, manager.reserveNetId())
        assertEquals(MIN_NET_ID + 2, manager.reserveNetId())
        assertEquals(MIN_NET_ID + 3, manager.reserveNetId())

        manager.releaseNetId(MIN_NET_ID + 1)
        manager.releaseNetId(MIN_NET_ID + 3)
        // IDs only loop once there is no higher ID available
        assertEquals(MIN_NET_ID + 4, manager.reserveNetId())
        assertEquals(MIN_NET_ID + 1, manager.reserveNetId())
        assertEquals(MIN_NET_ID + 3, manager.reserveNetId())
        assertThrows(IllegalStateException::class.java, ThrowingRunnable { manager.reserveNetId() })
        manager.releaseNetId(MIN_NET_ID + 5)
        // Still no ID available: MIN_NET_ID + 5 was not reserved
        assertThrows(IllegalStateException::class.java, ThrowingRunnable { manager.reserveNetId() })
        manager.releaseNetId(MIN_NET_ID + 2)
        // Throwing an exception still leaves the manager in a working state
        assertEquals(MIN_NET_ID + 2, manager.reserveNetId())
    }
}