/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.net.watchlist;

import android.privacy.DifferentialPrivacyEncoder;
import android.privacy.internal.longitudinalreporting.LongitudinalReportingConfig;
import android.privacy.internal.longitudinalreporting.LongitudinalReportingEncoder;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper class to apply differential privacy to watchlist reports.
 */
class PrivacyUtils {

    private static final String TAG = "PrivacyUtils";
    private static final boolean DEBUG = NetworkWatchlistService.DEBUG;

    /**
     * Parameters used for encoding watchlist reports.
     * These numbers are optimal parameters for protecting privacy with good utility.
     *
     * TODO: Add links to explain the math behind.
     */
    private static final String ENCODER_ID_PREFIX = "watchlist_encoder:";
    private static final double PROB_F = 0.469;
    private static final double PROB_P = 0.28;
    private static final double PROB_Q = 1.0;

    private PrivacyUtils() {
    }

    /**
     * Get insecure DP encoder.
     * Should not apply it directly on real data as seed is not randomized.
     */
    @VisibleForTesting
    static DifferentialPrivacyEncoder createInsecureDPEncoderForTest(String appDigest) {
        final LongitudinalReportingConfig config = createLongitudinalReportingConfig(appDigest);
        return LongitudinalReportingEncoder.createInsecureEncoderForTest(config);
    }

    /**
     * Get secure encoder to encode watchlist.
     *
     * Warning: If you use the same user secret and app digest, then you will get the same
     * PRR result.
     */
    @VisibleForTesting
    static DifferentialPrivacyEncoder createSecureDPEncoder(byte[] userSecret,
            String appDigest) {
        final LongitudinalReportingConfig config = createLongitudinalReportingConfig(appDigest);
        return LongitudinalReportingEncoder.createEncoder(config, userSecret);
    }

    /**
     * Get DP config for encoding watchlist reports.
     */
    private static LongitudinalReportingConfig createLongitudinalReportingConfig(String appDigest) {
        return new LongitudinalReportingConfig(ENCODER_ID_PREFIX + appDigest, PROB_F, PROB_P,
                PROB_Q);
    }

    /**
     * Create a map that stores appDigest, encoded_visitedWatchlist pairs.
     */
    @VisibleForTesting
    static Map<String, Boolean> createDpEncodedReportMap(boolean isSecure, byte[] userSecret,
            List<String> appDigestList, WatchlistReportDbHelper.AggregatedResult aggregatedResult) {
        if (DEBUG) Slog.i(TAG, "createDpEncodedReportMap start");
        final int appDigestListSize = appDigestList.size();
        final HashMap<String, Boolean> resultMap = new HashMap<>(appDigestListSize);
        for (int i = 0; i < appDigestListSize; i++) {
            final String appDigest = appDigestList.get(i);
            // Each app needs to have different PRR result, hence we use appDigest as encoder Id.
            final DifferentialPrivacyEncoder encoder = isSecure
                    ? createSecureDPEncoder(userSecret, appDigest)
                    : createInsecureDPEncoderForTest(appDigest);
            final boolean visitedWatchlist = aggregatedResult.appDigestList.contains(appDigest);
            if (DEBUG) Slog.i(TAG, appDigest + ": " + visitedWatchlist);
            // Get the least significant bit of first byte, and set result to True if it is 1
            boolean encodedVisitedWatchlist = ((int) encoder.encodeBoolean(visitedWatchlist)[0]
                    & 0x1) == 0x1;
            resultMap.put(appDigest, encodedVisitedWatchlist);
        }
        return resultMap;
    }
}
