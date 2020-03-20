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
import android.os.Build
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import java.net.InetAddress
import java.util.Arrays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class KeepalivePacketDataTest {
    @Rule @JvmField
    val ignoreRule: DevSdkIgnoreRule = DevSdkIgnoreRule()

    private val INVALID_PORT = 65537
    private val TEST_DST_PORT = 4244
    private val TEST_SRC_PORT = 4243

    private val TESTBYTES = byteArrayOf(12, 31, 22, 44)
    private val TEST_SRC_ADDRV4 = "198.168.0.2".address()
    private val TEST_DST_ADDRV4 = "198.168.0.1".address()
    private val TEST_ADDRV6 = "2001:db8::1".address()

    private fun String.address() = InetAddresses.parseNumericAddress(this)

    // Add for test because constructor of KeepalivePacketData is protected.
    private inner class TestKeepalivePacketData(
        srcAddress: InetAddress? = TEST_SRC_ADDRV4,
        srcPort: Int = TEST_SRC_PORT,
        dstAddress: InetAddress? = TEST_DST_ADDRV4,
        dstPort: Int = TEST_DST_PORT,
        data: ByteArray = TESTBYTES
    ) : KeepalivePacketData(srcAddress, srcPort, dstAddress, dstPort, data)

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testConstructor() {
        var data: TestKeepalivePacketData

        try {
            data = TestKeepalivePacketData(srcAddress = null)
            fail("Null src address should cause exception")
        } catch (e: InvalidPacketException) {
            assertEquals(e.error, ERROR_INVALID_IP_ADDRESS)
        }

        try {
            data = TestKeepalivePacketData(dstAddress = null)
            fail("Null dst address should cause exception")
        } catch (e: InvalidPacketException) {
            assertEquals(e.error, ERROR_INVALID_IP_ADDRESS)
        }

        try {
            data = TestKeepalivePacketData(dstAddress = TEST_ADDRV6)
            fail("Ip family mismatched should cause exception")
        } catch (e: InvalidPacketException) {
            assertEquals(e.error, ERROR_INVALID_IP_ADDRESS)
        }

        try {
            data = TestKeepalivePacketData(srcPort = INVALID_PORT)
            fail("Invalid srcPort should cause exception")
        } catch (e: InvalidPacketException) {
            assertEquals(e.error, ERROR_INVALID_PORT)
        }

        try {
            data = TestKeepalivePacketData(dstPort = INVALID_PORT)
            fail("Invalid dstPort should cause exception")
        } catch (e: InvalidPacketException) {
            assertEquals(e.error, ERROR_INVALID_PORT)
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testSrcAddress() = assertEquals(TEST_SRC_ADDRV4, TestKeepalivePacketData().srcAddress)

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testDstAddress() = assertEquals(TEST_DST_ADDRV4, TestKeepalivePacketData().dstAddress)

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testSrcPort() = assertEquals(TEST_SRC_PORT, TestKeepalivePacketData().srcPort)

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testDstPort() = assertEquals(TEST_DST_PORT, TestKeepalivePacketData().dstPort)

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testPacket() = assertTrue(Arrays.equals(TESTBYTES, TestKeepalivePacketData().packet))
}