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
package com.android.internal.util;

import android.os.SystemClock;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

import java.io.PrintWriter;

public class ProviderAccessStats {
    private final Object mLock = new Object();

    private final long mStartUptime = SystemClock.uptimeMillis();

    private final SparseBooleanArray mAllCallingUids = new SparseBooleanArray();
    private final SparseLongArray mQueryStats = new SparseLongArray(16);
    private final SparseLongArray mBatchStats = new SparseLongArray(0);
    private final SparseLongArray mInsertStats = new SparseLongArray(0);
    private final SparseLongArray mUpdateStats = new SparseLongArray(0);
    private final SparseLongArray mDeleteStats = new SparseLongArray(0);
    private final SparseLongArray mInsertInBatchStats = new SparseLongArray(0);
    private final SparseLongArray mUpdateInBatchStats = new SparseLongArray(0);
    private final SparseLongArray mDeleteInBatchStats = new SparseLongArray(0);

    private final SparseLongArray mOperationDurationMillis = new SparseLongArray(16);

    private static class PerThreadData {
        public int nestCount;
        public long startUptimeMillis;
    }

    private final ThreadLocal<PerThreadData> mThreadLocal =
            ThreadLocal.withInitial(() -> new PerThreadData());

    private void incrementStats(int callingUid, SparseLongArray stats) {
        synchronized (mLock) {
            stats.put(callingUid, stats.get(callingUid) + 1);
            mAllCallingUids.put(callingUid, true);
        }

        final PerThreadData data = mThreadLocal.get();
        data.nestCount++;
        if (data.nestCount == 1) {
            data.startUptimeMillis = SystemClock.uptimeMillis();
        }
    }

    private void incrementStats(int callingUid, boolean inBatch,
            SparseLongArray statsNonBatch, SparseLongArray statsInBatch) {
        incrementStats(callingUid, inBatch ? statsInBatch : statsNonBatch);
    }

    public final void incrementInsertStats(int callingUid, boolean inBatch) {
        incrementStats(callingUid, inBatch, mInsertStats, mInsertInBatchStats);
    }

    public final void incrementUpdateStats(int callingUid, boolean inBatch) {
        incrementStats(callingUid, inBatch, mUpdateStats, mUpdateInBatchStats);
    }

    public final void incrementDeleteStats(int callingUid, boolean inBatch) {
        incrementStats(callingUid, inBatch, mDeleteStats, mDeleteInBatchStats);
    }

    public final void incrementQueryStats(int callingUid) {
        incrementStats(callingUid, mQueryStats);
    }

    public final void incrementBatchStats(int callingUid) {
        incrementStats(callingUid, mBatchStats);
    }

    public void finishOperation(int callingUid) {
        final PerThreadData data = mThreadLocal.get();
        data.nestCount--;
        if (data.nestCount == 0) {
            // Because we only have millisecond granularity, let's always attribute at least 1ms
            // for each operation.
            final long duration = Math.max(1, SystemClock.uptimeMillis() - data.startUptimeMillis);

            synchronized (mLock) {
                mOperationDurationMillis.put(callingUid,
                        mOperationDurationMillis.get(callingUid) + duration);
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.print("  Process uptime: ");
            pw.print((SystemClock.uptimeMillis() - mStartUptime) / (60 * 1000));
            pw.println(" minutes");
            pw.println();

            pw.print(prefix);
            pw.println("Client activities:");
            pw.print(prefix);
            pw.println("  UID        Query  Insert Update Delete   Batch Insert Update Delete"
                    + "          Sec");
            for (int i = 0; i < mAllCallingUids.size(); i++) {
                final int uid = mAllCallingUids.keyAt(i);
                pw.print(prefix);
                pw.println(String.format(
                        "  %-9d %6d  %6d %6d %6d  %6d %6d %6d %6d %12.3f",
                        uid,
                        mQueryStats.get(uid),
                        mInsertStats.get(uid),
                        mUpdateStats.get(uid),
                        mDeleteStats.get(uid),
                        mBatchStats.get(uid),
                        mInsertInBatchStats.get(uid),
                        mUpdateInBatchStats.get(uid),
                        mDeleteInBatchStats.get(uid),
                        (mOperationDurationMillis.get(uid) / 1000.0)
                ));
            }
            pw.println();
        }
    }
}
