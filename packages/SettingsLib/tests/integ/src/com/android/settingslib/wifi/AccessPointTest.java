/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.settingslib.wifi;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkKey;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.SpannableString;
import android.text.style.TtsSpan;

import com.android.settingslib.R;

import com.android.settingslib.wifi.AccessPoint.Speed;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AccessPointTest {

    private static final String TEST_SSID = "test_ssid";
    private static final int NUM_SCAN_RESULTS = 5;

    private static final ArrayList<ScanResult> SCAN_RESULTS = buildScanResultCache();

    private Context mContext;
    @Mock private RssiCurve mockBadgeCurve;
    @Mock private WifiNetworkScoreCache mockWifiNetworkScoreCache;

    private static ScanResult createScanResult(String ssid, String bssid, int rssi) {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.level = rssi;
        scanResult.BSSID = bssid;
        scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
        scanResult.capabilities = "";
        return scanResult;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getTargetContext();
        WifiTracker.sVerboseLogging = false;
    }

    @Test
    public void testSsidIsTelephoneSpan() {
        final Bundle bundle = new Bundle();
        bundle.putString("key_ssid", TEST_SSID);
        final AccessPoint ap = new AccessPoint(InstrumentationRegistry.getTargetContext(), bundle);
        final CharSequence ssid = ap.getSsid();

        assertThat(ssid instanceof SpannableString).isTrue();

        TtsSpan[] spans = ((SpannableString) ssid).getSpans(0, TEST_SSID.length(), TtsSpan.class);

        assertThat(spans.length).isEqualTo(1);
        assertThat(spans[0].getType()).isEqualTo(TtsSpan.TYPE_TELEPHONE);
    }

    @Test
    public void testCopyAccessPoint_dataShouldMatch() {
        WifiConfiguration configuration = createWifiConfiguration();
        configuration.meteredHint = true;

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 2, "WIFI", "WIFI_SUBTYPE");
        AccessPoint originalAccessPoint = new AccessPoint(mContext, configuration);
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(configuration.SSID));
        wifiInfo.setBSSID(configuration.BSSID);
        originalAccessPoint.update(configuration, wifiInfo, networkInfo);
        AccessPoint copy = new AccessPoint(mContext, originalAccessPoint);

        assertThat(originalAccessPoint.getSsid().toString()).isEqualTo(copy.getSsid().toString());
        assertThat(originalAccessPoint.getBssid()).isEqualTo(copy.getBssid());
        assertThat(originalAccessPoint.getConfig()).isEqualTo(copy.getConfig());
        assertThat(originalAccessPoint.getSecurity()).isEqualTo(copy.getSecurity());
        assertThat(originalAccessPoint.isMetered()).isEqualTo(copy.isMetered());
        assertThat(originalAccessPoint.compareTo(copy) == 0).isTrue();
    }

    @Test
    public void testThatCopyAccessPoint_scanCacheShouldMatch() {
        AccessPoint original = createAccessPointWithScanResultCache();
        assertThat(original.getRssi()).isEqualTo(4);
        AccessPoint copy = new AccessPoint(mContext, createWifiConfiguration());
        assertThat(copy.getRssi()).isEqualTo(AccessPoint.UNREACHABLE_RSSI);
        copy.copyFrom(original);
        assertThat(original.getRssi()).isEqualTo(copy.getRssi());
    }

    @Test
    public void testCompareTo_GivesActiveBeforeInactive() {
        AccessPoint activeAp = new TestAccessPointBuilder(mContext).setActive(true).build();
        AccessPoint inactiveAp = new TestAccessPointBuilder(mContext).setActive(false).build();

        assertSortingWorks(activeAp, inactiveAp);
    }

    @Test
    public void testCompareTo_GivesReachableBeforeUnreachable() {
        AccessPoint nearAp = new TestAccessPointBuilder(mContext).setReachable(true).build();
        AccessPoint farAp = new TestAccessPointBuilder(mContext).setReachable(false).build();

        assertSortingWorks(nearAp, farAp);
    }

    @Test
    public void testCompareTo_GivesSavedBeforeUnsaved() {
        AccessPoint savedAp = new TestAccessPointBuilder(mContext).setSaved(true).build();
        AccessPoint notSavedAp = new TestAccessPointBuilder(mContext).setSaved(false).build();

        assertSortingWorks(savedAp, notSavedAp);
    }

    @Test
    public void testCompareTo_GivesHighSpeedBeforeLowSpeed() {
        AccessPoint fastAp = new TestAccessPointBuilder(mContext).setSpeed(Speed.FAST).build();
        AccessPoint slowAp = new TestAccessPointBuilder(mContext).setSpeed(Speed.SLOW).build();

        assertSortingWorks(fastAp, slowAp);
    }

    @Test
    public void testCompareTo_GivesHighLevelBeforeLowLevel() {
        final int highLevel = AccessPoint.SIGNAL_LEVELS - 1;
        final int lowLevel = 1;
        assertThat(highLevel).isGreaterThan(lowLevel);

        AccessPoint strongAp = new TestAccessPointBuilder(mContext).setLevel(highLevel).build();
        AccessPoint weakAp = new TestAccessPointBuilder(mContext).setLevel(lowLevel).build();

        assertSortingWorks(strongAp, weakAp);
    }

    @Test
    public void testCompareTo_GivesSsidAlphabetically() {

        final String firstName = "AAAAAA";
        final String secondName = "zzzzzz";

        AccessPoint firstAp = new TestAccessPointBuilder(mContext).setSsid(firstName).build();
        AccessPoint secondAp = new TestAccessPointBuilder(mContext).setSsid(secondName).build();

        assertThat(firstAp.getSsidStr().compareToIgnoreCase(secondAp.getSsidStr()) < 0).isTrue();
        assertSortingWorks(firstAp, secondAp);
    }

    @Test
    public void testCompareTo_GivesSsidCasePrecendenceAfterAlphabetical() {

        final String firstName = "aaAaaa";
        final String secondName = "aaaaaa";
        final String thirdName = "BBBBBB";

        AccessPoint firstAp = new TestAccessPointBuilder(mContext).setSsid(firstName).build();
        AccessPoint secondAp = new TestAccessPointBuilder(mContext).setSsid(secondName).build();
        AccessPoint thirdAp = new TestAccessPointBuilder(mContext).setSsid(thirdName).build();

        assertSortingWorks(firstAp, secondAp);
        assertSortingWorks(secondAp, thirdAp);
    }

    @Test
    public void testCompareTo_AllSortingRulesCombined() {

        AccessPoint active = new TestAccessPointBuilder(mContext).setActive(true).build();
        AccessPoint reachableAndMinLevel = new TestAccessPointBuilder(mContext)
                .setReachable(true).build();
        AccessPoint saved = new TestAccessPointBuilder(mContext).setSaved(true).build();
        AccessPoint highLevelAndReachable = new TestAccessPointBuilder(mContext)
                .setLevel(AccessPoint.SIGNAL_LEVELS - 1).build();
        AccessPoint firstName = new TestAccessPointBuilder(mContext).setSsid("a").build();
        AccessPoint lastname = new TestAccessPointBuilder(mContext).setSsid("z").build();

        ArrayList<AccessPoint> points = new ArrayList<AccessPoint>();
        points.add(lastname);
        points.add(firstName);
        points.add(highLevelAndReachable);
        points.add(saved);
        points.add(reachableAndMinLevel);
        points.add(active);

        Collections.sort(points);
        assertThat(points.indexOf(active)).isLessThan(points.indexOf(reachableAndMinLevel));
        assertThat(points.indexOf(reachableAndMinLevel)).isLessThan(points.indexOf(saved));
        // note: the saved AP will not appear before highLevelAndReachable,
        // because all APs with a signal level are reachable,
        // and isReachable() takes higher sorting precedence than isSaved().
        assertThat(points.indexOf(saved)).isLessThan(points.indexOf(firstName));
        assertThat(points.indexOf(highLevelAndReachable)).isLessThan(points.indexOf(firstName));
        assertThat(points.indexOf(firstName)).isLessThan(points.indexOf(lastname));
    }

    @Test
    public void testRssiIsSetFromScanResults() {
        AccessPoint ap = createAccessPointWithScanResultCache();
        int originalRssi = ap.getRssi();
        assertThat(originalRssi).isNotEqualTo(AccessPoint.UNREACHABLE_RSSI);
    }

    @Test
    public void testGetRssiShouldReturnSetRssiValue() {
        AccessPoint ap = createAccessPointWithScanResultCache();
        int originalRssi = ap.getRssi();
        int newRssi = originalRssi - 10;
        ap.setRssi(newRssi);
        assertThat(ap.getRssi()).isEqualTo(newRssi);
    }

    @Test
    public void testUpdateWithScanResultShouldAverageRssi() {
        String ssid = "ssid";
        int originalRssi = -65;
        int newRssi = -80;
        int expectedRssi = (originalRssi + newRssi) / 2;
        AccessPoint ap =
                new TestAccessPointBuilder(mContext).setSsid(ssid).setRssi(originalRssi).build();

        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.level = newRssi;
        scanResult.BSSID = "bssid";
        scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
        scanResult.capabilities = "";
        assertThat(ap.update(scanResult, true /* evict old scan results */)).isTrue();

        assertThat(ap.getRssi()).isEqualTo(expectedRssi);
    }

    @Test
    public void testCreateFromPasspointConfig() {
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        homeSp.setFriendlyName("Test Provider");
        config.setHomeSp(homeSp);
        AccessPoint ap = new AccessPoint(mContext, config);
        assertThat(ap.isPasspointConfig()).isTrue();
    }

    @Test
    public void testIsMetered_returnTrueWhenWifiConfigurationIsMetered() {
        WifiConfiguration configuration = createWifiConfiguration();
        configuration.meteredHint = true;

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 2, "WIFI", "WIFI_SUBTYPE");
        AccessPoint accessPoint = new AccessPoint(mContext, configuration);
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(configuration.SSID));
        wifiInfo.setBSSID(configuration.BSSID);
        wifiInfo.setNetworkId(configuration.networkId);
        accessPoint.update(configuration, wifiInfo, networkInfo);

        assertThat(accessPoint.isMetered()).isTrue();
    }

    @Test
    public void testIsMetered_returnTrueWhenWifiInfoIsMetered() {
        WifiConfiguration configuration = createWifiConfiguration();

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 2, "WIFI", "WIFI_SUBTYPE");
        AccessPoint accessPoint = new AccessPoint(mContext, configuration);
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(configuration.SSID));
        wifiInfo.setBSSID(configuration.BSSID);
        wifiInfo.setNetworkId(configuration.networkId);
        wifiInfo.setMeteredHint(true);
        accessPoint.update(configuration, wifiInfo, networkInfo);

        assertThat(accessPoint.isMetered()).isTrue();
    }

    @Test
    public void testIsMetered_returnTrueWhenScoredNetworkIsMetered() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(
                        new ScoredNetwork(
                                null /* NetworkKey */,
                                null /* rssiCurve */,
                                true /* metered */));
        ap.update(mockWifiNetworkScoreCache, false /* scoringUiEnabled */);

        assertThat(ap.isMetered()).isTrue();
    }

    @Test
    public void testIsMetered_returnFalseByDefault() {
        WifiConfiguration configuration = createWifiConfiguration();

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 2, "WIFI", "WIFI_SUBTYPE");
        AccessPoint accessPoint = new AccessPoint(mContext, configuration);
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(configuration.SSID));
        wifiInfo.setBSSID(configuration.BSSID);
        wifiInfo.setNetworkId(configuration.networkId);
        accessPoint.update(configuration, wifiInfo, networkInfo);

        assertThat(accessPoint.isMetered()).isFalse();
    }

    @Test
    public void testSpeedLabel_returnsVeryFast() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.VERY_FAST);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        assertThat(ap.getSpeed()).isEqualTo(AccessPoint.Speed.VERY_FAST);
        assertThat(ap.getSpeedLabel())
                .isEqualTo(mContext.getString(R.string.speed_label_very_fast));
    }

    @Test
    public void testSpeedLabel_returnsFast() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.FAST);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        assertThat(ap.getSpeed()).isEqualTo(AccessPoint.Speed.FAST);
        assertThat(ap.getSpeedLabel())
                .isEqualTo(mContext.getString(R.string.speed_label_fast));
    }

    @Test
    public void testSpeedLabel_returnsOkay() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.MODERATE);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        assertThat(ap.getSpeed()).isEqualTo(AccessPoint.Speed.MODERATE);
        assertThat(ap.getSpeedLabel())
                .isEqualTo(mContext.getString(R.string.speed_label_okay));
    }

    @Test
    public void testSpeedLabel_returnsSlow() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.SLOW);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        assertThat(ap.getSpeed()).isEqualTo(AccessPoint.Speed.SLOW);
        assertThat(ap.getSpeedLabel())
                .isEqualTo(mContext.getString(R.string.speed_label_slow));
    }

    @Test
    public void testSpeedLabel_isDerivedFromConnectedBssidWhenScoreAvailable() {
        int rssi = -55;
        String bssid = "00:00:00:00:00:00";
        int networkId = 123;

        WifiInfo info = new WifiInfo();
        info.setRssi(rssi);
        info.setSSID(WifiSsid.createFromAsciiEncoded(TEST_SSID));
        info.setBSSID(bssid);
        info.setNetworkId(networkId);

        ArrayList<ScanResult> scanResults = new ArrayList<>();
        ScanResult scanResultUnconnected = createScanResult(TEST_SSID, "11:11:11:11:11:11", rssi);
        scanResults.add(scanResultUnconnected);

        ScanResult scanResultConnected = createScanResult(TEST_SSID, bssid, rssi);
        scanResults.add(scanResultConnected);

        AccessPoint ap =
                new TestAccessPointBuilder(mContext)
                        .setActive(true)
                        .setNetworkId(networkId)
                        .setSsid(TEST_SSID)
                        .setScanResultCache(scanResults)
                        .setWifiInfo(info)
                        .build();

        when(mockWifiNetworkScoreCache.getScoredNetwork(scanResultUnconnected))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) Speed.SLOW);

        int connectedSpeed = Speed.VERY_FAST;
        RssiCurve connectedBadgeCurve = mock(RssiCurve.class);
        Bundle attr1 = new Bundle();
        attr1.putParcelable(ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE, connectedBadgeCurve);
        ScoredNetwork connectedScore = new ScoredNetwork(
                NetworkKey.createFromScanResult(scanResultConnected),
                connectedBadgeCurve,
                false /* meteredHint */,
                attr1);
        when(mockWifiNetworkScoreCache.getScoredNetwork(scanResultConnected))
                .thenReturn(connectedScore);
        when(connectedBadgeCurve.lookupScore(anyInt())).thenReturn((byte) connectedSpeed);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        assertThat(ap.getSpeed()).isEqualTo(connectedSpeed);
    }

    @Test
    public void testSummaryString_showsSpeedLabel() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.VERY_FAST);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        assertThat(ap.getSummary()).isEqualTo(mContext.getString(R.string.speed_label_very_fast));
    }

    @Test
    public void testVerboseSummaryString_showsScanResultSpeedLabel() {
        WifiTracker.sVerboseLogging = true;

        Bundle bundle = new Bundle();
        ArrayList<ScanResult> scanResults = buildScanResultCache();
        bundle.putParcelableArrayList(AccessPoint.KEY_SCANRESULTCACHE, scanResults);
        AccessPoint ap = new AccessPoint(mContext, bundle);

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.VERY_FAST);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);
        String summary = ap.verboseScanResultSummary(scanResults.get(0), null);

        assertThat(summary.contains(mContext.getString(R.string.speed_label_very_fast))).isTrue();
    }

    @Test
    public void testSummaryString_concatenatesSpeedLabel() {
        AccessPoint ap = createAccessPointWithScanResultCache();
        ap.update(new WifiConfiguration());

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.VERY_FAST);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        String expectedString = mContext.getString(R.string.speed_label_very_fast) + " / "
                + mContext.getString(R.string.wifi_remembered);
        assertThat(ap.getSummary()).isEqualTo(expectedString);
    }

    @Test
    public void testSummaryString_showsWrongPasswordLabel() {
        WifiConfiguration configuration = createWifiConfiguration();
        configuration.getNetworkSelectionStatus().setNetworkSelectionStatus(
                WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);
        configuration.getNetworkSelectionStatus().setNetworkSelectionDisableReason(
                WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD);
        AccessPoint ap = new AccessPoint(mContext, configuration);

        assertThat(ap.getSummary()).isEqualTo(mContext.getString(
                R.string.wifi_check_password_try_again));
    }

    @Test
    public void testSummaryString_showsAvaiableViaCarrier() {
        String carrierName = "Test Carrier";
        ScanResult result = new ScanResult();
        result.BSSID = "00:11:22:33:44:55";
        result.capabilities = "EAP";
        result.isCarrierAp = true;
        result.carrierApEapType = WifiEnterpriseConfig.Eap.SIM;
        result.carrierName = carrierName;

        AccessPoint ap = new AccessPoint(mContext, result);
        assertThat(ap.getSummary()).isEqualTo(String.format(mContext.getString(
                R.string.available_via_carrier), carrierName));
        assertThat(ap.isCarrierAp()).isEqualTo(true);
        assertThat(ap.getCarrierApEapType()).isEqualTo(WifiEnterpriseConfig.Eap.SIM);
        assertThat(ap.getCarrierName()).isEqualTo(carrierName);
    }

    @Test
    public void testSummaryString_showsConnectedViaCarrier() {
        int networkId = 123;
        int rssi = -55;
        String carrierName = "Test Carrier";
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = networkId;
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(networkId);
        wifiInfo.setRssi(rssi);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(networkId)
                .setRssi(rssi)
                .setWifiInfo(wifiInfo)
                .setIsCarrierAp(true)
                .setCarrierName(carrierName)
                .build();
        assertThat(ap.getSummary()).isEqualTo(String.format(mContext.getString(
                R.string.connected_via_carrier), carrierName));
    }

    @Test
    public void testUpdateScanResultWithCarrierInfo() {
        String ssid = "ssid";
        AccessPoint ap = new TestAccessPointBuilder(mContext).setSsid(ssid).build();
        assertThat(ap.isCarrierAp()).isEqualTo(false);
        assertThat(ap.getCarrierApEapType()).isEqualTo(WifiEnterpriseConfig.Eap.NONE);
        assertThat(ap.getCarrierName()).isEqualTo(null);

        int carrierApEapType = WifiEnterpriseConfig.Eap.SIM;
        String carrierName = "Test Carrier";
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.BSSID = "00:11:22:33:44:55";
        scanResult.capabilities = "";
        scanResult.isCarrierAp = true;
        scanResult.carrierApEapType = carrierApEapType;
        scanResult.carrierName = carrierName;
        assertThat(ap.update(scanResult, true /* evictOldScanresults */)).isTrue();

        assertThat(ap.isCarrierAp()).isEqualTo(true);
        assertThat(ap.getCarrierApEapType()).isEqualTo(carrierApEapType);
        assertThat(ap.getCarrierName()).isEqualTo(carrierName);
    }

    private ScoredNetwork buildScoredNetworkWithMockBadgeCurve() {
        return buildScoredNetworkWithGivenBadgeCurve(mockBadgeCurve);

    }

    private ScoredNetwork buildScoredNetworkWithGivenBadgeCurve(RssiCurve badgeCurve) {
        Bundle attr1 = new Bundle();
        attr1.putParcelable(ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE, badgeCurve);
        return new ScoredNetwork(
                new NetworkKey(new WifiKey("\"ssid\"", "00:00:00:00:00:00")),
                mockBadgeCurve,
                false /* meteredHint */,
                attr1);

    }

    private AccessPoint createAccessPointWithScanResultCache() {
        Bundle bundle = new Bundle();
        bundle.putParcelableArrayList(AccessPoint.KEY_SCANRESULTCACHE, SCAN_RESULTS);
        return new AccessPoint(mContext, bundle);
    }

    private static ArrayList<ScanResult> buildScanResultCache() {
        ArrayList<ScanResult> scanResults = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ScanResult scanResult = createScanResult(TEST_SSID, "bssid-" + i, i);
            scanResults.add(scanResult);
        }
        return scanResults;
    }

    private WifiConfiguration createWifiConfiguration() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.BSSID = "bssid";
        configuration.SSID = "ssid";
        configuration.networkId = 123;
        return configuration;
    }

    /**
    * Assert that the first AccessPoint appears before the second AccessPoint
    * once sorting has been completed.
    */
    private void assertSortingWorks(AccessPoint first, AccessPoint second) {

        ArrayList<AccessPoint> points = new ArrayList<AccessPoint>();

        // add in reverse order so we can tell that sorting actually changed something
        points.add(second);
        points.add(first);
        Collections.sort(points);
        assertWithMessage(
                String.format("After sorting: second AccessPoint should have higher array index "
                    + "than the first, but found indicies second '%s' and first '%s'.",
                    points.indexOf(second), points.indexOf(first)))
            .that(points.indexOf(second)).isGreaterThan(points.indexOf(first));
    }

    @Test
    public void testBuilder_setActive() {
        AccessPoint activeAp = new TestAccessPointBuilder(mContext).setActive(true).build();
        assertThat(activeAp.isActive()).isTrue();

        AccessPoint inactiveAp = new TestAccessPointBuilder(mContext).setActive(false).build();
        assertThat(inactiveAp.isActive()).isFalse();
    }

    @Test
    public void testBuilder_setReachable() {
        AccessPoint nearAp = new TestAccessPointBuilder(mContext).setReachable(true).build();
        assertThat(nearAp.isReachable()).isTrue();

        AccessPoint farAp = new TestAccessPointBuilder(mContext).setReachable(false).build();
        assertThat(farAp.isReachable()).isFalse();
    }

    @Test
    public void testBuilder_setSaved() {
        AccessPoint savedAp = new TestAccessPointBuilder(mContext).setSaved(true).build();
        assertThat(savedAp.isSaved()).isTrue();

        AccessPoint newAp = new TestAccessPointBuilder(mContext).setSaved(false).build();
        assertThat(newAp.isSaved()).isFalse();
    }

    @Test
    public void testBuilder_setLevel() {
        AccessPoint testAp;

        for (int i = 0; i < AccessPoint.SIGNAL_LEVELS; i++) {
            testAp = new TestAccessPointBuilder(mContext).setLevel(i).build();
            assertThat(testAp.getLevel()).isEqualTo(i);
        }

        // numbers larger than the max level should be set to max
        testAp = new TestAccessPointBuilder(mContext).setLevel(AccessPoint.SIGNAL_LEVELS).build();
        assertThat(testAp.getLevel()).isEqualTo(AccessPoint.SIGNAL_LEVELS - 1);

        // numbers less than 0 should give level 0
        testAp = new TestAccessPointBuilder(mContext).setLevel(-100).build();
        assertThat(testAp.getLevel()).isEqualTo(0);
    }

    @Test
    public void testBuilder_settingReachableAfterLevelDoesNotAffectLevel() {
        int level = 1;
        assertThat(level).isLessThan(AccessPoint.SIGNAL_LEVELS - 1);

        AccessPoint testAp =
                new TestAccessPointBuilder(mContext).setLevel(level).setReachable(true).build();
        assertThat(testAp.getLevel()).isEqualTo(level);
    }

    @Test
    public void testBuilder_setSsid() {
        String name = "AmazingSsid!";
        AccessPoint namedAp = new TestAccessPointBuilder(mContext).setSsid(name).build();
        assertThat(namedAp.getSsidStr()).isEqualTo(name);
    }

    @Test
    public void testBuilder_passpointConfig() {
        String fqdn = "Test.com";
        String providerFriendlyName = "Test Provider";
        AccessPoint ap = new TestAccessPointBuilder(mContext).setFqdn(fqdn)
                .setProviderFriendlyName(providerFriendlyName).build();
        assertThat(ap.isPasspointConfig()).isTrue();
        assertThat(ap.getPasspointFqdn()).isEqualTo(fqdn);
        assertThat(ap.getConfigName()).isEqualTo(providerFriendlyName);
    }

    @Test
    public void testUpdateNetworkInfo_returnsTrue() {
        int networkId = 123;
        int rssi = -55;
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = networkId;
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(networkId);
        wifiInfo.setRssi(rssi);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTING, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(networkId)
                .setRssi(rssi)
                .setWifiInfo(wifiInfo)
                .build();

        NetworkInfo newInfo = new NetworkInfo(networkInfo);
        newInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");
        assertThat(ap.update(config, wifiInfo, newInfo)).isTrue();
    }

    @Test
    public void testUpdateNetworkInfoWithSameInfo_returnsFalse() {
        int networkId = 123;
        int rssi = -55;
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = networkId;
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(networkId);
        wifiInfo.setRssi(rssi);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTING, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(networkId)
                .setRssi(rssi)
                .setWifiInfo(wifiInfo)
                .build();

        NetworkInfo newInfo = new NetworkInfo(networkInfo); // same values
        assertThat(ap.update(config, wifiInfo, newInfo)).isFalse();
    }

    @Test
    public void testUpdateWithDifferentRssi_returnsTrue() {
        int networkId = 123;
        int rssi = -55;
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = networkId;
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(networkId);
        wifiInfo.setRssi(rssi);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTING, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(networkId)
                .setRssi(rssi)
                .setWifiInfo(wifiInfo)
                .build();

        NetworkInfo newInfo = new NetworkInfo(networkInfo); // same values
        wifiInfo.setRssi(rssi + 1);
        assertThat(ap.update(config, wifiInfo, newInfo)).isTrue();
    }

    @Test
    public void testUpdateWithInvalidRssi_returnsFalse() {
        int networkId = 123;
        int rssi = -55;
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = networkId;
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(networkId);
        wifiInfo.setRssi(rssi);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTING, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(networkId)
                .setRssi(rssi)
                .setWifiInfo(wifiInfo)
                .build();

        NetworkInfo newInfo = new NetworkInfo(networkInfo); // same values
        wifiInfo.setRssi(WifiInfo.INVALID_RSSI);
        assertThat(ap.update(config, wifiInfo, newInfo)).isFalse();
    }
    @Test
    public void testUpdateWithConfigChangeOnly_returnsFalseButInvokesListener() {
        int networkId = 123;
        int rssi = -55;
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = networkId;
        config.numNoInternetAccessReports = 1;

        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(networkId);
        wifiInfo.setRssi(rssi);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(networkId)
                .setRssi(rssi)
                .setWifiInfo(wifiInfo)
                .build();

        AccessPoint.AccessPointListener mockListener = mock(AccessPoint.AccessPointListener.class);
        ap.setListener(mockListener);
        WifiConfiguration newConfig = new WifiConfiguration(config);
        config.validatedInternetAccess = true;

        assertThat(ap.update(newConfig, wifiInfo, networkInfo)).isFalse();
        verify(mockListener).onAccessPointChanged(ap);
    }

    @Test
    public void testUpdateWithNullWifiConfiguration_doesNotThrowNPE() {
        int networkId = 123;
        int rssi = -55;
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = networkId;
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(networkId);
        wifiInfo.setRssi(rssi);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTING, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(networkId)
                .setRssi(rssi)
                .setWifiInfo(wifiInfo)
                .build();

        ap.update(null, wifiInfo, networkInfo);
    }

    @Test
    public void testSpeedLabelAveragesAllBssidScores() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        int speed1 = Speed.MODERATE;
        RssiCurve badgeCurve1 = mock(RssiCurve.class);
        when(badgeCurve1.lookupScore(anyInt())).thenReturn((byte) speed1);
        when(mockWifiNetworkScoreCache.getScoredNetwork(SCAN_RESULTS.get(0)))
                .thenReturn(buildScoredNetworkWithGivenBadgeCurve(badgeCurve1));
        int speed2 = Speed.VERY_FAST;
        RssiCurve badgeCurve2 = mock(RssiCurve.class);
        when(badgeCurve2.lookupScore(anyInt())).thenReturn((byte) speed2);
        when(mockWifiNetworkScoreCache.getScoredNetwork(SCAN_RESULTS.get(1)))
                .thenReturn(buildScoredNetworkWithGivenBadgeCurve(badgeCurve2));

        int expectedSpeed = (speed1 + speed2) / 2;

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        assertThat(ap.getSpeed()).isEqualTo(expectedSpeed);
    }

    @Test
    public void testSpeedLabelAverageIgnoresNoSpeedScores() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        int speed1 = Speed.VERY_FAST;
        RssiCurve badgeCurve1 = mock(RssiCurve.class);
        when(badgeCurve1.lookupScore(anyInt())).thenReturn((byte) speed1);
        when(mockWifiNetworkScoreCache.getScoredNetwork(SCAN_RESULTS.get(0)))
                .thenReturn(buildScoredNetworkWithGivenBadgeCurve(badgeCurve1));
        int speed2 = Speed.NONE;
        RssiCurve badgeCurve2 = mock(RssiCurve.class);
        when(badgeCurve2.lookupScore(anyInt())).thenReturn((byte) speed2);
        when(mockWifiNetworkScoreCache.getScoredNetwork(SCAN_RESULTS.get(1)))
                .thenReturn(buildScoredNetworkWithGivenBadgeCurve(badgeCurve2));

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        assertThat(ap.getSpeed()).isEqualTo(speed1);
    }

    @Test
    public void testSpeedLabelUsesFallbackScoreWhenConnectedAccessPointScoreUnavailable() {
        int rssi = -55;
        String bssid = "00:00:00:00:00:00";
        int networkId = 123;

        WifiInfo info = new WifiInfo();
        info.setRssi(rssi);
        info.setSSID(WifiSsid.createFromAsciiEncoded(TEST_SSID));
        info.setBSSID(bssid);
        info.setNetworkId(networkId);

        ArrayList<ScanResult> scanResults = new ArrayList<>();
        ScanResult scanResultUnconnected = createScanResult(TEST_SSID, "11:11:11:11:11:11", rssi);
        scanResults.add(scanResultUnconnected);

        ScanResult scanResultConnected = createScanResult(TEST_SSID, bssid, rssi);
        scanResults.add(scanResultConnected);

        AccessPoint ap =
                new TestAccessPointBuilder(mContext)
                        .setActive(true)
                        .setNetworkId(networkId)
                        .setSsid(TEST_SSID)
                        .setScanResultCache(scanResults)
                        .setWifiInfo(info)
                        .build();

        int fallbackSpeed = Speed.SLOW;
        when(mockWifiNetworkScoreCache.getScoredNetwork(scanResultUnconnected))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) fallbackSpeed);

        when(mockWifiNetworkScoreCache.getScoredNetwork(scanResultConnected))
                .thenReturn(null);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */);

        assertThat(ap.getSpeed()).isEqualTo(fallbackSpeed);
    }
}
