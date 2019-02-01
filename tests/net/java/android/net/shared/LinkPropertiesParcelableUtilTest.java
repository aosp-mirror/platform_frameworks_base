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

package android.net.shared;

import static android.net.shared.LinkPropertiesParcelableUtil.fromStableParcelable;
import static android.net.shared.LinkPropertiesParcelableUtil.toStableParcelable;
import static android.net.shared.ParcelableTestUtil.assertFieldCountEquals;

import static org.junit.Assert.assertEquals;

import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.Uri;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

/**
 * Tests for {@link LinkPropertiesParcelableUtil}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class LinkPropertiesParcelableUtilTest {
    private LinkProperties mLinkProperties;

    private static final String TEST_LINKPROPS_IFACE = "TEST_IFACE";

    @Before
    public void setUp() {
        mLinkProperties = new LinkProperties();
        mLinkProperties.setInterfaceName(TEST_LINKPROPS_IFACE);
        mLinkProperties.setLinkAddresses(Arrays.asList(
                new LinkAddress(InetAddresses.parseNumericAddress("192.168.0.42"), 16),
                new LinkAddress(InetAddresses.parseNumericAddress("2001:db8::7"), 42)));
        mLinkProperties.setDnsServers(Arrays.asList(
                InetAddresses.parseNumericAddress("2001:db8::42"),
                InetAddresses.parseNumericAddress("192.168.1.1")
        ));
        mLinkProperties.setValidatedPrivateDnsServers(Arrays.asList(
                InetAddresses.parseNumericAddress("2001:db8::43"),
                InetAddresses.parseNumericAddress("192.168.42.43")
        ));
        mLinkProperties.setPcscfServers(Arrays.asList(
                InetAddresses.parseNumericAddress("2001:db8::47"),
                InetAddresses.parseNumericAddress("192.168.42.47")
        ));
        mLinkProperties.setUsePrivateDns(true);
        mLinkProperties.setPrivateDnsServerName("test.example.com");
        mLinkProperties.setDomains("test1.example.com,test2.example.com");
        mLinkProperties.addRoute(new RouteInfo(
                new IpPrefix(InetAddresses.parseNumericAddress("2001:db8::44"), 45),
                InetAddresses.parseNumericAddress("2001:db8::45"),
                TEST_LINKPROPS_IFACE,
                RouteInfo.RTN_UNICAST
        ));
        mLinkProperties.addRoute(new RouteInfo(
                new IpPrefix(InetAddresses.parseNumericAddress("192.168.44.45"), 16),
                InetAddresses.parseNumericAddress("192.168.45.1"),
                TEST_LINKPROPS_IFACE,
                RouteInfo.RTN_THROW
        ));
        mLinkProperties.setHttpProxy(new ProxyInfo("test3.example.com", 8000,
                "excl1.example.com,excl2.example.com"));
        mLinkProperties.setMtu(5000);
        mLinkProperties.setTcpBufferSizes("1,2,3,4,5,6");
        mLinkProperties.setNat64Prefix(
                new IpPrefix(InetAddresses.parseNumericAddress("2001:db8::48"), 96));

        // Verify that this test does not miss any new field added later.
        // If any added field is not included in LinkProperties#equals, assertLinkPropertiesEquals
        // must also be updated.
        assertFieldCountEquals(14, LinkProperties.class);
    }

    @Test
    public void testParcelUnparcel() {
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullInterface() {
        mLinkProperties.setInterfaceName(null);
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullPrivateDnsServer() {
        mLinkProperties.setPrivateDnsServerName(null);
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullDomains() {
        mLinkProperties.setDomains(null);
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullProxy() {
        mLinkProperties.setHttpProxy(null);
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullTcpBufferSizes() {
        mLinkProperties.setTcpBufferSizes(null);
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_EmptyLinkAddresses() {
        mLinkProperties.setLinkAddresses(Collections.emptyList());
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_EmptyDnses() {
        mLinkProperties.setDnsServers(Collections.emptyList());
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_EmptyValidatedPrivateDnses() {
        mLinkProperties.setValidatedPrivateDnsServers(Collections.emptyList());
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_EmptyRoutes() {
        for (RouteInfo r : mLinkProperties.getAllRoutes()) {
            mLinkProperties.removeRoute(r);
        }
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_PacFileProxyInfo() {
        mLinkProperties.setHttpProxy(new ProxyInfo(Uri.parse("http://pacfile.example.com")));
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullNat64Prefix() {
        mLinkProperties.setNat64Prefix(null);
        doParcelUnparcelTest();
    }

    private void doParcelUnparcelTest() {
        final LinkProperties unparceled = fromStableParcelable(toStableParcelable(mLinkProperties));
        assertLinkPropertiesEquals(mLinkProperties, unparceled);
    }

    private static void assertLinkPropertiesEquals(LinkProperties expected, LinkProperties actual) {
        assertEquals(expected, actual);

        // Equality on stacked links is not tested as they should not be passed to processes using
        // LinkPropertiesParcelable.
    }
}
