/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.ip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.ip.IpManager.Callback;
import android.net.ip.IpManager.InitialConfiguration;
import android.net.ip.IpManager.ProvisioningConfiguration;
import android.os.INetworkManagementService;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

/**
 * Tests for IpManager.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpManagerTest {
    private static final int DEFAULT_AVOIDBADWIFI_CONFIG_VALUE = 1;

    private static final String VALID = "VALID";
    private static final String INVALID = "INVALID";

    @Mock private Context mContext;
    @Mock private INetworkManagementService mNMService;
    @Mock private Resources mResources;
    @Mock private Callback mCb;
    @Mock private AlarmManager mAlarm;
    private MockContentResolver mContentResolver;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(eq(Context.ALARM_SERVICE))).thenReturn(mAlarm);
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getInteger(R.integer.config_networkAvoidBadWifi))
                .thenReturn(DEFAULT_AVOIDBADWIFI_CONFIG_VALUE);

        mContentResolver = new MockContentResolver();
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
    }

    private IpManager makeIpManager(String ifname) throws Exception {
        final IpManager ipm = new IpManager(mContext, ifname, mCb, mNMService);
        verify(mNMService, timeout(100).times(1)).disableIpv6(ifname);
        verify(mNMService, timeout(100).times(1)).clearInterfaceAddresses(ifname);
        return ipm;
    }

    @Test
    public void testNullCallbackDoesNotThrow() throws Exception {
        final IpManager ipm = new IpManager(mContext, "lo", null, mNMService);
    }

    @Test
    public void testInvalidInterfaceDoesNotThrow() throws Exception {
        final IpManager ipm = new IpManager(mContext, "test_wlan0", mCb, mNMService);
    }

    @Test
    public void testDefaultProvisioningConfiguration() throws Exception {
        final String iface = "test_wlan0";
        final IpManager ipm = makeIpManager(iface);

        ProvisioningConfiguration config = new ProvisioningConfiguration.Builder()
                .withoutIPv4()
                // TODO: mock IpReachabilityMonitor's dependencies (NetworkInterface, PowerManager)
                // and enable it in this test
                .withoutIpReachabilityMonitor()
                .build();

        ipm.startProvisioning(config);
        verify(mCb, times(1)).setNeighborDiscoveryOffload(true);
        verify(mCb, timeout(100).times(1)).setFallbackMulticastFilter(false);
        verify(mCb, never()).onProvisioningFailure(any());

        ipm.stop();
        verify(mNMService, timeout(100).times(1)).disableIpv6(iface);
        verify(mNMService, timeout(100).times(1)).clearInterfaceAddresses(iface);
    }

    @Test
    public void testInitialConfigurations() throws Exception {
        InitialConfigurationTestCase[] testcases = {
            validConf("valid IPv4 configuration",
                    links("192.0.2.12/24"), prefixes("192.0.2.0/24"), dns("192.0.2.2")),
            validConf("another valid IPv4 configuration",
                    links("192.0.2.12/24"), prefixes("192.0.2.0/24"), dns()),
            validConf("valid IPv6 configurations",
                    links("2001:db8:dead:beef:f00::a0/64", "fe80::1/64"),
                    prefixes("2001:db8:dead:beef::/64", "fe80::/64"),
                    dns("2001:db8:dead:beef:f00::02")),
            validConf("valid IPv6 configurations",
                    links("fe80::1/64"), prefixes("fe80::/64"), dns()),
            validConf("valid IPv6/v4 configuration",
                    links("2001:db8:dead:beef:f00::a0/48", "192.0.2.12/24"),
                    prefixes("2001:db8:dead:beef::/64", "192.0.2.0/24"),
                    dns("192.0.2.2", "2001:db8:dead:beef:f00::02")),
            validConf("valid IPv6 configuration without any GUA.",
                    links("fd00:1234:5678::1/48"),
                    prefixes("fd00:1234:5678::/48"),
                    dns("fd00:1234:5678::1000")),

            invalidConf("v4 addr and dns not in any prefix",
                    links("192.0.2.12/24"), prefixes("198.51.100.0/24"), dns("192.0.2.2")),
            invalidConf("v4 addr not in any prefix",
                    links("198.51.2.12/24"), prefixes("198.51.100.0/24"), dns("192.0.2.2")),
            invalidConf("v4 dns addr not in any prefix",
                    links("192.0.2.12/24"), prefixes("192.0.2.0/24"), dns("198.51.100.2")),
            invalidConf("v6 addr not in any prefix",
                    links("2001:db8:dead:beef:f00::a0/64", "fe80::1/64"),
                    prefixes("2001:db8:dead:beef::/64"),
                    dns("2001:db8:dead:beef:f00::02")),
            invalidConf("v6 dns addr not in any prefix",
                    links("fe80::1/64"), prefixes("fe80::/64"), dns("2001:db8:dead:beef:f00::02")),
            invalidConf("default ipv6 route and no GUA",
                    links("fd01:1111:2222:3333::a0/128"), prefixes("::/0"), dns()),
            invalidConf("invalid v6 prefix length",
                    links("2001:db8:dead:beef:f00::a0/128"), prefixes("2001:db8:dead:beef::/32"),
                    dns()),
            invalidConf("another invalid v6 prefix length",
                    links("2001:db8:dead:beef:f00::a0/128"), prefixes("2001:db8:dead:beef::/72"),
                    dns())
        };

        for (InitialConfigurationTestCase testcase : testcases) {
            if (testcase.config.isValid() != testcase.isValid) {
                fail(testcase.errorMessage());
            }
        }
    }

    static class InitialConfigurationTestCase {
        String descr;
        boolean isValid;
        InitialConfiguration config;
        public String errorMessage() {
            return String.format("%s: expected configuration %s to be %s, but was %s",
                    descr, config, validString(isValid), validString(!isValid));
        }
    }

    static String validString(boolean isValid) {
        return isValid ? VALID : INVALID;
    }

    static InitialConfigurationTestCase validConf(String descr, Set<LinkAddress> links,
            Set<IpPrefix> prefixes, Set<InetAddress> dns) {
        return confTestCase(descr, true, conf(links, prefixes, dns));
    }

    static InitialConfigurationTestCase invalidConf(String descr, Set<LinkAddress> links,
            Set<IpPrefix> prefixes, Set<InetAddress> dns) {
        return confTestCase(descr, false, conf(links, prefixes, dns));
    }

    static InitialConfigurationTestCase confTestCase(
            String descr, boolean isValid, InitialConfiguration config) {
        InitialConfigurationTestCase testcase = new InitialConfigurationTestCase();
        testcase.descr = descr;
        testcase.isValid = isValid;
        testcase.config = config;
        return testcase;
    }

    static InitialConfiguration conf(
            Set<LinkAddress> links, Set<IpPrefix> prefixes, Set<InetAddress> dns) {
        InitialConfiguration conf = new InitialConfiguration();
        conf.ipAddresses.addAll(links);
        conf.directlyConnectedRoutes.addAll(prefixes);
        conf.dnsServers.addAll(dns);
        return conf;
    }

    static Set<IpPrefix> prefixes(String... prefixes) {
        return mapIntoSet(prefixes, IpPrefix::new);
    }

    static Set<LinkAddress> links(String... addresses) {
        return mapIntoSet(addresses, LinkAddress::new);
    }

    static Set<InetAddress> ips(String... addresses) {
        return mapIntoSet(addresses, InetAddress::getByName);
    }

    static Set<InetAddress> dns(String... addresses) {
        return ips(addresses);
    }

    static <A, B> Set<B> mapIntoSet(A[] in, Fn<A, B> fn) {
        Set<B> out = new HashSet<>(in.length);
        for (A item : in) {
            try {
                out.add(fn.call(item));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return out;
    }

    interface Fn<A,B> {
        B call(A a) throws Exception;
    }
}
