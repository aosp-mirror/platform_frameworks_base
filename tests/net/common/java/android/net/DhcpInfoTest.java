/*
 * Copyright (C) 2009 The Android Open Source Project
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

import static android.net.shared.Inet4AddressUtils.inet4AddressToIntHTL;

import static com.android.testutils.MiscAssertsKt.assertFieldCountEquals;
import static com.android.testutils.ParcelUtilsKt.parcelingRoundTrip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.annotation.Nullable;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.InetAddress;

@RunWith(AndroidJUnit4.class)
public class DhcpInfoTest {
    private static final String STR_ADDR1 = "255.255.255.255";
    private static final String STR_ADDR2 = "127.0.0.1";
    private static final String STR_ADDR3 = "192.168.1.1";
    private static final String STR_ADDR4 = "192.168.1.0";
    private static final int LEASE_TIME = 9999;

    private int ipToInteger(String ipString) throws Exception {
        return inet4AddressToIntHTL((Inet4Address) InetAddress.getByName(ipString));
    }

    private DhcpInfo createDhcpInfoObject() throws Exception {
        final DhcpInfo dhcpInfo = new DhcpInfo();
        dhcpInfo.ipAddress = ipToInteger(STR_ADDR1);
        dhcpInfo.gateway = ipToInteger(STR_ADDR2);
        dhcpInfo.netmask = ipToInteger(STR_ADDR3);
        dhcpInfo.dns1 = ipToInteger(STR_ADDR4);
        dhcpInfo.dns2 = ipToInteger(STR_ADDR4);
        dhcpInfo.serverAddress = ipToInteger(STR_ADDR2);
        dhcpInfo.leaseDuration = LEASE_TIME;
        return dhcpInfo;
    }

    @Test
    public void testConstructor() {
        new DhcpInfo();
    }

    @Test
    public void testToString() throws Exception {
        final String expectedDefault = "ipaddr 0.0.0.0 gateway 0.0.0.0 netmask 0.0.0.0 "
                + "dns1 0.0.0.0 dns2 0.0.0.0 DHCP server 0.0.0.0 lease 0 seconds";

        DhcpInfo dhcpInfo = new DhcpInfo();

        // Test default string.
        assertEquals(expectedDefault, dhcpInfo.toString());

        dhcpInfo = createDhcpInfoObject();

        final String expected = "ipaddr " + STR_ADDR1 + " gateway " + STR_ADDR2 + " netmask "
                + STR_ADDR3 + " dns1 " + STR_ADDR4 + " dns2 " + STR_ADDR4 + " DHCP server "
                + STR_ADDR2 + " lease " + LEASE_TIME + " seconds";
        // Test with new values
        assertEquals(expected, dhcpInfo.toString());
    }

    private boolean dhcpInfoEquals(@Nullable DhcpInfo left, @Nullable DhcpInfo right) {
        if (left == null && right == null) return true;

        if (left == null || right == null) return false;

        return left.ipAddress == right.ipAddress
                && left.gateway == right.gateway
                && left.netmask == right.netmask
                && left.dns1 == right.dns1
                && left.dns2 == right.dns2
                && left.serverAddress == right.serverAddress
                && left.leaseDuration == right.leaseDuration;
    }

    @Test
    public void testParcelDhcpInfo() throws Exception {
        // Cannot use assertParcelSane() here because this requires .equals() to work as
        // defined, but DhcpInfo has a different legacy behavior that we cannot change.
        final DhcpInfo dhcpInfo = createDhcpInfoObject();
        assertFieldCountEquals(7, DhcpInfo.class);

        final DhcpInfo dhcpInfoRoundTrip = parcelingRoundTrip(dhcpInfo);
        assertTrue(dhcpInfoEquals(null, null));
        assertFalse(dhcpInfoEquals(null, dhcpInfoRoundTrip));
        assertFalse(dhcpInfoEquals(dhcpInfo, null));
        assertTrue(dhcpInfoEquals(dhcpInfo, dhcpInfoRoundTrip));
    }
}
