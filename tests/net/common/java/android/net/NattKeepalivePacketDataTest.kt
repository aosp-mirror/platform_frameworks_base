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

package android.net

import android.net.InvalidPacketException.ERROR_INVALID_IP_ADDRESS
import android.net.InvalidPacketException.ERROR_INVALID_PORT
import android.net.NattSocketKeepalive.NATT_PORT
import android.os.Build
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.testutils.assertEqualBothWays
import com.android.testutils.assertFieldCountEquals
import com.android.testutils.assertParcelSane
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.parcelingRoundTrip
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class NattKeepalivePacketDataTest {
    @Rule @JvmField
    val ignoreRule: DevSdkIgnoreRule = DevSdkIgnoreRule()

    /* Refer to the definition in {@code NattKeepalivePacketData} */
    private val IPV4_HEADER_LENGTH = 20
    private val UDP_HEADER_LENGTH = 8

    private val TEST_PORT = 4243
    private val TEST_PORT2 = 4244
    private val TEST_SRC_ADDRV4 = "198.168.0.2".address()
    private val TEST_DST_ADDRV4 = "198.168.0.1".address()
    private val TEST_ADDRV6 = "2001:db8::1".address()

    private fun String.address() = InetAddresses.parseNumericAddress(this)
    private fun nattKeepalivePacket(
        srcAddress: InetAddress? = TEST_SRC_ADDRV4,
        srcPort: Int = TEST_PORT,
        dstAddress: InetAddress? = TEST_DST_ADDRV4,
        dstPort: Int = NATT_PORT
    ) = NattKeepalivePacketData.nattKeepalivePacket(srcAddress, srcPort, dstAddress, dstPort)

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testConstructor() {
        try {
            nattKeepalivePacket(dstPort = TEST_PORT)
            fail("Dst port is not NATT port should cause exception")
        } catch (e: InvalidPacketException) {
            assertEquals(e.error, ERROR_INVALID_PORT)
        }

        try {
            nattKeepalivePacket(srcAddress = TEST_ADDRV6)
            fail("A v6 srcAddress should cause exception")
        } catch (e: InvalidPacketException) {
            assertEquals(e.error, ERROR_INVALID_IP_ADDRESS)
        }

        try {
            nattKeepalivePacket(dstAddress = TEST_ADDRV6)
            fail("A v6 dstAddress should cause exception")
        } catch (e: InvalidPacketException) {
            assertEquals(e.error, ERROR_INVALID_IP_ADDRESS)
        }

        try {
            parcelingRoundTrip(
                    NattKeepalivePacketData(TEST_SRC_ADDRV4, TEST_PORT, TEST_DST_ADDRV4, TEST_PORT,
                    byteArrayOf(12, 31, 22, 44)))
            fail("Invalid data should cause exception")
        } catch (e: IllegalArgumentException) { }
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testParcel() {
        assertParcelSane(nattKeepalivePacket(), 0)
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testEquals() {
        assertEqualBothWays(nattKeepalivePacket(), nattKeepalivePacket())
        assertNotEquals(nattKeepalivePacket(dstAddress = TEST_SRC_ADDRV4), nattKeepalivePacket())
        assertNotEquals(nattKeepalivePacket(srcAddress = TEST_DST_ADDRV4), nattKeepalivePacket())
        // Test src port only because dst port have to be NATT_PORT
        assertNotEquals(nattKeepalivePacket(srcPort = TEST_PORT2), nattKeepalivePacket())
        // Make sure the parceling test is updated if fields are added in the base class.
        assertFieldCountEquals(5, KeepalivePacketData::class.java)
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testHashCode() {
        assertEquals(nattKeepalivePacket().hashCode(), nattKeepalivePacket().hashCode())
    }
}