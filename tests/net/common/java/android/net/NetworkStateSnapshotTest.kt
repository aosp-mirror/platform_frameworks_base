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

import android.net.ConnectivityManager.TYPE_NONE
import android.net.ConnectivityManager.TYPE_WIFI
import android.net.InetAddresses.parseNumericAddress
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.assertParcelSane
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.Inet6Address

private const val TEST_IMSI = "imsi1"
private const val TEST_SSID = "SSID1"
private const val TEST_NETID = 123

private val TEST_IPV4_GATEWAY = parseNumericAddress("192.168.222.3") as Inet4Address
private val TEST_IPV6_GATEWAY = parseNumericAddress("2001:db8::1") as Inet6Address
private val TEST_IPV4_LINKADDR = LinkAddress("192.168.222.123/24")
private val TEST_IPV6_LINKADDR = LinkAddress("2001:db8::123/64")
private val TEST_IFACE = "fake0"
private val TEST_LINK_PROPERTIES = LinkProperties().apply {
    interfaceName = TEST_IFACE
    addLinkAddress(TEST_IPV4_LINKADDR)
    addLinkAddress(TEST_IPV6_LINKADDR)

    // Add default routes
    addRoute(RouteInfo(IpPrefix(parseNumericAddress("0.0.0.0"), 0), TEST_IPV4_GATEWAY))
    addRoute(RouteInfo(IpPrefix(parseNumericAddress("::"), 0), TEST_IPV6_GATEWAY))
}

private val TEST_CAPABILITIES = NetworkCapabilities().apply {
    addTransportType(TRANSPORT_WIFI)
    setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, false)
    setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, true)
    setSSID(TEST_SSID)
}

@SmallTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class NetworkStateSnapshotTest {

    @Test
    fun testParcelUnparcel() {
        val emptySnapshot = NetworkStateSnapshot(Network(TEST_NETID), NetworkCapabilities(),
                LinkProperties(), null, TYPE_NONE)
        val snapshot = NetworkStateSnapshot(
                Network(TEST_NETID), TEST_CAPABILITIES, TEST_LINK_PROPERTIES, TEST_IMSI, TYPE_WIFI)
        assertParcelSane(emptySnapshot, 5)
        assertParcelSane(snapshot, 5)
    }
}
