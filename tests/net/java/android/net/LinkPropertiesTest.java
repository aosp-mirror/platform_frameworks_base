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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.CompareResult;
import android.net.LinkProperties.ProvisioningChange;
import android.net.RouteInfo;
import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.system.OsConstants;
import android.util.ArraySet;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LinkPropertiesTest {
    private static InetAddress ADDRV4 = NetworkUtils.numericToInetAddress("75.208.6.1");
    private static InetAddress ADDRV6 = NetworkUtils.numericToInetAddress(
            "2001:0db8:85a3:0000:0000:8a2e:0370:7334");
    private static InetAddress DNS1 = NetworkUtils.numericToInetAddress("75.208.7.1");
    private static InetAddress DNS2 = NetworkUtils.numericToInetAddress("69.78.7.1");
    private static InetAddress DNS6 = NetworkUtils.numericToInetAddress("2001:4860:4860::8888");
    private static InetAddress PCSCFV6 =  NetworkUtils.numericToInetAddress(
            "2001:0db8:85a3:0000:0000:8a2e:0370:1");
    private static InetAddress GATEWAY1 = NetworkUtils.numericToInetAddress("75.208.8.1");
    private static InetAddress GATEWAY2 = NetworkUtils.numericToInetAddress("69.78.8.1");
    private static InetAddress GATEWAY61 = NetworkUtils.numericToInetAddress("fe80::6:0000:613");
    private static InetAddress GATEWAY62 = NetworkUtils.numericToInetAddress("fe80::6:2222");
    private static String NAME = "qmi0";
    private static int MTU = 1500;

    private static LinkAddress LINKADDRV4 = new LinkAddress(ADDRV4, 32);
    private static LinkAddress LINKADDRV6 = new LinkAddress(ADDRV6, 128);
    private static LinkAddress LINKADDRV6LINKLOCAL = new LinkAddress("fe80::1/64");

    // TODO: replace all calls to NetworkUtils.numericToInetAddress with calls to this method.
    private InetAddress Address(String addrString) {
        return NetworkUtils.numericToInetAddress(addrString);
    }

    public void assertLinkPropertiesEqual(LinkProperties source, LinkProperties target) {
        // Check implementation of equals(), element by element.
        assertTrue(source.isIdenticalInterfaceName(target));
        assertTrue(target.isIdenticalInterfaceName(source));

        assertTrue(source.isIdenticalAddresses(target));
        assertTrue(target.isIdenticalAddresses(source));

        assertTrue(source.isIdenticalDnses(target));
        assertTrue(target.isIdenticalDnses(source));

        assertTrue(source.isIdenticalPrivateDns(target));
        assertTrue(target.isIdenticalPrivateDns(source));

        assertTrue(source.isIdenticalValidatedPrivateDnses(target));
        assertTrue(target.isIdenticalValidatedPrivateDnses(source));

        assertTrue(source.isIdenticalPcscfs(target));
        assertTrue(target.isIdenticalPcscfs(source));

        assertTrue(source.isIdenticalRoutes(target));
        assertTrue(target.isIdenticalRoutes(source));

        assertTrue(source.isIdenticalHttpProxy(target));
        assertTrue(target.isIdenticalHttpProxy(source));

        assertTrue(source.isIdenticalStackedLinks(target));
        assertTrue(target.isIdenticalStackedLinks(source));

        assertTrue(source.isIdenticalMtu(target));
        assertTrue(target.isIdenticalMtu(source));

        assertTrue(source.isIdenticalTcpBufferSizes(target));
        assertTrue(target.isIdenticalTcpBufferSizes(source));

        // Check result of equals().
        assertTrue(source.equals(target));
        assertTrue(target.equals(source));

        // Check hashCode.
        assertEquals(source.hashCode(), target.hashCode());
    }

    @Test
    public void testEqualsNull() {
        LinkProperties source = new LinkProperties();
        LinkProperties target = new LinkProperties();

        assertFalse(source == target);
        assertLinkPropertiesEqual(source, target);
    }

    @Test
    public void testEqualsSameOrder() throws Exception {
        LinkProperties source = new LinkProperties();
        source.setInterfaceName(NAME);
        // set 2 link addresses
        source.addLinkAddress(LINKADDRV4);
        source.addLinkAddress(LINKADDRV6);
        // set 2 dnses
        source.addDnsServer(DNS1);
        source.addDnsServer(DNS2);
        // set 1 pcscf
        source.addPcscfServer(PCSCFV6);
        // set 2 gateways
        source.addRoute(new RouteInfo(GATEWAY1));
        source.addRoute(new RouteInfo(GATEWAY2));
        source.setMtu(MTU);

        LinkProperties target = new LinkProperties();

        // All fields are same
        target.setInterfaceName(NAME);
        target.addLinkAddress(LINKADDRV4);
        target.addLinkAddress(LINKADDRV6);
        target.addDnsServer(DNS1);
        target.addDnsServer(DNS2);
        target.addPcscfServer(PCSCFV6);
        target.addRoute(new RouteInfo(GATEWAY1));
        target.addRoute(new RouteInfo(GATEWAY2));
        target.setMtu(MTU);

        assertLinkPropertiesEqual(source, target);

        target.clear();
        // change Interface Name
        target.setInterfaceName("qmi1");
        target.addLinkAddress(LINKADDRV4);
        target.addLinkAddress(LINKADDRV6);
        target.addDnsServer(DNS1);
        target.addDnsServer(DNS2);
        target.addPcscfServer(PCSCFV6);
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
        target.addDnsServer(DNS1);
        target.addDnsServer(DNS2);
        target.addPcscfServer(PCSCFV6);
        target.addRoute(new RouteInfo(GATEWAY1));
        target.addRoute(new RouteInfo(GATEWAY2));
        target.setMtu(MTU);
        assertFalse(source.equals(target));

        target.clear();
        target.setInterfaceName(NAME);
        target.addLinkAddress(LINKADDRV4);
        target.addLinkAddress(LINKADDRV6);
        // change dnses
        target.addDnsServer(NetworkUtils.numericToInetAddress("75.208.7.2"));
        target.addDnsServer(DNS2);
        target.addPcscfServer(PCSCFV6);
        target.addRoute(new RouteInfo(GATEWAY1));
        target.addRoute(new RouteInfo(GATEWAY2));
        target.setMtu(MTU);
        assertFalse(source.equals(target));

        target.clear();
        target.setInterfaceName(NAME);
        target.addLinkAddress(LINKADDRV4);
        target.addLinkAddress(LINKADDRV6);
        target.addDnsServer(NetworkUtils.numericToInetAddress("75.208.7.2"));
        target.addDnsServer(DNS2);
        // change pcscf
        target.addPcscfServer(NetworkUtils.numericToInetAddress(
            "2001::1"));
        target.addRoute(new RouteInfo(GATEWAY1));
        target.addRoute(new RouteInfo(GATEWAY2));
        target.setMtu(MTU);
        assertFalse(source.equals(target));

        target.clear();
        target.setInterfaceName(NAME);
        target.addLinkAddress(LINKADDRV4);
        target.addLinkAddress(LINKADDRV6);
        target.addDnsServer(DNS1);
        target.addDnsServer(DNS2);
        // change gateway
        target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress("75.208.8.2")));
        target.addRoute(new RouteInfo(GATEWAY2));
        target.setMtu(MTU);
        assertFalse(source.equals(target));

        target.clear();
        target.setInterfaceName(NAME);
        target.addLinkAddress(LINKADDRV4);
        target.addLinkAddress(LINKADDRV6);
        target.addDnsServer(DNS1);
        target.addDnsServer(DNS2);
        target.addRoute(new RouteInfo(GATEWAY1));
        target.addRoute(new RouteInfo(GATEWAY2));
        // change mtu
        target.setMtu(1440);
        assertFalse(source.equals(target));
    }

    @Test
    public void testEqualsDifferentOrder() throws Exception {
        LinkProperties source = new LinkProperties();
        source.setInterfaceName(NAME);
        // set 2 link addresses
        source.addLinkAddress(LINKADDRV4);
        source.addLinkAddress(LINKADDRV6);
        // set 2 dnses
        source.addDnsServer(DNS1);
        source.addDnsServer(DNS2);
        // set 2 gateways
        source.addRoute(new RouteInfo(GATEWAY1));
        source.addRoute(new RouteInfo(GATEWAY2));
        source.setMtu(MTU);

        LinkProperties target = new LinkProperties();
        // Exchange order
        target.setInterfaceName(NAME);
        target.addLinkAddress(LINKADDRV6);
        target.addLinkAddress(LINKADDRV4);
        target.addDnsServer(DNS2);
        target.addDnsServer(DNS1);
        target.addRoute(new RouteInfo(GATEWAY2));
        target.addRoute(new RouteInfo(GATEWAY1));
        target.setMtu(MTU);

        assertLinkPropertiesEqual(source, target);
    }

    @Test
    public void testEqualsDuplicated() throws Exception {
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
    }

    private void assertAllRoutesHaveInterface(String iface, LinkProperties lp) {
        for (RouteInfo r : lp.getRoutes()) {
            assertEquals(iface, r.getInterface());
        }
    }

    @Test
    public void testRouteInterfaces() {
        LinkAddress prefix = new LinkAddress(
            NetworkUtils.numericToInetAddress("2001:db8::"), 32);
        InetAddress address = ADDRV6;

        // Add a route with no interface to a LinkProperties with no interface. No errors.
        LinkProperties lp = new LinkProperties();
        RouteInfo r = new RouteInfo(prefix, address, null);
        assertTrue(lp.addRoute(r));
        assertEquals(1, lp.getRoutes().size());
        assertAllRoutesHaveInterface(null, lp);

        // Adding the same route twice has no effect.
        assertFalse(lp.addRoute(r));
        assertEquals(1, lp.getRoutes().size());

        // Add a route with an interface. Expect an exception.
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
        r = new RouteInfo(prefix, null, "wlan0");
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

    @Test
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

        assertTrue(rmnet0.removeStackedLink("clat4"));
        assertEquals(0, rmnet0.getStackedLinks().size());
        assertEquals(1, rmnet0.getAddresses().size());
        assertEquals(1, rmnet0.getLinkAddresses().size());
        assertEquals(1, rmnet0.getAllAddresses().size());
        assertEquals(1, rmnet0.getAllLinkAddresses().size());

        assertFalse(rmnet0.removeStackedLink("clat4"));
    }

    private LinkAddress getFirstLinkAddress(LinkProperties lp) {
        return lp.getLinkAddresses().iterator().next();
    }

    @Test
    public void testAddressMethods() {
        LinkProperties lp = new LinkProperties();

        // No addresses.
        assertFalse(lp.hasIPv4Address());
        assertFalse(lp.hasGlobalIPv6Address());

        // Addresses on stacked links don't count.
        LinkProperties stacked = new LinkProperties();
        stacked.setInterfaceName("stacked");
        lp.addStackedLink(stacked);
        stacked.addLinkAddress(LINKADDRV4);
        stacked.addLinkAddress(LINKADDRV6);
        assertTrue(stacked.hasIPv4Address());
        assertTrue(stacked.hasGlobalIPv6Address());
        assertFalse(lp.hasIPv4Address());
        assertFalse(lp.hasGlobalIPv6Address());
        lp.removeStackedLink("stacked");
        assertFalse(lp.hasIPv4Address());
        assertFalse(lp.hasGlobalIPv6Address());

        // Addresses on the base link.
        // Check the return values of hasIPvXAddress and ensure the add/remove methods return true
        // iff something changes.
        assertEquals(0, lp.getLinkAddresses().size());
        assertTrue(lp.addLinkAddress(LINKADDRV6));
        assertEquals(1, lp.getLinkAddresses().size());
        assertFalse(lp.hasIPv4Address());
        assertTrue(lp.hasGlobalIPv6Address());

        assertTrue(lp.removeLinkAddress(LINKADDRV6));
        assertEquals(0, lp.getLinkAddresses().size());

        assertTrue(lp.addLinkAddress(LINKADDRV6LINKLOCAL));
        assertEquals(1, lp.getLinkAddresses().size());
        assertFalse(lp.hasGlobalIPv6Address());

        assertTrue(lp.addLinkAddress(LINKADDRV4));
        assertEquals(2, lp.getLinkAddresses().size());
        assertTrue(lp.hasIPv4Address());
        assertFalse(lp.hasGlobalIPv6Address());

        assertTrue(lp.addLinkAddress(LINKADDRV6));
        assertEquals(3, lp.getLinkAddresses().size());
        assertTrue(lp.hasIPv4Address());
        assertTrue(lp.hasGlobalIPv6Address());

        assertTrue(lp.removeLinkAddress(LINKADDRV6LINKLOCAL));
        assertEquals(2, lp.getLinkAddresses().size());
        assertTrue(lp.hasIPv4Address());
        assertTrue(lp.hasGlobalIPv6Address());

        // Adding an address twice has no effect.
        // Removing an address that's not present has no effect.
        assertFalse(lp.addLinkAddress(LINKADDRV4));
        assertEquals(2, lp.getLinkAddresses().size());
        assertTrue(lp.hasIPv4Address());
        assertTrue(lp.removeLinkAddress(LINKADDRV4));
        assertEquals(1, lp.getLinkAddresses().size());
        assertFalse(lp.hasIPv4Address());
        assertFalse(lp.removeLinkAddress(LINKADDRV4));
        assertEquals(1, lp.getLinkAddresses().size());

        // Adding an address that's already present but with different properties causes the
        // existing address to be updated and returns true.
        // Start with only LINKADDRV6.
        assertEquals(1, lp.getLinkAddresses().size());
        assertEquals(LINKADDRV6, getFirstLinkAddress(lp));

        // Create a LinkAddress object for the same address, but with different flags.
        LinkAddress deprecated = new LinkAddress(ADDRV6, 128,
                OsConstants.IFA_F_DEPRECATED, OsConstants.RT_SCOPE_UNIVERSE);
        assertTrue(deprecated.isSameAddressAs(LINKADDRV6));
        assertFalse(deprecated.equals(LINKADDRV6));

        // Check that adding it updates the existing address instead of adding a new one.
        assertTrue(lp.addLinkAddress(deprecated));
        assertEquals(1, lp.getLinkAddresses().size());
        assertEquals(deprecated, getFirstLinkAddress(lp));
        assertFalse(LINKADDRV6.equals(getFirstLinkAddress(lp)));

        // Removing LINKADDRV6 removes deprecated, because removing addresses ignores properties.
        assertTrue(lp.removeLinkAddress(LINKADDRV6));
        assertEquals(0, lp.getLinkAddresses().size());
    }

    @Test
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

    @Test
    public void testIsProvisioned() {
        LinkProperties lp4 = new LinkProperties();
        assertFalse("v4only:empty", lp4.isProvisioned());
        lp4.addLinkAddress(LINKADDRV4);
        assertFalse("v4only:addr-only", lp4.isProvisioned());
        lp4.addDnsServer(DNS1);
        assertFalse("v4only:addr+dns", lp4.isProvisioned());
        lp4.addRoute(new RouteInfo(GATEWAY1));
        assertTrue("v4only:addr+dns+route", lp4.isProvisioned());
        assertTrue("v4only:addr+dns+route", lp4.isIPv4Provisioned());
        assertFalse("v4only:addr+dns+route", lp4.isIPv6Provisioned());

        LinkProperties lp6 = new LinkProperties();
        assertFalse("v6only:empty", lp6.isProvisioned());
        lp6.addLinkAddress(LINKADDRV6LINKLOCAL);
        assertFalse("v6only:fe80-only", lp6.isProvisioned());
        lp6.addDnsServer(DNS6);
        assertFalse("v6only:fe80+dns", lp6.isProvisioned());
        lp6.addRoute(new RouteInfo(GATEWAY61));
        assertFalse("v6only:fe80+dns+route", lp6.isProvisioned());
        lp6.addLinkAddress(LINKADDRV6);
        assertTrue("v6only:fe80+global+dns+route", lp6.isIPv6Provisioned());
        assertTrue("v6only:fe80+global+dns+route", lp6.isProvisioned());
        lp6.removeLinkAddress(LINKADDRV6LINKLOCAL);
        assertFalse("v6only:global+dns+route", lp6.isIPv4Provisioned());
        assertTrue("v6only:global+dns+route", lp6.isIPv6Provisioned());
        assertTrue("v6only:global+dns+route", lp6.isProvisioned());

        LinkProperties lp46 = new LinkProperties();
        lp46.addLinkAddress(LINKADDRV4);
        lp46.addLinkAddress(LINKADDRV6);
        lp46.addDnsServer(DNS1);
        lp46.addDnsServer(DNS6);
        assertFalse("dualstack:missing-routes", lp46.isProvisioned());
        lp46.addRoute(new RouteInfo(GATEWAY1));
        assertTrue("dualstack:v4-provisioned", lp46.isIPv4Provisioned());
        assertFalse("dualstack:v4-provisioned", lp46.isIPv6Provisioned());
        assertTrue("dualstack:v4-provisioned", lp46.isProvisioned());
        lp46.addRoute(new RouteInfo(GATEWAY61));
        assertTrue("dualstack:both-provisioned", lp46.isIPv4Provisioned());
        assertTrue("dualstack:both-provisioned", lp46.isIPv6Provisioned());
        assertTrue("dualstack:both-provisioned", lp46.isProvisioned());

        // A link with an IPv6 address and default route, but IPv4 DNS server.
        LinkProperties mixed = new LinkProperties();
        mixed.addLinkAddress(LINKADDRV6);
        mixed.addDnsServer(DNS1);
        mixed.addRoute(new RouteInfo(GATEWAY61));
        assertFalse("mixed:addr6+route6+dns4", mixed.isIPv4Provisioned());
        assertFalse("mixed:addr6+route6+dns4", mixed.isIPv6Provisioned());
        assertFalse("mixed:addr6+route6+dns4", mixed.isProvisioned());
    }

    @Test
    public void testCompareProvisioning() {
        LinkProperties v4lp = new LinkProperties();
        v4lp.addLinkAddress(LINKADDRV4);
        v4lp.addRoute(new RouteInfo(GATEWAY1));
        v4lp.addDnsServer(DNS1);
        assertTrue(v4lp.isProvisioned());

        LinkProperties v4r = new LinkProperties(v4lp);
        v4r.removeDnsServer(DNS1);
        assertFalse(v4r.isProvisioned());

        assertEquals(ProvisioningChange.STILL_NOT_PROVISIONED,
                LinkProperties.compareProvisioning(v4r, v4r));
        assertEquals(ProvisioningChange.LOST_PROVISIONING,
                LinkProperties.compareProvisioning(v4lp, v4r));
        assertEquals(ProvisioningChange.GAINED_PROVISIONING,
                LinkProperties.compareProvisioning(v4r, v4lp));
        assertEquals(ProvisioningChange.STILL_PROVISIONED,
                LinkProperties.compareProvisioning(v4lp, v4lp));

        // Check that losing IPv4 provisioning on a dualstack network is
        // seen as a total loss of provisioning.
        LinkProperties v6lp = new LinkProperties();
        v6lp.addLinkAddress(LINKADDRV6);
        v6lp.addRoute(new RouteInfo(GATEWAY61));
        v6lp.addDnsServer(DNS6);
        assertFalse(v6lp.isIPv4Provisioned());
        assertTrue(v6lp.isIPv6Provisioned());
        assertTrue(v6lp.isProvisioned());

        LinkProperties v46lp = new LinkProperties(v6lp);
        v46lp.addLinkAddress(LINKADDRV4);
        v46lp.addRoute(new RouteInfo(GATEWAY1));
        v46lp.addDnsServer(DNS1);
        assertTrue(v46lp.isIPv4Provisioned());
        assertTrue(v46lp.isIPv6Provisioned());
        assertTrue(v46lp.isProvisioned());

        assertEquals(ProvisioningChange.STILL_PROVISIONED,
                LinkProperties.compareProvisioning(v4lp, v46lp));
        assertEquals(ProvisioningChange.STILL_PROVISIONED,
                LinkProperties.compareProvisioning(v6lp, v46lp));
        assertEquals(ProvisioningChange.LOST_PROVISIONING,
                LinkProperties.compareProvisioning(v46lp, v6lp));
        assertEquals(ProvisioningChange.LOST_PROVISIONING,
                LinkProperties.compareProvisioning(v46lp, v4lp));

        // Check that losing and gaining a secondary router does not change
        // the provisioning status.
        LinkProperties v6lp2 = new LinkProperties(v6lp);
        v6lp2.addRoute(new RouteInfo(GATEWAY62));
        assertTrue(v6lp2.isProvisioned());

        assertEquals(ProvisioningChange.STILL_PROVISIONED,
                LinkProperties.compareProvisioning(v6lp2, v6lp));
        assertEquals(ProvisioningChange.STILL_PROVISIONED,
                LinkProperties.compareProvisioning(v6lp, v6lp2));
    }

    @Test
    public void testIsReachable() {
        final LinkProperties v4lp = new LinkProperties();
        assertFalse(v4lp.isReachable(DNS1));
        assertFalse(v4lp.isReachable(DNS2));

        // Add an on-link route, making the on-link DNS server reachable,
        // but there is still no IPv4 address.
        assertTrue(v4lp.addRoute(new RouteInfo(
                new IpPrefix(NetworkUtils.numericToInetAddress("75.208.0.0"), 16))));
        assertFalse(v4lp.isReachable(DNS1));
        assertFalse(v4lp.isReachable(DNS2));

        // Adding an IPv4 address (right now, any IPv4 address) means we use
        // the routes to compute likely reachability.
        assertTrue(v4lp.addLinkAddress(new LinkAddress(ADDRV4, 16)));
        assertTrue(v4lp.isReachable(DNS1));
        assertFalse(v4lp.isReachable(DNS2));

        // Adding a default route makes the off-link DNS server reachable.
        assertTrue(v4lp.addRoute(new RouteInfo(GATEWAY1)));
        assertTrue(v4lp.isReachable(DNS1));
        assertTrue(v4lp.isReachable(DNS2));

        final LinkProperties v6lp = new LinkProperties();
        final InetAddress kLinkLocalDns = NetworkUtils.numericToInetAddress("fe80::6:1");
        final InetAddress kLinkLocalDnsWithScope = NetworkUtils.numericToInetAddress("fe80::6:2%43");
        final InetAddress kOnLinkDns = NetworkUtils.numericToInetAddress("2001:db8:85a3::53");
        assertFalse(v6lp.isReachable(kLinkLocalDns));
        assertFalse(v6lp.isReachable(kLinkLocalDnsWithScope));
        assertFalse(v6lp.isReachable(kOnLinkDns));
        assertFalse(v6lp.isReachable(DNS6));

        // Add a link-local route, making the link-local DNS servers reachable. Because
        // we assume the presence of an IPv6 link-local address, link-local DNS servers
        // are considered reachable, but only those with a non-zero scope identifier.
        assertTrue(v6lp.addRoute(new RouteInfo(
                new IpPrefix(NetworkUtils.numericToInetAddress("fe80::"), 64))));
        assertFalse(v6lp.isReachable(kLinkLocalDns));
        assertTrue(v6lp.isReachable(kLinkLocalDnsWithScope));
        assertFalse(v6lp.isReachable(kOnLinkDns));
        assertFalse(v6lp.isReachable(DNS6));

        // Add a link-local address--nothing changes.
        assertTrue(v6lp.addLinkAddress(LINKADDRV6LINKLOCAL));
        assertFalse(v6lp.isReachable(kLinkLocalDns));
        assertTrue(v6lp.isReachable(kLinkLocalDnsWithScope));
        assertFalse(v6lp.isReachable(kOnLinkDns));
        assertFalse(v6lp.isReachable(DNS6));

        // Add a global route on link, but no global address yet. DNS servers reachable
        // via a route that doesn't require a gateway: give them the benefit of the
        // doubt and hope the link-local source address suffices for communication.
        assertTrue(v6lp.addRoute(new RouteInfo(
                new IpPrefix(NetworkUtils.numericToInetAddress("2001:db8:85a3::"), 64))));
        assertFalse(v6lp.isReachable(kLinkLocalDns));
        assertTrue(v6lp.isReachable(kLinkLocalDnsWithScope));
        assertTrue(v6lp.isReachable(kOnLinkDns));
        assertFalse(v6lp.isReachable(DNS6));

        // Add a global address; the on-link global address DNS server is (still)
        // presumed reachable.
        assertTrue(v6lp.addLinkAddress(new LinkAddress(ADDRV6, 64)));
        assertFalse(v6lp.isReachable(kLinkLocalDns));
        assertTrue(v6lp.isReachable(kLinkLocalDnsWithScope));
        assertTrue(v6lp.isReachable(kOnLinkDns));
        assertFalse(v6lp.isReachable(DNS6));

        // Adding a default route makes the off-link DNS server reachable.
        assertTrue(v6lp.addRoute(new RouteInfo(GATEWAY62)));
        assertFalse(v6lp.isReachable(kLinkLocalDns));
        assertTrue(v6lp.isReachable(kLinkLocalDnsWithScope));
        assertTrue(v6lp.isReachable(kOnLinkDns));
        assertTrue(v6lp.isReachable(DNS6));

        // Check isReachable on stacked links. This requires that the source IP address be assigned
        // on the interface returned by the route lookup.
        LinkProperties stacked = new LinkProperties();

        // Can't add a stacked link without an interface name.
        stacked.setInterfaceName("v4-test0");
        v6lp.addStackedLink(stacked);

        InetAddress stackedAddress = Address("192.0.0.4");
        LinkAddress stackedLinkAddress = new LinkAddress(stackedAddress, 32);
        assertFalse(v6lp.isReachable(stackedAddress));
        stacked.addLinkAddress(stackedLinkAddress);
        assertFalse(v6lp.isReachable(stackedAddress));
        stacked.addRoute(new RouteInfo(stackedLinkAddress));
        assertTrue(stacked.isReachable(stackedAddress));
        assertTrue(v6lp.isReachable(stackedAddress));

        assertFalse(v6lp.isReachable(DNS1));
        stacked.addRoute(new RouteInfo((IpPrefix) null, stackedAddress));
        assertTrue(v6lp.isReachable(DNS1));
    }

    @Test
    public void testLinkPropertiesEnsureDirectlyConnectedRoutes() {
        // IPv4 case: no route added initially
        LinkProperties rmnet0 = new LinkProperties();
        rmnet0.setInterfaceName("rmnet0");
        rmnet0.addLinkAddress(new LinkAddress("10.0.0.2/8"));
        RouteInfo directRoute0 = new RouteInfo(new IpPrefix("10.0.0.0/8"), null,
                rmnet0.getInterfaceName());

        // Since no routes is added explicitly, getAllRoutes() should return empty.
        assertTrue(rmnet0.getAllRoutes().isEmpty());
        rmnet0.ensureDirectlyConnectedRoutes();
        // ensureDirectlyConnectedRoutes() should have added the missing local route.
        assertEqualRoutes(Collections.singletonList(directRoute0), rmnet0.getAllRoutes());

        // IPv4 case: both direct and default routes added initially
        LinkProperties rmnet1 = new LinkProperties();
        rmnet1.setInterfaceName("rmnet1");
        rmnet1.addLinkAddress(new LinkAddress("10.0.0.3/8"));
        RouteInfo defaultRoute1 = new RouteInfo((IpPrefix) null,
                NetworkUtils.numericToInetAddress("10.0.0.1"), rmnet1.getInterfaceName());
        RouteInfo directRoute1 = new RouteInfo(new IpPrefix("10.0.0.0/8"), null,
                rmnet1.getInterfaceName());
        rmnet1.addRoute(defaultRoute1);
        rmnet1.addRoute(directRoute1);

        // Check added routes
        assertEqualRoutes(Arrays.asList(defaultRoute1, directRoute1), rmnet1.getAllRoutes());
        // ensureDirectlyConnectedRoutes() shouldn't change the routes since direct connected
        // route is already part of the configuration.
        rmnet1.ensureDirectlyConnectedRoutes();
        assertEqualRoutes(Arrays.asList(defaultRoute1, directRoute1), rmnet1.getAllRoutes());

        // IPv6 case: only default routes added initially
        LinkProperties rmnet2 = new LinkProperties();
        rmnet2.setInterfaceName("rmnet2");
        rmnet2.addLinkAddress(new LinkAddress("fe80::cafe/64"));
        rmnet2.addLinkAddress(new LinkAddress("2001:db8::2/64"));
        RouteInfo defaultRoute2 = new RouteInfo((IpPrefix) null,
                NetworkUtils.numericToInetAddress("2001:db8::1"), rmnet2.getInterfaceName());
        RouteInfo directRoute2 = new RouteInfo(new IpPrefix("2001:db8::/64"), null,
                rmnet2.getInterfaceName());
        RouteInfo linkLocalRoute2 = new RouteInfo(new IpPrefix("fe80::/64"), null,
                rmnet2.getInterfaceName());
        rmnet2.addRoute(defaultRoute2);

        assertEqualRoutes(Arrays.asList(defaultRoute2), rmnet2.getAllRoutes());
        rmnet2.ensureDirectlyConnectedRoutes();
        assertEqualRoutes(Arrays.asList(defaultRoute2, directRoute2, linkLocalRoute2),
                rmnet2.getAllRoutes());

        // Corner case: no interface name
        LinkProperties rmnet3 = new LinkProperties();
        rmnet3.addLinkAddress(new LinkAddress("192.168.0.2/24"));
        RouteInfo directRoute3 = new RouteInfo(new IpPrefix("192.168.0.0/24"), null,
                rmnet3.getInterfaceName());

        assertTrue(rmnet3.getAllRoutes().isEmpty());
        rmnet3.ensureDirectlyConnectedRoutes();
        assertEqualRoutes(Collections.singletonList(directRoute3), rmnet3.getAllRoutes());

    }

    @Test
    public void testCompareResult() {
        // Either adding or removing items
        compareResult(Arrays.asList(1, 2, 3, 4), Arrays.asList(1),
                Arrays.asList(2, 3, 4), new ArrayList<>());
        compareResult(Arrays.asList(1, 2), Arrays.asList(3, 2, 1, 4),
                new ArrayList<>(), Arrays.asList(3, 4));


        // adding and removing items at the same time
        compareResult(Arrays.asList(1, 2, 3, 4), Arrays.asList(2, 3, 4, 5),
                Arrays.asList(1), Arrays.asList(5));
        compareResult(Arrays.asList(1, 2, 3), Arrays.asList(4, 5, 6),
                Arrays.asList(1, 2, 3), Arrays.asList(4, 5, 6));

        // null cases
        compareResult(Arrays.asList(1, 2, 3), null, Arrays.asList(1, 2, 3), new ArrayList<>());
        compareResult(null, Arrays.asList(3, 2, 1), new ArrayList<>(), Arrays.asList(1, 2, 3));
        compareResult(null, null, new ArrayList<>(), new ArrayList<>());
    }

    private void assertEqualRoutes(Collection<RouteInfo> expected, Collection<RouteInfo> actual) {
        Set<RouteInfo> expectedSet = new ArraySet<>(expected);
        Set<RouteInfo> actualSet = new ArraySet<>(actual);
        // Duplicated entries in actual routes are considered failures
        assertEquals(actual.size(), actualSet.size());

        assertEquals(expectedSet, actualSet);
    }

    private <T> void compareResult(List<T> oldItems, List<T> newItems, List<T> expectRemoved,
            List<T> expectAdded) {
        CompareResult<T> result = new CompareResult<>(oldItems, newItems);
        assertEquals(new ArraySet<>(expectAdded), new ArraySet<>(result.added));
        assertEquals(new ArraySet<>(expectRemoved), (new ArraySet<>(result.removed)));
    }

    @Test
    public void testLinkPropertiesParcelable() {
        LinkProperties source = new LinkProperties();
        source.setInterfaceName(NAME);
        // set 2 link addresses
        source.addLinkAddress(LINKADDRV4);
        source.addLinkAddress(LINKADDRV6);
        // set 2 dnses
        source.addDnsServer(DNS1);
        source.addDnsServer(DNS2);
        // set 2 gateways
        source.addRoute(new RouteInfo(GATEWAY1));
        source.addRoute(new RouteInfo(GATEWAY2));
        // set 2 validated private dnses
        source.addValidatedPrivateDnsServer(DNS6);
        source.addValidatedPrivateDnsServer(GATEWAY61);

        source.setMtu(MTU);

        Parcel p = Parcel.obtain();
        source.writeToParcel(p, /* flags */ 0);
        p.setDataPosition(0);
        final byte[] marshalled = p.marshall();
        p = Parcel.obtain();
        p.unmarshall(marshalled, 0, marshalled.length);
        p.setDataPosition(0);
        LinkProperties dest = LinkProperties.CREATOR.createFromParcel(p);

        assertEquals(source, dest);
    }
}
