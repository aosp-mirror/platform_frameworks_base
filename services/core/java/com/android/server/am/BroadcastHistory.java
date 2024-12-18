/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.os.Bundle;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Collection of recent historical broadcasts that are available to be dumped
 * for debugging purposes. Automatically trims itself over time.
 */
public class BroadcastHistory {
    private final int MAX_BROADCAST_HISTORY;
    private final int MAX_BROADCAST_SUMMARY_HISTORY;

    public BroadcastHistory(@NonNull BroadcastConstants constants) {
        MAX_BROADCAST_HISTORY = constants.MAX_HISTORY_COMPLETE_SIZE;
        MAX_BROADCAST_SUMMARY_HISTORY = constants.MAX_HISTORY_SUMMARY_SIZE;

        mBroadcastHistory = new BroadcastRecord[MAX_BROADCAST_HISTORY];
        mBroadcastSummaryHistory = new Intent[MAX_BROADCAST_SUMMARY_HISTORY];
        mSummaryHistoryEnqueueTime = new long[MAX_BROADCAST_SUMMARY_HISTORY];
        mSummaryHistoryDispatchTime = new long[MAX_BROADCAST_SUMMARY_HISTORY];
        mSummaryHistoryFinishTime = new long[MAX_BROADCAST_SUMMARY_HISTORY];
    }

    /**
     * List of broadcasts in frozen processes that are yet to be enqueued.
     */
    private final ArrayList<BroadcastRecord> mFrozenBroadcasts = new ArrayList<>();

    /**
     * List of broadcasts which are being delivered or yet to be delivered.
     */
    private final ArrayList<BroadcastRecord> mPendingBroadcasts = new ArrayList<>();

    /**
     * Historical data of past broadcasts, for debugging.  This is a ring buffer
     * whose last element is at mHistoryNext.
     */
    final BroadcastRecord[] mBroadcastHistory;
    int mHistoryNext = 0;

    /**
     * Summary of historical data of past broadcasts, for debugging.  This is a
     * ring buffer whose last element is at mSummaryHistoryNext.
     */
    final Intent[] mBroadcastSummaryHistory;
    int mSummaryHistoryNext = 0;

    /**
     * Various milestone timestamps of entries in the mBroadcastSummaryHistory ring
     * buffer, also tracked via the mSummaryHistoryNext index.  These are all in wall
     * clock time, not elapsed.
     */
    final long[] mSummaryHistoryEnqueueTime;
    final long[] mSummaryHistoryDispatchTime;
    final long[] mSummaryHistoryFinishTime;

    void onBroadcastFrozenLocked(@NonNull BroadcastRecord r) {
        mFrozenBroadcasts.add(r);
    }

    void onBroadcastEnqueuedLocked(@NonNull BroadcastRecord r) {
        mFrozenBroadcasts.remove(r);
        mPendingBroadcasts.add(r);
    }

    void onBroadcastFinishedLocked(@NonNull BroadcastRecord r) {
        mPendingBroadcasts.remove(r);
        addBroadcastToHistoryLocked(r);
    }

    public void addBroadcastToHistoryLocked(@NonNull BroadcastRecord original) {
        // Note sometimes (only for sticky broadcasts?) we reuse BroadcastRecords,
        // So don't change the incoming record directly.
        final BroadcastRecord historyRecord = original.maybeStripForHistory();

        mBroadcastHistory[mHistoryNext] = historyRecord;
        mHistoryNext = ringAdvance(mHistoryNext, 1, MAX_BROADCAST_HISTORY);

        mBroadcastSummaryHistory[mSummaryHistoryNext] = historyRecord.intent;
        mSummaryHistoryEnqueueTime[mSummaryHistoryNext] = historyRecord.enqueueClockTime;
        mSummaryHistoryDispatchTime[mSummaryHistoryNext] = historyRecord.dispatchClockTime;
        mSummaryHistoryFinishTime[mSummaryHistoryNext] = System.currentTimeMillis();
        mSummaryHistoryNext = ringAdvance(mSummaryHistoryNext, 1, MAX_BROADCAST_SUMMARY_HISTORY);
    }

    private int ringAdvance(int x, final int increment, final int ringSize) {
        x += increment;
        if (x < 0) return (ringSize - 1);
        else if (x >= ringSize) return 0;
        else return x;
    }

    @NeverCompile
    public void dumpDebug(@NonNull ProtoOutputStream proto) {
        for (int i = 0; i < mPendingBroadcasts.size(); ++i) {
            final BroadcastRecord r = mPendingBroadcasts.get(i);
            r.dumpDebug(proto, BroadcastQueueProto.PENDING_BROADCASTS);
        }
        for (int i = 0; i < mFrozenBroadcasts.size(); ++i) {
            final BroadcastRecord r = mFrozenBroadcasts.get(i);
            r.dumpDebug(proto, BroadcastQueueProto.FROZEN_BROADCASTS);
        }

        int lastIndex = mHistoryNext;
        int ringIndex = lastIndex;
        do {
            // increasing index = more recent entry, and we want to print the most
            // recent first and work backwards, so we roll through the ring backwards.
            ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_HISTORY);
            BroadcastRecord r = mBroadcastHistory[ringIndex];
            if (r != null) {
                r.dumpDebug(proto, BroadcastQueueProto.HISTORICAL_BROADCASTS);
            }
        } while (ringIndex != lastIndex);

        lastIndex = ringIndex = mSummaryHistoryNext;
        do {
            ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_SUMMARY_HISTORY);
            Intent intent = mBroadcastSummaryHistory[ringIndex];
            if (intent == null) {
                continue;
            }
            long summaryToken = proto.start(BroadcastQueueProto.HISTORICAL_BROADCASTS_SUMMARY);
            intent.dumpDebug(proto, BroadcastQueueProto.BroadcastSummary.INTENT,
                    false, true, true, false);
            proto.write(BroadcastQueueProto.BroadcastSummary.ENQUEUE_CLOCK_TIME_MS,
                    mSummaryHistoryEnqueueTime[ringIndex]);
            proto.write(BroadcastQueueProto.BroadcastSummary.DISPATCH_CLOCK_TIME_MS,
                    mSummaryHistoryDispatchTime[ringIndex]);
            proto.write(BroadcastQueueProto.BroadcastSummary.FINISH_CLOCK_TIME_MS,
                    mSummaryHistoryFinishTime[ringIndex]);
            proto.end(summaryToken);
        } while (ringIndex != lastIndex);
    }

    @NeverCompile
    public boolean dumpLocked(@NonNull PrintWriter pw, @Nullable String dumpPackage,
            @NonNull String queueName, @NonNull SimpleDateFormat sdf,
            boolean dumpAll, boolean needSep) {
        dumpBroadcastList(pw, sdf, mFrozenBroadcasts, "Frozen");
        dumpBroadcastList(pw, sdf, mPendingBroadcasts, "Pending");

        int i;
        boolean printed = false;

        i = -1;
        int lastIndex = mHistoryNext;
        int ringIndex = lastIndex;
        do {
            // increasing index = more recent entry, and we want to print the most
            // recent first and work backwards, so we roll through the ring backwards.
            ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_HISTORY);
            BroadcastRecord r = mBroadcastHistory[ringIndex];
            if (r == null) {
                continue;
            }

            i++; // genuine record of some sort even if we're filtering it out
            if (dumpPackage != null && !dumpPackage.equals(r.callerPackage)) {
                continue;
            }
            if (!printed) {
                if (needSep) {
                    pw.println();
                }
                needSep = true;
                pw.println("  Historical broadcasts [" + queueName + "]:");
                printed = true;
            }
            if (dumpAll) {
                pw.print("  Historical Broadcast " + queueName + " #");
                        pw.print(i); pw.println(":");
                r.dump(pw, "    ", sdf);
            } else {
                pw.print("  #"); pw.print(i); pw.print(": "); pw.println(r);
                pw.print("    ");
                pw.println(r.intent.toShortString(false, true, true, false));
                if (r.targetComp != null && r.targetComp != r.intent.getComponent()) {
                    pw.print("    targetComp: "); pw.println(r.targetComp.toShortString());
                }
                Bundle bundle = r.intent.getExtras();
                if (bundle != null) {
                    pw.print("    extras: "); pw.println(bundle.toString());
                }
            }
        } while (ringIndex != lastIndex);

        if (dumpPackage == null) {
            lastIndex = ringIndex = mSummaryHistoryNext;
            if (dumpAll) {
                printed = false;
                i = -1;
            } else {
                // roll over the 'i' full dumps that have already been issued
                for (int j = i;
                        j > 0 && ringIndex != lastIndex;) {
                    ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_SUMMARY_HISTORY);
                    BroadcastRecord r = mBroadcastHistory[ringIndex];
                    if (r == null) {
                        continue;
                    }
                    j--;
                }
            }
            // done skipping; dump the remainder of the ring. 'i' is still the ordinal within
            // the overall broadcast history.
            do {
                ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_SUMMARY_HISTORY);
                Intent intent = mBroadcastSummaryHistory[ringIndex];
                if (intent == null) {
                    continue;
                }
                if (!printed) {
                    if (needSep) {
                        pw.println();
                    }
                    needSep = true;
                    pw.println("  Historical broadcasts summary [" + queueName + "]:");
                    printed = true;
                }
                if (!dumpAll && i >= 50) {
                    pw.println("  ...");
                    break;
                }
                i++;
                pw.print("  #"); pw.print(i); pw.print(": ");
                pw.println(intent.toShortString(false, true, true, false));
                pw.print("    ");
                TimeUtils.formatDuration(mSummaryHistoryDispatchTime[ringIndex]
                        - mSummaryHistoryEnqueueTime[ringIndex], pw);
                pw.print(" dispatch ");
                TimeUtils.formatDuration(mSummaryHistoryFinishTime[ringIndex]
                        - mSummaryHistoryDispatchTime[ringIndex], pw);
                pw.println(" finish");
                pw.print("    enq=");
                pw.print(sdf.format(new Date(mSummaryHistoryEnqueueTime[ringIndex])));
                pw.print(" disp=");
                pw.print(sdf.format(new Date(mSummaryHistoryDispatchTime[ringIndex])));
                pw.print(" fin=");
                pw.println(sdf.format(new Date(mSummaryHistoryFinishTime[ringIndex])));
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    pw.print("    extras: "); pw.println(bundle.toString());
                }
            } while (ringIndex != lastIndex);
        }
        return needSep;
    }

    private void dumpBroadcastList(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
            @NonNull ArrayList<BroadcastRecord> broadcasts, @NonNull String flavor) {
        pw.print("  "); pw.print(flavor); pw.println(" broadcasts:");
        if (broadcasts.isEmpty()) {
            pw.println("    <empty>");
        } else {
            for (int idx = broadcasts.size() - 1; idx >= 0; --idx) {
                final BroadcastRecord r = broadcasts.get(idx);
                pw.print(flavor); pw.print("  broadcast #"); pw.print(idx); pw.println(":");
                r.dump(pw, "    ", sdf);
            }
        }
    }
}
