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
import static android.net.shared.ParcelableTestUtil.assertFieldCountEquals;

import static org.junit.Assert.assertEquals;

import android.net.DhcpResults;
import android.net.LinkAddress;
import android.net.StaticIpConfiguration;
import android.net.apf.ApfCapabilities;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

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
    private StaticIpConfiguration mStaticIpConfiguration;
    private DhcpResults mDhcpResults;

    @Before
    public void setUp() {
        mStaticIpConfiguration = new StaticIpConfiguration();
        mStaticIpConfiguration.ipAddress = new LinkAddress(parseNumericAddress("2001:db8::42"), 64);
        mStaticIpConfiguration.gateway = parseNumericAddress("192.168.42.42");
        mStaticIpConfiguration.dnsServers.add(parseNumericAddress("2001:db8::43"));
        mStaticIpConfiguration.dnsServers.add(parseNumericAddress("192.168.43.43"));
        mStaticIpConfiguration.domains = "example.com";
        // Any added StaticIpConfiguration field must be included in equals() to be tested properly
        assertFieldCountEquals(4, StaticIpConfiguration.class);

        mDhcpResults = new DhcpResults(mStaticIpConfiguration);
        mDhcpResults.serverAddress = (Inet4Address) parseNumericAddress("192.168.44.44");
        mDhcpResults.vendorInfo = "TEST_VENDOR_INFO";
        mDhcpResults.leaseDuration = 3600;
        mDhcpResults.mtu = 1450;
        // Any added DhcpResults field must be included in equals() to be tested properly
        assertFieldCountEquals(4, DhcpResults.class);
    }

    @Test
    public void testParcelUnparcelStaticConfiguration() {
        doStaticConfigurationParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelStaticConfiguration_NullIpAddress() {
        mStaticIpConfiguration.ipAddress = null;
        doStaticConfigurationParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelStaticConfiguration_NullGateway() {
        mStaticIpConfiguration.gateway = null;
        doStaticConfigurationParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelStaticConfiguration_NullDomains() {
        mStaticIpConfiguration.domains = null;
        doStaticConfigurationParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcelStaticConfiguration_EmptyDomains() {
        mStaticIpConfiguration.domains = "";
        doStaticConfigurationParcelUnparcelTest();
    }

    private void doStaticConfigurationParcelUnparcelTest() {
        final StaticIpConfiguration unparceled =
                fromStableParcelable(toStableParcelable(mStaticIpConfiguration));
        assertEquals(mStaticIpConfiguration, unparceled);
    }

    @Test
    public void testParcelUnparcelDhcpResults() {
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

    private void doDhcpResultsParcelUnparcelTest() {
        final DhcpResults unparceled = fromStableParcelable(toStableParcelable(mDhcpResults));
        assertEquals(mDhcpResults, unparceled);
    }

    @Test
    public void testParcelUnparcelApfCapabilities() {
        final ApfCapabilities caps = new ApfCapabilities(123, 456, 789);
        assertEquals(caps, fromStableParcelable(toStableParcelable(caps)));
    }
}
