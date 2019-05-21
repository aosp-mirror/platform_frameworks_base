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

import static android.net.InetAddresses.parseNumericAddress;
import static android.net.shared.IpConfigurationParcelableUtil.fromStableParcelable;
import static android.net.shared.IpConfigurationParcelableUtil.toStableParcelable;

import static com.android.internal.util.ParcelableTestUtil.assertFieldCountEquals;

import static org.junit.Assert.assertEquals;

import android.net.DhcpResults;
import android.net.LinkAddress;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;

/**
 * Tests for {@link IpConfigurationParcelableUtil}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpConfigurationParcelableUtilTest {
    private DhcpResults mDhcpResults;

    @Before
    public void setUp() {
        mDhcpResults = new DhcpResults();
        mDhcpResults.ipAddress = new LinkAddress(parseNumericAddress("2001:db8::42"), 64);
        mDhcpResults.gateway = parseNumericAddress("192.168.42.42");
        mDhcpResults.dnsServers.add(parseNumericAddress("2001:db8::43"));
        mDhcpResults.dnsServers.add(parseNumericAddress("192.168.43.43"));
        mDhcpResults.domains = "example.com";
        mDhcpResults.serverAddress = (Inet4Address) parseNumericAddress("192.168.44.44");
        mDhcpResults.vendorInfo = "TEST_VENDOR_INFO";
        mDhcpResults.leaseDuration = 3600;
        mDhcpResults.serverHostName = "dhcp.example.com";
        mDhcpResults.mtu = 1450;
        // Any added DhcpResults field must be included in equals() to be tested properly
        assertFieldCountEquals(9, DhcpResults.class);
    }

    @Test
    public void testParcelUnparcelDhcpResults() {
        doDhcpResultsParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelDhcpResults_NullIpAddress() {
        mDhcpResults.ipAddress = null;
        doDhcpResultsParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelDhcpResults_NullGateway() {
        mDhcpResults.gateway = null;
        doDhcpResultsParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelDhcpResults_NullDomains() {
        mDhcpResults.domains = null;
        doDhcpResultsParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelDhcpResults_EmptyDomains() {
        mDhcpResults.domains = "";
        doDhcpResultsParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelDhcpResults_NullServerAddress() {
        mDhcpResults.serverAddress = null;
        doDhcpResultsParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelDhcpResults_NullVendorInfo() {
        mDhcpResults.vendorInfo = null;
        doDhcpResultsParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelDhcpResults_NullServerHostName() {
        mDhcpResults.serverHostName = null;
        doDhcpResultsParcelUnparcelTest();
    }

    private void doDhcpResultsParcelUnparcelTest() {
        final DhcpResults unparceled = fromStableParcelable(toStableParcelable(mDhcpResults));
        assertEquals(mDhcpResults, unparceled);
    }
}
