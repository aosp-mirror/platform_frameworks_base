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

package android.net.wifi;

import static org.junit.Assert.*;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.NetworkKey;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.WifiNetworkScoreCache.CacheListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/** Unit tests for {@link WifiNetworkScoreCache}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class WifiNetworkScoreCacheTest {

    public static final String SSID = "ssid";
    public static final String FORMATTED_SSID = "\"" + SSID + "\"";
    public static final String BSSID = "AA:AA:AA:AA:AA:AA";

    public static final WifiKey VALID_KEY = new WifiKey(FORMATTED_SSID, BSSID);

    public static final ScanResult VALID_SCAN_RESULT = buildScanResult(SSID, BSSID);

    @Mock private Context mockApplicationContext;
    @Mock private Context mockContext; // isn't used, can be null
    @Mock private RssiCurve mockRssiCurve;


    private CacheListener mCacheListener;
    private CountDownLatch mLatch;
    private Handler mHandler;
    private List<ScoredNetwork> mUpdatedNetworksCaptor;
    private ScoredNetwork mValidScoredNetwork;
    private WifiNetworkScoreCache mScoreCache;

    private static ScanResult buildScanResult(String ssid, String bssid) {
        return new ScanResult(
                WifiSsid.createFromAsciiEncoded(ssid),
                bssid,
                "" /* caps */,
                0 /* level */,
                0 /* frequency */,
                0 /* tsf */,
                0 /* distCm */,
                0 /* distSdCm*/);
    }

    private static ScoredNetwork buildScoredNetwork(WifiKey key, RssiCurve curve) {
        return new ScoredNetwork(new NetworkKey(key), curve);
    }

    // Called from setup
    private void initializeCacheWithValidScoredNetwork() {
        mScoreCache.updateScores(ImmutableList.of(mValidScoredNetwork));
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mockContext.getApplicationContext()).thenReturn(mockApplicationContext);

        mValidScoredNetwork = buildScoredNetwork(VALID_KEY, mockRssiCurve);
        mScoreCache = new WifiNetworkScoreCache(mockContext);
        initializeCacheWithValidScoredNetwork();

        HandlerThread thread = new HandlerThread("WifiNetworkScoreCacheTest Handler Thread");
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mLatch = new CountDownLatch(1);
        mCacheListener = new CacheListener(mHandler) {
            @Override
            public void networkCacheUpdated(List<ScoredNetwork> updatedNetworks) {
                mUpdatedNetworksCaptor = updatedNetworks;
                mLatch.countDown();
            }
        };
    }


    @Test
    public void isScoredNetworkShouldReturnTrueAfterUpdateScoresIsCalled() {
        assertTrue(mScoreCache.isScoredNetwork(VALID_SCAN_RESULT));
    }

    @Test
    public void isScoredNetworkShouldReturnFalseAfterClearScoresIsCalled() {
        mScoreCache.clearScores();
        assertFalse(mScoreCache.isScoredNetwork(VALID_SCAN_RESULT));
    }

    @Test
    public void updateScoresShouldAddNewNetwork() {
        WifiKey key2 = new WifiKey("\"ssid2\"", BSSID);
        ScoredNetwork network2 = buildScoredNetwork(key2, mockRssiCurve);
        ScanResult result2 = buildScanResult("ssid2", BSSID);

        mScoreCache.updateScores(ImmutableList.of(network2));

        assertTrue(mScoreCache.isScoredNetwork(VALID_SCAN_RESULT));
        assertTrue(mScoreCache.isScoredNetwork(result2));
    }

    @Test
    public void hasScoreCurveShouldReturnTrue() {
        assertTrue(mScoreCache.hasScoreCurve(VALID_SCAN_RESULT));
    }

    @Test
    public void hasScoreCurveShouldReturnFalseWhenNoCachedNetwork() {
        ScanResult unscored = buildScanResult("fake", BSSID);
        assertFalse(mScoreCache.hasScoreCurve(unscored));
    }

    @Test
    public void hasScoreCurveShouldReturnFalseWhenScoredNetworkHasNoCurve() {
        ScoredNetwork noCurve = buildScoredNetwork(VALID_KEY, null /* rssiCurve */);
        mScoreCache.updateScores(ImmutableList.of(noCurve));

        assertFalse(mScoreCache.hasScoreCurve(VALID_SCAN_RESULT));
    }

    @Test
    public void getNetworkScoreShouldReturnScore() {
        final byte score = 50;
        final int rssi = -70;
        ScanResult result = new ScanResult(VALID_SCAN_RESULT);
        result.level = rssi;

        when(mockRssiCurve.lookupScore(rssi)).thenReturn(score);

        assertEquals(score, mScoreCache.getNetworkScore(result));
    }

    @Test
    public void getMeteredHintShouldReturnFalse() {
        assertFalse(mScoreCache.getMeteredHint(VALID_SCAN_RESULT));
    }

    @Test
    public void getMeteredHintShouldReturnTrue() {
        ScoredNetwork network =
                new ScoredNetwork(
                    new NetworkKey(VALID_KEY), mockRssiCurve, true /* metered Hint */);
        mScoreCache.updateScores(ImmutableList.of(network));

        assertTrue(mScoreCache.getMeteredHint(VALID_SCAN_RESULT));
    }

    @Test
    public void updateScoresShouldInvokeCacheListener_networkCacheUpdated() {
        mScoreCache = new WifiNetworkScoreCache(mockContext, mCacheListener);
        initializeCacheWithValidScoredNetwork();

        try {
            mLatch.await(1, TimeUnit.SECONDS); // wait for listener to be executed
        } catch (InterruptedException e) {
            fail("Interrupted Exception while waiting for listener to be invoked.");
        }
        assertEquals("One network should be updated", 1, mUpdatedNetworksCaptor.size());
        assertEquals(mValidScoredNetwork, mUpdatedNetworksCaptor.get(0));
    }
}
