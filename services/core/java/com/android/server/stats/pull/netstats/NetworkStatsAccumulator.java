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

import android.annotation.NonNull;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.util.Log;

import java.util.Objects;

/**
 * A class that queries NetworkStats, accumulates query results, and exposes cumulative stats across
 * the time range covered by the queries. This class is not thread-safe.
 * <p>
 * This is class should be used when querying NetworkStats since boot, as NetworkStats persists data
 * only for a limited period of time.
 *
 * @hide
 */
public class NetworkStatsAccumulator {

    private static final String TAG = "NetworkStatsAccumulator";
    private final NetworkTemplate mTemplate;
    private final boolean mWithTags;
    private final long mBucketDurationMillis;
    private NetworkStats mSnapshot;
    private long mSnapshotEndTimeMillis;

    public NetworkStatsAccumulator(@NonNull NetworkTemplate template, boolean withTags,
            long bucketDurationMillis, long snapshotEndTimeMillis) {
        mTemplate = template;
        mWithTags = withTags;
        mBucketDurationMillis = bucketDurationMillis;
        mSnapshot = new NetworkStats(0, 1);
        mSnapshotEndTimeMillis = snapshotEndTimeMillis;
    }

    /**
     * Provides cumulative NetworkStats until given timestamp.
     * <p>
     * This method method may call {@code queryFunction} more than once, which includes maintaining
     * an internal cumulative stats snapshot and querying stats after the snapshot.
     */
    @NonNull
    public NetworkStats queryStats(long currentTimeMillis,
            @NonNull StatsQueryFunction queryFunction) {
        NetworkStats completeStats = snapshotPlusFollowingStats(currentTimeMillis, queryFunction);
        maybeExpandSnapshot(currentTimeMillis, completeStats, queryFunction);
        return completeStats;
    }

    /**
     * Returns true if the accumulator is using given query parameters.
     */
    public boolean hasEqualParameters(@NonNull NetworkTemplate template, boolean withTags) {
        return Objects.equals(mTemplate, template) && mWithTags == withTags;
    }

    /**
     * Expands the internal cumulative stats snapshot, if possible, by querying NetworkStats.
     */
    private void maybeExpandSnapshot(long currentTimeMillis,
            NetworkStats completeStatsUntilCurrentTime,
            @NonNull StatsQueryFunction queryFunction) {
        // Update snapshot only if it is possible to expand it by at least one full bucket, and only
        // if the new snapshot's end is not in the active bucket.
        long newEndTimeMillis = currentTimeMillis - mBucketDurationMillis;
        if (newEndTimeMillis - mSnapshotEndTimeMillis > mBucketDurationMillis) {
            Log.v(TAG,
                    "Expanding snapshot (mTemplate=" + mTemplate + ", mWithTags=" + mWithTags
                            + ") from " + mSnapshotEndTimeMillis + " to " + newEndTimeMillis
                            + " at " + currentTimeMillis);
            NetworkStats extraStats = queryFunction.queryNetworkStats(
                    mTemplate, mWithTags, mSnapshotEndTimeMillis, newEndTimeMillis);
            mSnapshot = mSnapshot.add(extraStats);
            mSnapshotEndTimeMillis = newEndTimeMillis;

            // NetworkStats queries interpolate historical data using integers maths, which makes
            // queries non-transitive: Query(t0, t1) + Query(t1, t2) <= Query(t0, t2).
            // Compute interpolation data loss from moving the snapshot's end-point, and add it to
            // the snapshot to avoid under-counting.
            NetworkStats newStats = snapshotPlusFollowingStats(currentTimeMillis, queryFunction);
            NetworkStats interpolationLoss = completeStatsUntilCurrentTime.subtract(newStats);
            mSnapshot = mSnapshot.add(interpolationLoss);
        }
    }

    /**
     * Adds up stats in the internal cumulative snapshot and the stats that follow after it.
     */
    @NonNull
    private NetworkStats snapshotPlusFollowingStats(long currentTimeMillis,
            @NonNull StatsQueryFunction queryFunction) {
        // Set end time in the future to include all stats in the active bucket.
        NetworkStats extraStats = queryFunction.queryNetworkStats(mTemplate, mWithTags,
                mSnapshotEndTimeMillis, currentTimeMillis + mBucketDurationMillis);
        return mSnapshot.add(extraStats);
    }

    @FunctionalInterface
    public interface StatsQueryFunction {
        /**
         * Returns network stats during the given time period.
         */
        @NonNull
        NetworkStats queryNetworkStats(@NonNull NetworkTemplate template, boolean includeTags,
                long startTime, long endTime);
    }
}
