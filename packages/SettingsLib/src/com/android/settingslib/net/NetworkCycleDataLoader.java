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

import static android.net.NetworkStatsHistory.FIELD_RX_BYTES;
import static android.net.NetworkStatsHistory.FIELD_TX_BYTES;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.TrafficStats;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.loader.content.AsyncTaskLoader;

/**
 * Loader for network data usage history. It returns a list of usage data per billing cycle.
 */
public class NetworkCycleDataLoader extends AsyncTaskLoader<List<NetworkCycleData>> {
    private static final String TAG = "CycleDataSummaryLoader";
    private final NetworkStatsManager mNetworkStatsManager;
    private final String mSubId;
    private final int mNetworkType;
    private final NetworkPolicy mPolicy;
    private final NetworkTemplate mNetworkTemplate;
    @VisibleForTesting
    final INetworkStatsService mNetworkStatsService;

    private NetworkCycleDataLoader(Builder builder) {
        super(builder.mContext);
        mPolicy = builder.mPolicy;
        mSubId = builder.mSubId;
        mNetworkType = builder.mNetworkType;
        mNetworkTemplate = builder.mNetworkTemplate;
        mNetworkStatsManager = (NetworkStatsManager)
            builder.mContext.getSystemService(Context.NETWORK_STATS_SERVICE);
        mNetworkStatsService = INetworkStatsService.Stub.asInterface(
            ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();
        forceLoad();
    }

    @Override
    public List<NetworkCycleData> loadInBackground() {
        if (mPolicy == null) {
            return loadFourWeeksData();
        }
        final List<NetworkCycleData> data = new ArrayList<>();
        final Iterator<Pair<ZonedDateTime, ZonedDateTime>> iterator = NetworkPolicyManager
            .cycleIterator(mPolicy);
        while (iterator.hasNext()) {
            final Pair<ZonedDateTime, ZonedDateTime> cycle = iterator.next();
            final long cycleStart = cycle.first.toInstant().toEpochMilli();
            final long cycleEnd = cycle.second.toInstant().toEpochMilli();
            getUsage(cycleStart, cycleEnd, data);
        }
        return data;
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
    List<NetworkCycleData> loadFourWeeksData() {
        final List<NetworkCycleData> data = new ArrayList<>();
        try {
            final INetworkStatsSession networkSession = mNetworkStatsService.openSession();
            final NetworkStatsHistory networkHistory = networkSession.getHistoryForNetwork(
                mNetworkTemplate, FIELD_RX_BYTES | FIELD_TX_BYTES);
            final long historyStart = networkHistory.getStart();
            final long historyEnd = networkHistory.getEnd();

            long cycleEnd = historyEnd;
            while (cycleEnd > historyStart) {
                final long cycleStart = cycleEnd - (DateUtils.WEEK_IN_MILLIS * 4);
                getUsage(cycleStart, cycleEnd, data);
                cycleEnd = cycleStart;
            }

            TrafficStats.closeQuietly(networkSession);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
        return data;
    }

    @VisibleForTesting
    void getUsage(long start, long end, @NonNull List<NetworkCycleData> data) {
        try {
            final NetworkStats stats = mNetworkStatsManager.querySummary(
                mNetworkType, mSubId, start, end);
            final long total = getTotalUsage(stats);
            if (total > 0L) {
                data.add(new NetworkCycleData.Builder()
                    .setStartTime(start)
                    .setEndTime(end)
                    .setTotalUsage(total)
                    .setUsageBuckets(getUsageBuckets(start, end))
                    .build());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception querying network detail.", e);
        }
    }

    private long getTotalUsage(NetworkStats stats) {
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

    private List<NetworkCycleData> getUsageBuckets(long start, long end) {
        final List<NetworkCycleData> data = new ArrayList<>();
        long bucketStart = start;
        long bucketEnd = start + NetworkCycleData.BUCKET_DURATION_MS;
        while (bucketEnd <= end) {
            long usage = 0L;
            try {
                final NetworkStats stats = mNetworkStatsManager.querySummary(
                    mNetworkType, mSubId, bucketStart, bucketEnd);
                usage = getTotalUsage(stats);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception querying network detail.", e);
            }
            data.add(new NetworkCycleData.Builder()
                .setStartTime(bucketStart).setEndTime(bucketEnd).setTotalUsage(usage).build());
            bucketStart = bucketEnd;
            bucketEnd += NetworkCycleData.BUCKET_DURATION_MS;
        }
        return data;
    }

    public static class Builder {
        private final Context mContext;
        private NetworkPolicy mPolicy;
        private String mSubId;
        private int mNetworkType;
        private NetworkTemplate mNetworkTemplate;

        public Builder(Context context) {
            mContext = context;
        }

        public Builder setNetworkPolicy(NetworkPolicy policy) {
            mPolicy = policy;
            return this;
        }

        public Builder setSubscriberId(String subId) {
            mSubId = subId;
            return this;
        }

        public Builder setNetworkType(int networkType) {
            mNetworkType = networkType;
            return this;
        }

        public Builder setNetworkTemplate(NetworkTemplate template) {
            mNetworkTemplate = template;
            return this;
        }

        public NetworkCycleDataLoader build() {
            return new NetworkCycleDataLoader(this);
        }
    }

}
