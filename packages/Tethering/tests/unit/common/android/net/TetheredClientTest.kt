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
import android.net.TetheredClient.AddressInfo
import android.net.TetheringManager.TETHERING_BLUETOOTH
import android.net.TetheringManager.TETHERING_USB
import android.system.OsConstants.RT_SCOPE_UNIVERSE
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.testutils.assertParcelSane
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private val TEST_MACADDR = MacAddress.fromBytes(byteArrayOf(12, 23, 34, 45, 56, 67))
private val TEST_OTHER_MACADDR = MacAddress.fromBytes(byteArrayOf(23, 34, 45, 56, 67, 78))
private val TEST_ADDR1 = makeLinkAddress("192.168.113.3", prefixLength = 24, expTime = 123L)
private val TEST_ADDR2 = makeLinkAddress("fe80::1:2:3", prefixLength = 64, expTime = 456L)
private val TEST_HOSTNAME = "test_hostname"
private val TEST_OTHER_HOSTNAME = "test_other_hostname"
private val TEST_ADDRINFO1 = AddressInfo(TEST_ADDR1, TEST_HOSTNAME)
private val TEST_ADDRINFO2 = AddressInfo(TEST_ADDR2, null)

private fun makeLinkAddress(addr: String, prefixLength: Int, expTime: Long) = LinkAddress(
        parseNumericAddress(addr),
        prefixLength,
        0 /* flags */,
        RT_SCOPE_UNIVERSE,
        expTime /* deprecationTime */,
        expTime /* expirationTime */)

@RunWith(AndroidJUnit4::class)
@SmallTest
class TetheredClientTest {
    @Test
    fun testParceling() {
        assertParcelSane(TEST_ADDRINFO1, fieldCount = 2)
        assertParcelSane(makeTestClient(), fieldCount = 3)
    }

    @Test
    fun testEquals() {
        assertEquals(makeTestClient(), makeTestClient())

        // Different mac address
        assertNotEquals(makeTestClient(), TetheredClient(
                TEST_OTHER_MACADDR,
                listOf(TEST_ADDRINFO1, TEST_ADDRINFO2),
                TETHERING_BLUETOOTH))

        // Different hostname
        assertNotEquals(makeTestClient(), TetheredClient(
                TEST_MACADDR,
                listOf(AddressInfo(TEST_ADDR1, TEST_OTHER_HOSTNAME), TEST_ADDRINFO2),
                TETHERING_BLUETOOTH))

        // Null hostname
        assertNotEquals(makeTestClient(), TetheredClient(
                TEST_MACADDR,
                listOf(AddressInfo(TEST_ADDR1, null), TEST_ADDRINFO2),
                TETHERING_BLUETOOTH))

        // Missing address
        assertNotEquals(makeTestClient(), TetheredClient(
                TEST_MACADDR,
                listOf(TEST_ADDRINFO2),
                TETHERING_BLUETOOTH))

        // Different type
        assertNotEquals(makeTestClient(), TetheredClient(
                TEST_MACADDR,
                listOf(TEST_ADDRINFO1, TEST_ADDRINFO2),
                TETHERING_USB))
    }

    @Test
    fun testAddAddresses() {
        val client1 = TetheredClient(TEST_MACADDR, listOf(TEST_ADDRINFO1), TETHERING_USB)
        val client2 = TetheredClient(TEST_OTHER_MACADDR, listOf(TEST_ADDRINFO2), TETHERING_USB)
        assertEquals(TetheredClient(
                TEST_MACADDR,
                listOf(TEST_ADDRINFO1, TEST_ADDRINFO2),
                TETHERING_USB), client1.addAddresses(client2))
    }

    @Test
    fun testGetters() {
        assertEquals(TEST_MACADDR, makeTestClient().macAddress)
        assertEquals(listOf(TEST_ADDRINFO1, TEST_ADDRINFO2), makeTestClient().addresses)
        assertEquals(TETHERING_BLUETOOTH, makeTestClient().tetheringType)
    }

    @Test
    fun testAddressInfo_Getters() {
        assertEquals(TEST_ADDR1, TEST_ADDRINFO1.address)
        assertEquals(TEST_ADDR2, TEST_ADDRINFO2.address)
        assertEquals(TEST_HOSTNAME, TEST_ADDRINFO1.hostname)
        assertEquals(null, TEST_ADDRINFO2.hostname)
    }

    private fun makeTestClient() = TetheredClient(
            TEST_MACADDR,
            listOf(TEST_ADDRINFO1, TEST_ADDRINFO2),
            TETHERING_BLUETOOTH)
}