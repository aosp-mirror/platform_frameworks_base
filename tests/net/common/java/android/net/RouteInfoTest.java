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

import static android.net.RouteInfo.RTN_UNREACHABLE;

import static com.android.testutils.MiscAssertsKt.assertEqualBothWays;
import static com.android.testutils.MiscAssertsKt.assertFieldCountEquals;
import static com.android.testutils.MiscAssertsKt.assertNotEqualEitherWay;
import static com.android.testutils.ParcelUtilsKt.assertParcelingIsLossless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Build;

import androidx.core.os.BuildCompat;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RouteInfoTest {
    @Rule
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    private static final int INVALID_ROUTE_TYPE = -1;

    private InetAddress Address(String addr) {
        return InetAddresses.parseNumericAddress(addr);
    }

    private IpPrefix Prefix(String prefix) {
        return new IpPrefix(prefix);
    }

    private static boolean isAtLeastR() {
        // BuildCompat.isAtLeastR is documented to return false on release SDKs (including R)
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.Q || BuildCompat.isAtLeastR();
    }

    @Test
    public void testConstructor() {
        RouteInfo r;
        // Invalid input.
        try {
            r = new RouteInfo((IpPrefix) null, null, "rmnet0");
            fail("Expected RuntimeException:  destination and gateway null");
        } catch (RuntimeException e) { }

        try {
            r = new RouteInfo(Prefix("2001:db8:ace::/49"), Address("2001:db8::1"), "rmnet0",
                    INVALID_ROUTE_TYPE);
            fail("Invalid route type should cause exception");
        } catch (IllegalArgumentException e) { }

        try {
            r = new RouteInfo(Prefix("2001:db8:ace::/49"), Address("192.0.2.1"), "rmnet0",
                    RTN_UNREACHABLE);
            fail("Address family mismatch should cause exception");
        } catch (IllegalArgumentException e) { }

        try {
            r = new RouteInfo(Prefix("0.0.0.0/0"), Address("2001:db8::1"), "rmnet0",
                    RTN_UNREACHABLE);
            fail("Address family mismatch should cause exception");
        } catch (IllegalArgumentException e) { }

        // Null destination is default route.
        r = new RouteInfo((IpPrefix) null, Address("2001:db8::1"), null);
        assertEquals(Prefix("::/0"), r.getDestination());
        assertEquals(Address("2001:db8::1"), r.getGateway());
        assertNull(r.getInterface());

        r = new RouteInfo((IpPrefix) null, Address("192.0.2.1"), "wlan0");
        assertEquals(Prefix("0.0.0.0/0"), r.getDestination());
        assertEquals(Address("192.0.2.1"), r.getGateway());
        assertEquals("wlan0", r.getInterface());

        // Null gateway sets gateway to unspecified address (why?).
        r = new RouteInfo(Prefix("2001:db8:beef:cafe::/48"), null, "lo");
        assertEquals(Prefix("2001:db8:beef::/48"), r.getDestination());
        assertEquals(Address("::"), r.getGateway());
        assertEquals("lo", r.getInterface());

        r = new RouteInfo(Prefix("192.0.2.5/24"), null);
        assertEquals(Prefix("192.0.2.0/24"), r.getDestination());
        assertEquals(Address("0.0.0.0"), r.getGateway());
        assertNull(r.getInterface());
    }

    @Test
    public void testMatches() {
        class PatchedRouteInfo {
            private final RouteInfo mRouteInfo;

            public PatchedRouteInfo(IpPrefix destination, InetAddress gateway, String iface) {
                mRouteInfo = new RouteInfo(destination, gateway, iface);
            }

            public boolean matches(InetAddress destination) {
                return mRouteInfo.matches(destination);
            }
        }

        PatchedRouteInfo r;

        r = new PatchedRouteInfo(Prefix("2001:db8:f00::ace:d00d/127"), null, "rmnet0");
        assertTrue(r.matches(Address("2001:db8:f00::ace:d00c")));
        assertTrue(r.matches(Address("2001:db8:f00::ace:d00d")));
        assertFalse(r.matches(Address("2001:db8:f00::ace:d00e")));
        assertFalse(r.matches(Address("2001:db8:f00::bad:d00d")));
        assertFalse(r.matches(Address("2001:4868:4860::8888")));
        assertFalse(r.matches(Address("8.8.8.8")));

        r = new PatchedRouteInfo(Prefix("192.0.2.0/23"), null, "wlan0");
        assertTrue(r.matches(Address("192.0.2.43")));
        assertTrue(r.matches(Address("192.0.3.21")));
        assertFalse(r.matches(Address("192.0.0.21")));
        assertFalse(r.matches(Address("8.8.8.8")));

        PatchedRouteInfo ipv6Default = new PatchedRouteInfo(Prefix("::/0"), null, "rmnet0");
        assertTrue(ipv6Default.matches(Address("2001:db8::f00")));
        assertFalse(ipv6Default.matches(Address("192.0.2.1")));

        PatchedRouteInfo ipv4Default = new PatchedRouteInfo(Prefix("0.0.0.0/0"), null, "rmnet0");
        assertTrue(ipv4Default.matches(Address("255.255.255.255")));
        assertTrue(ipv4Default.matches(Address("192.0.2.1")));
        assertFalse(ipv4Default.matches(Address("2001:db8::f00")));
    }

    @Test
    public void testEquals() {
        // IPv4
        RouteInfo r1 = new RouteInfo(Prefix("2001:db8:ace::/48"), Address("2001:db8::1"), "wlan0");
        RouteInfo r2 = new RouteInfo(Prefix("2001:db8:ace::/48"), Address("2001:db8::1"), "wlan0");
        assertEqualBothWays(r1, r2);

        RouteInfo r3 = new RouteInfo(Prefix("2001:db8:ace::/49"), Address("2001:db8::1"), "wlan0");
        RouteInfo r4 = new RouteInfo(Prefix("2001:db8:ace::/48"), Address("2001:db8::2"), "wlan0");
        RouteInfo r5 = new RouteInfo(Prefix("2001:db8:ace::/48"), Address("2001:db8::1"), "rmnet0");
        assertNotEqualEitherWay(r1, r3);
        assertNotEqualEitherWay(r1, r4);
        assertNotEqualEitherWay(r1, r5);

        // IPv6
        r1 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), "wlan0");
        r2 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), "wlan0");
        assertEqualBothWays(r1, r2);

        r3 = new RouteInfo(Prefix("192.0.2.0/24"), Address("192.0.2.1"), "wlan0");
        r4 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.2"), "wlan0");
        r5 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), "rmnet0");
        assertNotEqualEitherWay(r1, r3);
        assertNotEqualEitherWay(r1, r4);
        assertNotEqualEitherWay(r1, r5);

        // Interfaces (but not destinations or gateways) can be null.
        r1 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), null);
        r2 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), null);
        r3 = new RouteInfo(Prefix("192.0.2.0/24"), Address("192.0.2.1"), "wlan0");
        assertEqualBothWays(r1, r2);
        assertNotEqualEitherWay(r1, r3);
    }

    @Test
    public void testHostAndDefaultRoutes() {
        RouteInfo r;

        r = new RouteInfo(Prefix("0.0.0.0/0"), Address("0.0.0.0"), "wlan0");
        assertFalse(r.isHostRoute());
        assertTrue(r.isDefaultRoute());
        assertTrue(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("::/0"), Address("::"), "wlan0");
        assertFalse(r.isHostRoute());
        assertTrue(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertTrue(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("192.0.2.0/24"), null, "wlan0");
        assertFalse(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("2001:db8::/48"), null, "wlan0");
        assertFalse(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("192.0.2.0/32"), Address("0.0.0.0"), "wlan0");
        assertTrue(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("2001:db8::/128"), Address("::"), "wlan0");
        assertTrue(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("192.0.2.0/32"), null, "wlan0");
        assertTrue(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("2001:db8::/128"), null, "wlan0");
        assertTrue(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("::/128"), Address("fe80::"), "wlan0");
        assertTrue(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("0.0.0.0/32"), Address("192.0.2.1"), "wlan0");
        assertTrue(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(Prefix("0.0.0.0/32"), Address("192.0.2.1"), "wlan0");
        assertTrue(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), RTN_UNREACHABLE);
        assertFalse(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertTrue(r.isIPv4UnreachableDefault());
            assertFalse(r.isIPv6UnreachableDefault());
        }

        r = new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), RTN_UNREACHABLE);
        assertFalse(r.isHostRoute());
        assertFalse(r.isDefaultRoute());
        assertFalse(r.isIPv4Default());
        assertFalse(r.isIPv6Default());
        if (isAtLeastR()) {
            assertFalse(r.isIPv4UnreachableDefault());
            assertTrue(r.isIPv6UnreachableDefault());
        }
    }

    @Test
    public void testTruncation() {
      LinkAddress l;
      RouteInfo r;

      l = new LinkAddress("192.0.2.5/30");
      r = new RouteInfo(l, Address("192.0.2.1"), "wlan0");
      assertEquals("192.0.2.4", r.getDestination().getAddress().getHostAddress());

      l = new LinkAddress("2001:db8:1:f::5/63");
      r = new RouteInfo(l, Address("2001:db8:5::1"), "wlan0");
      assertEquals("2001:db8:1:e::", r.getDestination().getAddress().getHostAddress());
    }

    // Make sure that creating routes to multicast addresses doesn't throw an exception. Even though
    // there's nothing we can do with them, we don't want to crash if, e.g., someone calls
    // requestRouteToHostAddress("230.0.0.0", MOBILE_HIPRI);
    @Test
    public void testMulticastRoute() {
      RouteInfo r;
      r = new RouteInfo(Prefix("230.0.0.0/32"), Address("192.0.2.1"), "wlan0");
      r = new RouteInfo(Prefix("ff02::1/128"), Address("2001:db8::1"), "wlan0");
      // No exceptions? Good.
    }

    @Test
    public void testParceling() {
        RouteInfo r;
        r = new RouteInfo(Prefix("192.0.2.0/24"), Address("192.0.2.1"), null);
        assertParcelingIsLossless(r);
        r = new RouteInfo(Prefix("192.0.2.0/24"), null, "wlan0");
        assertParcelingIsLossless(r);
        r = new RouteInfo(Prefix("192.0.2.0/24"), Address("192.0.2.1"), "wlan0", RTN_UNREACHABLE);
        assertParcelingIsLossless(r);
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testMtuParceling() {
        final RouteInfo r = new RouteInfo(Prefix("ff02::1/128"), Address("2001:db8::"), "testiface",
                RTN_UNREACHABLE, 1450 /* mtu */);
        assertParcelingIsLossless(r);
    }

    @Test @IgnoreAfter(Build.VERSION_CODES.Q)
    public void testFieldCount_Q() {
        assertFieldCountEquals(6, RouteInfo.class);
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testFieldCount() {
        // Make sure any new field is covered by the above parceling tests when changing this number
        assertFieldCountEquals(7, RouteInfo.class);
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testMtu() {
        RouteInfo r;
        r = new RouteInfo(Prefix("0.0.0.0/0"), Address("0.0.0.0"), "wlan0",
                RouteInfo.RTN_UNICAST, 1500);
        assertEquals(1500, r.getMtu());

        r = new RouteInfo(Prefix("0.0.0.0/0"), Address("0.0.0.0"), "wlan0");
        assertEquals(0, r.getMtu());
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testRouteKey() {
        RouteInfo.RouteKey k1, k2;
        // Only prefix, null gateway and null interface
        k1 = new RouteInfo(Prefix("2001:db8::/128"), null).getRouteKey();
        k2 = new RouteInfo(Prefix("2001:db8::/128"), null).getRouteKey();
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());

        // With prefix, gateway and interface. Type and MTU does not affect RouteKey equality
        k1 = new RouteInfo(Prefix("192.0.2.0/24"), Address("192.0.2.1"), "wlan0",
                RTN_UNREACHABLE, 1450).getRouteKey();
        k2 = new RouteInfo(Prefix("192.0.2.0/24"), Address("192.0.2.1"), "wlan0",
                RouteInfo.RTN_UNICAST, 1400).getRouteKey();
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());

        // Different scope IDs are ignored by the kernel, so we consider them equal here too.
        k1 = new RouteInfo(Prefix("2001:db8::/64"), Address("fe80::1%1"), "wlan0").getRouteKey();
        k2 = new RouteInfo(Prefix("2001:db8::/64"), Address("fe80::1%2"), "wlan0").getRouteKey();
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());

        // Different prefix
        k1 = new RouteInfo(Prefix("192.0.2.0/24"), null).getRouteKey();
        k2 = new RouteInfo(Prefix("192.0.3.0/24"), null).getRouteKey();
        assertNotEquals(k1, k2);

        // Different gateway
        k1 = new RouteInfo(Prefix("ff02::1/128"), Address("2001:db8::1"), null).getRouteKey();
        k2 = new RouteInfo(Prefix("ff02::1/128"), Address("2001:db8::2"), null).getRouteKey();
        assertNotEquals(k1, k2);

        // Different interface
        k1 = new RouteInfo(Prefix("ff02::1/128"), null, "tun0").getRouteKey();
        k2 = new RouteInfo(Prefix("ff02::1/128"), null, "tun1").getRouteKey();
        assertNotEquals(k1, k2);
    }
}
