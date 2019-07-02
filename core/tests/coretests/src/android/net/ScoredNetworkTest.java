/*
 t Copyright (C) 2016 The Android Open Source Project
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

package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Bundle;
import android.os.Parcel;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/** Unit tests for {@link ScoredNetwork}. */
@RunWith(AndroidJUnit4.class)
public class ScoredNetworkTest {

    private static final int RSSI_START = -110;
    private static final int TEST_RSSI = -50;
    private static final byte TEST_SCORE = 5;
    private static final RssiCurve CURVE =
            new RssiCurve(RSSI_START, 10, new byte[] {-1, 0, 1, 2, 3, 4, TEST_SCORE, 6, 7});

    private static final byte RANKING_SCORE_OFFSET = 13;
    private static final Bundle ATTRIBUTES;
    static {
        ATTRIBUTES = new Bundle();
        ATTRIBUTES.putInt(
                ScoredNetwork.ATTRIBUTES_KEY_RANKING_SCORE_OFFSET, RANKING_SCORE_OFFSET);
    }

    private static final NetworkKey KEY
        = new NetworkKey(new WifiKey("\"ssid\"", "00:00:00:00:00:00"));

    @Test
    public void scoredNetworksWithBothNullAttributeBundle_equal() {
        ScoredNetwork scoredNetwork1 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, null /* attributes */);
        ScoredNetwork scoredNetwork2 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, null /* attributes */);
        assertTrue(scoredNetwork1.equals(scoredNetwork2));
    }

    @Test
    public void scoredNetworksWithOneNullAttributeBundle_notEqual() {
        ScoredNetwork scoredNetwork1 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, ATTRIBUTES);
        ScoredNetwork scoredNetwork2 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, null /* attributes */);
        assertFalse(scoredNetwork1.equals(scoredNetwork2));
    }

    @Test
    public void scoredNetworksWithDifferentSizedAttributeBundle_notEqual() {
        ScoredNetwork scoredNetwork1 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, ATTRIBUTES);
        Bundle attr = new Bundle(ATTRIBUTES);
        attr.putBoolean(ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL, true);
        ScoredNetwork scoredNetwork2 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, attr);
        assertFalse(scoredNetwork1.equals(scoredNetwork2));
    }

    @Test
    public void scoredNetworksWithDifferentAttributeValues_notEqual() {
        ScoredNetwork scoredNetwork1 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, ATTRIBUTES);
        Bundle attr = new Bundle();
        attr.putInt(ScoredNetwork.ATTRIBUTES_KEY_RANKING_SCORE_OFFSET, Integer.MIN_VALUE);
        ScoredNetwork scoredNetwork2 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, attr);
        assertFalse(scoredNetwork1.equals(scoredNetwork2));
    }

    @Test
    public void scoredNetworksWithSameAttributeValuesAndSize_equal() {
        ScoredNetwork scoredNetwork1 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, ATTRIBUTES);
        ScoredNetwork scoredNetwork2 =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, ATTRIBUTES);
        assertTrue(scoredNetwork1.equals(scoredNetwork2));
    }

    @Test
    public void calculateRankingOffsetShouldThrowUnsupportedOperationException() {
        // No curve or ranking score offset set in curve
        ScoredNetwork scoredNetwork = new ScoredNetwork(KEY, null);
        try {
            scoredNetwork.calculateRankingScore(TEST_RSSI);
            fail("Should have thrown UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void calculateRankingOffsetWithRssiCurveShouldReturnExpectedScore() {
        ScoredNetwork scoredNetwork = new ScoredNetwork(KEY, CURVE);
        assertEquals(TEST_SCORE << Byte.SIZE, scoredNetwork.calculateRankingScore(TEST_RSSI));
    }

    @Test
    public void rankingScoresShouldDifferByRankingScoreOffset() {
        ScoredNetwork scoredNetwork1 = new ScoredNetwork(KEY, CURVE);
        ScoredNetwork scoredNetwork2
            = new ScoredNetwork(KEY, CURVE, false /* meteredHint */, ATTRIBUTES);
        int scoreDifference =
            scoredNetwork2.calculateRankingScore(TEST_RSSI)
            - scoredNetwork1.calculateRankingScore(TEST_RSSI);
        assertEquals(RANKING_SCORE_OFFSET, scoreDifference);
    }

    @Test
    public void calculateRankingScoreShouldNotResultInIntegerOverflow() {
        Bundle attr = new Bundle();
        attr.putInt(ScoredNetwork.ATTRIBUTES_KEY_RANKING_SCORE_OFFSET, Integer.MAX_VALUE);
        ScoredNetwork scoredNetwork
            = new ScoredNetwork(KEY, CURVE, false /* meteredHint */, attr);
        assertEquals(Integer.MAX_VALUE, scoredNetwork.calculateRankingScore(TEST_RSSI));
    }

    @Test
    public void calculateRankingScoreShouldNotResultInIntegerUnderflow() {
        Bundle attr = new Bundle();
        attr.putInt(ScoredNetwork.ATTRIBUTES_KEY_RANKING_SCORE_OFFSET, Integer.MIN_VALUE);
        ScoredNetwork scoredNetwork =
                new ScoredNetwork(KEY, CURVE, false /* meteredHint */, attr);
        assertEquals(Integer.MIN_VALUE, scoredNetwork.calculateRankingScore(RSSI_START));
    }

    @Test
    public void hasRankingScoreShouldReturnFalse() {
        ScoredNetwork network = new ScoredNetwork(KEY, null /* rssiCurve */);
        assertFalse(network.hasRankingScore());
    }

    @Test
    public void hasRankingScoreShouldReturnTrueWhenAttributesHasRankingScoreOffset() {
        ScoredNetwork network =
                new ScoredNetwork(KEY, null /* rssiCurve */, false /* meteredHint */, ATTRIBUTES);
        assertTrue(network.hasRankingScore());
    }

    @Test
    public void hasRankingScoreShouldReturnTrueWhenCurveIsPresent() {
        ScoredNetwork network =
                new ScoredNetwork(KEY, CURVE , false /* meteredHint */);
        assertTrue(network.hasRankingScore());
    }

    @Test
    public void shouldWriteAndReadFromParcelWhenAllFieldsSet() {
        ScoredNetwork network = new ScoredNetwork(KEY, CURVE, true /* meteredHint */, ATTRIBUTES);
        ScoredNetwork newNetwork;

        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            network.writeToParcel(parcel, 0 /* flags */);
            parcel.setDataPosition(0);
            newNetwork = ScoredNetwork.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
        assertEquals(CURVE.start, newNetwork.rssiCurve.start);
        assertEquals(CURVE.bucketWidth, newNetwork.rssiCurve.bucketWidth);
        assertTrue(Arrays.equals(CURVE.rssiBuckets, newNetwork.rssiCurve.rssiBuckets));
        assertTrue(newNetwork.meteredHint);
        assertNotNull(newNetwork.attributes);
        assertEquals(
                RANKING_SCORE_OFFSET,
                newNetwork.attributes.getInt(ScoredNetwork.ATTRIBUTES_KEY_RANKING_SCORE_OFFSET));
    }

    @Test
    public void shouldWriteAndReadFromParcelWithoutBundle() {
        ScoredNetwork network = new ScoredNetwork(KEY, CURVE, true /* meteredHint */);
        ScoredNetwork newNetwork;

        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            network.writeToParcel(parcel, 0 /* flags */);
            parcel.setDataPosition(0);
            newNetwork = ScoredNetwork.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
        assertEquals(CURVE.start, newNetwork.rssiCurve.start);
        assertEquals(CURVE.bucketWidth, newNetwork.rssiCurve.bucketWidth);
        assertTrue(Arrays.equals(CURVE.rssiBuckets, newNetwork.rssiCurve.rssiBuckets));
        assertTrue(newNetwork.meteredHint);
        assertNull(newNetwork.attributes);
    }

    @Test
    public void calculateBadgeShouldReturnNoBadgeWhenNoAttributesBundle() {
        ScoredNetwork network = new ScoredNetwork(KEY, CURVE);
        assertEquals(NetworkBadging.BADGING_NONE, network.calculateBadge(TEST_RSSI));
    }

    @Test
    public void calculateBadgeShouldReturnNoBadgeWhenNoBadgingCurveInBundle() {
        ScoredNetwork network = new ScoredNetwork(KEY, CURVE, false /* meteredHint */, ATTRIBUTES);
        assertEquals(NetworkBadging.BADGING_NONE, network.calculateBadge(TEST_RSSI));
    }

    @Test
    public void calculateBadgeShouldReturn4kBadge() {
        ScoredNetwork network =
            buildScoredNetworkWithGivenBadgeForTestRssi(NetworkBadging.BADGING_4K);
        assertEquals(NetworkBadging.BADGING_4K, network.calculateBadge(TEST_RSSI));
    }

    @Test
    public void calculateBadgeShouldReturnHdBadge() {
        ScoredNetwork network =
            buildScoredNetworkWithGivenBadgeForTestRssi(NetworkBadging.BADGING_HD);
        assertEquals(NetworkBadging.BADGING_HD, network.calculateBadge(TEST_RSSI));
    }

    @Test
    public void calculateBadgeShouldReturnSdBadge() {
        ScoredNetwork network =
            buildScoredNetworkWithGivenBadgeForTestRssi(NetworkBadging.BADGING_SD);
        assertEquals(NetworkBadging.BADGING_SD, network.calculateBadge(TEST_RSSI));
    }

    @Test
    public void calculateBadgeShouldReturnNoBadge() {
        ScoredNetwork network =
            buildScoredNetworkWithGivenBadgeForTestRssi(NetworkBadging.BADGING_NONE);
        assertEquals(NetworkBadging.BADGING_NONE, network.calculateBadge(TEST_RSSI));
    }

    private ScoredNetwork buildScoredNetworkWithGivenBadgeForTestRssi(int badge) {
        RssiCurve badgingCurve =
               new RssiCurve(RSSI_START, 10, new byte[] {0, 0, 0, 0, 0, 0, (byte) badge});
        Bundle attr = new Bundle();
        attr.putParcelable(ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE, badgingCurve);
        return new ScoredNetwork(KEY, CURVE, false /* meteredHint */, attr);
    }
}
