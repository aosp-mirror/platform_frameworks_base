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

import android.annotation.Nullable;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Helper class to encode and generate serialized DP encoded watchlist report.
 *
 * <p>Serialized report data structure:
 * [4 bytes magic number][4_bytes_report_version_code][32_bytes_watchlist_hash]
 * [app_1_digest_byte_array][app_1_encoded_visited_cnc_byte]
 * [app_2_digest_byte_array][app_2_encoded_visited_cnc_byte]
 * ...
 *
 * Total size: 4 + 4 + 32 + (32+1)*N, where N = number of digests
 */
class ReportEncoder {

    private static final String TAG = "ReportEncoder";

    // Report header magic number
    private static final byte[] MAGIC_NUMBER = {(byte) 0x8D, (byte) 0x37, (byte) 0x0A, (byte) 0xAC};
    // Report version number, as file format / parameters can be changed in later version, we need
    // to have versioning on watchlist report format
    private static final byte[] REPORT_VERSION = {(byte) 0x00, (byte) 0x01};

    private static final int WATCHLIST_HASH_SIZE = 32;
    private static final int APP_DIGEST_SIZE = 32;

    /**
     * Apply DP on watchlist results, and generate a serialized watchlist report ready to store
     * in DropBox.
     */
    static byte[] encodeWatchlistReport(WatchlistConfig config, byte[] userSecret,
            List<String> appDigestList, WatchlistReportDbHelper.AggregatedResult aggregatedResult) {
        Map<String, Boolean> resultMap = PrivacyUtils.createDpEncodedReportMap(
                config.isConfigSecure(), userSecret, appDigestList, aggregatedResult);
        return serializeReport(config, resultMap);
    }

    /**
     * Convert DP encoded watchlist report into byte[] format.
     * TODO: Serialize it using protobuf
     *
     * @param encodedReportMap DP encoded watchlist report.
     * @return Watchlist report in byte[] format, which will be shared in Dropbox. Null if
     * watchlist report cannot be generated.
     */
    @Nullable
    @VisibleForTesting
    static byte[] serializeReport(WatchlistConfig config,
            Map<String, Boolean> encodedReportMap) {
        // TODO: Handle watchlist config changed case
        final byte[] watchlistHash = config.getWatchlistConfigHash();
        if (watchlistHash == null) {
            Log.e(TAG, "No watchlist hash");
            return null;
        }
        if (watchlistHash.length != WATCHLIST_HASH_SIZE) {
            Log.e(TAG, "Unexpected hash length");
            return null;
        }
        final int reportMapSize = encodedReportMap.size();
        final byte[] outputReport =
                new byte[MAGIC_NUMBER.length + REPORT_VERSION.length + WATCHLIST_HASH_SIZE
                        + reportMapSize * (APP_DIGEST_SIZE + /* Result */ 1)];
        final List<String> sortedKeys = new ArrayList(encodedReportMap.keySet());
        Collections.sort(sortedKeys);

        int offset = 0;

        // Set magic number to report
        System.arraycopy(MAGIC_NUMBER, 0, outputReport, offset, MAGIC_NUMBER.length);
        offset += MAGIC_NUMBER.length;

        // Set report version to report
        System.arraycopy(REPORT_VERSION, 0, outputReport, offset, REPORT_VERSION.length);
        offset += REPORT_VERSION.length;

        // Set watchlist hash to report
        System.arraycopy(watchlistHash, 0, outputReport, offset, watchlistHash.length);
        offset += watchlistHash.length;

        // Set app digest, encoded_isPha pair to report
        for (int i = 0; i < reportMapSize; i++) {
            String key = sortedKeys.get(i);
            byte[] digest = HexDump.hexStringToByteArray(key);
            boolean isPha = encodedReportMap.get(key);
            System.arraycopy(digest, 0, outputReport, offset, APP_DIGEST_SIZE);
            offset += digest.length;
            outputReport[offset] = (byte) (isPha ? 1 : 0);
            offset += 1;
        }
        if (outputReport.length != offset) {
            Log.e(TAG, "Watchlist report size does not match! Offset: " + offset + ", report size: "
                    + outputReport.length);

        }
        return outputReport;
    }
}
