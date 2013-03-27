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

import java.lang.reflect.Method;
import java.net.InetAddress;

import android.net.LinkAddress;
import android.net.RouteInfo;
import android.os.Parcel;

import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;

public class RouteInfoTest extends TestCase {

    private InetAddress Address(String addr) {
        return InetAddress.parseNumericAddress(addr);
    }

    private LinkAddress Prefix(String prefix) {
        String[] parts = prefix.split("/");
        return new LinkAddress(Address(parts[0]), Integer.parseInt(parts[1]));
    }

    @SmallTest
    public void testConstructor() {
        RouteInfo r;

        // Invalid input.
        try {
            r = new RouteInfo(null, null, "rmnet0");
            fail("Expected RuntimeException:  destination and gateway null");
        } catch(RuntimeException e) {}

        // Null destination is default route.
        r = new RouteInfo(null, Address("2001:db8::1"), null);
        assertEquals(Prefix("::/0"), r.getDestination());
        assertEquals(Address("2001:db8::1"), r.getGateway());
        assertNull(r.getInterface());

        r = new RouteInfo(null, Address("192.0.2.1"), "wlan0");
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

    public void testMatches() {
        class PatchedRouteInfo extends RouteInfo {
            public PatchedRouteInfo(LinkAddress destination, InetAddress gateway, String iface) {
                super(destination, gateway, iface);
            }

            public boolean matches(InetAddress destination) {
                return super.matches(destination);
            }
        }

        RouteInfo r;

        r = new PatchedRouteInfo(Prefix("2001:db8:f00::ace:d00d/127"), null, "rmnet0");
        assertTrue(r.matches(Address("2001:db8:f00::ace:d00c")));
        assertTrue(r.matches(Address("2001:db8:f00::ace:d00d")));
        assertFalse(r.matches(Address("2001:db8:f00::ace:d00e")));
        assertFalse(r.matches(Address("2001:db8:f00::bad:d00d")));
        assertFalse(r.matches(Address("2001:4868:4860::8888")));

        r = new PatchedRouteInfo(Prefix("192.0.2.0/23"), null, "wlan0");
        assertTrue(r.matches(Address("192.0.2.43")));
        assertTrue(r.matches(Address("192.0.3.21")));
        assertFalse(r.matches(Address("192.0.0.21")));
        assertFalse(r.matches(Address("8.8.8.8")));

        RouteInfo ipv6Default = new PatchedRouteInfo(Prefix("::/0"), null, "rmnet0");
        assertTrue(ipv6Default.matches(Address("2001:db8::f00")));
        assertFalse(ipv6Default.matches(Address("192.0.2.1")));

        RouteInfo ipv4Default = new PatchedRouteInfo(Prefix("0.0.0.0/0"), null, "rmnet0");
        assertTrue(ipv4Default.matches(Address("255.255.255.255")));
        assertTrue(ipv4Default.matches(Address("192.0.2.1")));
        assertFalse(ipv4Default.matches(Address("2001:db8::f00")));
    }

    private void assertAreEqual(Object o1, Object o2) {
        assertTrue(o1.equals(o2));
        assertTrue(o2.equals(o1));
    }

    private void assertAreNotEqual(Object o1, Object o2) {
        assertFalse(o1.equals(o2));
        assertFalse(o2.equals(o1));
    }

    public void testEquals() {
        // IPv4
        RouteInfo r1 = new RouteInfo(Prefix("2001:db8:ace::/48"), Address("2001:db8::1"), "wlan0");
        RouteInfo r2 = new RouteInfo(Prefix("2001:db8:ace::/48"), Address("2001:db8::1"), "wlan0");
        assertAreEqual(r1, r2);

        RouteInfo r3 = new RouteInfo(Prefix("2001:db8:ace::/49"), Address("2001:db8::1"), "wlan0");
        RouteInfo r4 = new RouteInfo(Prefix("2001:db8:ace::/48"), Address("2001:db8::2"), "wlan0");
        RouteInfo r5 = new RouteInfo(Prefix("2001:db8:ace::/48"), Address("2001:db8::1"), "rmnet0");
        assertAreNotEqual(r1, r3);
        assertAreNotEqual(r1, r4);
        assertAreNotEqual(r1, r5);

        // IPv6
        r1 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), "wlan0");
        r2 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), "wlan0");
        assertAreEqual(r1, r2);

        r3 = new RouteInfo(Prefix("192.0.2.0/24"), Address("192.0.2.1"), "wlan0");
        r4 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.2"), "wlan0");
        r5 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), "rmnet0");
        assertAreNotEqual(r1, r3);
        assertAreNotEqual(r1, r4);
        assertAreNotEqual(r1, r5);

        // Interfaces (but not destinations or gateways) can be null.
        r1 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), null);
        r2 = new RouteInfo(Prefix("192.0.2.0/25"), Address("192.0.2.1"), null);
        r3 = new RouteInfo(Prefix("192.0.2.0/24"), Address("192.0.2.1"), "wlan0");
        assertAreEqual(r1, r2);
        assertAreNotEqual(r1, r3);
    }

    public void testHostRoute() {
      RouteInfo r;

      r = new RouteInfo(Prefix("0.0.0.0/0"), Address("0.0.0.0"), "wlan0");
      assertFalse(r.isHostRoute());

      r = new RouteInfo(Prefix("::/0"), Address("::"), "wlan0");
      assertFalse(r.isHostRoute());

      r = new RouteInfo(Prefix("192.0.2.0/24"), null, "wlan0");
      assertFalse(r.isHostRoute());

      r = new RouteInfo(Prefix("2001:db8::/48"), null, "wlan0");
      assertFalse(r.isHostRoute());

      r = new RouteInfo(Prefix("192.0.2.0/32"), Address("0.0.0.0"), "wlan0");
      assertTrue(r.isHostRoute());

      r = new RouteInfo(Prefix("2001:db8::/128"), Address("::"), "wlan0");
      assertTrue(r.isHostRoute());

      r = new RouteInfo(Prefix("192.0.2.0/32"), null, "wlan0");
      assertTrue(r.isHostRoute());

      r = new RouteInfo(Prefix("2001:db8::/128"), null, "wlan0");
      assertTrue(r.isHostRoute());

      r = new RouteInfo(Prefix("::/128"), Address("fe80::"), "wlan0");
      assertTrue(r.isHostRoute());

      r = new RouteInfo(Prefix("0.0.0.0/32"), Address("192.0.2.1"), "wlan0");
      assertTrue(r.isHostRoute());
    }

    public RouteInfo passThroughParcel(RouteInfo r) {
        Parcel p = Parcel.obtain();
        RouteInfo r2 = null;
        try {
            r.writeToParcel(p, 0);
            p.setDataPosition(0);
            r2 = RouteInfo.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
        assertNotNull(r2);
        return r2;
    }

    public void assertParcelingIsLossless(RouteInfo r) {
      RouteInfo r2 = passThroughParcel(r);
      assertEquals(r, r2);
    }

    public void testParceling() {
        RouteInfo r;

        r = new RouteInfo(Prefix("::/0"), Address("2001:db8::"), null);
        assertParcelingIsLossless(r);

        r = new RouteInfo(Prefix("192.0.2.0/24"), null, "wlan0");
        assertParcelingIsLossless(r);
    }
}
