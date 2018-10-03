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

import java.util.ArrayList;
import java.util.List;

/**
 * Loader for network data usage history. It returns a list of usage data per billing cycle for a
 * specific Uid.
 */
public class NetworkCycleDataForUidLoader extends
        NetworkCycleDataLoader<List<NetworkCycleDataForUid>> {
    private static final String TAG = "NetworkDataForUidLoader";

    private final List<NetworkCycleDataForUid> mData;
    private final int mUid;
    private final boolean mRetrieveDetail;

    private NetworkCycleDataForUidLoader(Builder builder) {
        super(builder);
        mUid = builder.mUid;
        mRetrieveDetail = builder.mRetrieveDetail;
        mData = new ArrayList<NetworkCycleDataForUid>();
    }

    @Override
    void recordUsage(long start, long end) {
        try {
            final NetworkStats stats = mNetworkStatsManager.queryDetailsForUid(
                mNetworkType, mSubId, start, end, mUid);
            final long total = getTotalUsage(stats);
            if (total > 0L) {
                final NetworkCycleDataForUid.Builder builder = new NetworkCycleDataForUid.Builder();
                builder.setStartTime(start)
                    .setEndTime(end)
                    .setTotalUsage(total);
                if (mRetrieveDetail) {
                    final long foreground = getForegroundUsage(start, end);
                    builder.setBackgroundUsage(total - foreground)
                        .setForegroundUsage(foreground);
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

    private long getForegroundUsage(long start, long end) {
        final NetworkStats stats = mNetworkStatsManager.queryDetailsForUidTagState(
            mNetworkType, mSubId, start, end, mUid, TAG_NONE, STATE_FOREGROUND);
        return getTotalUsage(stats);
    }

    public static abstract class Builder<T extends NetworkCycleDataForUidLoader>
            extends NetworkCycleDataLoader.Builder<T> {

        private int mUid;
        private boolean mRetrieveDetail = true;

        public Builder(Context context) {
            super(context);
        }

        public Builder<T> setUid(int uid) {
            mUid = uid;
            return this;
        }

        public Builder<T> setRetrieveDetail(boolean retrieveDetail) {
            mRetrieveDetail = retrieveDetail;
            return this;
        }
    }

}
