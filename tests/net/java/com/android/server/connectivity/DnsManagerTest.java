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
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;
import static android.net.NetworkCapabilities.MAX_TRANSPORT;
import static android.net.NetworkCapabilities.MIN_TRANSPORT;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.provider.Settings.Global.PRIVATE_DNS_DEFAULT_MODE;
import static android.provider.Settings.Global.PRIVATE_DNS_MODE;
import static android.provider.Settings.Global.PRIVATE_DNS_SPECIFIER;

import static com.android.testutils.MiscAssertsKt.assertContainsExactly;
import static com.android.testutils.MiscAssertsKt.assertContainsStringsExactly;
import static com.android.testutils.MiscAssertsKt.assertFieldCountEquals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;
import android.net.IDnsResolver;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ResolverOptionsParcel;
import android.net.ResolverParamsParcel;
import android.net.RouteInfo;
import android.net.shared.PrivateDnsConfig;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.MessageUtils;
import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.util.Arrays;

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
    static final int TEST_DEFAULT_SAMPLE_VALIDITY_SECONDS = 1800;
    static final int TEST_DEFAULT_SUCCESS_THRESHOLD_PERCENT = 25;
    static final int TEST_DEFAULT_MIN_SAMPLES = 8;
    static final int TEST_DEFAULT_MAX_SAMPLES = 64;
    static final int[] TEST_TRANSPORT_TYPES = {TRANSPORT_WIFI, TRANSPORT_VPN};

    DnsManager mDnsManager;
    MockContentResolver mContentResolver;

    @Mock Context mCtx;
    @Mock IDnsResolver mMockDnsResolver;
    @Mock MockableSystemProperties mSystemProperties;

    private void assertResolverOptionsEquals(
            @NonNull ResolverOptionsParcel actual,
            @NonNull ResolverOptionsParcel expected) {
        assertEquals(actual.hosts, expected.hosts);
        assertEquals(actual.tcMode, expected.tcMode);
        assertFieldCountEquals(2, ResolverOptionsParcel.class);
    }

    private void assertResolverParamsEquals(@NonNull ResolverParamsParcel actual,
            @NonNull ResolverParamsParcel expected) {
        assertEquals(actual.netId, expected.netId);
        assertEquals(actual.sampleValiditySeconds, expected.sampleValiditySeconds);
        assertEquals(actual.successThreshold, expected.successThreshold);
        assertEquals(actual.minSamples, expected.minSamples);
        assertEquals(actual.maxSamples, expected.maxSamples);
        assertEquals(actual.baseTimeoutMsec, expected.baseTimeoutMsec);
        assertEquals(actual.retryCount, expected.retryCount);
        assertContainsStringsExactly(actual.servers, expected.servers);
        assertContainsStringsExactly(actual.domains, expected.domains);
        assertEquals(actual.tlsName, expected.tlsName);
        assertContainsStringsExactly(actual.tlsServers, expected.tlsServers);
        assertContainsStringsExactly(actual.tlsFingerprints, expected.tlsFingerprints);
        assertEquals(actual.caCertificate, expected.caCertificate);
        assertEquals(actual.tlsConnectTimeoutMs, expected.tlsConnectTimeoutMs);
        assertResolverOptionsEquals(actual.resolverOptions, expected.resolverOptions);
        assertContainsExactly(actual.transportTypes, expected.transportTypes);
        assertFieldCountEquals(16, ResolverParamsParcel.class);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContentResolver = new MockContentResolver();
        mContentResolver.addProvider(Settings.AUTHORITY,
                new FakeSettingsProvider());
        when(mCtx.getContentResolver()).thenReturn(mContentResolver);
        mDnsManager = new DnsManager(mCtx, mMockDnsResolver, mSystemProperties);

        // Clear the private DNS settings
        Settings.Global.putString(mContentResolver, PRIVATE_DNS_DEFAULT_MODE, "");
        Settings.Global.putString(mContentResolver, PRIVATE_DNS_MODE, "");
        Settings.Global.putString(mContentResolver, PRIVATE_DNS_SPECIFIER, "");
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
        mDnsManager.updateTransportsForNetwork(TEST_NETID, TEST_TRANSPORT_TYPES);
        mDnsManager.noteDnsServersForNetwork(TEST_NETID, lp);
        mDnsManager.setDefaultDnsSystemProperties(lp.getDnsServers());
        mDnsManager.flushVmDnsCache();
        mDnsManager.updateTransportsForNetwork(TEST_NETID_ALTERNATE, TEST_TRANSPORT_TYPES);
        mDnsManager.noteDnsServersForNetwork(TEST_NETID_ALTERNATE, lp);
        mDnsManager.flushVmDnsCache();
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
                PRIVATE_DNS_MODE, PRIVATE_DNS_MODE_PROVIDER_HOSTNAME);
        Settings.Global.putString(mContentResolver, PRIVATE_DNS_SPECIFIER, "strictmode.com");
        mDnsManager.updatePrivateDns(new Network(TEST_NETID),
                new PrivateDnsConfig("strictmode.com", new InetAddress[] {
                    InetAddress.parseNumericAddress("6.6.6.6"),
                    InetAddress.parseNumericAddress("2001:db8:66:66::1")
                    }));
        mDnsManager.updateTransportsForNetwork(TEST_NETID, TEST_TRANSPORT_TYPES);
        mDnsManager.noteDnsServersForNetwork(TEST_NETID, lp);
        mDnsManager.setDefaultDnsSystemProperties(lp.getDnsServers());
        mDnsManager.flushVmDnsCache();
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
        mDnsManager.updateTransportsForNetwork(TEST_NETID, TEST_TRANSPORT_TYPES);
        mDnsManager.noteDnsServersForNetwork(TEST_NETID, lp);
        mDnsManager.setDefaultDnsSystemProperties(lp.getDnsServers());
        mDnsManager.flushVmDnsCache();
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("3.3.3.3"), "", true));
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, lp);
        assertFalse(lp.isPrivateDnsActive());
        assertNull(lp.getPrivateDnsServerName());

        // Validation event has untracked netId
        mDnsManager.updatePrivateDns(new Network(TEST_NETID),
                mDnsManager.getPrivateDnsConfig());
        mDnsManager.updateTransportsForNetwork(TEST_NETID, TEST_TRANSPORT_TYPES);
        mDnsManager.noteDnsServersForNetwork(TEST_NETID, lp);
        mDnsManager.setDefaultDnsSystemProperties(lp.getDnsServers());
        mDnsManager.flushVmDnsCache();
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
        Settings.Global.putString(mContentResolver, PRIVATE_DNS_MODE, PRIVATE_DNS_MODE_OFF);
        mDnsManager.updatePrivateDns(new Network(TEST_NETID),
                mDnsManager.getPrivateDnsConfig());
        mDnsManager.updateTransportsForNetwork(TEST_NETID, TEST_TRANSPORT_TYPES);
        mDnsManager.noteDnsServersForNetwork(TEST_NETID, lp);
        mDnsManager.setDefaultDnsSystemProperties(lp.getDnsServers());
        mDnsManager.flushVmDnsCache();
        mDnsManager.updatePrivateDnsValidation(
                new DnsManager.PrivateDnsValidationUpdate(TEST_NETID,
                InetAddress.parseNumericAddress("3.3.3.3"), "", true));
        mDnsManager.updatePrivateDnsStatus(TEST_NETID, lp);
        assertFalse(lp.isPrivateDnsActive());
        assertNull(lp.getPrivateDnsServerName());
    }

    @Test
    public void testOverrideDefaultMode() throws Exception {
        // Hard-coded default is opportunistic mode.
        final PrivateDnsConfig cfgAuto = DnsManager.getPrivateDnsConfig(mContentResolver);
        assertTrue(cfgAuto.useTls);
        assertEquals("", cfgAuto.hostname);
        assertEquals(new InetAddress[0], cfgAuto.ips);

        // Pretend a gservices push sets the default to "off".
        Settings.Global.putString(mContentResolver, PRIVATE_DNS_DEFAULT_MODE, "off");
        final PrivateDnsConfig cfgOff = DnsManager.getPrivateDnsConfig(mContentResolver);
        assertFalse(cfgOff.useTls);
        assertEquals("", cfgOff.hostname);
        assertEquals(new InetAddress[0], cfgOff.ips);

        // Strict mode still works.
        Settings.Global.putString(
                mContentResolver, PRIVATE_DNS_MODE, PRIVATE_DNS_MODE_PROVIDER_HOSTNAME);
        Settings.Global.putString(mContentResolver, PRIVATE_DNS_SPECIFIER, "strictmode.com");
        final PrivateDnsConfig cfgStrict = DnsManager.getPrivateDnsConfig(mContentResolver);
        assertTrue(cfgStrict.useTls);
        assertEquals("strictmode.com", cfgStrict.hostname);
        assertEquals(new InetAddress[0], cfgStrict.ips);
    }

    @Test
    public void testSendDnsConfiguration() throws Exception {
        reset(mMockDnsResolver);
        mDnsManager.updatePrivateDns(new Network(TEST_NETID),
                mDnsManager.getPrivateDnsConfig());
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(TEST_IFACENAME);
        lp.addDnsServer(InetAddress.getByName("3.3.3.3"));
        lp.addDnsServer(InetAddress.getByName("4.4.4.4"));
        mDnsManager.updateTransportsForNetwork(TEST_NETID, TEST_TRANSPORT_TYPES);
        mDnsManager.noteDnsServersForNetwork(TEST_NETID, lp);
        mDnsManager.setDefaultDnsSystemProperties(lp.getDnsServers());
        mDnsManager.flushVmDnsCache();

        final ArgumentCaptor<ResolverParamsParcel> resolverParamsParcelCaptor =
                ArgumentCaptor.forClass(ResolverParamsParcel.class);
        verify(mMockDnsResolver, times(1)).setResolverConfiguration(
                resolverParamsParcelCaptor.capture());
        final ResolverParamsParcel actualParams = resolverParamsParcelCaptor.getValue();
        final ResolverParamsParcel expectedParams = new ResolverParamsParcel();
        expectedParams.netId = TEST_NETID;
        expectedParams.sampleValiditySeconds = TEST_DEFAULT_SAMPLE_VALIDITY_SECONDS;
        expectedParams.successThreshold = TEST_DEFAULT_SUCCESS_THRESHOLD_PERCENT;
        expectedParams.minSamples = TEST_DEFAULT_MIN_SAMPLES;
        expectedParams.maxSamples = TEST_DEFAULT_MAX_SAMPLES;
        expectedParams.servers = new String[]{"3.3.3.3", "4.4.4.4"};
        expectedParams.domains = new String[]{};
        expectedParams.tlsName = "";
        expectedParams.tlsServers = new String[]{"3.3.3.3", "4.4.4.4"};
        expectedParams.transportTypes = TEST_TRANSPORT_TYPES;
        expectedParams.resolverOptions = new ResolverOptionsParcel();
        assertResolverParamsEquals(actualParams, expectedParams);
    }

    @Test
    public void testTransportTypesEqual() throws Exception {
        SparseArray<String> ncTransTypes = MessageUtils.findMessageNames(
                new Class[] { NetworkCapabilities.class }, new String[]{ "TRANSPORT_" });
        SparseArray<String> dnsTransTypes = MessageUtils.findMessageNames(
                new Class[] { IDnsResolver.class }, new String[]{ "TRANSPORT_" });
        assertEquals(0, MIN_TRANSPORT);
        assertEquals(MAX_TRANSPORT + 1, ncTransTypes.size());
        // TRANSPORT_UNKNOWN in IDnsResolver is defined to -1 and only for resolver.
        assertEquals("TRANSPORT_UNKNOWN", dnsTransTypes.get(-1));
        assertEquals(ncTransTypes.size(), dnsTransTypes.size() - 1);
        for (int i = MIN_TRANSPORT; i < MAX_TRANSPORT; i++) {
            String name = ncTransTypes.get(i, null);
            assertNotNull("Could not find NetworkCapabilies.TRANSPORT_* constant equal to "
                    + i, name);
            assertEquals(name, dnsTransTypes.get(i));
        }
    }
}
