/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import static android.text.format.DateUtils.HOUR_IN_MILLIS;

import static com.android.server.tare.TareUtils.appToString;

import android.annotation.NonNull;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseArrayMap;

import com.android.internal.annotations.GuardedBy;

/**
 * Maintains the current TARE state and handles writing it to disk and reading it back from disk.
 */
public class Scribe {
    private static final String TAG = "TARE-" + Scribe.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    /** The maximum number of transactions to dump per ledger. */
    private static final int MAX_NUM_TRANSACTION_DUMP = 25;
    /**
     * The maximum amount of time we'll keep a transaction around for.
     * For now, only keep transactions we actually have a use for. We can increase it if we want
     * to use older transactions or provide older transactions to apps.
     */
    private static final long MAX_TRANSACTION_AGE_MS = 24 * HOUR_IN_MILLIS;

    private final InternalResourceService mIrs;

    @GuardedBy("mIrs.mLock")
    private long mLastReclamationTime;
    @GuardedBy("mIrs.mLock")
    private long mNarcsInCirculation;
    @GuardedBy("mIrs.mLock")
    private final SparseArrayMap<String, Ledger> mLedgers = new SparseArrayMap<>();

    private final Runnable mCleanRunnable = this::cleanupLedgers;

    Scribe(InternalResourceService irs) {
        mIrs = irs;
    }

    @GuardedBy("mIrs.mLock")
    void adjustNarcsInCirculationLocked(long delta) {
        if (delta != 0) {
            // No point doing any work if the change is 0.
            mNarcsInCirculation += delta;
        }
    }

    @GuardedBy("mIrs.mLock")
    void discardLedgerLocked(final int userId, @NonNull final String pkgName) {
        mLedgers.delete(userId, pkgName);
    }

    @GuardedBy("mIrs.mLock")
    long getLastReclamationTimeLocked() {
        return mLastReclamationTime;
    }

    @GuardedBy("mIrs.mLock")
    @NonNull
    Ledger getLedgerLocked(final int userId, @NonNull final String pkgName) {
        Ledger ledger = mLedgers.get(userId, pkgName);
        if (ledger == null) {
            ledger = new Ledger();
            mLedgers.add(userId, pkgName, ledger);
        }
        return ledger;
    }

    /** Returns the total amount of narcs currently allocated to apps. */
    @GuardedBy("mIrs.mLock")
    long getNarcsInCirculationLocked() {
        return mNarcsInCirculation;
    }

    @GuardedBy("mIrs.mLock")
    void setLastReclamationTimeLocked(long time) {
        mLastReclamationTime = time;
    }

    @GuardedBy("mIrs.mLock")
    void tearDownLocked() {
        mLedgers.clear();
        mNarcsInCirculation = 0;
        mLastReclamationTime = 0;
    }

    private void scheduleCleanup(long earliestEndTime) {
        if (earliestEndTime == Long.MAX_VALUE) {
            return;
        }
        // This is just cleanup to manage memory. We don't need to do it too often or at the exact
        // intended real time, so the delay that comes from using the Handler (and is limited
        // to uptime) should be fine.
        final long delayMs = Math.max(HOUR_IN_MILLIS,
                earliestEndTime + MAX_TRANSACTION_AGE_MS - System.currentTimeMillis());
        TareHandlerThread.getHandler().postDelayed(mCleanRunnable, delayMs);
    }

    private void cleanupLedgers() {
        synchronized (mIrs.getLock()) {
            TareHandlerThread.getHandler().removeCallbacks(mCleanRunnable);
            long earliestEndTime = Long.MAX_VALUE;
            for (int uIdx = mLedgers.numMaps() - 1; uIdx >= 0; --uIdx) {
                final int userId = mLedgers.keyAt(uIdx);

                for (int pIdx = mLedgers.numElementsForKey(userId) - 1; pIdx >= 0; --pIdx) {
                    final String pkgName = mLedgers.keyAt(uIdx, pIdx);
                    final Ledger ledger = mLedgers.get(userId, pkgName);
                    ledger.removeOldTransactions(MAX_TRANSACTION_AGE_MS);
                    Ledger.Transaction transaction = ledger.getEarliestTransaction();
                    if (transaction != null) {
                        earliestEndTime = Math.min(earliestEndTime, transaction.endTimeMs);
                    }
                }
            }
            scheduleCleanup(earliestEndTime);
        }
    }

    @GuardedBy("mIrs.mLock")
    void dumpLocked(IndentingPrintWriter pw) {
        pw.println("Ledgers:");
        pw.increaseIndent();
        mLedgers.forEach((userId, pkgName, ledger) -> {
            pw.print(appToString(userId, pkgName));
            if (mIrs.isSystem(userId, pkgName)) {
                pw.print(" (system)");
            }
            pw.println();
            pw.increaseIndent();
            ledger.dump(pw, MAX_NUM_TRANSACTION_DUMP);
            pw.decreaseIndent();
        });
        pw.decreaseIndent();
    }
}
