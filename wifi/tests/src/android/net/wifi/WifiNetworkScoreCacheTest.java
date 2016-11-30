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
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.NetworkKey;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link WifiNetworkScoreCache}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class WifiNetworkScoreCacheTest {

  @Mock public Context mockContext; // isn't used, can be null
  @Mock private RssiCurve mockRssiCurve;

  public static final String SSID = "ssid";
  public static final String FORMATTED_SSID = "\"" + SSID + "\"";
  public static final String BSSID = "AA:AA:AA:AA:AA:AA";

  public static final WifiKey VALID_KEY = new WifiKey(FORMATTED_SSID, BSSID);

  public static final ScanResult VALID_SCAN_RESULT = buildScanResult(SSID, BSSID);

  private ScoredNetwork mValidScoredNetwork;
  private WifiNetworkScoreCache mScoreCache =
      new WifiNetworkScoreCache(mockContext);

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
    mValidScoredNetwork = buildScoredNetwork(VALID_KEY, mockRssiCurve);
    mScoreCache = new WifiNetworkScoreCache(mockContext);
    initializeCacheWithValidScoredNetwork();
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
        new ScoredNetwork(new NetworkKey(VALID_KEY), mockRssiCurve, true /* metered Hint */);
    mScoreCache.updateScores(ImmutableList.of(network));

    assertTrue(mScoreCache.getMeteredHint(VALID_SCAN_RESULT));
  }
}
