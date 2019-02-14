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
import android.util.Pair;

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
    protected final String mSubId;
    protected final int mNetworkType;
    private final NetworkPolicy mPolicy;
    private final NetworkTemplate mNetworkTemplate;
    private final ArrayList<Long> mCycles;
    @VisibleForTesting
    final INetworkStatsService mNetworkStatsService;

    protected NetworkCycleDataLoader(Builder<?> builder) {
        super(builder.mContext);
        mSubId = builder.mSubId;
        mNetworkType = builder.mNetworkType;
        mNetworkTemplate = builder.mNetworkTemplate;
        mCycles = builder.mCycles;
        mNetworkStatsManager = (NetworkStatsManager)
            builder.mContext.getSystemService(Context.NETWORK_STATS_SERVICE);
        mNetworkStatsService = INetworkStatsService.Stub.asInterface(
            ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
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
        try {
            final INetworkStatsSession networkSession = mNetworkStatsService.openSession();
            final NetworkStatsHistory networkHistory = networkSession.getHistoryForNetwork(
                mNetworkTemplate, FIELD_RX_BYTES | FIELD_TX_BYTES);
            final long historyStart = networkHistory.getStart();
            final long historyEnd = networkHistory.getEnd();

            long cycleEnd = historyEnd;
            while (cycleEnd > historyStart) {
                final long cycleStart = cycleEnd - (DateUtils.WEEK_IN_MILLIS * 4);
                recordUsage(cycleStart, cycleEnd);
                cycleEnd = cycleStart;
            }

            TrafficStats.closeQuietly(networkSession);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
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

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public ArrayList<Long> getCycles() {
        return mCycles;
    }

    public static abstract class Builder<T extends NetworkCycleDataLoader> {
        private final Context mContext;
        private String mSubId;
        private int mNetworkType;
        private NetworkTemplate mNetworkTemplate;
        private ArrayList<Long> mCycles;

        public Builder (Context context) {
            mContext = context;
        }

        public Builder<T> setSubscriberId(String subId) {
            mSubId = subId;
            return this;
        }

        public Builder<T> setNetworkTemplate(NetworkTemplate template) {
            mNetworkTemplate = template;
            mNetworkType = DataUsageController.getNetworkType(template);
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
