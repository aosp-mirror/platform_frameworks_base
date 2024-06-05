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
package com.android.settingslib.wifi;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.NetworkKey;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.sharedconnectivity.app.NetworkProviderInfo;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.ArraySet;

import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class WifiUtilsTest {
    private static final String TEST_SSID = "\"test_ssid\"";
    private static final String TEST_BSSID = "00:00:00:00:00:00";
    private static final long MAX_SCORE_CACHE_AGE_MILLIS =
            20 * DateUtils.MINUTE_IN_MILLIS;

    private Context mContext;
    @Mock
    private RssiCurve mockBadgeCurve;
    @Mock
    private WifiNetworkScoreCache mockWifiNetworkScoreCache;
    @Mock
    private AccessPoint mAccessPoint;
    @Mock
    WifiConfiguration mWifiConfig;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        when(mContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mock(WifiManager.class));
    }

    @Test
    public void testVerboseSummaryString_showsScanResultSpeedLabel() {
        WifiTracker.sVerboseLogging = true;

        Bundle bundle = new Bundle();
        ArrayList<ScanResult> scanResults = buildScanResultCache();
        bundle.putParcelableArray(AccessPoint.KEY_SCANRESULTS,
                                  scanResults.toArray(new Parcelable[0]));
        AccessPoint ap = new AccessPoint(mContext, bundle);

        when(mockWifiNetworkScoreCache.getScoredNetwork(any(ScanResult.class)))
                .thenReturn(buildScoredNetworkWithGivenBadgeCurve(mockBadgeCurve));
        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) AccessPoint.Speed.VERY_FAST);

        ap.update(mockWifiNetworkScoreCache, true /* scoringUiEnabled */,
                MAX_SCORE_CACHE_AGE_MILLIS);
        String summary = WifiUtils.verboseScanResultSummary(ap, scanResults.get(0), null, 0);

        assertThat(summary.contains(mContext.getString(R.string.speed_label_very_fast))).isTrue();
    }

    @Test
    public void testGetVisibilityStatus_nullResultDoesNotCrash() {
        doReturn(null).when(mAccessPoint).getInfo();
        Set<ScanResult> set = new ArraySet<>();
        set.add(null);
        doReturn(set).when(mAccessPoint).getScanResults();
        WifiUtils.getVisibilityStatus(mAccessPoint);
    }

    @Test
    public void testGetMeteredLabel_returnsCorrectValues() {
        mWifiConfig.meteredHint = true;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NONE;
        assertThat(WifiUtils.getMeteredLabel(mContext, mWifiConfig)).isEqualTo("Metered");

        mWifiConfig.meteredHint = false;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
        assertThat(WifiUtils.getMeteredLabel(mContext, mWifiConfig)).isEqualTo("Metered");

        mWifiConfig.meteredHint = true;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
        assertThat(WifiUtils.getMeteredLabel(mContext, mWifiConfig)).isEqualTo("Metered");

        mWifiConfig.meteredHint = false;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
        assertThat(WifiUtils.getMeteredLabel(mContext, mWifiConfig)).isEqualTo("Unmetered");

        mWifiConfig.meteredHint = true;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
        assertThat(WifiUtils.getMeteredLabel(mContext, mWifiConfig)).isEqualTo("Unmetered");
    }

    @Test
    public void testIsMeteredOverridden_returnsCorrectValues() {
        mWifiConfig.meteredHint = true;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NONE;
        assertThat(WifiUtils.isMeteredOverridden(mWifiConfig)).isFalse();

        mWifiConfig.meteredHint = false;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
        assertThat(WifiUtils.isMeteredOverridden(mWifiConfig)).isTrue();

        mWifiConfig.meteredHint = true;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
        assertThat(WifiUtils.isMeteredOverridden(mWifiConfig)).isTrue();

        mWifiConfig.meteredHint = false;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
        assertThat(WifiUtils.isMeteredOverridden(mWifiConfig)).isTrue();

        mWifiConfig.meteredHint = true;
        mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
        assertThat(WifiUtils.isMeteredOverridden(mWifiConfig)).isTrue();
    }

    @Test
    public void getWifiDialogIntent_returnsCorrectValues() {
        String key = "test_key";

        // Test that connectForCaller is true.
        Intent intent = WifiUtils.getWifiDialogIntent(key, true /* connectForCaller */);

        assertThat(intent.getAction()).isEqualTo(WifiUtils.ACTION_WIFI_DIALOG);
        assertThat(intent.getStringExtra(WifiUtils.EXTRA_CHOSEN_WIFI_ENTRY_KEY)).isEqualTo(key);
        assertThat(intent.getBooleanExtra(WifiUtils.EXTRA_CONNECT_FOR_CALLER, true))
                .isEqualTo(true /* connectForCaller */);

        // Test that connectForCaller is false.
        intent = WifiUtils.getWifiDialogIntent(key, false /* connectForCaller */);

        assertThat(intent.getAction()).isEqualTo(WifiUtils.ACTION_WIFI_DIALOG);
        assertThat(intent.getStringExtra(WifiUtils.EXTRA_CHOSEN_WIFI_ENTRY_KEY)).isEqualTo(key);
        assertThat(intent.getBooleanExtra(WifiUtils.EXTRA_CONNECT_FOR_CALLER, true))
                .isEqualTo(false /* connectForCaller */);
    }

    @Test
    public void getWifiDetailsSettingsIntent_returnsCorrectValues() {
        final String key = "test_key";

        final Intent intent = WifiUtils.getWifiDetailsSettingsIntent(key);

        assertThat(intent.getAction()).isEqualTo(WifiUtils.ACTION_WIFI_DETAILS_SETTINGS);
        final Bundle bundle = intent.getBundleExtra(WifiUtils.EXTRA_SHOW_FRAGMENT_ARGUMENTS);
        assertThat(bundle.getString(WifiUtils.KEY_CHOSEN_WIFIENTRY_KEY)).isEqualTo(key);
    }

    @Test
    public void getInternetIconResource_levelOutOfRange_shouldNotCrash() {
        // Verify that Wi-Fi level is less than the minimum level (0)
        int level = -1;
        WifiUtils.getInternetIconResource(level, false /* noInternet*/);
        WifiUtils.getInternetIconResource(level, true /* noInternet*/);

        // Verify that Wi-Fi level is greater than the maximum level (4)
        level = WifiUtils.WIFI_PIE.length;
        WifiUtils.getInternetIconResource(level, false /* noInternet*/);
        WifiUtils.getInternetIconResource(level, true /* noInternet*/);
    }

    @Test
    public void getHotspotIconResource_deviceTypeUnknown_shouldNotCrash() {
        WifiUtils.getHotspotIconResource(NetworkProviderInfo.DEVICE_TYPE_UNKNOWN);
    }

    @Test
    public void getHotspotIconResource_deviceTypeExists_shouldNotNull() {
        assertThat(WifiUtils.getHotspotIconResource(NetworkProviderInfo.DEVICE_TYPE_PHONE))
                .isNotNull();
        assertThat(WifiUtils.getHotspotIconResource(NetworkProviderInfo.DEVICE_TYPE_TABLET))
                .isNotNull();
        assertThat(WifiUtils.getHotspotIconResource(NetworkProviderInfo.DEVICE_TYPE_LAPTOP))
                .isNotNull();
        assertThat(WifiUtils.getHotspotIconResource(NetworkProviderInfo.DEVICE_TYPE_WATCH))
                .isNotNull();
        assertThat(WifiUtils.getHotspotIconResource(NetworkProviderInfo.DEVICE_TYPE_AUTO))
                .isNotNull();
    }

    @Test
    public void testInternetIconInjector_getIcon_returnsCorrectValues() {
        WifiUtils.InternetIconInjector iconInjector = new WifiUtils.InternetIconInjector(mContext);

        for (int level = 0; level <= 4; level++) {
            iconInjector.getIcon(false /* noInternet */, level);
            verify(mContext).getDrawable(
                    WifiUtils.getInternetIconResource(level, false /* noInternet */));

            iconInjector.getIcon(true /* noInternet */, level);
            verify(mContext).getDrawable(
                    WifiUtils.getInternetIconResource(level, true /* noInternet */));
        }
    }

    private static ArrayList<ScanResult> buildScanResultCache() {
        ArrayList<ScanResult> scanResults = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ScanResult scanResult = createScanResult(TEST_SSID, "bssid-" + i, i);
            scanResults.add(scanResult);
        }
        return scanResults;
    }

    private static ScanResult createScanResult(String ssid, String bssid, int rssi) {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.level = rssi;
        scanResult.BSSID = bssid;
        scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
        scanResult.capabilities = "";
        return scanResult;
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
}
