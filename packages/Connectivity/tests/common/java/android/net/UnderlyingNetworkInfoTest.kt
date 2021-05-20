/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.assertParcelSane
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

private const val TEST_OWNER_UID = 123
private const val TEST_IFACE = "test_tun0"
private val TEST_IFACE_LIST = listOf("wlan0", "rmnet_data0", "eth0")

@SmallTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class UnderlyingNetworkInfoTest {
    @Test
    fun testParcelUnparcel() {
        val testInfo = UnderlyingNetworkInfo(TEST_OWNER_UID, TEST_IFACE, TEST_IFACE_LIST)
        assertEquals(TEST_OWNER_UID, testInfo.getOwnerUid())
        assertEquals(TEST_IFACE, testInfo.getInterface())
        assertEquals(TEST_IFACE_LIST, testInfo.getUnderlyingInterfaces())
        assertParcelSane(testInfo, 3)

        val emptyInfo = UnderlyingNetworkInfo(0, String(), listOf())
        assertEquals(0, emptyInfo.getOwnerUid())
        assertEquals(String(), emptyInfo.getInterface())
        assertEquals(listOf(), emptyInfo.getUnderlyingInterfaces())
        assertParcelSane(emptyInfo, 3)
    }
}