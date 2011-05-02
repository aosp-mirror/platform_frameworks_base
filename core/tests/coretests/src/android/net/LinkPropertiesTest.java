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

public class LinkPropertiesTest extends TestCase {
    private static String ADDRV4 = "75.208.6.1";
    private static String ADDRV6 = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
    private static String DNS1 = "75.208.7.1";
    private static String DNS2 = "69.78.7.1";
    private static String GATEWAY1 = "75.208.8.1";
    private static String GATEWAY2 = "69.78.8.1";
    private static String NAME = "qmi0";

    @SmallTest
    public void testEqualsNull() {
        LinkProperties source = new LinkProperties();
        LinkProperties target = new LinkProperties();

        assertFalse(source == target);
        assertTrue(source.equals(target));
        assertTrue(source.hashCode() == target.hashCode());
    }

    @SmallTest
    public void testEqualsSameOrder() {
        try {
            LinkProperties source = new LinkProperties();
            source.setInterfaceName(NAME);
            // set 2 link addresses
            source.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            source.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));
            // set 2 dnses
            source.addDns(NetworkUtils.numericToInetAddress(DNS1));
            source.addDns(NetworkUtils.numericToInetAddress(DNS2));
            // set 2 gateways
            source.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY1)));
            source.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY2)));

            LinkProperties target = new LinkProperties();

            // All fields are same
            target.setInterfaceName(NAME);
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));
            target.addDns(NetworkUtils.numericToInetAddress(DNS1));
            target.addDns(NetworkUtils.numericToInetAddress(DNS2));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY1)));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY2)));

            assertTrue(source.equals(target));
            assertTrue(source.hashCode() == target.hashCode());

            target.clear();
            // change Interface Name
            target.setInterfaceName("qmi1");
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));
            target.addDns(NetworkUtils.numericToInetAddress(DNS1));
            target.addDns(NetworkUtils.numericToInetAddress(DNS2));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY1)));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY2)));
            assertFalse(source.equals(target));

            target.clear();
            target.setInterfaceName(NAME);
            // change link addresses
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress("75.208.6.2"), 32));
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));
            target.addDns(NetworkUtils.numericToInetAddress(DNS1));
            target.addDns(NetworkUtils.numericToInetAddress(DNS2));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY1)));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY2)));
            assertFalse(source.equals(target));

            target.clear();
            target.setInterfaceName(NAME);
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));
            // change dnses
            target.addDns(NetworkUtils.numericToInetAddress("75.208.7.2"));
            target.addDns(NetworkUtils.numericToInetAddress(DNS2));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY1)));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY2)));
            assertFalse(source.equals(target));

            target.clear();
            target.setInterfaceName(NAME);
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));
            target.addDns(NetworkUtils.numericToInetAddress(DNS1));
            target.addDns(NetworkUtils.numericToInetAddress(DNS2));
            // change gateway
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress("75.208.8.2")));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY2)));
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
            source.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            source.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));
            // set 2 dnses
            source.addDns(NetworkUtils.numericToInetAddress(DNS1));
            source.addDns(NetworkUtils.numericToInetAddress(DNS2));
            // set 2 gateways
            source.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY1)));
            source.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY2)));

            LinkProperties target = new LinkProperties();
            // Exchange order
            target.setInterfaceName(NAME);
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            target.addDns(NetworkUtils.numericToInetAddress(DNS2));
            target.addDns(NetworkUtils.numericToInetAddress(DNS1));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY2)));
            target.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(GATEWAY1)));

            assertTrue(source.equals(target));
            assertTrue(source.hashCode() == target.hashCode());
        } catch (Exception e) {
            fail();
        }
    }

    @SmallTest
    public void testEqualsDuplicated() {
        try {
            LinkProperties source = new LinkProperties();
            // set 3 link addresses, eg, [A, A, B]
            source.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            source.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            source.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));

            LinkProperties target = new LinkProperties();
            // set 3 link addresses, eg, [A, B, B]
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV4), 32));
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));
            target.addLinkAddress(new LinkAddress(
                    NetworkUtils.numericToInetAddress(ADDRV6), 128));

            assertTrue(source.equals(target));
            assertTrue(source.hashCode() == target.hashCode());
        } catch (Exception e) {
            fail();
        }
    }

}
