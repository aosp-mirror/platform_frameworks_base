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
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.NetworkKey;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkScoreCache;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.ArraySet;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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
        mContext = RuntimeEnvironment.application;
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
