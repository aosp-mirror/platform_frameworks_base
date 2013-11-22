/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.net.LinkProperties;
import android.net.RouteInfo;
import android.test.suitebuilder.annotation.SmallTest;
import junit.framework.TestCase;

import java.net.InetAddress;
import java.util.ArrayList;

public class LinkPropertiesTest extends TestCase {
    private static InetAddress ADDRV4 = NetworkUtils.numericToInetAddress("75.208.6.1");
    private static InetAddress ADDRV6 = NetworkUtils.numericToInetAddress(
            "2001:0db8:85a3:0000:0000:8a2e:0370:7334");
    private static InetAddress DNS1 = NetworkUtils.numericToInetAddress("75.208.7.1");
    private static InetAddress DNS2 = NetworkUtils.numericToInetAddress("69.78.7.1");
    private static InetAddress GATEWAY1 = NetworkUtils.numericToInetAddress("75.208.8.1");
    private static InetAddress GATEWAY2 = NetworkUtils.numericToInetAddress("69.78.8.1");
    private static String NAME = "qmi0";
    private static int MTU = 1500;

    private static LinkAddress LINKADDRV4 = new LinkAddress(ADDRV4, 32);
    private static LinkAddress LINKADDRV6 = new LinkAddress(ADDRV6, 128);

    public void assertLinkPropertiesEqual(LinkProperties source, LinkProperties target) {
        // Check implementation of equals(), element by element.
        assertTrue(source.isIdenticalInterfaceName(target));
        assertTrue(target.isIdenticalInterfaceName(source));

        assertTrue(source.isIdenticalAddresses(target));
        assertTrue(target.isIdenticalAddresses(source));

        assertTrue(source.isIdenticalDnses(target));
        assertTrue(target.isIdenticalDnses(source));

        assertTrue(source.isIdenticalRoutes(target));
        assertTrue(target.isIdenticalRoutes(source));

        assertTrue(source.isIdenticalHttpProxy(target));
        assertTrue(target.isIdenticalHttpProxy(source));

        assertTrue(source.isIdenticalStackedLinks(target));
        assertTrue(target.isIdenticalStackedLinks(source));

        assertTrue(source.isIdenticalMtu(target));
        assertTrue(target.isIdenticalMtu(source));

        // Check result of equals().
        assertTrue(source.equals(target));
        assertTrue(target.equals(source));

        // Check hashCode.
        assertEquals(source.hashCode(), target.hashCode());
    }

    @SmallTest
    public void testEqualsNull() {
        LinkProperties source = new LinkProperties();
        LinkProperties target = new LinkProperties();

        assertFalse(source == target);
        assertLinkPropertiesEqual(source, target);
    }

    @SmallTest
    public void testEqualsSameOrder() {
        try {
            LinkProperties source = new LinkProperties();
            source.setInterfaceName(NAME);
            // set 2 link addresses
            source.addLinkAddress(LINKADDRV4);
            source.addLinkAddress(LINKADDRV6);
            // set 2 dnses
            source.addDns(DNS1);
            source.addDns(DNS2);
            // set 2 gateways
            source.addRoute(new RouteInfo(GATEWAY1));
            source.addRoute(new RouteInfo(GATEWAY2));
            source.setMtu(MTU);

            LinkProperties target = new LinkProperties();

            // All fields are same
            target.setInterfaceName(NAME);
            target.addLinkAddress(LINKADDRV4);
            target.addLinkAddress(LINKADDRV6);
            target.addDns(DNS1);
            target.addDns(DNS2);
            target.addRoute(new RouteInfo(GATEWAY1));
            target.addRoute(new RouteInfo(GATEWAY2));
            target.setMtu(MTU);

            assertLinkPropertiesEqual(source, target);

            target.clear();
            // change Interface Name
            target.setInterfaceName("qmi1");
            target.addLinkAddress(LINKADDRV4);
            target.addLinkAddress(LINKADDRV6);
            target.addDns(DNS1);
            target.addDns(DNS2);
            target.addRoute(new RouteInfo(GATEWAY1));
            target.addRoute(new RouteInfo(GATEWAY2));
            target.setMtu(MTU);
            assertFalse(source.equals(target));

            target.clear();
            target.setInterfaceName(NAME);
            // change link addresses
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress("75.208.6.2"), 32));
            target.addLinkAddress(LINKADDRV6);
            target.addDns(DNS1);
            target.addDns(DNS2);
            target.addRoute(new RouteInfo(GATEWAY1));
            target.addRoute(new RouteInfo(GATEWAY2));
            target.setMtu(MTU);
            assertFalse(source.equals(target));

            target.clear();
            target.setInterfaceName(NAME);
            target.addLinkAddress(LINKADDRV4);
            target.addLinkAddress(LINKADDRV6);
            // change dnses
            target.addDns(NetworkUtils.numericToInetAddress("75.208.7.2"));
            target.addDns(DNS2);
            target.addRoute(new RouteInfo(GATEWAY1));
            target.addRoute(new RouteInfo(GATEWAY2));
            target.setMtu(MTU);
            assertFalse(source.equals(target));

            target.clear();
            target.setInterfaceName(NAME);
            target.addLinkAddress(LINKADDRV4);
            target.addLinkAddress(LINKADDRV6);
            target.addDns(DNS1);
            target.addDns(DNS2);
            // change gateway
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress("75.208.8.2")));
            target.addRoute(new RouteInfo(GATEWAY2));
            target.setMtu(MTU);
            assertFalse(source.equals(target));

            target.clear();
            target.setInterfaceName(NAME);
            target.addLinkAddress(LINKADDRV4);
            target.addLinkAddress(LINKADDRV6);
            target.addDns(DNS1);
            target.addDns(DNS2);
            target.addRoute(new RouteInfo(GATEWAY1));
            target.addRoute(new RouteInfo(GATEWAY2));
            // change mtu
            target.setMtu(1440);
            assertFalse(source.equals(target));

        } catch (Exception e) {
            throw new RuntimeException(e.toString());
            //fail();
        }
    }

    @SmallTest
    public void testEqualsDifferentOrder() {
        try {
            LinkProperties source = new LinkProperties();
            source.setInterfaceName(NAME);
            // set 2 link addresses
            source.addLinkAddress(LINKADDRV4);
            source.addLinkAddress(LINKADDRV6);
            // set 2 dnses
            source.addDns(DNS1);
            source.addDns(DNS2);
            // set 2 gateways
            source.addRoute(new RouteInfo(GATEWAY1));
            source.addRoute(new RouteInfo(GATEWAY2));
            source.setMtu(MTU);

            LinkProperties target = new LinkProperties();
            // Exchange order
            target.setInterfaceName(NAME);
            target.addLinkAddress(LINKADDRV6);
            target.addLinkAddress(LINKADDRV4);
            target.addDns(DNS2);
            target.addDns(DNS1);
            target.addRoute(new RouteInfo(GATEWAY2));
            target.addRoute(new RouteInfo(GATEWAY1));
            target.setMtu(MTU);

            assertLinkPropertiesEqual(source, target);
        } catch (Exception e) {
            fail();
        }
    }

    @SmallTest
    public void testEqualsDuplicated() {
        try {
            LinkProperties source = new LinkProperties();
            // set 3 link addresses, eg, [A, A, B]
            source.addLinkAddress(LINKADDRV4);
            source.addLinkAddress(LINKADDRV4);
            source.addLinkAddress(LINKADDRV6);

            LinkProperties target = new LinkProperties();
            // set 3 link addresses, eg, [A, B, B]
            target.addLinkAddress(LINKADDRV4);
            target.addLinkAddress(LINKADDRV6);
            target.addLinkAddress(LINKADDRV6);

            assertLinkPropertiesEqual(source, target);
        } catch (Exception e) {
            fail();
        }
    }

    private void assertAllRoutesHaveInterface(String iface, LinkProperties lp) {
        for (RouteInfo r : lp.getRoutes()) {
            assertEquals(iface, r.getInterface());
        }
    }

    @SmallTest
    public void testRouteInterfaces() {
        LinkAddress prefix = new LinkAddress(
            NetworkUtils.numericToInetAddress("2001:db8::"), 32);
        InetAddress address = ADDRV6;

        // Add a route with no interface to a LinkProperties with no interface. No errors.
        LinkProperties lp = new LinkProperties();
        RouteInfo r = new RouteInfo(prefix, address, null);
        lp.addRoute(r);
        assertEquals(1, lp.getRoutes().size());
        assertAllRoutesHaveInterface(null, lp);

        // Add a route with an interface. Except an exception.
        r = new RouteInfo(prefix, address, "wlan0");
        try {
          lp.addRoute(r);
          fail("Adding wlan0 route to LP with no interface, expect exception");
        } catch (IllegalArgumentException expected) {}

        // Change the interface name. All the routes should change their interface name too.
        lp.setInterfaceName("rmnet0");
        assertAllRoutesHaveInterface("rmnet0", lp);

        // Now add a route with the wrong interface. This causes an exception too.
        try {
          lp.addRoute(r);
          fail("Adding wlan0 route to rmnet0 LP, expect exception");
        } catch (IllegalArgumentException expected) {}

        // If the interface name matches, the route is added.
        lp.setInterfaceName("wlan0");
        lp.addRoute(r);
        assertEquals(2, lp.getRoutes().size());
        assertAllRoutesHaveInterface("wlan0", lp);

        // Routes with null interfaces are converted to wlan0.
        r = RouteInfo.makeHostRoute(ADDRV6, null);
        lp.addRoute(r);
        assertEquals(3, lp.getRoutes().size());
        assertAllRoutesHaveInterface("wlan0", lp);

        // Check comparisons work.
        LinkProperties lp2 = new LinkProperties(lp);
        assertAllRoutesHaveInterface("wlan0", lp);
        assertEquals(0, lp.compareAllRoutes(lp2).added.size());
        assertEquals(0, lp.compareAllRoutes(lp2).removed.size());

        lp2.setInterfaceName("p2p0");
        assertAllRoutesHaveInterface("p2p0", lp2);
        assertEquals(3, lp.compareAllRoutes(lp2).added.size());
        assertEquals(3, lp.compareAllRoutes(lp2).removed.size());
    }

    @SmallTest
    public void testStackedInterfaces() {
        LinkProperties rmnet0 = new LinkProperties();
        rmnet0.setInterfaceName("rmnet0");
        rmnet0.addLinkAddress(LINKADDRV6);

        LinkProperties clat4 = new LinkProperties();
        clat4.setInterfaceName("clat4");
        clat4.addLinkAddress(LINKADDRV4);

        assertEquals(0, rmnet0.getStackedLinks().size());
        assertEquals(1, rmnet0.getAddresses().size());
        assertEquals(1, rmnet0.getLinkAddresses().size());
        assertEquals(1, rmnet0.getAllAddresses().size());
        assertEquals(1, rmnet0.getAllLinkAddresses().size());

        rmnet0.addStackedLink(clat4);
        assertEquals(1, rmnet0.getStackedLinks().size());
        assertEquals(1, rmnet0.getAddresses().size());
        assertEquals(1, rmnet0.getLinkAddresses().size());
        assertEquals(2, rmnet0.getAllAddresses().size());
        assertEquals(2, rmnet0.getAllLinkAddresses().size());

        rmnet0.addStackedLink(clat4);
        assertEquals(1, rmnet0.getStackedLinks().size());
        assertEquals(1, rmnet0.getAddresses().size());
        assertEquals(1, rmnet0.getLinkAddresses().size());
        assertEquals(2, rmnet0.getAllAddresses().size());
        assertEquals(2, rmnet0.getAllLinkAddresses().size());

        assertEquals(0, clat4.getStackedLinks().size());

        // Modify an item in the returned collection to see what happens.
        for (LinkProperties link : rmnet0.getStackedLinks()) {
            if (link.getInterfaceName().equals("clat4")) {
               link.setInterfaceName("newname");
            }
        }
        for (LinkProperties link : rmnet0.getStackedLinks()) {
            assertFalse("newname".equals(link.getInterfaceName()));
        }

        assertTrue(rmnet0.removeStackedLink(clat4));
        assertEquals(0, rmnet0.getStackedLinks().size());
        assertEquals(1, rmnet0.getAddresses().size());
        assertEquals(1, rmnet0.getLinkAddresses().size());
        assertEquals(1, rmnet0.getAllAddresses().size());
        assertEquals(1, rmnet0.getAllLinkAddresses().size());

        assertFalse(rmnet0.removeStackedLink(clat4));
    }

    @SmallTest
    public void testAddressMethods() {
        LinkProperties lp = new LinkProperties();

        // No addresses.
        assertFalse(lp.hasIPv4Address());
        assertFalse(lp.hasIPv6Address());

        // Addresses on stacked links don't count.
        LinkProperties stacked = new LinkProperties();
        stacked.setInterfaceName("stacked");
        lp.addStackedLink(stacked);
        stacked.addLinkAddress(LINKADDRV4);
        stacked.addLinkAddress(LINKADDRV6);
        assertTrue(stacked.hasIPv4Address());
        assertTrue(stacked.hasIPv6Address());
        assertFalse(lp.hasIPv4Address());
        assertFalse(lp.hasIPv6Address());
        lp.removeStackedLink(stacked);
        assertFalse(lp.hasIPv4Address());
        assertFalse(lp.hasIPv6Address());

        // Addresses on the base link.
        // Check the return values of hasIPvXAddress and ensure the add/remove methods return true
        // iff something changes.
        assertTrue(lp.addLinkAddress(LINKADDRV6));
        assertFalse(lp.hasIPv4Address());
        assertTrue(lp.hasIPv6Address());

        assertTrue(lp.removeLinkAddress(LINKADDRV6));
        assertTrue(lp.addLinkAddress(LINKADDRV4));
        assertTrue(lp.hasIPv4Address());
        assertFalse(lp.hasIPv6Address());

        assertTrue(lp.addLinkAddress(LINKADDRV6));
        assertTrue(lp.hasIPv4Address());
        assertTrue(lp.hasIPv6Address());

        // Adding an address twice has no effect.
        // Removing an address that's not present has no effect.
        assertFalse(lp.addLinkAddress(LINKADDRV4));
        assertTrue(lp.hasIPv4Address());
        assertTrue(lp.removeLinkAddress(LINKADDRV4));
        assertFalse(lp.hasIPv4Address());
        assertFalse(lp.removeLinkAddress(LINKADDRV4));
    }

    @SmallTest
    public void testSetLinkAddresses() {
        LinkProperties lp = new LinkProperties();
        lp.addLinkAddress(LINKADDRV4);
        lp.addLinkAddress(LINKADDRV6);

        LinkProperties lp2 = new LinkProperties();
        lp2.addLinkAddress(LINKADDRV6);

        assertFalse(lp.equals(lp2));

        lp2.setLinkAddresses(lp.getLinkAddresses());
        assertTrue(lp.equals(lp));
    }
}
