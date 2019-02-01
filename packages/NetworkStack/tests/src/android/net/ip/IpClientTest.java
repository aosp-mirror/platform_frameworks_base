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

import static android.net.shared.LinkPropertiesParcelableUtil.fromStableParcelable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.RouteInfo;
import android.net.shared.InitialConfiguration;
import android.net.shared.ProvisioningConfiguration;
import android.net.util.InterfaceParams;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import com.android.internal.R;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.NetworkObserver;
import com.android.server.NetworkObserverRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for IpClient.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpClientTest {
    private static final int DEFAULT_AVOIDBADWIFI_CONFIG_VALUE = 1;

    private static final String VALID = "VALID";
    private static final String INVALID = "INVALID";
    private static final String TEST_IFNAME = "test_wlan0";
    private static final int TEST_IFINDEX = 1001;
    // See RFC 7042#section-2.1.2 for EUI-48 documentation values.
    private static final MacAddress TEST_MAC = MacAddress.fromString("00:00:5E:00:53:01");
    private static final int TEST_TIMEOUT_MS = 400;

    @Mock private Context mContext;
    @Mock private ConnectivityManager mCm;
    @Mock private NetworkObserverRegistry mObserverRegistry;
    @Mock private INetd mNetd;
    @Mock private Resources mResources;
    @Mock private IIpClientCallbacks mCb;
    @Mock private AlarmManager mAlarm;
    @Mock private IpClient.Dependencies mDependencies;
    private MockContentResolver mContentResolver;

    private NetworkObserver mObserver;
    private InterfaceParams mIfParams;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(eq(Context.ALARM_SERVICE))).thenReturn(mAlarm);
        when(mContext.getSystemService(eq(ConnectivityManager.class))).thenReturn(mCm);
        when(mContext.getSystemService(INetd.class)).thenReturn(mNetd);
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getInteger(R.integer.config_networkAvoidBadWifi))
                .thenReturn(DEFAULT_AVOIDBADWIFI_CONFIG_VALUE);

        mContentResolver = new MockContentResolver();
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        mIfParams = null;
    }

    private void setTestInterfaceParams(String ifname) {
        mIfParams = (ifname != null)
                ? new InterfaceParams(ifname, TEST_IFINDEX, TEST_MAC)
                : null;
        when(mDependencies.getInterfaceParams(anyString())).thenReturn(mIfParams);
    }

    private IpClient makeIpClient(String ifname) throws Exception {
        setTestInterfaceParams(ifname);
        final IpClient ipc = new IpClient(mContext, ifname, mCb, mObserverRegistry, mDependencies);
        verify(mNetd, timeout(TEST_TIMEOUT_MS).times(1)).interfaceSetEnableIPv6(ifname, false);
        verify(mNetd, timeout(TEST_TIMEOUT_MS).times(1)).interfaceClearAddrs(ifname);
        ArgumentCaptor<NetworkObserver> arg = ArgumentCaptor.forClass(NetworkObserver.class);
        verify(mObserverRegistry, times(1)).registerObserverForNonblockingCallback(arg.capture());
        mObserver = arg.getValue();
        reset(mObserverRegistry);
        reset(mNetd);
        // Verify IpClient doesn't call onLinkPropertiesChange() when it starts.
        verify(mCb, never()).onLinkPropertiesChange(any());
        reset(mCb);
        return ipc;
    }

    private static LinkProperties makeEmptyLinkProperties(String iface) {
        final LinkProperties empty = new LinkProperties();
        empty.setInterfaceName(iface);
        return empty;
    }

    @Test
    public void testNullInterfaceNameMostDefinitelyThrows() throws Exception {
        setTestInterfaceParams(null);
        try {
            final IpClient ipc = new IpClient(
                    mContext, null, mCb, mObserverRegistry, mDependencies);
            ipc.shutdown();
            fail();
        } catch (NullPointerException npe) {
            // Phew; null interface names not allowed.
        }
    }

    @Test
    public void testNullCallbackMostDefinitelyThrows() throws Exception {
        final String ifname = "lo";
        setTestInterfaceParams(ifname);
        try {
            final IpClient ipc = new IpClient(
                    mContext, ifname, null, mObserverRegistry, mDependencies);
            ipc.shutdown();
            fail();
        } catch (NullPointerException npe) {
            // Phew; null callbacks not allowed.
        }
    }

    @Test
    public void testInvalidInterfaceDoesNotThrow() throws Exception {
        setTestInterfaceParams(TEST_IFNAME);
        final IpClient ipc = new IpClient(
                mContext, TEST_IFNAME, mCb, mObserverRegistry, mDependencies);
        ipc.shutdown();
    }

    @Test
    public void testInterfaceNotFoundFailsImmediately() throws Exception {
        setTestInterfaceParams(null);
        final IpClient ipc = new IpClient(
                mContext, TEST_IFNAME, mCb, mObserverRegistry, mDependencies);
        ipc.startProvisioning(new ProvisioningConfiguration());
        verify(mCb, times(1)).onProvisioningFailure(any());
        ipc.shutdown();
    }

    @Test
    public void testDefaultProvisioningConfiguration() throws Exception {
        final String iface = TEST_IFNAME;
        final IpClient ipc = makeIpClient(iface);

        ProvisioningConfiguration config = new ProvisioningConfiguration.Builder()
                .withoutIPv4()
                // TODO: mock IpReachabilityMonitor's dependencies (NetworkInterface, PowerManager)
                // and enable it in this test
                .withoutIpReachabilityMonitor()
                .build();

        ipc.startProvisioning(config);
        verify(mCb, times(1)).setNeighborDiscoveryOffload(true);
        verify(mCb, timeout(TEST_TIMEOUT_MS).times(1)).setFallbackMulticastFilter(false);
        verify(mCb, never()).onProvisioningFailure(any());

        ipc.shutdown();
        verify(mNetd, timeout(TEST_TIMEOUT_MS).times(1)).interfaceSetEnableIPv6(iface, false);
        verify(mNetd, timeout(TEST_TIMEOUT_MS).times(1)).interfaceClearAddrs(iface);
        verify(mCb, timeout(TEST_TIMEOUT_MS).times(1))
                .onLinkPropertiesChange(argThat(
                        lp -> fromStableParcelable(lp).equals(makeEmptyLinkProperties(iface))));
    }

    @Test
    public void testProvisioningWithInitialConfiguration() throws Exception {
        final String iface = TEST_IFNAME;
        final IpClient ipc = makeIpClient(iface);

        String[] addresses = {
            "fe80::a4be:f92:e1f7:22d1/64",
            "fe80::f04a:8f6:6a32:d756/64",
            "fd2c:4e57:8e3c:0:548d:2db2:4fcf:ef75/64"
        };
        String[] prefixes = { "fe80::/64", "fd2c:4e57:8e3c::/64" };

        ProvisioningConfiguration config = new ProvisioningConfiguration.Builder()
                .withoutIPv4()
                .withoutIpReachabilityMonitor()
                .withInitialConfiguration(conf(links(addresses), prefixes(prefixes), ips()))
                .build();

        ipc.startProvisioning(config);
        verify(mCb, times(1)).setNeighborDiscoveryOffload(true);
        verify(mCb, timeout(TEST_TIMEOUT_MS).times(1)).setFallbackMulticastFilter(false);
        verify(mCb, never()).onProvisioningFailure(any());

        for (String addr : addresses) {
            String[] parts = addr.split("/");
            verify(mNetd, timeout(TEST_TIMEOUT_MS).times(1))
                    .interfaceAddAddress(iface, parts[0], Integer.parseInt(parts[1]));
        }

        final int lastAddr = addresses.length - 1;

        // Add N - 1 addresses
        for (int i = 0; i < lastAddr; i++) {
            mObserver.onInterfaceAddressUpdated(new LinkAddress(addresses[i]), iface);
            verify(mCb, timeout(TEST_TIMEOUT_MS)).onLinkPropertiesChange(any());
            reset(mCb);
        }

        // Add Nth address
        mObserver.onInterfaceAddressUpdated(new LinkAddress(addresses[lastAddr]), iface);
        LinkProperties want = linkproperties(links(addresses), routes(prefixes));
        want.setInterfaceName(iface);
        verify(mCb, timeout(TEST_TIMEOUT_MS).times(1)).onProvisioningSuccess(argThat(
                lp -> fromStableParcelable(lp).equals(want)));

        ipc.shutdown();
        verify(mNetd, timeout(TEST_TIMEOUT_MS).times(1)).interfaceSetEnableIPv6(iface, false);
        verify(mNetd, timeout(TEST_TIMEOUT_MS).times(1)).interfaceClearAddrs(iface);
        verify(mCb, timeout(TEST_TIMEOUT_MS).times(1))
                .onLinkPropertiesChange(argThat(
                        lp -> fromStableParcelable(lp).equals(makeEmptyLinkProperties(iface))));
    }

    @Test
    public void testIsProvisioned() throws Exception {
        InitialConfiguration empty = conf(links(), prefixes());
        IsProvisionedTestCase[] testcases = {
            // nothing
            notProvisionedCase(links(), routes(), dns(), null),
            notProvisionedCase(links(), routes(), dns(), empty),

            // IPv4
            provisionedCase(links("192.0.2.12/24"), routes(), dns(), empty),

            // IPv6
            notProvisionedCase(
                    links("fe80::a4be:f92:e1f7:22d1/64", "fd2c:4e57:8e3c:0:548d:2db2:4fcf:ef75/64"),
                    routes(), dns(), empty),
            notProvisionedCase(
                    links("fe80::a4be:f92:e1f7:22d1/64", "fd2c:4e57:8e3c:0:548d:2db2:4fcf:ef75/64"),
                    routes("fe80::/64", "fd2c:4e57:8e3c::/64"), dns("fd00:1234:5678::1000"), empty),
            provisionedCase(
                    links("2001:db8:dead:beef:f00::a0/64", "fe80::1/64"),
                    routes("::/0"),
                    dns("2001:db8:dead:beef:f00::02"), empty),

            // Initial configuration
            provisionedCase(
                    links("fe80::e1f7:22d1/64", "fd2c:4e57:8e3c:0:548d:2db2:4fcf:ef75/64"),
                    routes("fe80::/64", "fd2c:4e57:8e3c::/64"),
                    dns(),
                    conf(links("fe80::e1f7:22d1/64", "fd2c:4e57:8e3c:0:548d:2db2:4fcf:ef75/64"),
                        prefixes( "fe80::/64", "fd2c:4e57:8e3c::/64"), ips()))
        };

        for (IsProvisionedTestCase testcase : testcases) {
            if (IpClient.isProvisioned(testcase.lp, testcase.config) != testcase.isProvisioned) {
                fail(testcase.errorMessage());
            }
        }
    }

    static class IsProvisionedTestCase {
        boolean isProvisioned;
        LinkProperties lp;
        InitialConfiguration config;

        String errorMessage() {
            return String.format("expected %s with config %s to be %s, but was %s",
                     lp, config, provisioned(isProvisioned), provisioned(!isProvisioned));
        }

        static String provisioned(boolean isProvisioned) {
            return isProvisioned ? "provisioned" : "not provisioned";
        }
    }

    static IsProvisionedTestCase provisionedCase(Set<LinkAddress> lpAddrs, Set<RouteInfo> lpRoutes,
            Set<InetAddress> lpDns, InitialConfiguration config) {
        return provisioningTest(true, lpAddrs, lpRoutes, lpDns, config);
    }

    static IsProvisionedTestCase notProvisionedCase(Set<LinkAddress> lpAddrs,
            Set<RouteInfo> lpRoutes, Set<InetAddress> lpDns, InitialConfiguration config) {
        return provisioningTest(false, lpAddrs, lpRoutes, lpDns, config);
    }

    static IsProvisionedTestCase provisioningTest(boolean isProvisioned, Set<LinkAddress> lpAddrs,
            Set<RouteInfo> lpRoutes, Set<InetAddress> lpDns, InitialConfiguration config) {
        IsProvisionedTestCase testcase = new IsProvisionedTestCase();
        testcase.isProvisioned = isProvisioned;
        testcase.lp = new LinkProperties();
        testcase.lp.setLinkAddresses(lpAddrs);
        for (RouteInfo route : lpRoutes) {
            testcase.lp.addRoute(route);
        }
        for (InetAddress dns : lpDns) {
            testcase.lp.addDnsServer(dns);
        }
        testcase.config = config;
        return testcase;
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

            invalidConf("empty configuration", links(), prefixes(), dns()),
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
        static String validString(boolean isValid) {
            return isValid ? VALID : INVALID;
        }
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

    static LinkProperties linkproperties(Set<LinkAddress> addresses, Set<RouteInfo> routes) {
        LinkProperties lp = new LinkProperties();
        lp.setLinkAddresses(addresses);
        for (RouteInfo route : routes) {
            lp.addRoute(route);
        }
        return lp;
    }

    static InitialConfiguration conf(Set<LinkAddress> links, Set<IpPrefix> prefixes) {
        return conf(links, prefixes, new HashSet<>());
    }

    static InitialConfiguration conf(
            Set<LinkAddress> links, Set<IpPrefix> prefixes, Set<InetAddress> dns) {
        InitialConfiguration conf = new InitialConfiguration();
        conf.ipAddresses.addAll(links);
        conf.directlyConnectedRoutes.addAll(prefixes);
        conf.dnsServers.addAll(dns);
        return conf;
    }

    static Set<RouteInfo> routes(String... routes) {
        return mapIntoSet(routes, (r) -> new RouteInfo(new IpPrefix(r)));
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

    @Test
    public void testAll() {
        List<String> list1 = Arrays.asList();
        List<String> list2 = Arrays.asList("foo");
        List<String> list3 = Arrays.asList("bar", "baz");
        List<String> list4 = Arrays.asList("foo", "bar", "baz");

        assertTrue(InitialConfiguration.all(list1, (x) -> false));
        assertFalse(InitialConfiguration.all(list2, (x) -> false));
        assertTrue(InitialConfiguration.all(list3, (x) -> true));
        assertTrue(InitialConfiguration.all(list2, (x) -> x.charAt(0) == 'f'));
        assertFalse(InitialConfiguration.all(list4, (x) -> x.charAt(0) == 'f'));
    }

    @Test
    public void testAny() {
        List<String> list1 = Arrays.asList();
        List<String> list2 = Arrays.asList("foo");
        List<String> list3 = Arrays.asList("bar", "baz");
        List<String> list4 = Arrays.asList("foo", "bar", "baz");

        assertFalse(InitialConfiguration.any(list1, (x) -> true));
        assertTrue(InitialConfiguration.any(list2, (x) -> true));
        assertTrue(InitialConfiguration.any(list2, (x) -> x.charAt(0) == 'f'));
        assertFalse(InitialConfiguration.any(list3, (x) -> x.charAt(0) == 'f'));
        assertTrue(InitialConfiguration.any(list4, (x) -> x.charAt(0) == 'f'));
    }

    @Test
    public void testFindAll() {
        List<String> list1 = Arrays.asList();
        List<String> list2 = Arrays.asList("foo");
        List<String> list3 = Arrays.asList("foo", "bar", "baz");

        assertEquals(list1, IpClient.findAll(list1, (x) -> true));
        assertEquals(list1, IpClient.findAll(list3, (x) -> false));
        assertEquals(list3, IpClient.findAll(list3, (x) -> true));
        assertEquals(list2, IpClient.findAll(list3, (x) -> x.charAt(0) == 'f'));
    }
}
