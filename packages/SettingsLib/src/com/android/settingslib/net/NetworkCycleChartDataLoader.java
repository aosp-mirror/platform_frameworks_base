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

import android.app.usage.NetworkStats;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Loader for network data usage history. It returns a list of usage data per billing cycle with
 * bucketized usages.
 */
public class NetworkCycleChartDataLoader
        extends NetworkCycleDataLoader<List<NetworkCycleChartData>> {

    private static final String TAG = "NetworkCycleChartLoader";

    private final List<NetworkCycleChartData> mData;

    private NetworkCycleChartDataLoader(Builder builder) {
        super(builder);
        mData = new ArrayList<>();
    }

    @Override
    void recordUsage(long start, long end) {
        try {
            final NetworkStats.Bucket bucket = mNetworkStatsManager.querySummaryForDevice(
                    mNetworkTemplate, start, end);
            final long total = bucket == null ? 0L : bucket.getRxBytes() + bucket.getTxBytes();
            if (total > 0L) {
                final NetworkCycleChartData.Builder builder = new NetworkCycleChartData.Builder();
                builder.setUsageBuckets(getUsageBuckets(start, end))
                    .setStartTime(start)
                    .setEndTime(end)
                    .setTotalUsage(total);
                mData.add(builder.build());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception querying network detail.", e);
        }
    }

    @Override
    List<NetworkCycleChartData> getCycleUsage() {
        return mData;
    }

    public static Builder<?> builder(Context context) {
        return new Builder<NetworkCycleChartDataLoader>(context) {
            @Override
            public NetworkCycleChartDataLoader build() {
                return new NetworkCycleChartDataLoader(this);
            }
        };
    }

    private List<NetworkCycleData> getUsageBuckets(long start, long end) {
        final List<NetworkCycleData> data = new ArrayList<>();
        long bucketStart = start;
        long bucketEnd = start + NetworkCycleChartData.BUCKET_DURATION_MS;
        while (bucketEnd <= end) {
            long usage = 0L;
            try {
                final NetworkStats.Bucket bucket = mNetworkStatsManager.querySummaryForDevice(
                        mNetworkTemplate, bucketStart, bucketEnd);
                if (bucket != null) {
                    usage = bucket.getRxBytes() + bucket.getTxBytes();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception querying network detail.", e);
            }
            data.add(new NetworkCycleData.Builder()
                .setStartTime(bucketStart).setEndTime(bucketEnd).setTotalUsage(usage).build());
            bucketStart = bucketEnd;
            bucketEnd += NetworkCycleChartData.BUCKET_DURATION_MS;
        }
        return data;
    }

    public static abstract class Builder<T extends NetworkCycleChartDataLoader>
            extends NetworkCycleDataLoader.Builder<T> {

        public Builder(Context context) {
            super(context);
        }

    }

}
