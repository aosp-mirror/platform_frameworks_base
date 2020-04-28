/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class StaticIpConfigurationTest {

    private static final String ADDRSTR = "192.0.2.2/25";
    private static final LinkAddress ADDR = new LinkAddress(ADDRSTR);
    private static final InetAddress GATEWAY = IpAddress("192.0.2.1");
    private static final InetAddress OFFLINKGATEWAY = IpAddress("192.0.2.129");
    private static final InetAddress DNS1 = IpAddress("8.8.8.8");
    private static final InetAddress DNS2 = IpAddress("8.8.4.4");
    private static final InetAddress DNS3 = IpAddress("4.2.2.2");
    private static final String IFACE = "eth0";
    private static final String FAKE_DOMAINS = "google.com";

    private static InetAddress IpAddress(String addr) {
        return InetAddress.parseNumericAddress(addr);
    }

    private void checkEmpty(StaticIpConfiguration s) {
        assertNull(s.ipAddress);
        assertNull(s.gateway);
        assertNull(s.domains);
        assertEquals(0, s.dnsServers.size());
    }

    private static <T> void assertNotEquals(T t1, T t2) {
        assertFalse(Objects.equals(t1, t2));
    }

    private StaticIpConfiguration makeTestObject() {
        StaticIpConfiguration s = new StaticIpConfiguration();
        s.ipAddress = ADDR;
        s.gateway = GATEWAY;
        s.dnsServers.add(DNS1);
        s.dnsServers.add(DNS2);
        s.dnsServers.add(DNS3);
        s.domains = FAKE_DOMAINS;
        return s;
    }

    @Test
    public void testConstructor() {
        StaticIpConfiguration s = new StaticIpConfiguration();
        checkEmpty(s);
    }

    @Test
    public void testCopyAndClear() {
        StaticIpConfiguration empty = new StaticIpConfiguration((StaticIpConfiguration) null);
        checkEmpty(empty);

        StaticIpConfiguration s1 = makeTestObject();
        StaticIpConfiguration s2 = new StaticIpConfiguration(s1);
        assertEquals(s1, s2);
        s2.clear();
        assertEquals(empty, s2);
    }

    @Test
    public void testHashCodeAndEquals() {
        HashSet<Integer> hashCodes = new HashSet();
        hashCodes.add(0);

        StaticIpConfiguration s = new StaticIpConfiguration();
        // Check that this hash code is nonzero and different from all the ones seen so far.
        assertTrue(hashCodes.add(s.hashCode()));

        s.ipAddress = ADDR;
        assertTrue(hashCodes.add(s.hashCode()));

        s.gateway = GATEWAY;
        assertTrue(hashCodes.add(s.hashCode()));

        s.dnsServers.add(DNS1);
        assertTrue(hashCodes.add(s.hashCode()));

        s.dnsServers.add(DNS2);
        assertTrue(hashCodes.add(s.hashCode()));

        s.dnsServers.add(DNS3);
        assertTrue(hashCodes.add(s.hashCode()));

        s.domains = "example.com";
        assertTrue(hashCodes.add(s.hashCode()));

        assertFalse(s.equals(null));
        assertEquals(s, s);

        StaticIpConfiguration s2 = new StaticIpConfiguration(s);
        assertEquals(s, s2);

        s.ipAddress = new LinkAddress(DNS1, 32);
        assertNotEquals(s, s2);

        s2 = new StaticIpConfiguration(s);
        s.domains = "foo";
        assertNotEquals(s, s2);

        s2 = new StaticIpConfiguration(s);
        s.gateway = DNS2;
        assertNotEquals(s, s2);

        s2 = new StaticIpConfiguration(s);
        s.dnsServers.add(DNS3);
        assertNotEquals(s, s2);
    }

    @Test
    public void testToLinkProperties() {
        LinkProperties expected = new LinkProperties();
        expected.setInterfaceName(IFACE);

        StaticIpConfiguration s = new StaticIpConfiguration();
        assertEquals(expected, s.toLinkProperties(IFACE));

        final RouteInfo connectedRoute = new RouteInfo(new IpPrefix(ADDRSTR), null, IFACE);
        s.ipAddress = ADDR;
        expected.addLinkAddress(ADDR);
        expected.addRoute(connectedRoute);
        assertEquals(expected, s.toLinkProperties(IFACE));

        s.gateway = GATEWAY;
        RouteInfo defaultRoute = new RouteInfo(new IpPrefix("0.0.0.0/0"), GATEWAY, IFACE);
        expected.addRoute(defaultRoute);
        assertEquals(expected, s.toLinkProperties(IFACE));

        s.gateway = OFFLINKGATEWAY;
        expected.removeRoute(defaultRoute);
        defaultRoute = new RouteInfo(new IpPrefix("0.0.0.0/0"), OFFLINKGATEWAY, IFACE);
        expected.addRoute(defaultRoute);

        RouteInfo gatewayRoute = new RouteInfo(new IpPrefix("192.0.2.129/32"), null, IFACE);
        expected.addRoute(gatewayRoute);
        assertEquals(expected, s.toLinkProperties(IFACE));

        s.dnsServers.add(DNS1);
        expected.addDnsServer(DNS1);
        assertEquals(expected, s.toLinkProperties(IFACE));

        s.dnsServers.add(DNS2);
        s.dnsServers.add(DNS3);
        expected.addDnsServer(DNS2);
        expected.addDnsServer(DNS3);
        assertEquals(expected, s.toLinkProperties(IFACE));

        s.domains = FAKE_DOMAINS;
        expected.setDomains(FAKE_DOMAINS);
        assertEquals(expected, s.toLinkProperties(IFACE));

        s.gateway = null;
        expected.removeRoute(defaultRoute);
        expected.removeRoute(gatewayRoute);
        assertEquals(expected, s.toLinkProperties(IFACE));

        // Without knowing the IP address, we don't have a directly-connected route, so we can't
        // tell if the gateway is off-link or not and we don't add a host route. This isn't a real
        // configuration, but we should at least not crash.
        s.gateway = OFFLINKGATEWAY;
        s.ipAddress = null;
        expected.removeLinkAddress(ADDR);
        expected.removeRoute(connectedRoute);
        expected.addRoute(defaultRoute);
        assertEquals(expected, s.toLinkProperties(IFACE));
    }

    private StaticIpConfiguration passThroughParcel(StaticIpConfiguration s) {
        Parcel p = Parcel.obtain();
        StaticIpConfiguration s2 = null;
        try {
            s.writeToParcel(p, 0);
            p.setDataPosition(0);
            s2 = StaticIpConfiguration.readFromParcel(p);
        } finally {
            p.recycle();
        }
        assertNotNull(s2);
        return s2;
    }

    @Test
    public void testParceling() {
        StaticIpConfiguration s = makeTestObject();
        StaticIpConfiguration s2 = passThroughParcel(s);
        assertEquals(s, s2);
    }

    @Test
    public void testBuilder() {
        final ArrayList<InetAddress> dnsServers = new ArrayList<>();
        dnsServers.add(DNS1);

        final StaticIpConfiguration s = new StaticIpConfiguration.Builder()
                .setIpAddress(ADDR)
                .setGateway(GATEWAY)
                .setDomains(FAKE_DOMAINS)
                .setDnsServers(dnsServers)
                .build();

        assertEquals(s.ipAddress, s.getIpAddress());
        assertEquals(ADDR, s.getIpAddress());
        assertEquals(s.gateway, s.getGateway());
        assertEquals(GATEWAY, s.getGateway());
        assertEquals(s.domains, s.getDomains());
        assertEquals(FAKE_DOMAINS, s.getDomains());
        assertTrue(s.dnsServers.equals(s.getDnsServers()));
        assertEquals(1, s.getDnsServers().size());
        assertEquals(DNS1, s.getDnsServers().get(0));
    }

    @Test
    public void testAddDnsServers() {
        final StaticIpConfiguration s = new StaticIpConfiguration((StaticIpConfiguration) null);
        checkEmpty(s);

        s.addDnsServer(DNS1);
        assertEquals(1, s.getDnsServers().size());
        assertEquals(DNS1, s.getDnsServers().get(0));

        s.addDnsServer(DNS2);
        s.addDnsServer(DNS3);
        assertEquals(3, s.getDnsServers().size());
        assertEquals(DNS2, s.getDnsServers().get(1));
        assertEquals(DNS3, s.getDnsServers().get(2));
    }

    @Test
    public void testGetRoutes() {
        final StaticIpConfiguration s = makeTestObject();
        final List<RouteInfo> routeInfoList = s.getRoutes(IFACE);

        assertEquals(2, routeInfoList.size());
        assertEquals(new RouteInfo(ADDR, (InetAddress) null, IFACE), routeInfoList.get(0));
        assertEquals(new RouteInfo((IpPrefix) null, GATEWAY, IFACE), routeInfoList.get(1));
    }
}
