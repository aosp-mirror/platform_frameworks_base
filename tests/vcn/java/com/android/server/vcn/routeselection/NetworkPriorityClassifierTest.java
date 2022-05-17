/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.vcn.routeselection;

import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_FORBIDDEN;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_REQUIRED;
import static android.net.vcn.VcnUnderlyingNetworkTemplateTestBase.TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS;
import static android.net.vcn.VcnUnderlyingNetworkTemplateTestBase.TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS;
import static android.net.vcn.VcnUnderlyingNetworkTemplateTestBase.TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS;
import static android.net.vcn.VcnUnderlyingNetworkTemplateTestBase.TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS;

import static com.android.server.vcn.VcnTestUtils.setupSystemService;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.PRIORITY_ANY;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.calculatePriorityClass;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.checkMatchesCellPriorityRule;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.checkMatchesPriorityRule;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.checkMatchesWifiPriorityRule;
import static com.android.server.vcn.routeselection.UnderlyingNetworkControllerTest.getLinkPropertiesWithName;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.VcnCellUnderlyingNetworkTemplate;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnManager;
import android.net.vcn.VcnWifiUnderlyingNetworkTemplate;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.os.test.TestLooper;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnContext;
import com.android.server.vcn.VcnNetworkProvider;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;
import java.util.UUID;

public class NetworkPriorityClassifierTest {
    private static final String SSID = "TestWifi";
    private static final String SSID_OTHER = "TestWifiOther";
    private static final String PLMN_ID = "123456";
    private static final String PLMN_ID_OTHER = "234567";

    private static final int SUB_ID = 1;
    private static final int WIFI_RSSI = -60;
    private static final int WIFI_RSSI_HIGH = -50;
    private static final int WIFI_RSSI_LOW = -80;
    private static final int CARRIER_ID = 1;
    private static final int CARRIER_ID_OTHER = 2;

    private static final int LINK_UPSTREAM_BANDWIDTH_KBPS = 1024;
    private static final int LINK_DOWNSTREAM_BANDWIDTH_KBPS = 2048;

    private static final int TEST_MIN_UPSTREAM_BANDWIDTH_KBPS = 100;
    private static final int TEST_MIN_DOWNSTREAM_BANDWIDTH_KBPS = 200;

    private static final ParcelUuid SUB_GROUP = new ParcelUuid(new UUID(0, 0));

    private static final NetworkCapabilities WIFI_NETWORK_CAPABILITIES =
            new NetworkCapabilities.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setSignalStrength(WIFI_RSSI)
                    .setSsid(SSID)
                    .setLinkUpstreamBandwidthKbps(LINK_UPSTREAM_BANDWIDTH_KBPS)
                    .setLinkDownstreamBandwidthKbps(LINK_DOWNSTREAM_BANDWIDTH_KBPS)
                    .build();

    private static final TelephonyNetworkSpecifier TEL_NETWORK_SPECIFIER =
            new TelephonyNetworkSpecifier.Builder().setSubscriptionId(SUB_ID).build();
    private static final NetworkCapabilities CELL_NETWORK_CAPABILITIES =
            new NetworkCapabilities.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .setSubscriptionIds(Set.of(SUB_ID))
                    .setNetworkSpecifier(TEL_NETWORK_SPECIFIER)
                    .setLinkUpstreamBandwidthKbps(LINK_UPSTREAM_BANDWIDTH_KBPS)
                    .setLinkDownstreamBandwidthKbps(LINK_DOWNSTREAM_BANDWIDTH_KBPS)
                    .build();

    private static final LinkProperties LINK_PROPERTIES = getLinkPropertiesWithName("test_iface");

    @Mock private Network mNetwork;
    @Mock private TelephonySubscriptionSnapshot mSubscriptionSnapshot;
    @Mock private TelephonyManager mTelephonyManager;

    private TestLooper mTestLooper;
    private VcnContext mVcnContext;
    private UnderlyingNetworkRecord mWifiNetworkRecord;
    private UnderlyingNetworkRecord mCellNetworkRecord;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final Context mockContext = mock(Context.class);
        mTestLooper = new TestLooper();
        mVcnContext =
                spy(
                        new VcnContext(
                                mockContext,
                                mTestLooper.getLooper(),
                                mock(VcnNetworkProvider.class),
                                false /* isInTestMode */));
        doNothing().when(mVcnContext).ensureRunningOnLooperThread();

        mWifiNetworkRecord =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        WIFI_NETWORK_CAPABILITIES,
                        LINK_PROPERTIES,
                        false /* isBlocked */);

        mCellNetworkRecord =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        CELL_NETWORK_CAPABILITIES,
                        LINK_PROPERTIES,
                        false /* isBlocked */);

        setupSystemService(
                mockContext, mTelephonyManager, Context.TELEPHONY_SERVICE, TelephonyManager.class);
        when(mTelephonyManager.createForSubscriptionId(SUB_ID)).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getNetworkOperator()).thenReturn(PLMN_ID);
        when(mTelephonyManager.getSimSpecificCarrierId()).thenReturn(CARRIER_ID);
    }

    @Test
    public void testMatchWithoutNotMeteredBit() {
        final VcnWifiUnderlyingNetworkTemplate wifiNetworkPriority =
                new VcnWifiUnderlyingNetworkTemplate.Builder()
                        .setMetered(MATCH_FORBIDDEN)
                        .build();

        assertFalse(
                checkMatchesPriorityRule(
                        mVcnContext,
                        wifiNetworkPriority,
                        mWifiNetworkRecord,
                        SUB_GROUP,
                        mSubscriptionSnapshot,
                        null /* currentlySelecetd */,
                        null /* carrierConfig */));
    }

    private void verifyMatchesPriorityRuleForUpstreamBandwidth(
            int entryUpstreamBandwidth,
            int exitUpstreamBandwidth,
            UnderlyingNetworkRecord currentlySelected,
            boolean expectMatch) {
        final VcnWifiUnderlyingNetworkTemplate wifiNetworkPriority =
                new VcnWifiUnderlyingNetworkTemplate.Builder()
                        .setMinUpstreamBandwidthKbps(entryUpstreamBandwidth, exitUpstreamBandwidth)
                        .build();

        assertEquals(
                expectMatch,
                checkMatchesPriorityRule(
                        mVcnContext,
                        wifiNetworkPriority,
                        mWifiNetworkRecord,
                        SUB_GROUP,
                        mSubscriptionSnapshot,
                        currentlySelected,
                        null /* carrierConfig */));
    }

    private void verifyMatchesPriorityRuleForDownstreamBandwidth(
            int entryDownstreamBandwidth,
            int exitDownstreamBandwidth,
            UnderlyingNetworkRecord currentlySelected,
            boolean expectMatch) {
        final VcnWifiUnderlyingNetworkTemplate wifiNetworkPriority =
                new VcnWifiUnderlyingNetworkTemplate.Builder()
                        .setMinDownstreamBandwidthKbps(
                                entryDownstreamBandwidth, exitDownstreamBandwidth)
                        .build();

        assertEquals(
                expectMatch,
                checkMatchesPriorityRule(
                        mVcnContext,
                        wifiNetworkPriority,
                        mWifiNetworkRecord,
                        SUB_GROUP,
                        mSubscriptionSnapshot,
                        currentlySelected,
                        null /* carrierConfig */));
    }

    @Test
    public void testMatchWithEntryUpstreamBandwidthEquals() {
        verifyMatchesPriorityRuleForUpstreamBandwidth(
                TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS,
                TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS,
                null /* currentlySelected */,
                true);
    }

    @Test
    public void testMatchWithEntryUpstreamBandwidthTooLow() {
        verifyMatchesPriorityRuleForUpstreamBandwidth(
                LINK_UPSTREAM_BANDWIDTH_KBPS + 1,
                LINK_UPSTREAM_BANDWIDTH_KBPS + 1,
                null /* currentlySelected */,
                false);
    }

    @Test
    public void testMatchWithEntryDownstreamBandwidthEquals() {
        verifyMatchesPriorityRuleForDownstreamBandwidth(
                TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS,
                TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS,
                null /* currentlySelected */,
                true);
    }

    @Test
    public void testMatchWithEntryDownstreamBandwidthTooLow() {
        verifyMatchesPriorityRuleForDownstreamBandwidth(
                LINK_DOWNSTREAM_BANDWIDTH_KBPS + 1,
                LINK_DOWNSTREAM_BANDWIDTH_KBPS + 1,
                null /* currentlySelected */,
                false);
    }

    @Test
    public void testMatchWithExitUpstreamBandwidthEquals() {
        verifyMatchesPriorityRuleForUpstreamBandwidth(
                TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS,
                TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS,
                mWifiNetworkRecord,
                true);
    }

    @Test
    public void testMatchWithExitUpstreamBandwidthTooLow() {
        verifyMatchesPriorityRuleForUpstreamBandwidth(
                LINK_UPSTREAM_BANDWIDTH_KBPS + 1,
                LINK_UPSTREAM_BANDWIDTH_KBPS + 1,
                mWifiNetworkRecord,
                false);
    }

    @Test
    public void testMatchWithExitDownstreamBandwidthEquals() {
        verifyMatchesPriorityRuleForDownstreamBandwidth(
                TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS,
                TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS,
                mWifiNetworkRecord,
                true);
    }

    @Test
    public void testMatchWithExitDownstreamBandwidthTooLow() {
        verifyMatchesPriorityRuleForDownstreamBandwidth(
                LINK_DOWNSTREAM_BANDWIDTH_KBPS + 1,
                LINK_DOWNSTREAM_BANDWIDTH_KBPS + 1,
                mWifiNetworkRecord,
                false);
    }

    private void verifyMatchWifi(
            boolean isSelectedNetwork, PersistableBundle carrierConfig, boolean expectMatch) {
        final VcnWifiUnderlyingNetworkTemplate wifiNetworkPriority =
                new VcnWifiUnderlyingNetworkTemplate.Builder()
                        .setMinUpstreamBandwidthKbps(
                                TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS,
                                TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS)
                        .setMinDownstreamBandwidthKbps(
                                TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS,
                                TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS)
                        .build();
        final UnderlyingNetworkRecord selectedNetworkRecord =
                isSelectedNetwork ? mWifiNetworkRecord : null;
        assertEquals(
                expectMatch,
                checkMatchesWifiPriorityRule(
                        wifiNetworkPriority,
                        mWifiNetworkRecord,
                        selectedNetworkRecord,
                        carrierConfig == null
                                ? null
                                : new PersistableBundleWrapper(carrierConfig)));
    }

    @Test
    public void testMatchSelectedWifi() {
        verifyMatchWifi(
                true /* isSelectedNetwork */, null /* carrierConfig */, true /* expectMatch */);
    }

    @Test
    public void testMatchSelectedWifiBelowRssiThreshold() {
        final PersistableBundle carrierConfig = new PersistableBundle();
        carrierConfig.putInt(
                VcnManager.VCN_NETWORK_SELECTION_WIFI_EXIT_RSSI_THRESHOLD_KEY, WIFI_RSSI_HIGH);
        carrierConfig.putInt(
                VcnManager.VCN_NETWORK_SELECTION_WIFI_ENTRY_RSSI_THRESHOLD_KEY, WIFI_RSSI_HIGH);

        verifyMatchWifi(true /* isSelectedNetwork */, carrierConfig, false /* expectMatch */);
    }

    @Test
    public void testMatchUnselectedWifi() {
        verifyMatchWifi(
                false /* isSelectedNetwork */, null /* carrierConfig */, true /* expectMatch */);
    }

    @Test
    public void testMatchUnselectedWifiBelowRssiThreshold() {
        final PersistableBundle carrierConfig = new PersistableBundle();
        carrierConfig.putInt(
                VcnManager.VCN_NETWORK_SELECTION_WIFI_ENTRY_RSSI_THRESHOLD_KEY, WIFI_RSSI_HIGH);

        verifyMatchWifi(false /* isSelectedNetwork */, carrierConfig, false /* expectMatch */);
    }

    private void verifyMatchWifiWithSsid(boolean useMatchedSsid, boolean expectMatch) {
        final String nwPrioritySsid = useMatchedSsid ? SSID : SSID_OTHER;
        final VcnWifiUnderlyingNetworkTemplate wifiNetworkPriority =
                new VcnWifiUnderlyingNetworkTemplate.Builder()
                        .setMinUpstreamBandwidthKbps(
                                TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS,
                                TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS)
                        .setMinDownstreamBandwidthKbps(
                                TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS,
                                TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS)
                        .setSsids(Set.of(nwPrioritySsid))
                        .build();

        assertEquals(
                expectMatch,
                checkMatchesWifiPriorityRule(
                        wifiNetworkPriority,
                        mWifiNetworkRecord,
                        null /* currentlySelecetd */,
                        null /* carrierConfig */));
    }

    @Test
    public void testMatchWifiWithSsid() {
        verifyMatchWifiWithSsid(true /* useMatchedSsid */, true /* expectMatch */);
    }

    @Test
    public void testMatchWifiFailWithWrongSsid() {
        verifyMatchWifiWithSsid(false /* useMatchedSsid */, false /* expectMatch */);
    }

    private static VcnCellUnderlyingNetworkTemplate.Builder getCellNetworkPriorityBuilder() {
        return new VcnCellUnderlyingNetworkTemplate.Builder()
                .setMinUpstreamBandwidthKbps(
                        TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS,
                        TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS)
                .setMinDownstreamBandwidthKbps(
                        TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS,
                        TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS);
    }

    @Test
    public void testMatchMacroCell() {
        assertTrue(
                checkMatchesCellPriorityRule(
                        mVcnContext,
                        getCellNetworkPriorityBuilder().build(),
                        mCellNetworkRecord,
                        SUB_GROUP,
                        mSubscriptionSnapshot));
    }

    @Test
    public void testMatchOpportunisticCell() {
        final VcnCellUnderlyingNetworkTemplate opportunisticCellNetworkPriority =
                getCellNetworkPriorityBuilder().setOpportunistic(MATCH_REQUIRED).build();

        when(mSubscriptionSnapshot.isOpportunistic(SUB_ID)).thenReturn(true);
        when(mSubscriptionSnapshot.getAllSubIdsInGroup(SUB_GROUP)).thenReturn(new ArraySet<>());

        assertTrue(
                checkMatchesCellPriorityRule(
                        mVcnContext,
                        opportunisticCellNetworkPriority,
                        mCellNetworkRecord,
                        SUB_GROUP,
                        mSubscriptionSnapshot));
    }

    private void verifyMatchMacroCellWithAllowedPlmnIds(
            boolean useMatchedPlmnId, boolean expectMatch) {
        final String networkPriorityPlmnId = useMatchedPlmnId ? PLMN_ID : PLMN_ID_OTHER;
        final VcnCellUnderlyingNetworkTemplate networkPriority =
                getCellNetworkPriorityBuilder()
                        .setOperatorPlmnIds(Set.of(networkPriorityPlmnId))
                        .build();

        assertEquals(
                expectMatch,
                checkMatchesCellPriorityRule(
                        mVcnContext,
                        networkPriority,
                        mCellNetworkRecord,
                        SUB_GROUP,
                        mSubscriptionSnapshot));
    }

    @Test
    public void testMatchMacroCellWithAllowedPlmnIds() {
        verifyMatchMacroCellWithAllowedPlmnIds(true /* useMatchedPlmnId */, true /* expectMatch */);
    }

    @Test
    public void testMatchMacroCellFailWithDisallowedPlmnIds() {
        verifyMatchMacroCellWithAllowedPlmnIds(
                false /* useMatchedPlmnId */, false /* expectMatch */);
    }

    private void verifyMatchMacroCellWithAllowedSpecificCarrierIds(
            boolean useMatchedCarrierId, boolean expectMatch) {
        final int networkPriorityCarrierId = useMatchedCarrierId ? CARRIER_ID : CARRIER_ID_OTHER;
        final VcnCellUnderlyingNetworkTemplate networkPriority =
                getCellNetworkPriorityBuilder()
                        .setSimSpecificCarrierIds(Set.of(networkPriorityCarrierId))
                        .build();

        assertEquals(
                expectMatch,
                checkMatchesCellPriorityRule(
                        mVcnContext,
                        networkPriority,
                        mCellNetworkRecord,
                        SUB_GROUP,
                        mSubscriptionSnapshot));
    }

    @Test
    public void testMatchMacroCellWithAllowedSpecificCarrierIds() {
        verifyMatchMacroCellWithAllowedSpecificCarrierIds(
                true /* useMatchedCarrierId */, true /* expectMatch */);
    }

    @Test
    public void testMatchMacroCellFailWithDisallowedSpecificCarrierIds() {
        verifyMatchMacroCellWithAllowedSpecificCarrierIds(
                false /* useMatchedCarrierId */, false /* expectMatch */);
    }

    @Test
    public void testMatchWifiFailWithoutNotRoamingBit() {
        final VcnCellUnderlyingNetworkTemplate networkPriority =
                getCellNetworkPriorityBuilder().setRoaming(MATCH_FORBIDDEN).build();

        assertFalse(
                checkMatchesCellPriorityRule(
                        mVcnContext,
                        networkPriority,
                        mCellNetworkRecord,
                        SUB_GROUP,
                        mSubscriptionSnapshot));
    }

    private void verifyCalculatePriorityClass(
            UnderlyingNetworkRecord networkRecord, int expectedIndex) {
        final int priorityIndex =
                calculatePriorityClass(
                        mVcnContext,
                        networkRecord,
                        VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                        SUB_GROUP,
                        mSubscriptionSnapshot,
                        null /* currentlySelected */,
                        null /* carrierConfig */);

        assertEquals(expectedIndex, priorityIndex);
    }

    @Test
    public void testCalculatePriorityClass() throws Exception {
        verifyCalculatePriorityClass(mCellNetworkRecord, 2);
    }

    @Test
    public void testCalculatePriorityClassFailToMatchAny() throws Exception {
        final NetworkCapabilities nc =
                new NetworkCapabilities.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .setSignalStrength(WIFI_RSSI_LOW)
                        .setSsid(SSID)
                        .build();
        final UnderlyingNetworkRecord wifiNetworkRecord =
                new UnderlyingNetworkRecord(mNetwork, nc, LINK_PROPERTIES, false /* isBlocked */);

        verifyCalculatePriorityClass(wifiNetworkRecord, PRIORITY_ANY);
    }
}
