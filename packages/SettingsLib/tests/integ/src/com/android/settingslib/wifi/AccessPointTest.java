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

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkKey;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.settingslib.R;
import com.android.settingslib.utils.ThreadUtils;
import com.android.settingslib.wifi.AccessPoint.Speed;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AccessPointTest {
    private static final String TEST_SSID = "\"test_ssid\"";
    private static final String ROAMING_SSID = "\"roaming_ssid\"";
    private static final String OSU_FRIENDLY_NAME = "osu_friendly_name";

    private ArrayList<ScanResult> mScanResults;
    private ArrayList<ScanResult> mRoamingScans;

    private static final RssiCurve FAST_BADGE_CURVE =
            new RssiCurve(-150, 10, new byte[]{Speed.FAST});
    public static final String TEST_BSSID = "00:00:00:00:00:00";
    private static final long MAX_SCORE_CACHE_AGE_MILLIS =
            20 * DateUtils.MINUTE_IN_MILLIS;

    private Context mContext;
    private int mMaxSignalLevel;
    private WifiInfo mWifiInfo;
    @Mock private Context mMockContext;
    @Mock private WifiManager mMockWifiManager;
    @Mock private RssiCurve mockBadgeCurve;
    @Mock private WifiNetworkScoreCache mockWifiNetworkScoreCache;
    @Mock private AccessPoint.AccessPointListener mMockAccessPointListener;
    @Mock private WifiManager.ActionListener mMockConnectListener;
    private static final int NETWORK_ID = 123;
    private static final int DEFAULT_RSSI = -55;

    private ScanResult createScanResult(String ssid, String bssid, int rssi) {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.level = rssi;
        scanResult.BSSID = bssid;
        scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
        scanResult.capabilities = "";
        return scanResult;
    }

    private OsuProvider createOsuProvider() {
        Map<String, String> friendlyNames = new HashMap<>();
        friendlyNames.put("en", OSU_FRIENDLY_NAME);
        return new OsuProvider((WifiSsid) null, friendlyNames, null, null, null, null);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getTargetContext();
        mMaxSignalLevel = mContext.getSystemService(WifiManager.class).getMaxSignalLevel();
        mWifiInfo = new WifiInfo();
        mWifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(TEST_SSID));
        mWifiInfo.setBSSID(TEST_BSSID);
        mScanResults = buildScanResultCache(TEST_SSID);
        mRoamingScans = buildScanResultCache(ROAMING_SSID);
        WifiTracker.sVerboseLogging = false;
    }

    @Test
    public void testSsidIsSpannableString_returnFalse() {
        final Bundle bundle = new Bundle();
        bundle.putString("key_ssid", TEST_SSID);
        final AccessPoint ap = new AccessPoint(InstrumentationRegistry.getTargetContext(), bundle);
        final CharSequence ssid = ap.getSsid();

        assertThat(ssid instanceof SpannableString).isFalse();
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
        final int highLevel = mMaxSignalLevel;
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
                .setLevel(mMaxSignalLevel).build();
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

        ap.setScanResults(Collections.singletonList(scanResult));

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
        ap.update(mockWifiNetworkScoreCache, false /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

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

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

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

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

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

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

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

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        assertThat(ap.getSpeed()).isEqualTo(AccessPoint.Speed.SLOW);
        assertThat(ap.getSpeedLabel())
                .isEqualTo(mContext.getString(R.string.speed_label_slow));
    }

    @Test
    public void testSummaryString_showsSpeedLabel() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.VERY_FAST);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        assertThat(ap.getSummary()).isEqualTo(mContext.getString(R.string.speed_label_very_fast));
    }

    @Test
    public void testSummaryString_concatenatesSpeedLabel() {
        AccessPoint ap = createAccessPointWithScanResultCache();
        ap.update(new WifiConfiguration());

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.VERY_FAST);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        String expectedString = mContext.getString(R.string.speed_label_very_fast) + " / "
                + mContext.getString(R.string.wifi_remembered);
        assertThat(ap.getSummary()).isEqualTo(expectedString);
    }

    @Test
    public void testSummaryString_showsWrongPasswordLabel() {
        WifiConfiguration configuration = spy(createWifiConfiguration());
        WifiConfiguration.NetworkSelectionStatus status =
                mock(WifiConfiguration.NetworkSelectionStatus.class);
        when(configuration.getNetworkSelectionStatus()).thenReturn(status);
        when(status.getNetworkSelectionStatus()).thenReturn(
                WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED);
        when(status.getNetworkSelectionDisableReason()).thenReturn(
                WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD);
        AccessPoint ap = new AccessPoint(mContext, configuration);

        assertThat(ap.getSummary()).isEqualTo(mContext.getString(
                R.string.wifi_check_password_try_again));
    }

    @Test
    public void testSummaryString_showsDisconnected() {
        AccessPoint ap = createAccessPointWithScanResultCache();
        ap.update(new WifiConfiguration());

        assertThat(ap.getSettingsSummary(true /*convertSavedAsDisconnected*/))
                .isEqualTo(mContext.getString(R.string.wifi_disconnected));
    }

    @Test
    public void testSummaryString_concatenatedMeteredAndDisconnected() {
        AccessPoint ap = createAccessPointWithScanResultCache();
        WifiConfiguration config = new WifiConfiguration();
        config.meteredHint = true;
        ap.update(config);

        String expectedString =
                mContext.getResources().getString(R.string.preference_summary_default_combination,
                        mContext.getString(R.string.wifi_metered_label),
                        mContext.getString(R.string.wifi_disconnected));
        assertThat(ap.getSettingsSummary(true /*convertSavedAsDisconnected*/))
                .isEqualTo(expectedString);
    }

    @Test
    public void testSummaryString_showsConnectedViaSuggestionOrSpecifierApp() throws Exception {
        final int rssi = -55;
        final String appPackageName = "com.test.app";
        final CharSequence appLabel = "Test App";
        final String connectedViaAppResourceString = "Connected via ";

        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(TEST_SSID));
        wifiInfo.setEphemeral(true);
        wifiInfo.setRequestingPackageName(appPackageName);
        wifiInfo.setRssi(rssi);

        Context context = mock(Context.class);
        Resources resources = mock(Resources.class);
        PackageManager packageManager = mock(PackageManager.class);
        ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(context.getResources()).thenReturn(resources);
        when(resources.getString(R.string.connected_via_app, appLabel))
                .thenReturn(connectedViaAppResourceString + appLabel.toString());
        when(packageManager.getApplicationInfoAsUser(eq(appPackageName), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        when(applicationInfo.loadLabel(packageManager)).thenReturn(appLabel);
        when(context.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        when(mMockWifiManager.calculateSignalLevel(rssi)).thenReturn(4);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");

        AccessPoint ap = new TestAccessPointBuilder(context)
                .setSsid(TEST_SSID)
                .setNetworkInfo(networkInfo)
                .setRssi(rssi)
                .setSecurity(AccessPoint.SECURITY_NONE)
                .setWifiInfo(wifiInfo)
                .build();
        assertThat(ap.getSummary()).isEqualTo("Connected via Test App");
    }

    private ScoredNetwork buildScoredNetworkWithMockBadgeCurve() {
        return buildScoredNetworkWithGivenBadgeCurve(mockBadgeCurve);
    }

    private ScoredNetwork buildScoredNetworkWithGivenBadgeCurve(RssiCurve badgeCurve) {
        Bundle attr1 = new Bundle();
        attr1.putParcelable(ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE, badgeCurve);
        return new ScoredNetwork(
                new NetworkKey(new WifiKey(TEST_SSID, TEST_BSSID)),
                badgeCurve,
                false /* meteredHint */,
                attr1);
    }

    private AccessPoint createAccessPointWithScanResultCache() {
        Bundle bundle = new Bundle();
        bundle.putParcelableArray(
                AccessPoint.KEY_SCANRESULTS,
                mScanResults.toArray(new Parcelable[mScanResults.size()]));
        return new AccessPoint(mContext, bundle);
    }

    private ArrayList<ScanResult> buildScanResultCache(String ssid) {
        ArrayList<ScanResult> scanResults = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ScanResult scanResult = createScanResult(ssid, "bssid-" + i, i);
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

    private AccessPoint createApWithFastTimestampedScoredNetworkCache(
            long elapsedTimeMillis) {
        TimestampedScoredNetwork recentScore = new TimestampedScoredNetwork(
                buildScoredNetworkWithGivenBadgeCurve(FAST_BADGE_CURVE),
                elapsedTimeMillis);
        return new TestAccessPointBuilder(mContext)
                .setSsid(TEST_SSID)
                .setScoredNetworkCache(
                        new ArrayList<>(Arrays.asList(recentScore)))
                .build();
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

        for (int i = 0; i <= mMaxSignalLevel; i++) {
            testAp = new TestAccessPointBuilder(mContext).setLevel(i).build();
            assertThat(testAp.getLevel()).isEqualTo(i);
        }

        // numbers larger than the max level should be set to max
        testAp = new TestAccessPointBuilder(mContext).setLevel(mMaxSignalLevel + 1).build();
        assertThat(testAp.getLevel()).isEqualTo(mMaxSignalLevel);

        // numbers less than 0 should give level 0
        testAp = new TestAccessPointBuilder(mContext).setLevel(-100).build();
        assertThat(testAp.getLevel()).isEqualTo(0);
    }

    @Test
    public void testBuilder_settingReachableAfterLevelDoesNotAffectLevel() {
        int level = 1;
        assertThat(level).isLessThan(mMaxSignalLevel);

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
        assertThat(ap.getTitle()).isEqualTo(providerFriendlyName);
    }

    // This method doesn't copy mIsFailover, mIsAvailable and mIsRoaming because NetworkInfo
    // doesn't expose those three set methods. But that's fine since the tests don't use those three
    // variables.
    private NetworkInfo copyNetworkInfo(NetworkInfo ni) {
        final NetworkInfo copy = new NetworkInfo(ni.getType(), ni.getSubtype(), ni.getTypeName(),
                ni.getSubtypeName());
        copy.setDetailedState(ni.getDetailedState(), ni.getReason(), ni.getExtraInfo());
        return copy;
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

        NetworkInfo newInfo = copyNetworkInfo(networkInfo);
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

        NetworkInfo newInfo = copyNetworkInfo(networkInfo); // same values
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

        NetworkInfo newInfo = copyNetworkInfo(networkInfo); // same values
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

        NetworkInfo newInfo = copyNetworkInfo(networkInfo); // same values
        wifiInfo.setRssi(WifiInfo.INVALID_RSSI);
        assertThat(ap.update(config, wifiInfo, newInfo)).isFalse();
    }
    @Test
    public void testUpdateWithConfigChangeOnly_returnsFalseButInvokesListener()
            throws InterruptedException {
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = NETWORK_ID;
        config.numNoInternetAccessReports = 1;

        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(NETWORK_ID);
        wifiInfo.setRssi(DEFAULT_RSSI);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(NETWORK_ID)
                .setRssi(DEFAULT_RSSI)
                .setWifiInfo(wifiInfo)
                .build();

        AccessPoint.AccessPointListener mockListener = mock(AccessPoint.AccessPointListener.class);
        ap.setListener(mockListener);
        WifiConfiguration newConfig = new WifiConfiguration(config);
        config.validatedInternetAccess = true;

        assertThat(ap.update(newConfig, wifiInfo, networkInfo)).isFalse();

        // Wait for MainHandler to process callback
        CountDownLatch latch = new CountDownLatch(1);
        ThreadUtils.postOnMainThread(latch::countDown);

        latch.await();
        verify(mockListener).onAccessPointChanged(ap);
    }

    @Test
    public void testConnectionInfo_doesNotThrowNPE_ifListenerIsNulledWhileAwaitingExecution()
            throws InterruptedException {
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = NETWORK_ID;
        config.numNoInternetAccessReports = 1;

        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(NETWORK_ID);
        wifiInfo.setRssi(DEFAULT_RSSI);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(NETWORK_ID)
                .setRssi(DEFAULT_RSSI)
                .setWifiInfo(wifiInfo)
                .build();

        WifiConfiguration newConfig = new WifiConfiguration(config);
        config.validatedInternetAccess = true;

        performGivenUpdateAndThenNullListenerBeforeResumingMainHandlerExecution(
                ap, () -> assertThat(ap.update(newConfig, wifiInfo, networkInfo)).isFalse());

    }

    private void performGivenUpdateAndThenNullListenerBeforeResumingMainHandlerExecution(
            AccessPoint ap, Runnable r) {
        AccessPoint.AccessPointListener mockListener = mock(AccessPoint.AccessPointListener.class);
        ap.setListener(mockListener);

        // Put a latch on the MainHandler to prevent the callback from being invoked instantly
        CountDownLatch latch1 = new CountDownLatch(1);
        ThreadUtils.postOnMainThread(() -> {
            try{
                latch1.await();
            } catch (InterruptedException e) {
                fail("Interruped Exception thrown while awaiting latch countdown");
            }
        });

        r.run();

        ap.setListener(null);
        latch1.countDown();

        // The second latch ensures the previously posted listener invocation has processed on the
        // main thread.
        CountDownLatch latch2 = new CountDownLatch(1);
        ThreadUtils.postOnMainThread(latch2::countDown);

        try{
            latch2.await();
        } catch (InterruptedException e) {
            fail("Interruped Exception thrown while awaiting latch countdown");
        }
    }

    @Test
    public void testUpdateScanResults_doesNotThrowNPE_ifListenerIsNulledWhileAwaitingExecution()
            throws InterruptedException {
        String ssid = "ssid";
        int newRssi = -80;
        AccessPoint ap = new TestAccessPointBuilder(mContext).setSsid(ssid).build();

        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.level = newRssi;
        scanResult.BSSID = "bssid";
        scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
        scanResult.capabilities = "";

        performGivenUpdateAndThenNullListenerBeforeResumingMainHandlerExecution(
                ap, () -> ap.setScanResults(Collections.singletonList(scanResult)));
    }

    @Test
    public void testUpdateConfig_doesNotThrowNPE_ifListenerIsNulledWhileAwaitingExecution()
            throws InterruptedException {
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = NETWORK_ID;
        config.numNoInternetAccessReports = 1;

        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(NETWORK_ID);
        wifiInfo.setRssi(DEFAULT_RSSI);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(NETWORK_ID)
                .setRssi(DEFAULT_RSSI)
                .setWifiInfo(wifiInfo)
                .build();

        WifiConfiguration newConfig = new WifiConfiguration(config);
        config.validatedInternetAccess = true;

        performGivenUpdateAndThenNullListenerBeforeResumingMainHandlerExecution(
                ap, () -> ap.update(newConfig));
    }

    @Test
    public void testUpdateWithNullWifiConfiguration_doesNotThrowNPE() {
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = NETWORK_ID;
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(NETWORK_ID);
        wifiInfo.setRssi(DEFAULT_RSSI);

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTING, "", "");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setNetworkInfo(networkInfo)
                .setNetworkId(NETWORK_ID)
                .setRssi(DEFAULT_RSSI)
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
        when(mockWifiNetworkScoreCache.getScoredNetwork(mScanResults.get(0)))
                .thenReturn(buildScoredNetworkWithGivenBadgeCurve(badgeCurve1));
        int speed2 = Speed.VERY_FAST;
        RssiCurve badgeCurve2 = mock(RssiCurve.class);
        when(badgeCurve2.lookupScore(anyInt())).thenReturn((byte) speed2);
        when(mockWifiNetworkScoreCache.getScoredNetwork(mScanResults.get(1)))
                .thenReturn(buildScoredNetworkWithGivenBadgeCurve(badgeCurve2));

        int expectedSpeed = (speed1 + speed2) / 2;

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        assertThat(ap.getSpeed()).isEqualTo(expectedSpeed);
    }

    @Test
    public void testSpeedLabelAverageIgnoresNoSpeedScores() {
        AccessPoint ap = createAccessPointWithScanResultCache();

        int speed1 = Speed.VERY_FAST;
        RssiCurve badgeCurve1 = mock(RssiCurve.class);
        when(badgeCurve1.lookupScore(anyInt())).thenReturn((byte) speed1);
        when(mockWifiNetworkScoreCache.getScoredNetwork(mScanResults.get(0)))
                .thenReturn(buildScoredNetworkWithGivenBadgeCurve(badgeCurve1));
        int speed2 = Speed.NONE;
        RssiCurve badgeCurve2 = mock(RssiCurve.class);
        when(badgeCurve2.lookupScore(anyInt())).thenReturn((byte) speed2);
        when(mockWifiNetworkScoreCache.getScoredNetwork(mScanResults.get(1)))
                .thenReturn(buildScoredNetworkWithGivenBadgeCurve(badgeCurve2));

        ap.update(
            mockWifiNetworkScoreCache, true /* scoringUiEnabled */, MAX_SCORE_CACHE_AGE_MILLIS);

        assertThat(ap.getSpeed()).isEqualTo(speed1);
    }

    @Test
    public void testSpeedLabelFallbackScoreIgnoresNullCurves() {
        String bssid = "00:00:00:00:00:00";

        WifiInfo info = new WifiInfo();
        info.setRssi(DEFAULT_RSSI);
        info.setSSID(WifiSsid.createFromAsciiEncoded(TEST_SSID));
        info.setBSSID(bssid);
        info.setNetworkId(NETWORK_ID);

        ArrayList<ScanResult> scanResults = new ArrayList<>();
        ScanResult scanResultUnconnected =
                createScanResult(TEST_SSID, "11:11:11:11:11:11", DEFAULT_RSSI);
        scanResults.add(scanResultUnconnected);

        ScanResult scanResultConnected =
                createScanResult(TEST_SSID, bssid, DEFAULT_RSSI);
        scanResults.add(scanResultConnected);

        AccessPoint ap =
                new TestAccessPointBuilder(mContext)
                        .setActive(true)
                        .setNetworkId(NETWORK_ID)
                        .setSsid(TEST_SSID)
                        .setScanResults(scanResults)
                        .setWifiInfo(info)
                        .build();

        int fallbackSpeed = Speed.SLOW;
        when(mockWifiNetworkScoreCache.getScoredNetwork(scanResultUnconnected))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) fallbackSpeed);

        when(mockWifiNetworkScoreCache.getScoredNetwork(scanResultConnected))
                .thenReturn(null);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        assertThat(ap.getSpeed()).isEqualTo(fallbackSpeed);
    }

    @Test
    public void testScoredNetworkCacheBundling() {
        long timeMillis = SystemClock.elapsedRealtime();
        AccessPoint ap = createApWithFastTimestampedScoredNetworkCache(timeMillis);
        Bundle bundle = new Bundle();
        ap.saveWifiState(bundle);

        ArrayList<TimestampedScoredNetwork> list =
                bundle.getParcelableArrayList(AccessPoint.KEY_SCOREDNETWORKCACHE);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getUpdatedTimestampMillis()).isEqualTo(timeMillis);

        RssiCurve curve = list.get(0).getScore().attributes.getParcelable(
                ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE);
        assertThat(curve).isEqualTo(FAST_BADGE_CURVE);
    }

    @Test
    public void testRecentNetworkScoresAreUsedForSpeedLabelGeneration() {
        AccessPoint ap =
                createApWithFastTimestampedScoredNetworkCache(SystemClock.elapsedRealtime());

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        assertThat(ap.getSpeed()).isEqualTo(Speed.FAST);
    }

    @Test
    public void testNetworkScoresAreUsedForSpeedLabelGenerationWhenWithinAgeRange() {
        long withinRangeTimeMillis =
                SystemClock.elapsedRealtime() - (MAX_SCORE_CACHE_AGE_MILLIS - 10000);
        AccessPoint ap =
                createApWithFastTimestampedScoredNetworkCache(withinRangeTimeMillis);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        assertThat(ap.getSpeed()).isEqualTo(Speed.FAST);
    }

    @Test
    public void testOldNetworkScoresAreNotUsedForSpeedLabelGeneration() {
        long tooOldTimeMillis =
                SystemClock.elapsedRealtime() - (MAX_SCORE_CACHE_AGE_MILLIS + 1);
        AccessPoint ap =
                createApWithFastTimestampedScoredNetworkCache(tooOldTimeMillis);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        assertThat(ap.getSpeed()).isEqualTo(Speed.NONE);
    }

    @Test
    public void testUpdateScoresRefreshesScoredNetworkCacheTimestamps () {
        long tooOldTimeMillis =
                SystemClock.elapsedRealtime() - (MAX_SCORE_CACHE_AGE_MILLIS + 1);

        ScoredNetwork scoredNetwork = buildScoredNetworkWithGivenBadgeCurve(FAST_BADGE_CURVE);
        TimestampedScoredNetwork recentScore = new TimestampedScoredNetwork(
                scoredNetwork,
                tooOldTimeMillis);
        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setSsid(TEST_SSID)
                .setBssid(TEST_BSSID)
                .setActive(true)
                .setScoredNetworkCache(
                        new ArrayList(Arrays.asList(recentScore)))
                .setScanResults(mScanResults)
                .build();

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(scoredNetwork);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        // Fast should still be returned since cache was updated with recent time
        assertThat(ap.getSpeed()).isEqualTo(Speed.FAST);
    }

    @Test
    public void testUpdateScoresRefreshesScoredNetworkCacheWithNewSpeed () {
        long tooOldTimeMillis =
                SystemClock.elapsedRealtime() - (MAX_SCORE_CACHE_AGE_MILLIS + 1);

        ScoredNetwork scoredNetwork = buildScoredNetworkWithGivenBadgeCurve(FAST_BADGE_CURVE);
        TimestampedScoredNetwork recentScore = new TimestampedScoredNetwork(
                scoredNetwork,
                tooOldTimeMillis);
        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setSsid(TEST_SSID)
                .setBssid(TEST_BSSID)
                .setActive(true)
                .setScoredNetworkCache(
                        new ArrayList(Arrays.asList(recentScore)))
                .setScanResults(mScanResults)
                .build();

        int newSpeed = Speed.MODERATE;
        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithMockBadgeCurve());
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) newSpeed);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);

        // Fast should still be returned since cache was updated with recent time
        assertThat(ap.getSpeed()).isEqualTo(newSpeed);
    }

    /**
     * Verifies that a Passpoint WifiInfo updates the matching Passpoint AP
     */
    @Test
    public void testUpdate_passpointWifiInfo_updatesPasspointAccessPoint() {
        mWifiInfo.setFQDN("fqdn");
        mWifiInfo.setProviderFriendlyName("providerFriendlyName");

        WifiConfiguration spyConfig = spy(new WifiConfiguration());
        when(spyConfig.isPasspoint()).thenReturn(true);
        spyConfig.SSID = TEST_SSID;
        spyConfig.BSSID = TEST_BSSID;
        spyConfig.FQDN = "fqdn";
        spyConfig.providerFriendlyName = "providerFriendlyName";
        AccessPoint passpointAp = new AccessPoint(mContext, spyConfig);

        assertThat(passpointAp.update(null, mWifiInfo, null)).isTrue();
    }

    /**
     * Verifies that a Passpoint WifiInfo does not update a non-Passpoint AP with the same SSID.
     */
    @Test
    public void testUpdate_passpointWifiInfo_doesNotUpdateNonPasspointAccessPoint() {
        mWifiInfo.setFQDN("fqdn");
        mWifiInfo.setProviderFriendlyName("providerFriendlyName");

        AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setSsid(TEST_SSID)
                .setBssid(TEST_BSSID)
                .setScanResults(mScanResults)
                .build();

        assertThat(ap.update(null, mWifiInfo, null)).isFalse();
    }

    /**
     * Verifies that a non-Passpoint WifiInfo does not update a Passpoint AP with the same SSID.
     */
    @Test
    public void testUpdate_nonPasspointWifiInfo_doesNotUpdatePasspointAccessPoint() {
        WifiConfiguration spyConfig = spy(new WifiConfiguration());
        when(spyConfig.isPasspoint()).thenReturn(true);
        spyConfig.SSID = TEST_SSID;
        spyConfig.BSSID = TEST_BSSID;
        spyConfig.FQDN = "fqdn";
        spyConfig.providerFriendlyName = "providerFriendlyName";
        AccessPoint passpointAp = new AccessPoint(mContext, spyConfig);

        assertThat(passpointAp.update(null, mWifiInfo, null)).isFalse();
    }

    /**
     * Verifies that an AccessPoint's getKey() is consistent with the overloaded static getKey().
     */
    @Test
    public void testGetKey_matchesKeysCorrectly() {
        AccessPoint ap = new AccessPoint(mContext, mScanResults);
        assertThat(ap.getKey()).isEqualTo(AccessPoint.getKey(mContext, mScanResults.get(0)));

        WifiConfiguration spyConfig = spy(new WifiConfiguration());
        when(spyConfig.isPasspoint()).thenReturn(true);
        spyConfig.FQDN = "fqdn";
        AccessPoint passpointAp = new AccessPoint(mContext, spyConfig, mScanResults, null);
        assertThat(passpointAp.getKey()).isEqualTo(AccessPoint.getKey(spyConfig));

        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("fqdn");
        homeSp.setFriendlyName("Test Provider");
        passpointConfig.setHomeSp(homeSp);
        AccessPoint passpointConfigAp = new AccessPoint(mContext, passpointConfig);
        assertThat(passpointConfigAp.getKey()).isEqualTo(AccessPoint.getKey("fqdn"));

        OsuProvider provider = createOsuProvider();
        AccessPoint osuAp = new AccessPoint(mContext, provider, mScanResults);
        assertThat(osuAp.getKey()).isEqualTo(AccessPoint.getKey(provider));
    }

    /**
     * Test that getKey returns a key of SAE type for a PSK/SAE transition mode ScanResult.
     */
    @Test
    public void testGetKey_supportSaeTransitionMode_shouldGetSaeKey() {
        ScanResult scanResult = createScanResult(TEST_SSID, TEST_BSSID, DEFAULT_RSSI);
        scanResult.capabilities =
                "[WPA2-FT/PSK-CCMP][RSN-FT/PSK+PSK-SHA256+SAE+FT/SAE-CCMP][ESS][WPS]";
        when(mMockWifiManager.isWpa3SaeSupported()).thenReturn(true);
        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        StringBuilder key = new StringBuilder();
        key.append(AccessPoint.KEY_PREFIX_AP);
        key.append(TEST_SSID);
        key.append(',');
        key.append(AccessPoint.SECURITY_SAE);

        assertThat(AccessPoint.getKey(mMockContext, scanResult)).isEqualTo(key.toString());
    }

    /**
     * Test that getKey returns a key of PSK type for a PSK/SAE transition mode ScanResult.
     */
    @Test
    public void testGetKey_notSupportSaeTransitionMode_shouldGetPskKey() {
        ScanResult scanResult = createScanResult(TEST_SSID, TEST_BSSID, DEFAULT_RSSI);
        scanResult.capabilities =
                "[WPA2-FT/PSK-CCMP][RSN-FT/PSK+PSK-SHA256+SAE+FT/SAE-CCMP][ESS][WPS]";
        when(mMockWifiManager.isWpa3SaeSupported()).thenReturn(false);
        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        StringBuilder key = new StringBuilder();
        key.append(AccessPoint.KEY_PREFIX_AP);
        key.append(TEST_SSID);
        key.append(',');
        key.append(AccessPoint.SECURITY_PSK);

        assertThat(AccessPoint.getKey(mMockContext, scanResult)).isEqualTo(key.toString());
    }

    /**
     * Verifies that the Passpoint AccessPoint constructor creates AccessPoints whose isPasspoint()
     * returns true.
     */
    @Test
    public void testPasspointAccessPointConstructor_createdAccessPointIsPasspoint() {
        WifiConfiguration spyConfig = spy(new WifiConfiguration());
        when(spyConfig.isPasspoint()).thenReturn(true);
        AccessPoint passpointAccessPoint = new AccessPoint(mContext, spyConfig,
                mScanResults, mRoamingScans);

        assertThat(passpointAccessPoint.isPasspoint()).isTrue();
    }

    /**
     * Verifies that Passpoint AccessPoints set their config's SSID to the home scans', and to the
     * roaming scans' if no home scans are available.
     */
    @Test
    public void testSetScanResultsPasspoint_differentiatesHomeAndRoaming() {
        WifiConfiguration spyConfig = spy(new WifiConfiguration());
        when(spyConfig.isPasspoint()).thenReturn(true);
        AccessPoint passpointAccessPoint = new AccessPoint(mContext, spyConfig,
                mScanResults, mRoamingScans);
        assertThat(AccessPoint.removeDoubleQuotes(spyConfig.SSID)).isEqualTo(TEST_SSID);

        passpointAccessPoint.setScanResultsPasspoint(null, mRoamingScans);
        assertThat(AccessPoint.removeDoubleQuotes(spyConfig.SSID)).isEqualTo(ROAMING_SSID);

        passpointAccessPoint.setScanResultsPasspoint(mScanResults, null);
        assertThat(AccessPoint.removeDoubleQuotes(spyConfig.SSID)).isEqualTo(TEST_SSID);
    }

    /**
     * Verifies that getScanResults returns both home and roaming scans.
     */
    @Test
    public void testGetScanResults_showsHomeAndRoamingScans() {
        WifiConfiguration spyConfig = spy(new WifiConfiguration());
        when(spyConfig.isPasspoint()).thenReturn(true);
        AccessPoint passpointAccessPoint = new AccessPoint(mContext, spyConfig,
                mScanResults, mRoamingScans);
        Set<ScanResult> fullSet = new ArraySet<>();
        fullSet.addAll(mScanResults);
        fullSet.addAll(mRoamingScans);
        assertThat(passpointAccessPoint.getScanResults()).isEqualTo(fullSet);
    }

    /**
     * Verifies that the Passpoint AccessPoint takes the ssid of the strongest scan result.
     */
    @Test
    public void testPasspointAccessPoint_setsBestSsid() {
        WifiConfiguration spyConfig = spy(new WifiConfiguration());
        when(spyConfig.isPasspoint()).thenReturn(true);

        String badSsid = "badSsid";
        String goodSsid = "goodSsid";
        String bestSsid = "bestSsid";
        ScanResult badScanResult = createScanResult(badSsid, TEST_BSSID, -100);
        ScanResult goodScanResult = createScanResult(goodSsid, TEST_BSSID, -10);
        ScanResult bestScanResult = createScanResult(bestSsid, TEST_BSSID, -1);

        AccessPoint passpointAccessPoint = new AccessPoint(mContext, spyConfig,
                Arrays.asList(badScanResult, goodScanResult), null);
        assertThat(passpointAccessPoint.getConfig().SSID)
                .isEqualTo(AccessPoint.convertToQuotedString(goodSsid));
        passpointAccessPoint.setScanResultsPasspoint(
                Arrays.asList(badScanResult, goodScanResult, bestScanResult), null);
        assertThat(passpointAccessPoint.getConfig().SSID)
                .isEqualTo(AccessPoint.convertToQuotedString(bestSsid));
    }

    /**
     * Verifies that the OSU AccessPoint constructor creates AccessPoints whose isOsuProvider()
     * returns true.
     */
    @Test
    public void testOsuAccessPointConstructor_createdAccessPointIsOsuProvider() {
        AccessPoint osuAccessPoint = new AccessPoint(mContext, createOsuProvider(),
                mScanResults);

        assertThat(osuAccessPoint.isOsuProvider()).isTrue();
    }

    /**
     * Verifies that the summary of an OSU entry only shows the tap_to_sign_up string.
     */
    @Test
    public void testOsuAccessPointSummary_showsTapToSignUp() {
        AccessPoint osuAccessPoint = new AccessPoint(mContext, createOsuProvider(),
                mScanResults);

        assertThat(osuAccessPoint.getSummary())
                .isEqualTo(mContext.getString(R.string.tap_to_sign_up));
    }

    /**
     * Verifies that the summary of an OSU entry updates based on provisioning status.
     */
    @Test
    public void testOsuAccessPointSummary_showsProvisioningUpdates() {
        OsuProvider provider = createOsuProvider();
        Context spyContext = spy(new ContextWrapper(mContext));
        when(spyContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        Map<OsuProvider, PasspointConfiguration> osuProviderConfigMap = new HashMap<>();
        osuProviderConfigMap.put(provider, null);
        when(mMockWifiManager.getMatchingPasspointConfigsForOsuProviders(
                Collections.singleton(provider))).thenReturn(osuProviderConfigMap);
        AccessPoint osuAccessPoint = new AccessPoint(spyContext, provider,
                mScanResults);

        osuAccessPoint.setListener(mMockAccessPointListener);

        AccessPoint.AccessPointProvisioningCallback provisioningCallback =
                osuAccessPoint.new AccessPointProvisioningCallback();

        int[] openingProviderStatuses = {
                ProvisioningCallback.OSU_STATUS_AP_CONNECTING,
                ProvisioningCallback.OSU_STATUS_AP_CONNECTED,
                ProvisioningCallback.OSU_STATUS_SERVER_CONNECTING,
                ProvisioningCallback.OSU_STATUS_SERVER_VALIDATED,
                ProvisioningCallback.OSU_STATUS_SERVER_CONNECTED,
                ProvisioningCallback.OSU_STATUS_INIT_SOAP_EXCHANGE,
                ProvisioningCallback.OSU_STATUS_WAITING_FOR_REDIRECT_RESPONSE
        };
        int[] completingSignUpStatuses = {
                ProvisioningCallback.OSU_STATUS_REDIRECT_RESPONSE_RECEIVED,
                ProvisioningCallback.OSU_STATUS_SECOND_SOAP_EXCHANGE,
                ProvisioningCallback.OSU_STATUS_THIRD_SOAP_EXCHANGE,
                ProvisioningCallback.OSU_STATUS_RETRIEVING_TRUST_ROOT_CERTS,
        };

        for (int status : openingProviderStatuses) {
            provisioningCallback.onProvisioningStatus(status);
            assertThat(osuAccessPoint.getSummary())
                    .isEqualTo(String.format(mContext.getString(R.string.osu_opening_provider),
                            OSU_FRIENDLY_NAME));
        }

        provisioningCallback.onProvisioningFailure(0);
        assertThat(osuAccessPoint.getSummary())
                .isEqualTo(mContext.getString(R.string.osu_connect_failed));

        for (int status : completingSignUpStatuses) {
            provisioningCallback.onProvisioningStatus(status);
            assertThat(osuAccessPoint.getSummary())
                    .isEqualTo(mContext.getString(R.string.osu_completing_sign_up));
        }

        provisioningCallback.onProvisioningFailure(0);
        assertThat(osuAccessPoint.getSummary())
                .isEqualTo(mContext.getString(R.string.osu_sign_up_failed));

        provisioningCallback.onProvisioningComplete();
        assertThat(osuAccessPoint.getSummary())
                .isEqualTo(mContext.getString(R.string.osu_sign_up_complete));
    }

    /**
     * Verifies that after provisioning through an OSU provider, we connect to the freshly
     * provisioned network.
     */
    @Test
    public void testOsuAccessPoint_connectsAfterProvisioning() {
        // Set up mock for WifiManager.getAllMatchingWifiConfigs
        WifiConfiguration config = new WifiConfiguration();
        config.FQDN = "fqdn";
        Map<Integer, List<ScanResult>> scanMapping = new HashMap<>();
        scanMapping.put(WifiManager.PASSPOINT_HOME_NETWORK, mScanResults);
        Pair<WifiConfiguration, Map<Integer, List<ScanResult>>> configMapPair =
                new Pair<>(config, scanMapping);
        List<Pair<WifiConfiguration, Map<Integer, List<ScanResult>>>> matchingWifiConfig =
                new ArrayList<>();
        matchingWifiConfig.add(configMapPair);
        when(mMockWifiManager.getAllMatchingWifiConfigs(any())).thenReturn(matchingWifiConfig);

        // Set up mock for WifiManager.getMatchingPasspointConfigsForOsuProviders
        OsuProvider provider = createOsuProvider();
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("fqdn");
        homeSp.setFriendlyName("Test Provider");
        passpointConfig.setHomeSp(homeSp);
        Map<OsuProvider, PasspointConfiguration> osuProviderConfigMap = new HashMap<>();
        osuProviderConfigMap.put(provider, passpointConfig);
        when(mMockWifiManager
                .getMatchingPasspointConfigsForOsuProviders(Collections.singleton(provider)))
                .thenReturn(osuProviderConfigMap);

        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);

        AccessPoint osuAccessPoint = new AccessPoint(mMockContext, provider, mScanResults);
        osuAccessPoint.setListener(mMockAccessPointListener);

        AccessPoint.AccessPointProvisioningCallback provisioningCallback =
                osuAccessPoint.new AccessPointProvisioningCallback();
        provisioningCallback.onProvisioningComplete();

        verify(mMockWifiManager).connect(any(), any());
    }

    /**
     * Verifies that after provisioning through an OSU provider, we call the connect listener's
     * onFailure() method if we cannot find the network we just provisioned.
     */
    @Test
    public void testOsuAccessPoint_noMatchingConfigsAfterProvisioning_callsOnFailure() {
        // Set up mock for WifiManager.getAllMatchingWifiConfigs
        when(mMockWifiManager.getAllMatchingWifiConfigs(any())).thenReturn(new ArrayList<>());

        // Set up mock for WifiManager.getMatchingPasspointConfigsForOsuProviders
        OsuProvider provider = createOsuProvider();
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("fqdn");
        homeSp.setFriendlyName("Test Provider");
        passpointConfig.setHomeSp(homeSp);
        Map<OsuProvider, PasspointConfiguration> osuProviderConfigMap = new HashMap<>();
        osuProviderConfigMap.put(provider, passpointConfig);
        when(mMockWifiManager
                .getMatchingPasspointConfigsForOsuProviders(Collections.singleton(provider)))
                .thenReturn(osuProviderConfigMap);

        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);

        AccessPoint osuAccessPoint = new AccessPoint(mMockContext, provider, mScanResults);
        osuAccessPoint.setListener(mMockAccessPointListener);
        osuAccessPoint.startOsuProvisioning(mMockConnectListener);

        AccessPoint.AccessPointProvisioningCallback provisioningCallback =
                osuAccessPoint.new AccessPointProvisioningCallback();
        provisioningCallback.onProvisioningComplete();

        verify(mMockConnectListener).onFailure(anyInt());
    }

    /**
     * Verifies that isOpenNetwork returns true for SECURITY_NONE, SECURITY_OWE, and
     * SECURITY_OWE_TRANSITION.
     */
    @Test
    public void testIsOpenNetwork_returnValidResult() {
        final Bundle bundle = new Bundle();
        AccessPoint ap;

        for (int i = 0; i < AccessPoint.SECURITY_MAX_VAL; i++) {
            bundle.putInt("key_security", i);
            ap = new AccessPoint(InstrumentationRegistry.getTargetContext(), bundle);

            if (i == AccessPoint.SECURITY_NONE || i == AccessPoint.SECURITY_OWE) {
                assertThat(ap.isOpenNetwork()).isTrue();
            } else {
                assertThat(ap.isOpenNetwork()).isFalse();
            }
        }
    }

    /**
     * Verifies that matches(AccessPoint other) matches a PSK/SAE transition mode AP to a PSK or a
     * SAE AP.
     */
    @Test
    public void testMatches1_transitionModeApMatchesNotTransitionModeAp_shouldMatchCorrectly() {
        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        when(mMockWifiManager.isWpa3SaeSupported()).thenReturn(true);
        AccessPoint pskSaeTransitionModeAp = getPskSaeTransitionModeAp();

        // Transition mode AP matches a SAE AP.
        AccessPoint saeAccessPoint = new TestAccessPointBuilder(mContext)
                .setSsid(AccessPoint.removeDoubleQuotes(TEST_SSID))
                .setSecurity(AccessPoint.SECURITY_SAE)
                .build();
        assertThat(pskSaeTransitionModeAp.matches(saeAccessPoint)).isTrue();

        // Transition mode AP matches a PSK AP.
        AccessPoint pskAccessPoint = new TestAccessPointBuilder(mContext)
                .setSsid(AccessPoint.removeDoubleQuotes(TEST_SSID))
                .setSecurity(AccessPoint.SECURITY_PSK)
                .build();

        assertThat(pskSaeTransitionModeAp.matches(pskAccessPoint)).isTrue();

        // Transition mode AP does not match a SAE AP if the device does not support SAE.
        when(mMockWifiManager.isWpa3SaeSupported()).thenReturn(false);
        pskSaeTransitionModeAp = getPskSaeTransitionModeAp();
        saeAccessPoint = new TestAccessPointBuilder(mContext)
            .setSsid(AccessPoint.removeDoubleQuotes(TEST_SSID))
            .setSecurity(AccessPoint.SECURITY_SAE)
            .build();

        assertThat(pskSaeTransitionModeAp.matches(saeAccessPoint)).isFalse();
    }

    /**
     * Verifies that matches(WifiConfiguration config) matches a PSK/SAE transition mode AP to a PSK
     * or a SAE WifiConfiguration.
     */
    @Test
    public void testMatches2_transitionModeApMatchesNotTransitionModeAp_shouldMatchCorrectly() {
        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        when(mMockWifiManager.isWpa3SaeSupported()).thenReturn(true);
        AccessPoint pskSaeTransitionModeAp = getPskSaeTransitionModeAp();

        // Transition mode AP matches a SAE WifiConfiguration.
        WifiConfiguration saeConfig = new WifiConfiguration();
        saeConfig.SSID = TEST_SSID;
        saeConfig.allowedKeyManagement.set(KeyMgmt.SAE);

        assertThat(pskSaeTransitionModeAp.matches(saeConfig)).isTrue();

        // Transition mode AP matches a PSK WifiConfiguration.
        WifiConfiguration pskConfig = new WifiConfiguration();
        pskConfig.SSID = TEST_SSID;
        pskConfig.allowedKeyManagement.set(KeyMgmt.WPA_PSK);

        assertThat(pskSaeTransitionModeAp.matches(pskConfig)).isTrue();

        // Transition mode AP does not matches a SAE WifiConfiguration if the device does not
        // support SAE.
        when(mMockWifiManager.isWpa3SaeSupported()).thenReturn(false);
        pskSaeTransitionModeAp = getPskSaeTransitionModeAp();

        assertThat(pskSaeTransitionModeAp.matches(saeConfig)).isFalse();
    }

    /**
     * Verifies that matches(ScanResult scanResult) matches a PSK/SAE transition mode AP to a PSK
     * or a SAE ScanResult.
     */
    @Test
    public void testMatches3_transitionModeApMatchesNotTransitionModeAp_shouldMatchCorrectly() {
        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        when(mMockWifiManager.isWpa3SaeSupported()).thenReturn(true);
        AccessPoint pskSaeTransitionModeAp = getPskSaeTransitionModeAp();

        // Transition mode AP matches a SAE ScanResult.
        ScanResult saeScanResult = createScanResult(AccessPoint.removeDoubleQuotes(TEST_SSID),
                TEST_BSSID, DEFAULT_RSSI);
        saeScanResult.capabilities = "[SAE-CCMP][ESS][WPS]";

        assertThat(pskSaeTransitionModeAp.matches(saeScanResult)).isTrue();

        // Transition mode AP matches a PSK ScanResult.
        ScanResult pskScanResult = createScanResult(AccessPoint.removeDoubleQuotes(TEST_SSID),
                TEST_BSSID, DEFAULT_RSSI);
        pskScanResult.capabilities = "[RSN-PSK-CCMP][ESS][WPS]";

        assertThat(pskSaeTransitionModeAp.matches(pskScanResult)).isTrue();

        // Transition mode AP does not matches a SAE ScanResult if the device does not support SAE.
        when(mMockWifiManager.isWpa3SaeSupported()).thenReturn(false);
        pskSaeTransitionModeAp = getPskSaeTransitionModeAp();

        assertThat(pskSaeTransitionModeAp.matches(saeScanResult)).isFalse();
    }

    @Test
    public void testGetSecurityString_oweTransitionMode_shouldReturnCorrectly() {
        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        when(mMockWifiManager.isEnhancedOpenSupported()).thenReturn(true);
        AccessPoint oweTransitionModeAp = getOweTransitionModeAp();

        assertThat(oweTransitionModeAp.getSecurityString(true /* concise */))
                .isEqualTo(mContext.getString(R.string.wifi_security_short_none_owe));
        assertThat(oweTransitionModeAp.getSecurityString(false /* concise */))
                .isEqualTo(mContext.getString(R.string.wifi_security_none_owe));
    }

    private AccessPoint getPskSaeTransitionModeAp() {
        ScanResult scanResult = createScanResult(AccessPoint.removeDoubleQuotes(TEST_SSID),
                TEST_BSSID, DEFAULT_RSSI);
        scanResult.capabilities =
                "[WPA2-FT/PSK-CCMP][RSN-FT/PSK+PSK-SHA256+SAE+FT/SAE-CCMP][ESS][WPS]";
        return new TestAccessPointBuilder(mMockContext)
                .setScanResults(new ArrayList<ScanResult>(Arrays.asList(scanResult)))
                .build();
    }

    private AccessPoint getOweTransitionModeAp() {
        ScanResult scanResult = createScanResult(AccessPoint.removeDoubleQuotes(TEST_SSID),
                TEST_BSSID, DEFAULT_RSSI);
        scanResult.capabilities = "[OWE_TRANSITION]";
        return new TestAccessPointBuilder(mContext)
                .setScanResults(new ArrayList<ScanResult>(Arrays.asList(scanResult)))
                .build();
    }

    @Test
    public void testGenerateOpenNetworkConfig_oweNotSupported_shouldGetCorrectSecurity() {
        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        AccessPoint oweAccessPoint = new TestAccessPointBuilder(mMockContext)
                .setSecurity(AccessPoint.SECURITY_OWE).build();
        AccessPoint noneAccessPoint = new TestAccessPointBuilder(mMockContext)
                .setSecurity(AccessPoint.SECURITY_NONE).build();

        oweAccessPoint.generateOpenNetworkConfig();
        noneAccessPoint.generateOpenNetworkConfig();

        assertThat(oweAccessPoint.getConfig().allowedKeyManagement.get(KeyMgmt.NONE)).isFalse();
        assertThat(oweAccessPoint.getConfig().allowedKeyManagement.get(KeyMgmt.OWE)).isTrue();
        assertThat(noneAccessPoint.getConfig().allowedKeyManagement.get(KeyMgmt.NONE)).isTrue();
        assertThat(noneAccessPoint.getConfig().allowedKeyManagement.get(KeyMgmt.OWE)).isFalse();
    }
}
