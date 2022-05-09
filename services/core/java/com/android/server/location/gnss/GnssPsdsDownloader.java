/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.gnss;

import android.annotation.Nullable;
import android.net.TrafficStats;
import android.util.Log;

import com.android.internal.util.TrafficStatsConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * A class for downloading GNSS PSDS data.
 *
 * {@hide}
 */
class GnssPsdsDownloader {

    // how often to request PSDS download, in milliseconds
    // current setting 24 hours
    static final long PSDS_INTERVAL = 24 * 60 * 60 * 1000;

    private static final String TAG = "GnssPsdsDownloader";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long MAXIMUM_CONTENT_LENGTH_BYTES = 1000000;  // 1MB.
    private static final int CONNECTION_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(30);
    private static final int READ_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(60);

    static final int LONG_TERM_PSDS_SERVER_INDEX = 1;
    private static final int NORMAL_PSDS_SERVER_INDEX = 2;
    private static final int REALTIME_PSDS_SERVER_INDEX = 3;
    private static final int MAX_PSDS_TYPE_INDEX = 3;

    private final String[] mLongTermPsdsServers;
    private final String[] mPsdsServers;
    // to load balance our server requests
    private int mNextServerIndex;

    GnssPsdsDownloader(Properties properties) {
        // read PSDS servers from the Properties object
        int count = 0;
        String longTermPsdsServer1 = properties.getProperty(
                GnssConfiguration.CONFIG_LONGTERM_PSDS_SERVER_1);
        String longTermPsdsServer2 = properties.getProperty(
                GnssConfiguration.CONFIG_LONGTERM_PSDS_SERVER_2);
        String longTermPsdsServer3 = properties.getProperty(
                GnssConfiguration.CONFIG_LONGTERM_PSDS_SERVER_3);
        if (longTermPsdsServer1 != null) count++;
        if (longTermPsdsServer2 != null) count++;
        if (longTermPsdsServer3 != null) count++;

        if (count == 0) {
            Log.e(TAG, "No Long-Term PSDS servers were specified in the GnssConfiguration");
            mLongTermPsdsServers = null;
        } else {
            mLongTermPsdsServers = new String[count];
            count = 0;
            if (longTermPsdsServer1 != null) mLongTermPsdsServers[count++] = longTermPsdsServer1;
            if (longTermPsdsServer2 != null) mLongTermPsdsServers[count++] = longTermPsdsServer2;
            if (longTermPsdsServer3 != null) mLongTermPsdsServers[count++] = longTermPsdsServer3;

            // randomize first server
            Random random = new Random();
            mNextServerIndex = random.nextInt(count);
        }

        String normalPsdsServer = properties.getProperty(
                GnssConfiguration.CONFIG_NORMAL_PSDS_SERVER);
        String realtimePsdsServer = properties.getProperty(
                GnssConfiguration.CONFIG_REALTIME_PSDS_SERVER);
        mPsdsServers = new String[MAX_PSDS_TYPE_INDEX + 1];
        mPsdsServers[NORMAL_PSDS_SERVER_INDEX] = normalPsdsServer;
        mPsdsServers[REALTIME_PSDS_SERVER_INDEX] = realtimePsdsServer;
    }

    @Nullable
    byte[] downloadPsdsData(int psdsType) {
        byte[] result = null;
        int startIndex = mNextServerIndex;

        if (psdsType == LONG_TERM_PSDS_SERVER_INDEX && mLongTermPsdsServers == null) {
            return null;
        } else if (psdsType > LONG_TERM_PSDS_SERVER_INDEX && psdsType <= MAX_PSDS_TYPE_INDEX
                && mPsdsServers[psdsType] == null) {
            return null;
        }

        if (psdsType == LONG_TERM_PSDS_SERVER_INDEX) {
            // load balance our requests among the available servers
            while (result == null) {
                result = doDownloadWithTrafficAccounted(mLongTermPsdsServers[mNextServerIndex]);

                // increment mNextServerIndex and wrap around if necessary
                mNextServerIndex++;
                if (mNextServerIndex == mLongTermPsdsServers.length) {
                    mNextServerIndex = 0;
                }
                // break if we have tried all the servers
                if (mNextServerIndex == startIndex) break;
            }
        } else if (psdsType > LONG_TERM_PSDS_SERVER_INDEX && psdsType <= MAX_PSDS_TYPE_INDEX) {
            result = doDownloadWithTrafficAccounted(mPsdsServers[psdsType]);
        }

        return result;
    }

    @Nullable
    private byte[] doDownloadWithTrafficAccounted(String url) {
        byte[] result;
        final int oldTag = TrafficStats.getAndSetThreadStatsTag(
                TrafficStatsConstants.TAG_SYSTEM_GPS);
        try {
            result = doDownload(url);
        } finally {
            TrafficStats.setThreadStatsTag(oldTag);
        }
        return result;
    }

    @Nullable
    private byte[] doDownload(String url) {
        if (DEBUG) Log.d(TAG, "Downloading PSDS data from " + url);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) (new URL(url)).openConnection();
            connection.setRequestProperty(
                    "Accept",
                    "*/*, application/vnd.wap.mms-message, application/vnd.wap.sic");
            connection.setRequestProperty(
                    "x-wap-profile",
                    "http://www.openmobilealliance.org/tech/profiles/UAPROF/ccppschema-20021212#");
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            connection.connect();
            int statusCode = connection.getResponseCode();
            if (statusCode != HttpURLConnection.HTTP_OK) {
                if (DEBUG) Log.d(TAG, "HTTP error downloading gnss PSDS: " + statusCode);
                return null;
            }

            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > MAXIMUM_CONTENT_LENGTH_BYTES) {
                        if (DEBUG) Log.d(TAG, "PSDS file too large");
                        return null;
                    }
                }
                return bytes.toByteArray();
            }
        } catch (IOException ioe) {
            if (DEBUG) Log.d(TAG, "Error downloading gnss PSDS: ", ioe);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }
}
