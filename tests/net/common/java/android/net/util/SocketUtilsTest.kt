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

package android.net.util

import android.os.Build
import android.system.NetlinkSocketAddress
import android.system.Os
import android.system.OsConstants.AF_INET
import android.system.OsConstants.ETH_P_ALL
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.RTMGRP_NEIGH
import android.system.OsConstants.SOCK_DGRAM
import android.system.PacketSocketAddress
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_INDEX = 123
private const val TEST_PORT = 555
private const val FF_BYTE = 0xff.toByte()

@RunWith(AndroidJUnit4::class)
@SmallTest
class SocketUtilsTest {
    @Rule @JvmField
    val ignoreRule = DevSdkIgnoreRule()

    @Test
    fun testMakeNetlinkSocketAddress() {
        val nlAddress = SocketUtils.makeNetlinkSocketAddress(TEST_PORT, RTMGRP_NEIGH)
        if (nlAddress is NetlinkSocketAddress) {
            assertEquals(TEST_PORT, nlAddress.getPortId())
            assertEquals(RTMGRP_NEIGH, nlAddress.getGroupsMask())
        } else {
            fail("Not NetlinkSocketAddress object")
        }
    }

    @Test
    fun testMakePacketSocketAddress_Q() {
        val pkAddress = SocketUtils.makePacketSocketAddress(ETH_P_ALL, TEST_INDEX)
        assertTrue("Not PacketSocketAddress object", pkAddress is PacketSocketAddress)

        val pkAddress2 = SocketUtils.makePacketSocketAddress(TEST_INDEX, ByteArray(6) { FF_BYTE })
        assertTrue("Not PacketSocketAddress object", pkAddress2 is PacketSocketAddress)
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testMakePacketSocketAddress() {
        val pkAddress = SocketUtils.makePacketSocketAddress(
                ETH_P_ALL, TEST_INDEX, ByteArray(6) { FF_BYTE })
        assertTrue("Not PacketSocketAddress object", pkAddress is PacketSocketAddress)
    }

    @Test
    fun testCloseSocket() {
        // Expect no exception happening with null object.
        SocketUtils.closeSocket(null)

        val fd = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        assertTrue(fd.valid())
        SocketUtils.closeSocket(fd)
        assertFalse(fd.valid())
        // Expecting socket should be still invalid even closed socket again.
        SocketUtils.closeSocket(fd)
        assertFalse(fd.valid())
    }
}
