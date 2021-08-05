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

import static com.android.server.tare.TareUtils.dumpTime;
import static com.android.server.tare.TareUtils.getCurrentTimeMillis;
import static com.android.server.tare.TareUtils.narcToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.IndentingPrintWriter;
import android.util.SparseLongArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Ledger to track the last recorded balance and recent activities of an app.
 */
class Ledger {
    static class Transaction {
        public final long startTimeMs;
        public final long endTimeMs;
        public final int eventId;
        @Nullable
        public final String tag;
        public final long delta;

        Transaction(long startTimeMs, long endTimeMs,
                int eventId, @Nullable String tag, long delta) {
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.eventId = eventId;
            this.tag = tag;
            this.delta = delta;
        }
    }

    /** Last saved balance. This doesn't take currently ongoing events into account. */
    private long mCurrentBalance = 0;
    private final List<Transaction> mTransactions = new ArrayList<>();
    private final SparseLongArray mCumulativeDeltaPerReason = new SparseLongArray();
    private long mEarliestSumTime;

    Ledger() {
    }

    long getCurrentBalance() {
        return mCurrentBalance;
    }

    @Nullable
    Transaction getEarliestTransaction() {
        if (mTransactions.size() > 0) {
            return mTransactions.get(0);
        }
        return null;
    }

    void recordTransaction(@NonNull Transaction transaction) {
        mTransactions.add(transaction);
        mCurrentBalance += transaction.delta;

        final long sum = mCumulativeDeltaPerReason.get(transaction.eventId);
        mCumulativeDeltaPerReason.put(transaction.eventId, sum + transaction.delta);
        mEarliestSumTime = Math.min(mEarliestSumTime, transaction.startTimeMs);
    }

    long get24HourSum(int eventId, final long now) {
        final long windowStartTime = now - 24 * HOUR_IN_MILLIS;
        if (mEarliestSumTime < windowStartTime) {
            // Need to redo sums
            mCumulativeDeltaPerReason.clear();
            for (int i = mTransactions.size() - 1; i >= 0; --i) {
                final Transaction transaction = mTransactions.get(i);
                if (transaction.endTimeMs <= windowStartTime) {
                    break;
                }
                long sum = mCumulativeDeltaPerReason.get(transaction.eventId);
                if (transaction.startTimeMs >= windowStartTime) {
                    sum += transaction.delta;
                } else {
                    // Pro-rate durationed deltas. Intentionally floor the result.
                    sum += (long) (1.0 * (transaction.endTimeMs - windowStartTime)
                            * transaction.delta)
                            / (transaction.endTimeMs - transaction.startTimeMs);
                }
                mCumulativeDeltaPerReason.put(transaction.eventId, sum);
            }
            mEarliestSumTime = windowStartTime;
        }
        return mCumulativeDeltaPerReason.get(eventId);
    }

    /** Deletes transactions that are older than {@code minAgeMs}. */
    void removeOldTransactions(long minAgeMs) {
        final long cutoff = getCurrentTimeMillis() - minAgeMs;
        while (mTransactions.size() > 0 && mTransactions.get(0).endTimeMs <= cutoff) {
            mTransactions.remove(0);
        }
    }

    void dump(IndentingPrintWriter pw) {
        pw.print("Current balance", narcToString(getCurrentBalance())).println();
        mTransactions.forEach((transaction) -> {
            dumpTime(pw, transaction.startTimeMs);
            pw.print("--");
            dumpTime(pw, transaction.endTimeMs);
            pw.print(": ");
            pw.print(EconomicPolicy.eventToString(transaction.eventId));
            pw.print(" --> ");
            pw.println(narcToString(transaction.delta));
        });
    }
}
