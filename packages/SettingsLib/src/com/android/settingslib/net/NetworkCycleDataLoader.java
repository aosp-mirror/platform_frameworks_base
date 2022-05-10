/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.net;

import android.annotation.NonNull;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkTemplate;
import android.text.format.DateUtils;
import android.util.Pair;
import android.util.Range;

import androidx.annotation.VisibleForTesting;
import androidx.loader.content.AsyncTaskLoader;

import com.android.settingslib.NetworkPolicyEditor;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Loader for network data usage history. It returns a list of usage data per billing cycle.
 */
public abstract class NetworkCycleDataLoader<D> extends AsyncTaskLoader<D> {
    private static final String TAG = "NetworkCycleDataLoader";
    protected final NetworkStatsManager mNetworkStatsManager;
    protected final NetworkTemplate mNetworkTemplate;
    private final NetworkPolicy mPolicy;
    private final ArrayList<Long> mCycles;

    protected NetworkCycleDataLoader(Builder<?> builder) {
        super(builder.mContext);
        mNetworkTemplate = builder.mNetworkTemplate;
        mCycles = builder.mCycles;
        mNetworkStatsManager = (NetworkStatsManager)
            builder.mContext.getSystemService(Context.NETWORK_STATS_SERVICE);
        final NetworkPolicyEditor policyEditor =
            new NetworkPolicyEditor(NetworkPolicyManager.from(builder.mContext));
        policyEditor.read();
        mPolicy = policyEditor.getPolicy(mNetworkTemplate);
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();
        forceLoad();
    }

    public D loadInBackground() {
        if (mCycles != null && mCycles.size() > 1) {
            loadDataForSpecificCycles();
        } else if (mPolicy == null) {
            loadFourWeeksData();
        } else {
            loadPolicyData();
        }
        return getCycleUsage();
    }

    @VisibleForTesting
    void loadPolicyData() {
        final Iterator<Pair<ZonedDateTime, ZonedDateTime>> iterator =
            NetworkPolicyManager.cycleIterator(mPolicy);
        while (iterator.hasNext()) {
            final Pair<ZonedDateTime, ZonedDateTime> cycle = iterator.next();
            final long cycleStart = cycle.first.toInstant().toEpochMilli();
            final long cycleEnd = cycle.second.toInstant().toEpochMilli();
            recordUsage(cycleStart, cycleEnd);
        }
    }

    @Override
    protected void onStopLoading() {
        super.onStopLoading();
        cancelLoad();
    }

    @Override
    protected void onReset() {
        super.onReset();
        cancelLoad();
    }

    @VisibleForTesting
    void loadFourWeeksData() {
        if (mNetworkTemplate == null) return;
        final NetworkStats stats = mNetworkStatsManager.queryDetailsForDevice(
                mNetworkTemplate, Long.MIN_VALUE, Long.MAX_VALUE);
        try {
            final Range<Long> historyTimeRange = getTimeRangeOf(stats);

            long cycleEnd = historyTimeRange.getUpper();
            while (cycleEnd > historyTimeRange.getLower()) {
                final long cycleStart = cycleEnd - (DateUtils.WEEK_IN_MILLIS * 4);
                recordUsage(cycleStart, cycleEnd);
                cycleEnd = cycleStart;
            }
        } catch (IllegalArgumentException e) {
            // Empty history, ignore.
        }
    }

    @VisibleForTesting
    void loadDataForSpecificCycles() {
        long cycleEnd = mCycles.get(0);
        final int lastCycleIndex = mCycles.size() - 1;
        for (int i = 1; i <= lastCycleIndex; i++) {
            final long cycleStart = mCycles.get(i);
            recordUsage(cycleStart, cycleEnd);
            cycleEnd = cycleStart;
        }
    }

    @VisibleForTesting
    abstract void recordUsage(long start, long end);

    abstract D getCycleUsage();

    public static Builder<?> builder(Context context) {
        return new Builder<NetworkCycleDataLoader>(context) {
            @Override
            public NetworkCycleDataLoader build() {
                return null;
            }
        };
    }

    protected long getTotalUsage(NetworkStats stats) {
        long bytes = 0L;
        if (stats != null) {
            final NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            while (stats.hasNextBucket() && stats.getNextBucket(bucket)) {
                bytes += bucket.getRxBytes() + bucket.getTxBytes();
            }
            stats.close();
        }
        return bytes;
    }

    @NonNull
    @VisibleForTesting
    Range getTimeRangeOf(@NonNull NetworkStats stats) {
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        while (hasNextBucket(stats)) {
            final NetworkStats.Bucket bucket = getNextBucket(stats);
            start = Math.min(start, bucket.getStartTimeStamp());
            end = Math.max(end, bucket.getEndTimeStamp());
        }
        return new Range(start, end);
    }

    @VisibleForTesting
    boolean hasNextBucket(@NonNull NetworkStats stats) {
        return stats.hasNextBucket();
    }

    @NonNull
    @VisibleForTesting
    NetworkStats.Bucket getNextBucket(@NonNull NetworkStats stats) {
        NetworkStats.Bucket bucket = new NetworkStats.Bucket();
        stats.getNextBucket(bucket);
        return bucket;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public ArrayList<Long> getCycles() {
        return mCycles;
    }

    public static abstract class Builder<T extends NetworkCycleDataLoader> {
        private final Context mContext;
        private NetworkTemplate mNetworkTemplate;
        private ArrayList<Long> mCycles;

        public Builder (Context context) {
            mContext = context;
        }

        public Builder<T> setNetworkTemplate(NetworkTemplate template) {
            mNetworkTemplate = template;
            return this;
        }

        /**
         * Sets the network cycles to be used to query the usage data.
         * @param cycles the time slots for the network cycle to be used to query the network usage.
         * @return the builder
         */
        public Builder<T> setCycles(ArrayList<Long> cycles) {
            mCycles = cycles;
            return this;
        }

        public abstract T build();
    }

}
