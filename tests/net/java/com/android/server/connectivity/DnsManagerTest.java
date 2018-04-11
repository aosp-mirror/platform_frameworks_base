/*
 * Copyright (C) 2018, The Android Open Source Project
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

package com.android.server.connectivity;

import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OFF;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OPPORTUNISTIC;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.INetworkManagementService;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.connectivity.MockableSystemProperties;

import java.net.InetAddress;
import java.util.Arrays;

import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link DnsManager}.
 *
 * Build, install and run with:
 *  runtest frameworks-net -c com.android.server.connectivity.DnsManagerTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class DnsManagerTest {
    static final String TEST_IFACENAME = "test_wlan0";
    static final int TEST_NETID = 100;
    static final int TEST_NETID_ALTERNATE = 101;
    static final int TEST_NETID_UNTRACKED = 102;
    final boolean IS_DEFAULT = true;
    final boolean NOT_DEFAULT = false;

    DnsManager mDnsManager;
    MockContentResolver mContentResolver;

    @Mock Context mCtx;
    @Mock INetworkManagementService mNMService;
    @Mock MockableSystemProperties mSystemProperties;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContentResolver = new MockContentResolver();
        mContentResolver.addProvider(Settings.AUTHORITY,
                new FakeSettingsProvider());
        when(mCtx.getContentResolver()).thenReturn(mContentResolver);
        mDnsManager = new DnsManager(mCtx, mNMService, mSystemProperties);

        // Clear the private DNS settings
        Settings.Global.putString(mContentResolver,
                Settings.Global.PRIVATE_DNS_MODE, "");
        Settings.Global.putString(mContentResolver,
                Settings.Global.PRIVATE_DNS_SPECIFIER, "");
    }

    @Test
    public void testTrackedValidationUpdates() throws Exception {
        mDnsManager.updatePrivateDns(new Network(TEST_NETID),
                mDnsManager.getPrivateDnsConfig());
        mDnsManager.updatePrivateDns(new Network(TEST_NETID_ALTERNATE),
                mDnsManager.getPrivateDnsConfig());
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(TEST_IFACENAME);
        lp.addDnsServer(InetAddress.getByName("3.3.3.3"));
        lp.addDnsServer(InetAddress.getByName("4.4.4.4"));

        // Send a validation event that is tracked on the alternate netId
        mDnsManager.setDnsConfigurationForNetwork(TEST_NETID, lp, IS_DEFAULT);
        mDnsManager.setDnsConfigurationForNetwork(TEST_NETID_ALTERNATE, lp, NOT_DEFAULT);
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID_ALTERNATE,
                InetAddress.parseNumericAddress("4.4.4.4"), "", true));
        LinkProperties fixedLp = new LinkProperties(lp);
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, fixedLp);
        assertFalse(fixedLp.isPrivateDnsActive());
        assertNull(fixedLp.getPrivateDnsServerName());
        fixedLp = new LinkProperties(lp);
        mDnsManager.updatePrivateDnsStatus(TEST_NETID_ALTERNATE, fixedLp);
        assertTrue(fixedLp.isPrivateDnsActive());
        assertNull(fixedLp.getPrivateDnsServerName());
        assertEquals(Arrays.asList(InetAddress.getByName("4.4.4.4")),
                fixedLp.getValidatedPrivateDnsServers());

        // Set up addresses for strict mode and switch to it.
        lp.addLinkAddress(new LinkAddress("192.0.2.4/24"));
        lp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("192.0.2.4"),
                TEST_IFACENAME));
        lp.addLinkAddress(new LinkAddress("2001:db8:1::1/64"));
        lp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("2001:db8:1::1"),
                TEST_IFACENAME));

        Settings.Global.putString(mContentResolver,
                Settings.Global.PRIVATE_DNS_MODE, PRIVATE_DNS_MODE_PROVIDER_HOSTNAME);
        Settings.Global.putString(mContentResolver,
                Settings.Global.PRIVATE_DNS_SPECIFIER, "strictmode.com");
        mDnsManager.updatePrivateDns(new Network(TEST_NETID),
                new DnsManager.PrivateDnsConfig("strictmode.com", new InetAddress[] {
                    InetAddress.parseNumericAddress("6.6.6.6"),
                    InetAddress.parseNumericAddress("2001:db8:66:66::1")
                    }));
        mDnsManager.setDnsConfigurationForNetwork(TEST_NETID, lp, IS_DEFAULT);
        fixedLp = new LinkProperties(lp);
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, fixedLp);
        assertTrue(fixedLp.isPrivateDnsActive());
        assertEquals("strictmode.com", fixedLp.getPrivateDnsServerName());
        // No validation events yet.
        assertEquals(Arrays.asList(new InetAddress[0]), fixedLp.getValidatedPrivateDnsServers());
        // Validate one.
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("6.6.6.6"), "strictmode.com", true));
        fixedLp = new LinkProperties(lp);
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, fixedLp);
        assertEquals(Arrays.asList(InetAddress.parseNumericAddress("6.6.6.6")),
                fixedLp.getValidatedPrivateDnsServers());
        // Validate the 2nd one.
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("2001:db8:66:66::1"), "strictmode.com", true));
        fixedLp = new LinkProperties(lp);
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, fixedLp);
        assertEquals(Arrays.asList(
                        InetAddress.parseNumericAddress("2001:db8:66:66::1"),
                        InetAddress.parseNumericAddress("6.6.6.6")),
                fixedLp.getValidatedPrivateDnsServers());
    }

    @Test
    public void testIgnoreUntrackedValidationUpdates() throws Exception {
        // The PrivateDnsConfig map is empty, so no validation events will
        // be tracked.
        LinkProperties lp = new LinkProperties();
        lp.addDnsServer(InetAddress.getByName("3.3.3.3"));
        mDnsManager.setDnsConfigurationForNetwork(TEST_NETID, lp, IS_DEFAULT);
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("3.3.3.3"), "", true));
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, lp);
        assertFalse(lp.isPrivateDnsActive());
        assertNull(lp.getPrivateDnsServerName());

        // Validation event has untracked netId
        mDnsManager.updatePrivateDns(new Network(TEST_NETID),
                mDnsManager.getPrivateDnsConfig());
        mDnsManager.setDnsConfigurationForNetwork(TEST_NETID, lp, IS_DEFAULT);
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID_UNTRACKED,
                InetAddress.parseNumericAddress("3.3.3.3"), "", true));
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, lp);
        assertFalse(lp.isPrivateDnsActive());
        assertNull(lp.getPrivateDnsServerName());

        // Validation event has untracked ipAddress
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("4.4.4.4"), "", true));
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, lp);
        assertFalse(lp.isPrivateDnsActive());
        assertNull(lp.getPrivateDnsServerName());

        // Validation event has untracked hostname
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("3.3.3.3"), "hostname",
                true));
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, lp);
        assertFalse(lp.isPrivateDnsActive());
        assertNull(lp.getPrivateDnsServerName());

        // Validation event failed
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("3.3.3.3"), "", false));
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, lp);
        assertFalse(lp.isPrivateDnsActive());
        assertNull(lp.getPrivateDnsServerName());

        // Network removed
        mDnsManager.removeNetwork(new Network(TEST_NETID));
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("3.3.3.3"), "", true));
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, lp);
        assertFalse(lp.isPrivateDnsActive());
        assertNull(lp.getPrivateDnsServerName());

        // Turn private DNS mode off
        Settings.Global.putString(mContentResolver,
                Settings.Global.PRIVATE_DNS_MODE, PRIVATE_DNS_MODE_OFF);
        mDnsManager.updatePrivateDns(new Network(TEST_NETID),
                mDnsManager.getPrivateDnsConfig());
        mDnsManager.setDnsConfigurationForNetwork(TEST_NETID, lp, IS_DEFAULT);
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("3.3.3.3"), "", true));
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, lp);
        assertFalse(lp.isPrivateDnsActive());
        assertNull(lp.getPrivateDnsServerName());
    }
}
