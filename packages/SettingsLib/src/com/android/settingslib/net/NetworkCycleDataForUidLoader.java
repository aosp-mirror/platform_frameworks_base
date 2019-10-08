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

import static android.app.usage.NetworkStats.Bucket.STATE_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;

import android.app.usage.NetworkStats;
import android.content.Context;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Loader for network data usage history. It returns a list of usage data per billing cycle for the
 * specific Uid(s).
 */
public class NetworkCycleDataForUidLoader extends
        NetworkCycleDataLoader<List<NetworkCycleDataForUid>> {
    private static final String TAG = "NetworkDataForUidLoader";

    private final List<NetworkCycleDataForUid> mData;
    private final List<Integer> mUids;
    private final boolean mRetrieveDetail;

    private NetworkCycleDataForUidLoader(Builder builder) {
        super(builder);
        mUids = builder.mUids;
        mRetrieveDetail = builder.mRetrieveDetail;
        mData = new ArrayList<>();
    }

    @Override
    void recordUsage(long start, long end) {
        try {
            long totalUsage = 0L;
            long totalForeground = 0L;
            for (int uid : mUids) {
                final NetworkStats stats = mNetworkStatsManager.queryDetailsForUid(
                        mNetworkTemplate, start, end, uid);
                final long usage = getTotalUsage(stats);
                if (usage > 0L) {
                    totalUsage += usage;
                    if (mRetrieveDetail) {
                        totalForeground += getForegroundUsage(start, end, uid);
                    }
                }
            }
            if (totalUsage > 0L) {
                final NetworkCycleDataForUid.Builder builder = new NetworkCycleDataForUid.Builder();
                builder.setStartTime(start)
                    .setEndTime(end)
                    .setTotalUsage(totalUsage);
                if (mRetrieveDetail) {
                    builder.setBackgroundUsage(totalUsage - totalForeground)
                        .setForegroundUsage(totalForeground);
                }
                mData.add(builder.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception querying network detail.", e);
        }
    }

    @Override
    List<NetworkCycleDataForUid> getCycleUsage() {
        return mData;
    }

    public static Builder<?> builder(Context context) {
        return new Builder<NetworkCycleDataForUidLoader>(context) {
            @Override
            public NetworkCycleDataForUidLoader build() {
                return new NetworkCycleDataForUidLoader(this);
            }
        };
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public List<Integer> getUids() {
        return mUids;
    }

    private long getForegroundUsage(long start, long end, int uid) {
        final NetworkStats stats = mNetworkStatsManager.queryDetailsForUidTagState(
                mNetworkTemplate, start, end, uid, TAG_NONE, STATE_FOREGROUND);
        return getTotalUsage(stats);
    }

    public static abstract class Builder<T extends NetworkCycleDataForUidLoader>
            extends NetworkCycleDataLoader.Builder<T> {

        private final List<Integer> mUids = new ArrayList<>();
        private boolean mRetrieveDetail = true;

        public Builder(Context context) {
            super(context);
        }

        public Builder<T> addUid(int uid) {
            mUids.add(uid);
            return this;
        }

        public Builder<T> setRetrieveDetail(boolean retrieveDetail) {
            mRetrieveDetail = retrieveDetail;
            return this;
        }
    }

}
