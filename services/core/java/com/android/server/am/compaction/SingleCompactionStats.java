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

import android.app.ActivityManagerInternal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.am.CachedAppOptimizer;
import com.android.server.am.OomAdjuster;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;
import java.util.Random;

public final class SingleCompactionStats {
    private static final float STATSD_SAMPLE_RATE = 0.1f;
    private static final Random mRandom = new Random();
    private final long[] mRssAfterCompaction;
    public CachedAppOptimizer.CompactSource mSourceType;
    public String mProcessName;
    public final int mUid;
    public long mDeltaAnonRssKBs;
    public long mZramConsumedKBs;
    public long mAnonMemFreedKBs;
    public float mCpuTimeMillis;
    public long mOrigAnonRss;
    public int mProcState;
    public int mOomAdj;
    public @ActivityManagerInternal.OomAdjReason int mOomAdjReason;

    SingleCompactionStats(long[] rss, CachedAppOptimizer.CompactSource source, String processName,
            long deltaAnonRss, long zramConsumed, long anonMemFreed, long origAnonRss,
            long cpuTimeMillis, int procState, int oomAdj,
            @ActivityManagerInternal.OomAdjReason int oomAdjReason, int uid) {
        mRssAfterCompaction = rss;
        mSourceType = source;
        mProcessName = processName;
        mUid = uid;
        mDeltaAnonRssKBs = deltaAnonRss;
        mZramConsumedKBs = zramConsumed;
        mAnonMemFreedKBs = anonMemFreed;
        mCpuTimeMillis = cpuTimeMillis;
        mOrigAnonRss = origAnonRss;
        mProcState = procState;
        mOomAdj = oomAdj;
        mOomAdjReason = oomAdjReason;
    }

    double getCompactEfficiency() { return mAnonMemFreedKBs / (double) mOrigAnonRss; }

    double getSwapEfficiency() { return mDeltaAnonRssKBs / (double) mOrigAnonRss; }

    double getCompactCost() {
        // mCpuTimeMillis / (anonMemFreedKBs/1024) and metric is in (ms/MB)
        return mCpuTimeMillis / (double) mAnonMemFreedKBs * 1024;
    }

    public long[] getRssAfterCompaction() {
        return mRssAfterCompaction;
    }

    @NeverCompile
    void dump(PrintWriter pw) {
        pw.println("    (" + mProcessName + "," + mSourceType.name() + "," + mDeltaAnonRssKBs
                + "," + mZramConsumedKBs + "," + mAnonMemFreedKBs + ","
                + getSwapEfficiency() + "," + getCompactEfficiency()
                + "," + getCompactCost() + "," + mProcState + "," + mOomAdj + ","
                + OomAdjuster.oomAdjReasonToString(mOomAdjReason) + ")");
    }

    void sendStat() {
        if (mRandom.nextFloat() < STATSD_SAMPLE_RATE) {
            FrameworkStatsLog.write(FrameworkStatsLog.APP_COMPACTED_V2, mUid, mProcState,
                    mOomAdj, mDeltaAnonRssKBs, mZramConsumedKBs, mCpuTimeMillis, mOrigAnonRss,
                    mOomAdjReason);
        }
    }
}