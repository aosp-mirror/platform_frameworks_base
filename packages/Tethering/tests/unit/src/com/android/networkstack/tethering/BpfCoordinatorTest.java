/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStats.UID_TETHERING;
import static android.net.netstats.provider.NetworkStatsProvider.QUOTA_UNLIMITED;

import static com.android.networkstack.tethering.BpfCoordinator.StatsType;
import static com.android.networkstack.tethering.BpfCoordinator.StatsType.STATS_PER_IFACE;
import static com.android.networkstack.tethering.BpfCoordinator.StatsType.STATS_PER_UID;
import static com.android.networkstack.tethering.TetheringConfiguration.DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStatsManager;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.MacAddress;
import android.net.NetworkStats;
import android.net.TetherOffloadRuleParcel;
import android.net.TetherStatsParcel;
import android.net.ip.IpServer;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.networkstack.tethering.BpfCoordinator.Ipv6ForwardingRule;
import com.android.testutils.TestableNetworkStatsProviderCbBinder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BpfCoordinatorTest {
    private static final int DOWNSTREAM_IFINDEX = 10;
    private static final MacAddress DOWNSTREAM_MAC = MacAddress.ALL_ZEROS_ADDRESS;
    private static final InetAddress NEIGH_A = InetAddresses.parseNumericAddress("2001:db8::1");
    private static final InetAddress NEIGH_B = InetAddresses.parseNumericAddress("2001:db8::2");
    private static final MacAddress MAC_A = MacAddress.fromString("00:00:00:00:00:0a");
    private static final MacAddress MAC_B = MacAddress.fromString("11:22:33:00:00:0b");

    @Mock private NetworkStatsManager mStatsManager;
    @Mock private INetd mNetd;
    @Mock private IpServer mIpServer;
    @Mock private TetheringConfiguration mTetherConfig;

    // Late init since methods must be called by the thread that created this object.
    private TestableNetworkStatsProviderCbBinder mTetherStatsProviderCb;
    private BpfCoordinator.BpfTetherStatsProvider mTetherStatsProvider;
    private final ArgumentCaptor<ArrayList> mStringArrayCaptor =
            ArgumentCaptor.forClass(ArrayList.class);
    private final TestLooper mTestLooper = new TestLooper();
    private BpfCoordinator.Dependencies mDeps =
            new BpfCoordinator.Dependencies() {
            @NonNull
            public Handler getHandler() {
                return new Handler(mTestLooper.getLooper());
            }

            @NonNull
            public INetd getNetd() {
                return mNetd;
            }

            @NonNull
            public NetworkStatsManager getNetworkStatsManager() {
                return mStatsManager;
            }

            @NonNull
            public SharedLog getSharedLog() {
                return new SharedLog("test");
            }

            @Nullable
            public TetheringConfiguration getTetherConfig() {
                return mTetherConfig;
            }
    };

    @Before public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mTetherConfig.isBpfOffloadEnabled()).thenReturn(true /* default value */);
    }

    private void waitForIdle() {
        mTestLooper.dispatchAll();
    }

    private void setupFunctioningNetdInterface() throws Exception {
        when(mNetd.tetherOffloadGetStats()).thenReturn(new TetherStatsParcel[0]);
    }

    @NonNull
    private BpfCoordinator makeBpfCoordinator() throws Exception {
        final BpfCoordinator coordinator = new BpfCoordinator(mDeps);
        final ArgumentCaptor<BpfCoordinator.BpfTetherStatsProvider>
                tetherStatsProviderCaptor =
                ArgumentCaptor.forClass(BpfCoordinator.BpfTetherStatsProvider.class);
        verify(mStatsManager).registerNetworkStatsProvider(anyString(),
                tetherStatsProviderCaptor.capture());
        mTetherStatsProvider = tetherStatsProviderCaptor.getValue();
        assertNotNull(mTetherStatsProvider);
        mTetherStatsProviderCb = new TestableNetworkStatsProviderCbBinder();
        mTetherStatsProvider.setProviderCallbackBinder(mTetherStatsProviderCb);
        return coordinator;
    }

    @NonNull
    private static NetworkStats.Entry buildTestEntry(@NonNull StatsType how,
            @NonNull String iface, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        return new NetworkStats.Entry(iface, how == STATS_PER_IFACE ? UID_ALL : UID_TETHERING,
                SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, rxBytes,
                rxPackets, txBytes, txPackets, 0L);
    }

    @NonNull
    private static TetherStatsParcel buildTestTetherStatsParcel(@NonNull Integer ifIndex,
            long rxBytes, long rxPackets, long txBytes, long txPackets) {
        final TetherStatsParcel parcel = new TetherStatsParcel();
        parcel.ifIndex = ifIndex;
        parcel.rxBytes = rxBytes;
        parcel.rxPackets = rxPackets;
        parcel.txBytes = txBytes;
        parcel.txPackets = txPackets;
        return parcel;
    }

    // Set up specific tether stats list and wait for the stats cache is updated by polling thread
    // in the coordinator. Beware of that it is only used for the default polling interval.
    private void setTetherOffloadStatsList(TetherStatsParcel[] tetherStatsList) throws Exception {
        when(mNetd.tetherOffloadGetStats()).thenReturn(tetherStatsList);
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
    }

    @Test
    public void testGetForwardedStats() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();
        coordinator.startPolling();

        final String wlanIface = "wlan0";
        final Integer wlanIfIndex = 100;
        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 101;

        // Add interface name to lookup table. In realistic case, the upstream interface name will
        // be added by IpServer when IpServer has received with a new IPv6 upstream update event.
        coordinator.addUpstreamNameToLookupTable(wlanIfIndex, wlanIface);
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // [1] Both interface stats are changed.
        // Setup the tether stats of wlan and mobile interface. Note that move forward the time of
        // the looper to make sure the new tether stats has been updated by polling update thread.
        setTetherOffloadStatsList(new TetherStatsParcel[] {
                buildTestTetherStatsParcel(wlanIfIndex, 1000, 100, 2000, 200),
                buildTestTetherStatsParcel(mobileIfIndex, 3000, 300, 4000, 400)});

        final NetworkStats expectedIfaceStats = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_IFACE, wlanIface, 1000, 100, 2000, 200))
                .addEntry(buildTestEntry(STATS_PER_IFACE, mobileIface, 3000, 300, 4000, 400));

        final NetworkStats expectedUidStats = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_UID, wlanIface, 1000, 100, 2000, 200))
                .addEntry(buildTestEntry(STATS_PER_UID, mobileIface, 3000, 300, 4000, 400));

        // Force pushing stats update to verify the stats reported.
        // TODO: Perhaps make #expectNotifyStatsUpdated to use test TetherStatsParcel object for
        // verifying the notification.
        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(expectedIfaceStats, expectedUidStats);

        // [2] Only one interface stats is changed.
        // The tether stats of mobile interface is accumulated and The tether stats of wlan
        // interface is the same.
        setTetherOffloadStatsList(new TetherStatsParcel[] {
                buildTestTetherStatsParcel(wlanIfIndex, 1000, 100, 2000, 200),
                buildTestTetherStatsParcel(mobileIfIndex, 3010, 320, 4030, 440)});

        final NetworkStats expectedIfaceStatsDiff = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_IFACE, wlanIface, 0, 0, 0, 0))
                .addEntry(buildTestEntry(STATS_PER_IFACE, mobileIface, 10, 20, 30, 40));

        final NetworkStats expectedUidStatsDiff = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_UID, wlanIface, 0, 0, 0, 0))
                .addEntry(buildTestEntry(STATS_PER_UID, mobileIface, 10, 20, 30, 40));

        // Force pushing stats update to verify that only diff of stats is reported.
        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(expectedIfaceStatsDiff,
                expectedUidStatsDiff);

        // [3] Stop coordinator.
        // Shutdown the coordinator and clear the invocation history, especially the
        // tetherOffloadGetStats() calls.
        coordinator.stopPolling();
        clearInvocations(mNetd);

        // Verify the polling update thread stopped.
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        verify(mNetd, never()).tetherOffloadGetStats();
    }

    @Test
    public void testOnSetAlert() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();
        coordinator.startPolling();

        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 100;
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // Verify that set quota to 0 will immediately triggers a callback.
        mTetherStatsProvider.onSetAlert(0);
        waitForIdle();
        mTetherStatsProviderCb.expectNotifyAlertReached();

        // Verify that notifyAlertReached never fired if quota is not yet reached.
        when(mNetd.tetherOffloadGetStats()).thenReturn(
                new TetherStatsParcel[] {buildTestTetherStatsParcel(mobileIfIndex, 0, 0, 0, 0)});
        mTetherStatsProvider.onSetAlert(100);
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        mTetherStatsProviderCb.assertNoCallback();

        // Verify that notifyAlertReached fired when quota is reached.
        when(mNetd.tetherOffloadGetStats()).thenReturn(
                new TetherStatsParcel[] {buildTestTetherStatsParcel(mobileIfIndex, 50, 0, 50, 0)});
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        mTetherStatsProviderCb.expectNotifyAlertReached();

        // Verify that set quota with UNLIMITED won't trigger any callback.
        mTetherStatsProvider.onSetAlert(QUOTA_UNLIMITED);
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        mTetherStatsProviderCb.assertNoCallback();
    }

    // The custom ArgumentMatcher simply comes from IpServerTest.
    // TODO: move both of them into a common utility class for reusing the code.
    private static class TetherOffloadRuleParcelMatcher implements
            ArgumentMatcher<TetherOffloadRuleParcel> {
        public final int upstreamIfindex;
        public final int downstreamIfindex;
        public final Inet6Address address;
        public final MacAddress srcMac;
        public final MacAddress dstMac;

        TetherOffloadRuleParcelMatcher(@NonNull Ipv6ForwardingRule rule) {
            upstreamIfindex = rule.upstreamIfindex;
            downstreamIfindex = rule.downstreamIfindex;
            address = rule.address;
            srcMac = rule.srcMac;
            dstMac = rule.dstMac;
        }

        public boolean matches(@NonNull TetherOffloadRuleParcel parcel) {
            return upstreamIfindex == parcel.inputInterfaceIndex
                    && (downstreamIfindex == parcel.outputInterfaceIndex)
                    && Arrays.equals(address.getAddress(), parcel.destination)
                    && (128 == parcel.prefixLength)
                    && Arrays.equals(srcMac.toByteArray(), parcel.srcL2Address)
                    && Arrays.equals(dstMac.toByteArray(), parcel.dstL2Address);
        }

        public String toString() {
            return String.format("TetherOffloadRuleParcelMatcher(%d, %d, %s, %s, %s",
                    upstreamIfindex, downstreamIfindex, address.getHostAddress(), srcMac, dstMac);
        }
    }

    @NonNull
    private TetherOffloadRuleParcel matches(@NonNull Ipv6ForwardingRule rule) {
        return argThat(new TetherOffloadRuleParcelMatcher(rule));
    }

    @NonNull
    private static Ipv6ForwardingRule buildTestForwardingRule(
            int upstreamIfindex, @NonNull InetAddress address, @NonNull MacAddress dstMac) {
        return new Ipv6ForwardingRule(upstreamIfindex, DOWNSTREAM_IFINDEX, (Inet6Address) address,
                DOWNSTREAM_MAC, dstMac);
    }

    @Test
    public void testSetDataLimit() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 100;
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // [1] Default limit.
        // Set the unlimited quota as default if the service has never applied a data limit for a
        // given upstream. Note that the data limit only be applied on an upstream which has rules.
        final Ipv6ForwardingRule rule = buildTestForwardingRule(mobileIfIndex, NEIGH_A, MAC_A);
        final InOrder inOrder = inOrder(mNetd);
        coordinator.tetherOffloadRuleAdd(mIpServer, rule);
        inOrder.verify(mNetd).tetherOffloadRuleAdd(matches(rule));
        inOrder.verify(mNetd).tetherOffloadSetInterfaceQuota(mobileIfIndex, QUOTA_UNLIMITED);
        inOrder.verifyNoMoreInteractions();

        // [2] Specific limit.
        // Applying the data limit boundary {min, 1gb, max, infinity} on current upstream.
        for (final long quota : new long[] {0, 1048576000, Long.MAX_VALUE, QUOTA_UNLIMITED}) {
            mTetherStatsProvider.onSetLimit(mobileIface, quota);
            waitForIdle();
            inOrder.verify(mNetd).tetherOffloadSetInterfaceQuota(mobileIfIndex, quota);
            inOrder.verifyNoMoreInteractions();
        }

        // [3] Invalid limit.
        // The valid range of quota is 0..max_int64 or -1 (unlimited).
        final long invalidLimit = Long.MIN_VALUE;
        try {
            mTetherStatsProvider.onSetLimit(mobileIface, invalidLimit);
            waitForIdle();
            fail("No exception thrown for invalid limit " + invalidLimit + ".");
        } catch (IllegalArgumentException expected) {
            assertEquals(expected.getMessage(), "invalid quota value " + invalidLimit);
        }
    }

    // TODO: Test the case in which the rules are changed from different IpServer objects.
    @Test
    public void testSetDataLimitOnRuleChange() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 100;
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // Applying a data limit to the current upstream does not take any immediate action.
        // The data limit could be only set on an upstream which has rules.
        final long limit = 12345;
        final InOrder inOrder = inOrder(mNetd);
        mTetherStatsProvider.onSetLimit(mobileIface, limit);
        waitForIdle();
        inOrder.verify(mNetd, never()).tetherOffloadSetInterfaceQuota(anyInt(), anyLong());

        // Adding the first rule on current upstream immediately sends the quota to netd.
        final Ipv6ForwardingRule ruleA = buildTestForwardingRule(mobileIfIndex, NEIGH_A, MAC_A);
        coordinator.tetherOffloadRuleAdd(mIpServer, ruleA);
        inOrder.verify(mNetd).tetherOffloadRuleAdd(matches(ruleA));
        inOrder.verify(mNetd).tetherOffloadSetInterfaceQuota(mobileIfIndex, limit);
        inOrder.verifyNoMoreInteractions();

        // Adding the second rule on current upstream does not send the quota to netd.
        final Ipv6ForwardingRule ruleB = buildTestForwardingRule(mobileIfIndex, NEIGH_B, MAC_B);
        coordinator.tetherOffloadRuleAdd(mIpServer, ruleB);
        inOrder.verify(mNetd).tetherOffloadRuleAdd(matches(ruleB));
        inOrder.verify(mNetd, never()).tetherOffloadSetInterfaceQuota(anyInt(), anyLong());

        // Removing the second rule on current upstream does not send the quota to netd.
        coordinator.tetherOffloadRuleRemove(mIpServer, ruleB);
        inOrder.verify(mNetd).tetherOffloadRuleRemove(matches(ruleB));
        inOrder.verify(mNetd, never()).tetherOffloadSetInterfaceQuota(anyInt(), anyLong());

        // Removing the last rule on current upstream immediately sends the cleanup stuff to netd.
        when(mNetd.tetherOffloadGetAndClearStats(mobileIfIndex))
                .thenReturn(buildTestTetherStatsParcel(mobileIfIndex, 0, 0, 0, 0));
        coordinator.tetherOffloadRuleRemove(mIpServer, ruleA);
        inOrder.verify(mNetd).tetherOffloadRuleRemove(matches(ruleA));
        inOrder.verify(mNetd).tetherOffloadGetAndClearStats(mobileIfIndex);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testTetherOffloadRuleUpdateAndClear() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        final String ethIface = "eth1";
        final String mobileIface = "rmnet_data0";
        final Integer ethIfIndex = 100;
        final Integer mobileIfIndex = 101;
        coordinator.addUpstreamNameToLookupTable(ethIfIndex, ethIface);
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        final InOrder inOrder = inOrder(mNetd);

        // Before the rule test, here are the additional actions while the rules are changed.
        // - After adding the first rule on a given upstream, the coordinator adds a data limit.
        //   If the service has never applied the data limit, set an unlimited quota as default.
        // - After removing the last rule on a given upstream, the coordinator gets the last stats.
        //   Then, it clears the stats and the limit entry from BPF maps.
        // See tetherOffloadRule{Add, Remove, Clear, Clean}.

        // [1] Adding rules on the upstream Ethernet.
        // Note that the default data limit is applied after the first rule is added.
        final Ipv6ForwardingRule ethernetRuleA = buildTestForwardingRule(
                ethIfIndex, NEIGH_A, MAC_A);
        final Ipv6ForwardingRule ethernetRuleB = buildTestForwardingRule(
                ethIfIndex, NEIGH_B, MAC_B);

        coordinator.tetherOffloadRuleAdd(mIpServer, ethernetRuleA);
        inOrder.verify(mNetd).tetherOffloadRuleAdd(matches(ethernetRuleA));
        inOrder.verify(mNetd).tetherOffloadSetInterfaceQuota(ethIfIndex, QUOTA_UNLIMITED);

        coordinator.tetherOffloadRuleAdd(mIpServer, ethernetRuleB);
        inOrder.verify(mNetd).tetherOffloadRuleAdd(matches(ethernetRuleB));

        // [2] Update the existing rules from Ethernet to cellular.
        final Ipv6ForwardingRule mobileRuleA = buildTestForwardingRule(
                mobileIfIndex, NEIGH_A, MAC_A);
        final Ipv6ForwardingRule mobileRuleB = buildTestForwardingRule(
                mobileIfIndex, NEIGH_B, MAC_B);
        when(mNetd.tetherOffloadGetAndClearStats(ethIfIndex))
                .thenReturn(buildTestTetherStatsParcel(ethIfIndex, 10, 20, 30, 40));

        // Update the existing rules for upstream changes. The rules are removed and re-added one
        // by one for updating upstream interface index by #tetherOffloadRuleUpdate.
        coordinator.tetherOffloadRuleUpdate(mIpServer, mobileIfIndex);
        inOrder.verify(mNetd).tetherOffloadRuleRemove(matches(ethernetRuleA));
        inOrder.verify(mNetd).tetherOffloadRuleAdd(matches(mobileRuleA));
        inOrder.verify(mNetd).tetherOffloadSetInterfaceQuota(mobileIfIndex, QUOTA_UNLIMITED);
        inOrder.verify(mNetd).tetherOffloadRuleRemove(matches(ethernetRuleB));
        inOrder.verify(mNetd).tetherOffloadGetAndClearStats(ethIfIndex);
        inOrder.verify(mNetd).tetherOffloadRuleAdd(matches(mobileRuleB));

        // [3] Clear all rules for a given IpServer.
        when(mNetd.tetherOffloadGetAndClearStats(mobileIfIndex))
                .thenReturn(buildTestTetherStatsParcel(mobileIfIndex, 50, 60, 70, 80));
        coordinator.tetherOffloadRuleClear(mIpServer);
        inOrder.verify(mNetd).tetherOffloadRuleRemove(matches(mobileRuleA));
        inOrder.verify(mNetd).tetherOffloadRuleRemove(matches(mobileRuleB));
        inOrder.verify(mNetd).tetherOffloadGetAndClearStats(mobileIfIndex);

        // [4] Force pushing stats update to verify that the last diff of stats is reported on all
        // upstreams.
        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(
                new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_IFACE, ethIface, 10, 20, 30, 40))
                .addEntry(buildTestEntry(STATS_PER_IFACE, mobileIface, 50, 60, 70, 80)),
                new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_UID, ethIface, 10, 20, 30, 40))
                .addEntry(buildTestEntry(STATS_PER_UID, mobileIface, 50, 60, 70, 80)));
    }

    @Test
    public void testTetheringConfigDisable() throws Exception {
        setupFunctioningNetdInterface();
        when(mTetherConfig.isBpfOffloadEnabled()).thenReturn(false);

        final BpfCoordinator coordinator = makeBpfCoordinator();
        coordinator.startPolling();

        // The tether stats polling task should not be scheduled.
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        verify(mNetd, never()).tetherOffloadGetStats();

        // The interface name lookup table can't be added.
        final String iface = "rmnet_data0";
        final Integer ifIndex = 100;
        coordinator.addUpstreamNameToLookupTable(ifIndex, iface);
        assertEquals(0, coordinator.getInterfaceNamesForTesting().size());

        // The rule can't be added.
        final InetAddress neigh = InetAddresses.parseNumericAddress("2001:db8::1");
        final MacAddress mac = MacAddress.fromString("00:00:00:00:00:0a");
        final Ipv6ForwardingRule rule = buildTestForwardingRule(ifIndex, neigh, mac);
        coordinator.tetherOffloadRuleAdd(mIpServer, rule);
        verify(mNetd, never()).tetherOffloadRuleAdd(any());
        LinkedHashMap<Inet6Address, Ipv6ForwardingRule> rules =
                coordinator.getForwardingRulesForTesting().get(mIpServer);
        assertNull(rules);

        // The rule can't be removed. This is not a realistic case because adding rule is not
        // allowed. That implies no rule could be removed, cleared or updated. Verify these
        // cases just in case.
        rules = new LinkedHashMap<Inet6Address, Ipv6ForwardingRule>();
        rules.put(rule.address, rule);
        coordinator.getForwardingRulesForTesting().put(mIpServer, rules);
        coordinator.tetherOffloadRuleRemove(mIpServer, rule);
        verify(mNetd, never()).tetherOffloadRuleRemove(any());
        rules = coordinator.getForwardingRulesForTesting().get(mIpServer);
        assertNotNull(rules);
        assertEquals(1, rules.size());

        // The rule can't be cleared.
        coordinator.tetherOffloadRuleClear(mIpServer);
        verify(mNetd, never()).tetherOffloadRuleRemove(any());
        rules = coordinator.getForwardingRulesForTesting().get(mIpServer);
        assertNotNull(rules);
        assertEquals(1, rules.size());

        // The rule can't be updated.
        coordinator.tetherOffloadRuleUpdate(mIpServer, rule.upstreamIfindex + 1 /* new */);
        verify(mNetd, never()).tetherOffloadRuleRemove(any());
        verify(mNetd, never()).tetherOffloadRuleAdd(any());
        rules = coordinator.getForwardingRulesForTesting().get(mIpServer);
        assertNotNull(rules);
        assertEquals(1, rules.size());
    }

    @Test
    public void testTetheringConfigSetPollingInterval() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        // [1] The default polling interval.
        coordinator.startPolling();
        assertEquals(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS, coordinator.getPollingInterval());
        coordinator.stopPolling();

        // [2] Expect the invalid polling interval isn't applied. The valid range of interval is
        // DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS..max_long.
        for (final int interval
                : new int[] {0, 100, DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS - 1}) {
            when(mTetherConfig.getOffloadPollInterval()).thenReturn(interval);
            coordinator.startPolling();
            assertEquals(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS, coordinator.getPollingInterval());
            coordinator.stopPolling();
        }

        // [3] Set a specific polling interval which is larger than default value.
        // Use a large polling interval to avoid flaky test because the time forwarding
        // approximation is used to verify the scheduled time of the polling thread.
        final int pollingInterval = 100_000;
        when(mTetherConfig.getOffloadPollInterval()).thenReturn(pollingInterval);
        coordinator.startPolling();

        // Expect the specific polling interval to be applied.
        assertEquals(pollingInterval, coordinator.getPollingInterval());

        // Start on a new polling time slot.
        mTestLooper.moveTimeForward(pollingInterval);
        waitForIdle();
        clearInvocations(mNetd);

        // Move time forward to 90% polling interval time. Expect that the polling thread has not
        // scheduled yet.
        mTestLooper.moveTimeForward((long) (pollingInterval * 0.9));
        waitForIdle();
        verify(mNetd, never()).tetherOffloadGetStats();

        // Move time forward to the remaining 10% polling interval time. Expect that the polling
        // thread has scheduled.
        mTestLooper.moveTimeForward((long) (pollingInterval * 0.1));
        waitForIdle();
        verify(mNetd).tetherOffloadGetStats();
    }
}
