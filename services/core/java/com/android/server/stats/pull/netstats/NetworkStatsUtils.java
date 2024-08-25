/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.stats.pull.netstats;

import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.ROAMING_ALL;
import static android.net.NetworkStats.SET_ALL;

import android.app.usage.NetworkStats;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Utility methods for accessing {@link android.net.NetworkStats}.
 */
public class NetworkStatsUtils {

    /**
     * Convert structure from android.app.usage.NetworkStats to android.net.NetworkStats.
     */
    public static android.net.NetworkStats fromPublicNetworkStats(
            NetworkStats publiceNetworkStats) {
        android.net.NetworkStats stats = new android.net.NetworkStats(0L, 0);
        while (publiceNetworkStats.hasNextBucket()) {
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            publiceNetworkStats.getNextBucket(bucket);
            final android.net.NetworkStats.Entry entry = fromBucket(bucket);
            stats = stats.addEntry(entry);
        }
        return stats;
    }

    /**
     * Convert structure from android.app.usage.NetworkStats.Bucket
     * to android.net.NetworkStats.Entry.
     */
    @VisibleForTesting
    public static android.net.NetworkStats.Entry fromBucket(NetworkStats.Bucket bucket) {
        return new android.net.NetworkStats.Entry(
                null /* IFACE_ALL */, bucket.getUid(), convertBucketState(bucket.getState()),
                convertBucketTag(bucket.getTag()), convertBucketMetered(bucket.getMetered()),
                convertBucketRoaming(bucket.getRoaming()),
                convertBucketDefaultNetworkStatus(bucket.getDefaultNetworkStatus()),
                bucket.getRxBytes(), bucket.getRxPackets(),
                bucket.getTxBytes(), bucket.getTxPackets(), 0 /* operations */);
    }

    private static int convertBucketState(int networkStatsSet) {
        switch (networkStatsSet) {
            case NetworkStats.Bucket.STATE_ALL: return SET_ALL;
            case NetworkStats.Bucket.STATE_DEFAULT: return android.net.NetworkStats.SET_DEFAULT;
            case NetworkStats.Bucket.STATE_FOREGROUND:
                return android.net.NetworkStats.SET_FOREGROUND;
        }
        return 0;
    }

    private static int convertBucketTag(int tag) {
        switch (tag) {
            case NetworkStats.Bucket.TAG_NONE: return android.net.NetworkStats.TAG_NONE;
        }
        return tag;
    }

    private static int convertBucketMetered(int metered) {
        switch (metered) {
            case NetworkStats.Bucket.METERED_ALL: return METERED_ALL;
            case NetworkStats.Bucket.METERED_NO: return android.net.NetworkStats.METERED_NO;
            case NetworkStats.Bucket.METERED_YES: return android.net.NetworkStats.METERED_YES;
        }
        return 0;
    }

    private static int convertBucketRoaming(int roaming) {
        switch (roaming) {
            case NetworkStats.Bucket.ROAMING_ALL: return ROAMING_ALL;
            case NetworkStats.Bucket.ROAMING_NO: return android.net.NetworkStats.ROAMING_NO;
            case NetworkStats.Bucket.ROAMING_YES: return android.net.NetworkStats.ROAMING_YES;
        }
        return 0;
    }

    private static int convertBucketDefaultNetworkStatus(int defaultNetworkStatus) {
        switch (defaultNetworkStatus) {
            case NetworkStats.Bucket.DEFAULT_NETWORK_ALL:
                return DEFAULT_NETWORK_ALL;
            case NetworkStats.Bucket.DEFAULT_NETWORK_NO:
                return android.net.NetworkStats.DEFAULT_NETWORK_NO;
            case NetworkStats.Bucket.DEFAULT_NETWORK_YES:
                return android.net.NetworkStats.DEFAULT_NETWORK_YES;
        }
        return 0;
    }
}
