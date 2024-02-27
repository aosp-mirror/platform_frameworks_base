/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.stats.pull;

import android.app.ActivityManager;
import android.app.StatsManager;
import android.app.usage.NetworkStatsManager;
import android.net.NetworkStats;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.StatsEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.selinux.RateLimiter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Aggregates Mobile Data Usage by process state per uid
 */
class AggregatedMobileDataStatsPuller {
    private static final String TAG = "AggregatedMobileDataStatsPuller";

    private static final boolean DEBUG = false;

    private static class UidProcState {

        private final int mUid;
        private final int mState;

        UidProcState(int uid, int state) {
            mUid = uid;
            mState = state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UidProcState key)) return false;
            return mUid == key.mUid && mState == key.mState;
        }

        @Override
        public int hashCode() {
            int result = mUid;
            result = 31 * result + mState;
            return result;
        }

        public int getUid() {
            return mUid;
        }

        public int getState() {
            return mState;
        }

    }

    private static class MobileDataStats {
        private long mRxPackets = 0;
        private long mTxPackets = 0;
        private long mRxBytes = 0;
        private long mTxBytes = 0;

        public long getRxPackets() {
            return mRxPackets;
        }

        public long getTxPackets() {
            return mTxPackets;
        }

        public long getRxBytes() {
            return mRxBytes;
        }

        public long getTxBytes() {
            return mTxBytes;
        }

        public void addRxPackets(long rxPackets) {
            mRxPackets += rxPackets;
        }

        public void addTxPackets(long txPackets) {
            mTxPackets += txPackets;
        }

        public void addRxBytes(long rxBytes) {
            mRxBytes += rxBytes;
        }

        public void addTxBytes(long txBytes) {
            mTxBytes += txBytes;
        }

        public boolean isEmpty() {
            return mRxPackets == 0 && mTxPackets == 0 && mRxBytes == 0 && mTxBytes == 0;
        }
    }

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<UidProcState, MobileDataStats> mUidStats;

    private final SparseIntArray mUidPreviousState;

    private NetworkStats mLastMobileUidStats = new NetworkStats(0, -1);

    private final NetworkStatsManager mNetworkStatsManager;

    private final Handler mMobileDataStatsHandler;

    private final RateLimiter mRateLimiter;

    AggregatedMobileDataStatsPuller(NetworkStatsManager networkStatsManager) {
        if (DEBUG) {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_SYSTEM_SERVER)) {
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                        TAG + "-AggregatedMobileDataStatsPullerInit");
            }
        }

        mRateLimiter = new RateLimiter(/* window= */ Duration.ofSeconds(1));

        mUidStats = new ArrayMap<>();
        mUidPreviousState = new SparseIntArray();

        mNetworkStatsManager = networkStatsManager;

        HandlerThread mMobileDataStatsHandlerThread = new HandlerThread("MobileDataStatsHandler");
        mMobileDataStatsHandlerThread.start();
        mMobileDataStatsHandler = new Handler(mMobileDataStatsHandlerThread.getLooper());

        if (mNetworkStatsManager != null) {
            mMobileDataStatsHandler.post(
                    () -> {
                        updateNetworkStats(mNetworkStatsManager);
                    });
        }
        if (DEBUG) {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    public void noteUidProcessState(int uid, int state, long unusedElapsedRealtime,
                                    long unusedUptime) {
        mMobileDataStatsHandler.post(
                () -> {
                    noteUidProcessStateImpl(uid, state);
                });
    }

    public int pullDataBytesTransfer(List<StatsEvent> data) {
        synchronized (mLock) {
            return pullDataBytesTransferLocked(data);
        }
    }

    @GuardedBy("mLock")
    private MobileDataStats getUidStatsForPreviousStateLocked(int uid) {
        final int previousState = mUidPreviousState.get(uid, ActivityManager.PROCESS_STATE_UNKNOWN);
        if (DEBUG && previousState == ActivityManager.PROCESS_STATE_UNKNOWN) {
            Slog.d(TAG, "getUidStatsForPreviousStateLocked() no prev state info for uid "
                    + uid + ". Tracking stats with ActivityManager.PROCESS_STATE_UNKNOWN");
        }

        final UidProcState statsKey = new UidProcState(uid, previousState);
        MobileDataStats stats;
        if (mUidStats.containsKey(statsKey)) {
            stats = mUidStats.get(statsKey);
        } else {
            stats = new MobileDataStats();
            mUidStats.put(statsKey, stats);
        }
        return stats;
    }

    private void noteUidProcessStateImpl(int uid, int state) {
        if (mRateLimiter.tryAcquire()) {
            // noteUidProcessStateImpl can be called back to back several times while
            // the updateNetworkStats loops over several stats for multiple uids
            // and during the first call in a batch of proc state change event it can
            // contain info for uid with unknown previous state yet which can happen due to a few
            // reasons:
            // - app was just started
            // - app was started before the ActivityManagerService
            // as result stats would be created with state == ActivityManager.PROCESS_STATE_UNKNOWN
            if (mNetworkStatsManager != null) {
                updateNetworkStats(mNetworkStatsManager);
            } else {
                Slog.w(TAG, "noteUidProcessStateLocked() can not get mNetworkStatsManager");
            }
        }
        mUidPreviousState.put(uid, state);
    }

    private void updateNetworkStats(NetworkStatsManager networkStatsManager) {
        if (DEBUG) {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_SYSTEM_SERVER)) {
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, TAG + "-updateNetworkStats");
            }
        }

        final NetworkStats latestStats = networkStatsManager.getMobileUidStats();
        if (isEmpty(latestStats)) {
            if (DEBUG) {
                Slog.w(TAG, "getMobileUidStats() failed");
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
            return;
        }
        NetworkStats delta = latestStats.subtract(mLastMobileUidStats);
        mLastMobileUidStats = latestStats;

        if (!isEmpty(delta)) {
            updateNetworkStatsDelta(delta);
        } else if (DEBUG) {
            Slog.w(TAG, "updateNetworkStats() no delta");
        }
        if (DEBUG) {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    private void updateNetworkStatsDelta(NetworkStats delta) {
        synchronized (mLock) {
            for (NetworkStats.Entry entry : delta) {
                if (entry.getRxPackets() == 0 && entry.getTxPackets() == 0) {
                    continue;
                }
                MobileDataStats stats = getUidStatsForPreviousStateLocked(entry.getUid());
                stats.addTxBytes(entry.getTxBytes());
                stats.addRxBytes(entry.getRxBytes());
                stats.addTxPackets(entry.getTxPackets());
                stats.addRxPackets(entry.getRxPackets());
            }
        }
    }

    @GuardedBy("mLock")
    private int pullDataBytesTransferLocked(List<StatsEvent> pulledData) {
        if (DEBUG) {
            Slog.d(TAG, "pullDataBytesTransferLocked() start");
        }
        for (Map.Entry<UidProcState, MobileDataStats> uidStats : mUidStats.entrySet()) {
            if (!uidStats.getValue().isEmpty()) {
                MobileDataStats stats = uidStats.getValue();
                pulledData.add(FrameworkStatsLog.buildStatsEvent(
                        FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_PROC_STATE,
                        uidStats.getKey().getUid(),
                        ActivityManager.processStateAmToProto(uidStats.getKey().getState()),
                        stats.getRxBytes(),
                        stats.getRxPackets(),
                        stats.getTxBytes(),
                        stats.getTxPackets()));
            }
        }
        if (DEBUG) {
            Slog.d(TAG,
                    "pullDataBytesTransferLocked() done. results count " + pulledData.size());
        }
        return StatsManager.PULL_SUCCESS;
    }

    private static boolean isEmpty(NetworkStats stats) {
        long totalRxPackets = 0;
        long totalTxPackets = 0;
        for (NetworkStats.Entry entry : stats) {
            if (entry.getRxPackets() == 0 && entry.getTxPackets() == 0) {
                continue;
            }
            totalRxPackets += entry.getRxPackets();
            totalTxPackets += entry.getTxPackets();
            // at least one non empty entry located
            break;
        }
        final long totalPackets = totalRxPackets + totalTxPackets;
        return totalPackets == 0;
    }
}
