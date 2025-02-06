/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.am.compaction;

import android.annotation.IntDef;
import android.app.ActivityManagerInternal;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.CachedAppOptimizer;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

public final class CompactionStatsManager {
    private static CompactionStatsManager sInstance;

    private static String TAG = "CompactionStatsManager";

    // Size of history for the last 20 compactions for any process
    static final int LAST_COMPACTED_ANY_PROCESS_STATS_HISTORY_SIZE = 20;

    // Amount of processes supported to record for their last compaction.
    static final int LAST_COMPACTION_FOR_PROCESS_STATS_SIZE = 256;

    public static final int COMPACT_THROTTLE_REASON_NO_PID = 0;
    public static final int COMPACT_THROTTLE_REASON_OOM_ADJ = 1;
    public static final int COMPACT_THROTTLE_REASON_TIME_TOO_SOON = 2;
    public static final int COMPACT_THROTTLE_REASON_PROC_STATE = 3;
    public static final int COMPACT_THROTTLE_REASON_DELTA_RSS = 4;
    @IntDef(value = {
            COMPACT_THROTTLE_REASON_NO_PID, COMPACT_THROTTLE_REASON_OOM_ADJ,
            COMPACT_THROTTLE_REASON_TIME_TOO_SOON, COMPACT_THROTTLE_REASON_PROC_STATE,
            COMPACT_THROTTLE_REASON_DELTA_RSS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CompactThrottleReason {}

    private final LinkedHashMap<String, AggregatedProcessCompactionStats> mPerProcessCompactStats =
            new LinkedHashMap<>(256);
    private final EnumMap<CachedAppOptimizer.CompactSource, AggregatedSourceCompactionStats>
            mPerSourceCompactStats =
            new EnumMap<>(CachedAppOptimizer.CompactSource.class);

    private long mTotalCompactionDowngrades;
    private long mSystemCompactionsPerformed;
    private long mSystemTotalMemFreed;
    private EnumMap<CachedAppOptimizer.CancelCompactReason, Integer> mTotalCompactionsCancelled =
            new EnumMap<>(CachedAppOptimizer.CancelCompactReason.class);

    // Maps process ID to last compaction statistics for processes that we've fully compacted. Used
    // when evaluating throttles that we only consider for "full" compaction, so we don't store
    // data for "some" compactions. Uses LinkedHashMap to ensure insertion order is kept and
    // facilitate removal of the oldest entry.
    @VisibleForTesting
    @GuardedBy("mProcLock")
    LinkedHashMap<Integer, SingleCompactionStats> mLastCompactionStats =
            new LinkedHashMap<Integer, SingleCompactionStats>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > LAST_COMPACTION_FOR_PROCESS_STATS_SIZE;
                }
            };

    LinkedList<SingleCompactionStats> mCompactionStatsHistory =
            new LinkedList<SingleCompactionStats>() {
                @Override
                public boolean add(SingleCompactionStats e) {
                    if (size() >= LAST_COMPACTED_ANY_PROCESS_STATS_HISTORY_SIZE) {
                        this.remove();
                    }
                    return super.add(e);
                }
            };

    public static CompactionStatsManager getInstance() {
        if (sInstance == null) {
            sInstance = new CompactionStatsManager();
        }
        return sInstance;
    }

    public SingleCompactionStats getLastCompactionStats(int pid) {
        return mLastCompactionStats.get(pid);
    }

    @VisibleForTesting
    public LinkedHashMap<Integer, SingleCompactionStats> getLastCompactionStats() {
        return mLastCompactionStats;
    }

    @VisibleForTesting
    public void reinit() {
        sInstance = new CompactionStatsManager();
    }


    public void logCompactionRequested(CachedAppOptimizer.CompactSource source,
            CachedAppOptimizer.CompactProfile compactProfile, String processName) {
        AggregatedSourceCompactionStats perSourceStats = getPerSourceAggregatedCompactStat(source);
        AggregatedCompactionStats perProcStats =
                getPerProcessAggregatedCompactStat(processName);

        switch (compactProfile) {
            case SOME:
                ++perProcStats.mSomeCompactRequested;
                ++perSourceStats.mSomeCompactRequested;
                break;
            case FULL:
                ++perProcStats.mFullCompactRequested;
                ++perSourceStats.mFullCompactRequested;
                break;
            default:
                Slog.e(TAG,
                        "Stats cannot be logged for compaction type."+compactProfile);
        }
    }
    public void logCompactionThrottled(@CompactThrottleReason int reason,
            CachedAppOptimizer.CompactSource source, String processName) {
        AggregatedSourceCompactionStats perSourceStats =
                getPerSourceAggregatedCompactStat(source);
        AggregatedProcessCompactionStats perProcessStats =
                getPerProcessAggregatedCompactStat(processName);

        switch(reason) {
            case COMPACT_THROTTLE_REASON_NO_PID:
                ++perSourceStats.mProcCompactionsNoPidThrottled;
                ++perProcessStats.mProcCompactionsNoPidThrottled;
                break;
            case COMPACT_THROTTLE_REASON_OOM_ADJ:
                ++perProcessStats.mProcCompactionsOomAdjThrottled;
                ++perSourceStats.mProcCompactionsOomAdjThrottled;
                break;
            case COMPACT_THROTTLE_REASON_TIME_TOO_SOON:
                ++perProcessStats.mProcCompactionsTimeThrottled;
                ++perSourceStats.mProcCompactionsTimeThrottled;
                break;
            case COMPACT_THROTTLE_REASON_PROC_STATE:
                ++perProcessStats.mProcCompactionsMiscThrottled;
                ++perSourceStats.mProcCompactionsMiscThrottled;
                break;
            case COMPACT_THROTTLE_REASON_DELTA_RSS:
                ++perProcessStats.mProcCompactionsRSSThrottled;
                ++perSourceStats.mProcCompactionsRSSThrottled;
                break;
            default:
                break;
        }
    }

    public void logSomeCompactionPerformed(CachedAppOptimizer.CompactSource source,
            String processName) {
        AggregatedSourceCompactionStats perSourceStats =
                getPerSourceAggregatedCompactStat(source);
        AggregatedProcessCompactionStats perProcessStats =
                getPerProcessAggregatedCompactStat(processName);

        ++perSourceStats.mSomeCompactPerformed;
        ++perProcessStats.mSomeCompactPerformed;
    }

    public void logFullCompactionPerformed(
            CachedAppOptimizer.CompactSource source, String processName, long anonRssSavings,
            long zramConsumed, long memFreed, long origAnonRss, long totalCpuTimeMillis,
            long[] rssAfterCompact, int procState, int newOomAdj,
            @ActivityManagerInternal.OomAdjReason int oomAdjReason, int uid, int pid,
            boolean logFieldMetric) {
        AggregatedSourceCompactionStats perSourceStats =
                getPerSourceAggregatedCompactStat(source);
        AggregatedProcessCompactionStats perProcessStats =
                getPerProcessAggregatedCompactStat(processName);

        ++perSourceStats.mFullCompactPerformed;
        ++perProcessStats.mFullCompactPerformed;

        // Negative stats would skew averages and will likely be due to
        // noise of system doing other things so we put a floor at 0 to
        // avoid negative values.
        anonRssSavings = anonRssSavings > 0 ? anonRssSavings : 0;
        zramConsumed = zramConsumed > 0 ? zramConsumed : 0;
        memFreed = memFreed > 0 ? memFreed : 0;

        perProcessStats.addMemStats(anonRssSavings, zramConsumed, memFreed,
                origAnonRss, totalCpuTimeMillis);
        perSourceStats.addMemStats(anonRssSavings, zramConsumed, memFreed,
                origAnonRss, totalCpuTimeMillis);
        SingleCompactionStats memStats = new SingleCompactionStats(rssAfterCompact,
                source, processName, anonRssSavings, zramConsumed, memFreed,
                origAnonRss, totalCpuTimeMillis, procState, newOomAdj,
                oomAdjReason, uid);
        mLastCompactionStats.remove(pid);
        mLastCompactionStats.put(pid, memStats);
        mCompactionStatsHistory.add(memStats);
        if (!logFieldMetric) {
            memStats.sendStat();
        }
    }

    public void logCompactionDowngrade() {
        ++mTotalCompactionDowngrades;
    }

    public void logSystemCompactionPerformed(long memFreed) {
        ++mSystemCompactionsPerformed;
        mSystemTotalMemFreed += memFreed;
    }

    public void logCompactionCancelled(CachedAppOptimizer.CancelCompactReason cancelReason) {
        if (mTotalCompactionsCancelled.containsKey(cancelReason)) {
            int count = mTotalCompactionsCancelled.get(cancelReason);
            mTotalCompactionsCancelled.put(cancelReason, count + 1);
        } else {
            mTotalCompactionsCancelled.put(cancelReason, 1);
        }
    }

    private AggregatedProcessCompactionStats getPerProcessAggregatedCompactStat(
            String processName) {
        if (processName == null) {
            processName = "";
        }
        AggregatedProcessCompactionStats stats = mPerProcessCompactStats.get(processName);
        if (stats == null) {
            stats = new AggregatedProcessCompactionStats(processName);
            mPerProcessCompactStats.put(processName, stats);
        }
        return stats;
    }

    private AggregatedSourceCompactionStats getPerSourceAggregatedCompactStat(
            CachedAppOptimizer.CompactSource source) {
        AggregatedSourceCompactionStats stats = mPerSourceCompactStats.get(source);
        if (stats == null) {
            stats = new AggregatedSourceCompactionStats(source);
            mPerSourceCompactStats.put(source, stats);
        }
        return stats;
    }

    @NeverCompile
    public void dump(PrintWriter pw) {
        pw.println(" Per-Process Compaction Stats");
        long totalCompactPerformedSome = 0;
        long totalCompactPerformedFull = 0;
        for (AggregatedProcessCompactionStats stats : mPerProcessCompactStats.values()) {
            pw.println("-----" + stats.mProcessName + "-----");
            totalCompactPerformedSome += stats.mSomeCompactPerformed;
            totalCompactPerformedFull += stats.mFullCompactPerformed;
            stats.dump(pw);
            pw.println();
        }
        pw.println();
        pw.println(" Per-Source Compaction Stats");
        for (AggregatedSourceCompactionStats stats : mPerSourceCompactStats.values()) {
            pw.println("-----" + stats.mSourceType + "-----");
            stats.dump(pw);
            pw.println();
        }
        pw.println();

        pw.println("Total Compactions Performed by profile: " + totalCompactPerformedSome
                + " some, " + totalCompactPerformedFull + " full");
        pw.println("Total compactions downgraded: " + mTotalCompactionDowngrades);
        pw.println("Total compactions cancelled by reason: ");
        for (CachedAppOptimizer.CancelCompactReason reason : mTotalCompactionsCancelled.keySet()) {
            pw.println("    " + reason + ": " + mTotalCompactionsCancelled.get(reason));
        }
        pw.println();

        pw.println(" System Compaction Memory Stats");
        pw.println("    Compactions Performed: " + mSystemCompactionsPerformed);
        pw.println("    Total Memory Freed (KB): " + mSystemTotalMemFreed);
        double avgKBsPerSystemCompact = mSystemCompactionsPerformed > 0
                ? mSystemTotalMemFreed / mSystemCompactionsPerformed
                : 0;
        pw.println("    Avg Mem Freed per Compact (KB): " + avgKBsPerSystemCompact);
        pw.println();
        pw.println("  Tracking last compaction stats for " + mLastCompactionStats.size()
                + " processes.");
        pw.println("Last Compaction per process stats:");
        pw.println("    (ProcessName,Source,DeltaAnonRssKBs,ZramConsumedKBs,AnonMemFreedKBs"
                + ",SwapEfficiency,CompactEfficiency,CompactCost(ms/MB),procState,oomAdj,"
                + "oomAdjReason)");
        for (Map.Entry<Integer, SingleCompactionStats> entry :
                mLastCompactionStats.entrySet()) {
            SingleCompactionStats stats = entry.getValue();
            stats.dump(pw);
        }
        pw.println();
        pw.println("Last 20 Compactions Stats:");
        pw.println("    (ProcessName,Source,DeltaAnonRssKBs,ZramConsumedKBs,AnonMemFreedKBs,"
                + "SwapEfficiency,CompactEfficiency,CompactCost(ms/MB),procState,oomAdj,"
                + "oomAdjReason)");
        for (SingleCompactionStats stats : mCompactionStatsHistory) {
            stats.dump(pw);
        }
    }
}
