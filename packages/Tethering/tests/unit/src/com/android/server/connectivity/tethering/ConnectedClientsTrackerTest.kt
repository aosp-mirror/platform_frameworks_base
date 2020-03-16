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

package com.android.server.connectivity.tethering

import android.net.LinkAddress
import android.net.MacAddress
import android.net.TetheredClient
import android.net.TetheredClient.AddressInfo
import android.net.TetheringManager.TETHERING_USB
import android.net.TetheringManager.TETHERING_WIFI
import android.net.ip.IpServer
import android.net.wifi.WifiClient
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@SmallTest
class ConnectedClientsTrackerTest {

    private val server1 = mock(IpServer::class.java)
    private val server2 = mock(IpServer::class.java)
    private val servers = listOf(server1, server2)

    private val clock = TestClock(1324L)

    private val client1Addr = MacAddress.fromString("01:23:45:67:89:0A")
    private val client1 = TetheredClient(client1Addr, listOf(
            makeAddrInfo("192.168.43.44/32", null /* hostname */, clock.time + 20)),
            TETHERING_WIFI)
    private val wifiClient1 = makeWifiClient(client1Addr)
    private val client2Addr = MacAddress.fromString("02:34:56:78:90:AB")
    private val client2Exp30AddrInfo = makeAddrInfo(
            "192.168.43.45/32", "my_hostname", clock.time + 30)
    private val client2 = TetheredClient(client2Addr, listOf(
            client2Exp30AddrInfo,
            makeAddrInfo("2001:db8:12::34/72", "other_hostname", clock.time + 10)),
            TETHERING_WIFI)
    private val wifiClient2 = makeWifiClient(client2Addr)
    private val client3Addr = MacAddress.fromString("03:45:67:89:0A:BC")
    private val client3 = TetheredClient(client3Addr,
            listOf(makeAddrInfo("2001:db8:34::34/72", "other_other_hostname", clock.time + 10)),
            TETHERING_USB)

    private fun makeAddrInfo(addr: String, hostname: String?, expTime: Long) =
            LinkAddress(addr).let {
                AddressInfo(LinkAddress(it.address, it.prefixLength, it.flags, it.scope,
                        expTime /* deprecationTime */, expTime /* expirationTime */), hostname)
            }

    @Test
    fun testUpdateConnectedClients() {
        doReturn(emptyList<TetheredClient>()).`when`(server1).allLeases
        doReturn(emptyList<TetheredClient>()).`when`(server2).allLeases

        val tracker = ConnectedClientsTracker(clock)
        assertFalse(tracker.updateConnectedClients(servers, null))

        // Obtain a lease for client 1
        doReturn(listOf(client1)).`when`(server1).allLeases
        assertSameClients(listOf(client1), assertNewClients(tracker, servers, listOf(wifiClient1)))

        // Client 2 L2-connected, no lease yet
        val client2WithoutAddr = TetheredClient(client2Addr, emptyList(), TETHERING_WIFI)
        assertSameClients(listOf(client1, client2WithoutAddr),
                assertNewClients(tracker, servers, listOf(wifiClient1, wifiClient2)))

        // Client 2 lease obtained
        doReturn(listOf(client1, client2)).`when`(server1).allLeases
        assertSameClients(listOf(client1, client2), assertNewClients(tracker, servers, null))

        // Client 3 lease obtained
        doReturn(listOf(client3)).`when`(server2).allLeases
        assertSameClients(listOf(client1, client2, client3),
                assertNewClients(tracker, servers, null))

        // Client 2 L2-disconnected
        assertSameClients(listOf(client1, client3),
                assertNewClients(tracker, servers, listOf(wifiClient1)))

        // Client 1 L2-disconnected
        assertSameClients(listOf(client3), assertNewClients(tracker, servers, emptyList()))

        // Client 1 comes back
        assertSameClients(listOf(client1, client3),
                assertNewClients(tracker, servers, listOf(wifiClient1)))

        // Leases lost, client 1 still L2-connected
        doReturn(emptyList<TetheredClient>()).`when`(server1).allLeases
        doReturn(emptyList<TetheredClient>()).`when`(server2).allLeases
        assertSameClients(listOf(TetheredClient(client1Addr, emptyList(), TETHERING_WIFI)),
                assertNewClients(tracker, servers, null))
    }

    @Test
    fun testUpdateConnectedClients_LeaseExpiration() {
        val tracker = ConnectedClientsTracker(clock)
        doReturn(listOf(client1, client2)).`when`(server1).allLeases
        doReturn(listOf(client3)).`when`(server2).allLeases
        assertSameClients(listOf(client1, client2, client3), assertNewClients(
                tracker, servers, listOf(wifiClient1, wifiClient2)))

        clock.time += 20
        // Client 3 has no remaining lease: removed
        val expectedClients = listOf(
                // Client 1 has no remaining lease but is L2-connected
                TetheredClient(client1Addr, emptyList(), TETHERING_WIFI),
                // Client 2 has some expired leases
                TetheredClient(
                        client2Addr,
                        // Only the "t + 30" address is left, the "t + 10" address expired
                        listOf(client2Exp30AddrInfo),
                        TETHERING_WIFI))
        assertSameClients(expectedClients, assertNewClients(tracker, servers, null))
    }

    private fun assertNewClients(
        tracker: ConnectedClientsTracker,
        ipServers: Iterable<IpServer>,
        wifiClients: List<WifiClient>?
    ): List<TetheredClient> {
        assertTrue(tracker.updateConnectedClients(ipServers, wifiClients))
        return tracker.lastTetheredClients
    }

    private fun assertSameClients(expected: List<TetheredClient>, actual: List<TetheredClient>) {
        val expectedSet = HashSet(expected)
        assertEquals(expected.size, expectedSet.size)
        assertEquals(expectedSet, HashSet(actual))
    }

    private fun makeWifiClient(macAddr: MacAddress): WifiClient {
        // Use a mock WifiClient as the constructor is not part of the WiFi module exported API.
        return mock(WifiClient::class.java).apply { doReturn(macAddr).`when`(this).macAddress }
    }

    private class TestClock(var time: Long) : ConnectedClientsTracker.Clock() {
        override fun elapsedRealtime(): Long {
            return time
        }
    }
}