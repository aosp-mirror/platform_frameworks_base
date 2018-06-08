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
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;
import com.android.service.NetworkWatchlistReportProto;
import com.android.service.NetworkWatchlistAppResultProto;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Helper class to encode and generate serialized DP encoded watchlist proto report.
 */
class ReportEncoder {

    private static final String TAG = "ReportEncoder";

    // Report version number, as file format / parameters can be changed in later version, we need
    // to have versioning on watchlist report format
    private static final int REPORT_VERSION = 1;

    private static final int WATCHLIST_HASH_SIZE = 32;

    /**
     * Apply DP on watchlist results, and generate a serialized watchlist report ready to store
     * in DropBox.
     */
    @Nullable
    static byte[] encodeWatchlistReport(WatchlistConfig config, byte[] userSecret,
            List<String> appDigestList, WatchlistReportDbHelper.AggregatedResult aggregatedResult) {
        Map<String, Boolean> resultMap = PrivacyUtils.createDpEncodedReportMap(
                config.isConfigSecure(), userSecret, appDigestList, aggregatedResult);
        return serializeReport(config, resultMap);
    }

    /**
     * Convert DP encoded watchlist report into proto format.
     *
     * @param encodedReportMap DP encoded watchlist report.
     * @return Watchlist report in proto format, which will be shared in Dropbox. Null if
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
        final ByteArrayOutputStream reportOutputStream = new ByteArrayOutputStream();
        final ProtoOutputStream proto = new ProtoOutputStream(reportOutputStream);

        // Set report version to report
        proto.write(NetworkWatchlistReportProto.REPORT_VERSION, REPORT_VERSION);
        proto.write(NetworkWatchlistReportProto.WATCHLIST_CONFIG_HASH,
                HexDump.toHexString(watchlistHash));

        // Set app digest, encoded_isPha pair to report
        for (Map.Entry<String, Boolean> entry : encodedReportMap.entrySet()) {
            String key = entry.getKey();
            byte[] digest = HexDump.hexStringToByteArray(key);
            boolean encodedResult = entry.getValue();
            long token = proto.start(NetworkWatchlistReportProto.APP_RESULT);
            proto.write(NetworkWatchlistAppResultProto.APP_DIGEST, key);
            proto.write(NetworkWatchlistAppResultProto.ENCODED_RESULT, encodedResult);
            proto.end(token);
        }
        proto.flush();
        return reportOutputStream.toByteArray();
    }
}
