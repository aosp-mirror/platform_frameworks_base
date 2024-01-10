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

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_FORBIDDEN;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_REQUIRED;
import static android.net.vcn.VcnUnderlyingNetworkTemplateTestBase.TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS;
import static android.net.vcn.VcnUnderlyingNetworkTemplateTestBase.TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS;
import static android.net.vcn.VcnUnderlyingNetworkTemplateTestBase.TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS;
import static android.net.vcn.VcnUnderlyingNetworkTemplateTestBase.TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS;

import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.PRIORITY_FALLBACK;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.PRIORITY_INVALID;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.checkMatchesCellPriorityRule;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.checkMatchesPriorityRule;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.checkMatchesWifiPriorityRule;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.net.NetworkCapabilities;
import android.net.vcn.VcnCellUnderlyingNetworkTemplate;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnManager;
import android.net.vcn.VcnUnderlyingNetworkTemplate;
import android.net.vcn.VcnWifiUnderlyingNetworkTemplate;
import android.os.PersistableBundle;
import android.util.ArraySet;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class NetworkPriorityClassifierTest extends NetworkEvaluationTestBase {
    private UnderlyingNetworkRecord mWifiNetworkRecord;
    private UnderlyingNetworkRecord mCellNetworkRecord;

    @Before
    public void setUp() {
        super.setUp();

        mWifiNetworkRecord = getTestNetworkRecord(WIFI_NETWORK_CAPABILITIES);
        mCellNetworkRecord = getTestNetworkRecord(CELL_NETWORK_CAPABILITIES);
    }

    private UnderlyingNetworkRecord getTestNetworkRecord(NetworkCapabilities nc) {
        return new UnderlyingNetworkRecord(mNetwork, nc, LINK_PROPERTIES, false /* isBlocked */);
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
                        false /* isSelected */,
                        null /* carrierConfig */));
    }

    private void verifyMatchesPriorityRuleForUpstreamBandwidth(
            int entryUpstreamBandwidth,
            int exitUpstreamBandwidth,
            boolean isSelected,
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
                        isSelected,
                        null /* carrierConfig */));
    }

    private void verifyMatchesPriorityRuleForDownstreamBandwidth(
            int entryDownstreamBandwidth,
            int exitDownstreamBandwidth,
            boolean isSelected,
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
                        isSelected,
                        null /* carrierConfig */));
    }

    @Test
    public void testMatchWithEntryUpstreamBandwidthEquals() {
        verifyMatchesPriorityRuleForUpstreamBandwidth(
                TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS,
                TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS,
                false /* isSelected */,
                true);
    }

    @Test
    public void testMatchWithEntryUpstreamBandwidthTooLow() {
        verifyMatchesPriorityRuleForUpstreamBandwidth(
                LINK_UPSTREAM_BANDWIDTH_KBPS + 1,
                LINK_UPSTREAM_BANDWIDTH_KBPS + 1,
                false /* isSelected */,
                false);
    }

    @Test
    public void testMatchWithEntryDownstreamBandwidthEquals() {
        verifyMatchesPriorityRuleForDownstreamBandwidth(
                TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS,
                TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS,
                false /* isSelected */,
                true);
    }

    @Test
    public void testMatchWithEntryDownstreamBandwidthTooLow() {
        verifyMatchesPriorityRuleForDownstreamBandwidth(
                LINK_DOWNSTREAM_BANDWIDTH_KBPS + 1,
                LINK_DOWNSTREAM_BANDWIDTH_KBPS + 1,
                false /* isSelected */,
                false);
    }

    @Test
    public void testMatchWithExitUpstreamBandwidthEquals() {
        verifyMatchesPriorityRuleForUpstreamBandwidth(
                TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS,
                TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS,
                true /* isSelected */,
                true);
    }

    @Test
    public void testMatchWithExitUpstreamBandwidthTooLow() {
        verifyMatchesPriorityRuleForUpstreamBandwidth(
                LINK_UPSTREAM_BANDWIDTH_KBPS + 1,
                LINK_UPSTREAM_BANDWIDTH_KBPS + 1,
                true /* isSelected */,
                false);
    }

    @Test
    public void testMatchWithExitDownstreamBandwidthEquals() {
        verifyMatchesPriorityRuleForDownstreamBandwidth(
                TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS,
                TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS,
                true /* isSelected */,
                true);
    }

    @Test
    public void testMatchWithExitDownstreamBandwidthTooLow() {
        verifyMatchesPriorityRuleForDownstreamBandwidth(
                LINK_DOWNSTREAM_BANDWIDTH_KBPS + 1,
                LINK_DOWNSTREAM_BANDWIDTH_KBPS + 1,
                true /* isSelected */,
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
        assertEquals(
                expectMatch,
                checkMatchesWifiPriorityRule(
                        wifiNetworkPriority,
                        mWifiNetworkRecord,
                        isSelectedNetwork,
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
                        false /* isSelected */,
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

    private void verifyMatchCellWithRequiredCapabilities(
            VcnCellUnderlyingNetworkTemplate template, boolean expectMatch) {
        assertEquals(
                expectMatch,
                checkMatchesPriorityRule(
                        mVcnContext,
                        template,
                        mCellNetworkRecord,
                        SUB_GROUP,
                        mSubscriptionSnapshot,
                        false /* isSelected */,
                        null /* carrierConfig */));
    }

    @Test
    public void testMatchCell() {
        final VcnCellUnderlyingNetworkTemplate template =
                getCellNetworkPriorityBuilder().setInternet(MATCH_REQUIRED).build();
        verifyMatchCellWithRequiredCapabilities(template, true /* expectMatch */);
    }

    @Test
    public void testMatchCellFail_RequiredCapabilitiesMissing() {
        final VcnCellUnderlyingNetworkTemplate template =
                getCellNetworkPriorityBuilder().setCbs(MATCH_REQUIRED).build();
        verifyMatchCellWithRequiredCapabilities(template, false /* expectMatch */);
    }

    @Test
    public void testMatchCellFail_ForbiddenCapabilitiesFound() {
        final VcnCellUnderlyingNetworkTemplate template =
                getCellNetworkPriorityBuilder().setDun(MATCH_FORBIDDEN).build();
        verifyMatchCellWithRequiredCapabilities(template, false /* expectMatch */);
    }

    @Test
    public void testCalculatePriorityClass() throws Exception {
        final int priorityClass =
                NetworkPriorityClassifier.calculatePriorityClass(
                        mVcnContext,
                        mCellNetworkRecord,
                        VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                        SUB_GROUP,
                        mSubscriptionSnapshot,
                        false /* isSelected */,
                        null /* carrierConfig */);
        assertEquals(2, priorityClass);
    }

    private void checkCalculatePriorityClassFailToMatchAny(
            boolean hasInternet, int expectedPriorityClass) throws Exception {
        final List<VcnUnderlyingNetworkTemplate> templatesRequireDun =
                Collections.singletonList(
                        new VcnCellUnderlyingNetworkTemplate.Builder()
                                .setDun(MATCH_REQUIRED)
                                .build());

        final NetworkCapabilities.Builder ncBuilder =
                new NetworkCapabilities.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        if (hasInternet) {
            ncBuilder.addCapability(NET_CAPABILITY_INTERNET);
        }

        final UnderlyingNetworkRecord nonDunNetworkRecord = getTestNetworkRecord(ncBuilder.build());

        final int priorityClass =
                NetworkPriorityClassifier.calculatePriorityClass(
                        mVcnContext,
                        nonDunNetworkRecord,
                        templatesRequireDun,
                        SUB_GROUP,
                        mSubscriptionSnapshot,
                        false /* isSelected */,
                        null /* carrierConfig */);

        assertEquals(expectedPriorityClass, priorityClass);
    }

    @Test
    public void testCalculatePriorityClassFailToMatchAny_InternetNetwork() throws Exception {
        checkCalculatePriorityClassFailToMatchAny(true /* hasInternet */, PRIORITY_FALLBACK);
    }

    @Test
    public void testCalculatePriorityClassFailToMatchAny_NonInternetNetwork() throws Exception {
        checkCalculatePriorityClassFailToMatchAny(false /* hasInternet */, PRIORITY_INVALID);
    }
}
