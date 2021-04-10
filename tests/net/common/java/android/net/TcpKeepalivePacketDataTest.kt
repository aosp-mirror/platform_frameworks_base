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

import android.net.InetAddresses.parseNumericAddress
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.assertFieldCountEquals
import com.android.testutils.assertParcelSane
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R) // TcpKeepalivePacketData added to SDK in S
class TcpKeepalivePacketDataTest {
    private fun makeData(
        srcAddress: InetAddress = parseNumericAddress("192.0.2.123"),
        srcPort: Int = 1234,
        dstAddress: InetAddress = parseNumericAddress("192.0.2.231"),
        dstPort: Int = 4321,
        data: ByteArray = byteArrayOf(1, 2, 3),
        tcpSeq: Int = 135,
        tcpAck: Int = 246,
        tcpWnd: Int = 1234,
        tcpWndScale: Int = 2,
        ipTos: Int = 0x12,
        ipTtl: Int = 10
    ) = TcpKeepalivePacketData(srcAddress, srcPort, dstAddress, dstPort, data, tcpSeq, tcpAck,
            tcpWnd, tcpWndScale, ipTos, ipTtl)

    @Test
    fun testEquals() {
        val data1 = makeData()
        val data2 = makeData()
        assertEquals(data1, data2)
        assertEquals(data1.hashCode(), data2.hashCode())
    }

    @Test
    fun testNotEquals() {
        assertNotEquals(makeData(srcAddress = parseNumericAddress("192.0.2.124")), makeData())
        assertNotEquals(makeData(srcPort = 1235), makeData())
        assertNotEquals(makeData(dstAddress = parseNumericAddress("192.0.2.232")), makeData())
        assertNotEquals(makeData(dstPort = 4322), makeData())
        // .equals does not test .packet, as it should be generated from the other fields
        assertNotEquals(makeData(tcpSeq = 136), makeData())
        assertNotEquals(makeData(tcpAck = 247), makeData())
        assertNotEquals(makeData(tcpWnd = 1235), makeData())
        assertNotEquals(makeData(tcpWndScale = 3), makeData())
        assertNotEquals(makeData(ipTos = 0x14), makeData())
        assertNotEquals(makeData(ipTtl = 11), makeData())

        // Update above assertions if field is added
        assertFieldCountEquals(5, KeepalivePacketData::class.java)
        assertFieldCountEquals(6, TcpKeepalivePacketData::class.java)
    }

    @Test
    fun testParcelUnparcel() {
        assertParcelSane(makeData(), fieldCount = 6) { a, b ->
            // .equals() does not verify .packet
            a == b && a.packet contentEquals b.packet
        }
    }

    @Test
    fun testToString() {
        val data = makeData()
        val str = data.toString()

        assertTrue(str.contains(data.srcAddress.hostAddress))
        assertTrue(str.contains(data.srcPort.toString()))
        assertTrue(str.contains(data.dstAddress.hostAddress))
        assertTrue(str.contains(data.dstPort.toString()))
        // .packet not included in toString()
        assertTrue(str.contains(data.getTcpSeq().toString()))
        assertTrue(str.contains(data.getTcpAck().toString()))
        assertTrue(str.contains(data.getTcpWindow().toString()))
        assertTrue(str.contains(data.getTcpWindowScale().toString()))
        assertTrue(str.contains(data.getIpTos().toString()))
        assertTrue(str.contains(data.getIpTtl().toString()))

        // Update above assertions if field is added
        assertFieldCountEquals(5, KeepalivePacketData::class.java)
        assertFieldCountEquals(6, TcpKeepalivePacketData::class.java)
    }
}