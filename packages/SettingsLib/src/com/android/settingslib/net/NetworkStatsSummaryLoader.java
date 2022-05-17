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
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.NetworkTemplate;
import android.util.Log;

import androidx.loader.content.AsyncTaskLoader;

/**
 * Loader for retrieving the network stats summary for all UIDs.
 */
public class NetworkStatsSummaryLoader extends AsyncTaskLoader<NetworkStats> {

    private static final String TAG = "NetworkDetailLoader";
    private final NetworkStatsManager mNetworkStatsManager;
    private final long mStart;
    private final long mEnd;
    private final NetworkTemplate mNetworkTemplate;

    private NetworkStatsSummaryLoader(Builder builder) {
        super(builder.mContext);
        mStart = builder.mStart;
        mEnd = builder.mEnd;
        mNetworkTemplate = builder.mNetworkTemplate;
        mNetworkStatsManager = (NetworkStatsManager)
                builder.mContext.getSystemService(Context.NETWORK_STATS_SERVICE);
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();
        forceLoad();
    }

    @Override
    public NetworkStats loadInBackground() {
        try {
            return mNetworkStatsManager.querySummary(mNetworkTemplate, mStart, mEnd);
        } catch (RuntimeException e) {
            Log.e(TAG, "Exception querying network detail.", e);
            return null;
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

    public static class Builder {
        private final Context mContext;
        private long mStart;
        private long mEnd;
        private NetworkTemplate mNetworkTemplate;

        public Builder(Context context) {
            mContext = context;
        }

        public Builder setStartTime(long start) {
            mStart = start;
            return this;
        }

        public Builder setEndTime(long end) {
            mEnd = end;
            return this;
        }

        /**
         * Set {@link NetworkTemplate} for builder
         */
        public Builder setNetworkTemplate(NetworkTemplate template) {
            mNetworkTemplate = template;
            return this;
        }

        public NetworkStatsSummaryLoader build() {
            return new NetworkStatsSummaryLoader(this);
        }
    }
}
