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

package com.android.server.power.stats;

import android.app.StatsManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.StatsEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A class to initialise and log metrics pulled by statsd. */
public class WakelockStatsFrameworkEvents {
    // statsd has a dimensional limit on the number of different keys it can handle.
    // Beyond that limit, statsd will drop data.
    //
    // When we have seem SUMMARY_THRESHOLD distinct (uid, tag, wakeLockLevel) keys,
    // we start summarizing new keys as (uid, OVERFLOW_TAG, OVERFLOW_LEVEL) to
    // reduce the number of keys we pass to statsd.
    //
    // When we reach MAX_WAKELOCK_DIMENSIONS distinct keys, we summarize all new keys
    // as (OVERFLOW_UID, HARD_CAP_TAG, OVERFLOW_LEVEL) to hard cap the number of
    // distinct keys we pass to statsd.
    @VisibleForTesting public static final int SUMMARY_THRESHOLD = 500;
    @VisibleForTesting public static final int MAX_WAKELOCK_DIMENSIONS = 1000;

    @VisibleForTesting public static final int HARD_CAP_UID = -1;
    @VisibleForTesting public static final String OVERFLOW_TAG = "*overflow*";
    @VisibleForTesting public static final String HARD_CAP_TAG = "*overflow hard cap*";
    @VisibleForTesting public static final int OVERFLOW_LEVEL = 1;

    private static class WakeLockKey {
        private int uid;
        private String tag;
        private int powerManagerWakeLockLevel;
        private int hashCode;

        WakeLockKey(int uid, String tag, int powerManagerWakeLockLevel) {
            this.uid = uid;
            this.tag = new String(tag);
            this.powerManagerWakeLockLevel = powerManagerWakeLockLevel;

            this.hashCode = Objects.hash(uid, tag, powerManagerWakeLockLevel);
        }

        int getUid() {
            return uid;
        }

        String getTag() {
            return tag;
        }

        int getPowerManagerWakeLockLevel() {
            return powerManagerWakeLockLevel;
        }

        void setOverflow() {
            tag = OVERFLOW_TAG;
            powerManagerWakeLockLevel = OVERFLOW_LEVEL;
            this.hashCode = Objects.hash(uid, tag, powerManagerWakeLockLevel);
        }

        void setHardCap() {
            uid = HARD_CAP_UID;
            tag = HARD_CAP_TAG;
            powerManagerWakeLockLevel = OVERFLOW_LEVEL;
            this.hashCode = Objects.hash(uid, tag, powerManagerWakeLockLevel);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || !(o instanceof WakeLockKey)) return false;

            WakeLockKey that = (WakeLockKey) o;
            return uid == that.uid
                    && tag.equals(that.tag)
                    && powerManagerWakeLockLevel == that.powerManagerWakeLockLevel;
        }

        @Override
        public int hashCode() {
            return this.hashCode;
        }
    }

    private static class WakeLockStats {
        // accumulated uptime attributed to this WakeLock since boot, where overlap
        // (including nesting) is ignored
        public long uptimeMillis = 0;

        // count of WakeLocks that have been acquired and then released
        public long completedCount = 0;
    }

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Map<WakeLockKey, WakeLockStats> mWakeLockStats = new HashMap<>();

    private static class WakeLockData {
        // uptime millis when first acquired
        public long acquireUptimeMillis = 0;
        public int refCount = 0;

        WakeLockData(long uptimeMillis) {
            acquireUptimeMillis = uptimeMillis;
        }
    }

    @GuardedBy("mLock")
    private final Map<WakeLockKey, WakeLockData> mOpenWakeLocks = new HashMap<>();

    public void noteStartWakeLock(
            int uid, String tag, int powerManagerWakeLockLevel, long eventUptimeMillis) {
        final WakeLockKey key = new WakeLockKey(uid, tag, powerManagerWakeLockLevel);

        synchronized (mLock) {
            WakeLockData data =
                    mOpenWakeLocks.computeIfAbsent(key, k -> new WakeLockData(eventUptimeMillis));
            data.refCount++;
            mOpenWakeLocks.put(key, data);
        }
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    public boolean inOverflow() {
        return mWakeLockStats.size() >= SUMMARY_THRESHOLD;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    public boolean inHardCap() {
        return mWakeLockStats.size() >= MAX_WAKELOCK_DIMENSIONS;
    }

    public void noteStopWakeLock(
            int uid, String tag, int powerManagerWakeLockLevel, long eventUptimeMillis) {
        WakeLockKey key = new WakeLockKey(uid, tag, powerManagerWakeLockLevel);

        synchronized (mLock) {
            WakeLockData data = mOpenWakeLocks.get(key);
            if (data == null) {
                Log.e(TAG, "WakeLock not found when stopping: " + uid + " " + tag);
                return;
            }

            if (data.refCount == 1) {
                mOpenWakeLocks.remove(key);
                long wakeLockDur = eventUptimeMillis - data.acquireUptimeMillis;

                // Rewrite key if in an overflow state.
                if (inOverflow() && !mWakeLockStats.containsKey(key)) {
                    key.setOverflow();
                    if (inHardCap() && !mWakeLockStats.containsKey(key)) {
                        key.setHardCap();
                    }
                }

                WakeLockStats stats = mWakeLockStats.computeIfAbsent(key, k -> new WakeLockStats());
                stats.uptimeMillis += wakeLockDur;
                stats.completedCount++;
                mWakeLockStats.put(key, stats);
            } else {
                data.refCount--;
                mOpenWakeLocks.put(key, data);
            }
        }
    }

    // Shim interface for testing.
    @VisibleForTesting
    public interface EventLogger {
        void logResult(
                int uid, String tag, int wakeLockLevel, long uptimeMillis, long completedCount);
    }

    public List<StatsEvent> pullFrameworkWakelockInfoAtoms() {
        List<StatsEvent> result = new ArrayList<>();
        EventLogger logger =
                new EventLogger() {
                    public void logResult(
                            int uid,
                            String tag,
                            int wakeLockLevel,
                            long uptimeMillis,
                            long completedCount) {
                        StatsEvent event =
                                StatsEvent.newBuilder()
                                        .setAtomId(FrameworkStatsLog.FRAMEWORK_WAKELOCK_INFO)
                                        .writeInt(uid)
                                        .writeString(tag)
                                        .writeInt(wakeLockLevel)
                                        .writeLong(uptimeMillis)
                                        .writeLong(completedCount)
                                        .build();
                        result.add(event);
                    }
                };
        pullFrameworkWakelockInfoAtoms(SystemClock.uptimeMillis(), logger);
        return result;
    }

    @VisibleForTesting
    public void pullFrameworkWakelockInfoAtoms(long nowMillis, EventLogger logger) {
        HashSet<WakeLockKey> keys = new HashSet<>();

        // Used to collect open WakeLocks when in an overflow state.
        HashMap<WakeLockKey, WakeLockStats> openOverflowStats = new HashMap<>();

        synchronized (mLock) {
            keys.addAll(mWakeLockStats.keySet());

            // If we are in an overflow state, an open wakelock may have a new key
            // that needs to be summarized.
            if (inOverflow()) {
                for (WakeLockKey key : mOpenWakeLocks.keySet()) {
                    if (!mWakeLockStats.containsKey(key)) {
                        WakeLockData data = mOpenWakeLocks.get(key);

                        key.setOverflow();
                        if (inHardCap() && !mWakeLockStats.containsKey(key)) {
                            key.setHardCap();
                        }
                        keys.add(key);

                        WakeLockStats stats =
                                openOverflowStats.computeIfAbsent(key, k -> new WakeLockStats());
                        stats.uptimeMillis += nowMillis - data.acquireUptimeMillis;
                        openOverflowStats.put(key, stats);
                    }
                }
            } else {
                keys.addAll(mOpenWakeLocks.keySet());
            }

            for (WakeLockKey key : keys) {
                long openWakeLockUptime = 0;
                WakeLockData data = mOpenWakeLocks.get(key);
                if (data != null) {
                    openWakeLockUptime = nowMillis - data.acquireUptimeMillis;
                }

                WakeLockStats stats = mWakeLockStats.computeIfAbsent(key, k -> new WakeLockStats());
                WakeLockStats extraTime =
                        openOverflowStats.computeIfAbsent(key, k -> new WakeLockStats());

                stats.uptimeMillis += openWakeLockUptime + extraTime.uptimeMillis;

                logger.logResult(
                        key.getUid(),
                        key.getTag(),
                        key.getPowerManagerWakeLockLevel(),
                        stats.uptimeMillis,
                        stats.completedCount);
            }
        }
    }

    private static final String TAG = "BatteryStatsPulledMetrics";

    private final StatsPullCallbackHandler mStatsPullCallbackHandler =
            new StatsPullCallbackHandler();

    private boolean mIsInitialized = false;

    public void initialize(Context context) {
        if (mIsInitialized) {
            return;
        }

        final StatsManager statsManager = context.getSystemService(StatsManager.class);
        if (statsManager == null) {
            Log.e(
                    TAG,
                    "Error retrieving StatsManager. Cannot initialize BatteryStatsPulledMetrics.");
        } else {
            Log.d(TAG, "Registering callback with StatsManager");

            // DIRECT_EXECUTOR means that callback will run on binder thread.
            statsManager.setPullAtomCallback(
                    FrameworkStatsLog.FRAMEWORK_WAKELOCK_INFO,
                    null /* metadata */,
                    ConcurrentUtils.DIRECT_EXECUTOR,
                    mStatsPullCallbackHandler);
            mIsInitialized = true;
        }
    }

    private class StatsPullCallbackHandler implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            // handle the tags appropriately.
            List<StatsEvent> events = pullEvents(atomTag);
            if (events == null) {
                return StatsManager.PULL_SKIP;
            }

            data.addAll(events);
            return StatsManager.PULL_SUCCESS;
        }

        private List<StatsEvent> pullEvents(int atomTag) {
            switch (atomTag) {
                case FrameworkStatsLog.FRAMEWORK_WAKELOCK_INFO:
                    return pullFrameworkWakelockInfoAtoms();
                default:
                    return null;
            }
        }
    }
}
