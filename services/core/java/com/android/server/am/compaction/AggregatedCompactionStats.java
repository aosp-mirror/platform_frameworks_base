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

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;

class AggregatedCompactionStats {
    // Throttling stats
    public long mFullCompactRequested;
    public long mSomeCompactRequested;
    public long mFullCompactPerformed;
    public long mSomeCompactPerformed;
    public long mProcCompactionsNoPidThrottled;
    public long mProcCompactionsOomAdjThrottled;
    public long mProcCompactionsTimeThrottled;
    public long mProcCompactionsRSSThrottled;
    public long mProcCompactionsMiscThrottled;

    // Memory stats
    public long mTotalDeltaAnonRssKBs;
    public long mTotalZramConsumedKBs;
    public long mTotalAnonMemFreedKBs;
    public long mSumOrigAnonRss;
    public double mMaxCompactEfficiency;
    public double mMaxSwapEfficiency;

    // Cpu time
    public long mTotalCpuTimeMillis;

    public long getThrottledSome() { return mSomeCompactRequested - mSomeCompactPerformed; }

    public long getThrottledFull() { return mFullCompactRequested - mFullCompactPerformed; }

    public void addMemStats(long anonRssSaved, long zramConsumed, long memFreed,
            long origAnonRss, long totalCpuTimeMillis) {
        final double compactEfficiency = memFreed / (double) origAnonRss;
        if (compactEfficiency > mMaxCompactEfficiency) {
            mMaxCompactEfficiency = compactEfficiency;
        }
        final double swapEfficiency = anonRssSaved / (double) origAnonRss;
        if (swapEfficiency > mMaxSwapEfficiency) {
            mMaxSwapEfficiency = swapEfficiency;
        }
        mTotalDeltaAnonRssKBs += anonRssSaved;
        mTotalZramConsumedKBs += zramConsumed;
        mTotalAnonMemFreedKBs += memFreed;
        mSumOrigAnonRss += origAnonRss;
        mTotalCpuTimeMillis += totalCpuTimeMillis;
    }

    @NeverCompile
    public void dump(PrintWriter pw) {
        long totalCompactRequested = mSomeCompactRequested + mFullCompactRequested;
        long totalCompactPerformed = mSomeCompactPerformed + mFullCompactPerformed;
        pw.println("    Performed / Requested:");
        pw.println("      Some: (" + mSomeCompactPerformed + "/" + mSomeCompactRequested + ")");
        pw.println("      Full: (" + mFullCompactPerformed + "/" + mFullCompactRequested + ")");

        long throttledSome = getThrottledSome();
        long throttledFull = getThrottledFull();

        if (throttledSome > 0 || throttledFull > 0) {
            pw.println("    Throttled:");
            pw.println("       Some: " + throttledSome + " Full: " + throttledFull);
            pw.println("    Throttled by Type:");
            final long compactionsThrottled = totalCompactRequested - totalCompactPerformed;
            // Any throttle that was not part of the previous categories
            final long unaccountedThrottled = compactionsThrottled
                    - mProcCompactionsNoPidThrottled - mProcCompactionsOomAdjThrottled
                    - mProcCompactionsTimeThrottled - mProcCompactionsRSSThrottled
                    - mProcCompactionsMiscThrottled;
            pw.println("       NoPid: " + mProcCompactionsNoPidThrottled
                    + " OomAdj: " + mProcCompactionsOomAdjThrottled + " Time: "
                    + mProcCompactionsTimeThrottled + " RSS: " + mProcCompactionsRSSThrottled
                    + " Misc: " + mProcCompactionsMiscThrottled
                    + " Unaccounted: " + unaccountedThrottled);
            final double compactThrottlePercentage =
                    (compactionsThrottled / (double) totalCompactRequested) * 100.0;
            pw.println("    Throttle Percentage: " + compactThrottlePercentage);
        }

        if (mFullCompactPerformed > 0) {
            pw.println("    -----Memory Stats----");
            pw.println("    Total Delta Anon RSS (KB) : " + mTotalDeltaAnonRssKBs);
            pw.println("    Total Physical ZRAM Consumed (KB): " + mTotalZramConsumedKBs);
            // Anon Mem Freed = Delta Anon RSS - ZRAM Consumed
            pw.println("    Total Anon Memory Freed (KB): " + mTotalAnonMemFreedKBs);
            pw.println("    Avg Swap Efficiency (KB) (Delta Anon RSS/Orig Anon RSS): "
                    + (mTotalDeltaAnonRssKBs / (double) mSumOrigAnonRss));
            pw.println("    Max Swap Efficiency: " + mMaxSwapEfficiency);
            // This tells us how much anon memory we were able to free thanks to running
            // compaction
            pw.println("    Avg Compaction Efficiency (Anon Freed/Anon RSS): "
                    + (mTotalAnonMemFreedKBs / (double) mSumOrigAnonRss));
            pw.println("    Max Compaction Efficiency: " + mMaxCompactEfficiency);
            // This tells us how effective is the compression algorithm in physical memory
            pw.println("    Avg Compression Ratio (1 - ZRAM Consumed/DeltaAnonRSS): "
                    + (1.0 - mTotalZramConsumedKBs / (double) mTotalDeltaAnonRssKBs));
            long avgKBsPerProcCompact = mFullCompactPerformed > 0
                    ? (mTotalAnonMemFreedKBs / mFullCompactPerformed)
                    : 0;
            pw.println("    Avg Anon Mem Freed/Compaction (KB) : " + avgKBsPerProcCompact);
            double compactionCost =
                    mTotalCpuTimeMillis / (mTotalAnonMemFreedKBs / 1024.0); // ms/MB
            pw.println("    Compaction Cost (ms/MB): " + compactionCost);
        }
    }
}
