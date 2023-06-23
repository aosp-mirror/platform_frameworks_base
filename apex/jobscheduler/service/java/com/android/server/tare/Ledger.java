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
import static android.util.TimeUtils.dumpTime;

import static com.android.server.tare.TareUtils.cakeToString;
import static com.android.server.tare.TareUtils.getCurrentTimeMillis;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseLongArray;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Ledger to track the last recorded balance and recent activities of an app.
 */
class Ledger {
    private static final String TAG = "TARE-" + Ledger.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    /** The window size within which rewards will be counted and used towards reward limiting. */
    private static final long TOTAL_REWARD_WINDOW_MS = 24 * HOUR_IN_MILLIS;
    /** The number of buckets to split {@link #TOTAL_REWARD_WINDOW_MS} into. */
    @VisibleForTesting
    static final int NUM_REWARD_BUCKET_WINDOWS = 4;
    /**
     * The duration size of each bucket resulting from splitting {@link #TOTAL_REWARD_WINDOW_MS}
     * into smaller buckets.
     */
    private static final long REWARD_BUCKET_WINDOW_SIZE_MS =
            TOTAL_REWARD_WINDOW_MS / NUM_REWARD_BUCKET_WINDOWS;
    /** The maximum number of transactions to retain in memory at any one time. */
    @VisibleForTesting
    static final int MAX_TRANSACTION_COUNT = Build.IS_ENG || Build.IS_USERDEBUG || DEBUG ? 32 : 4;

    static class Transaction {
        public final long startTimeMs;
        public final long endTimeMs;
        public final int eventId;
        @Nullable
        public final String tag;
        public final long delta;
        public final long ctp;

        Transaction(long startTimeMs, long endTimeMs,
                int eventId, @Nullable String tag, long delta, long ctp) {
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.eventId = eventId;
            this.tag = tag == null ? null : tag.intern();
            this.delta = delta;
            this.ctp = ctp;
        }
    }

    static class RewardBucket {
        @CurrentTimeMillisLong
        public long startTimeMs;
        public final SparseLongArray cumulativeDelta = new SparseLongArray();

        private void reset() {
            startTimeMs = 0;
            cumulativeDelta.clear();
        }
    }

    /** Last saved balance. This doesn't take currently ongoing events into account. */
    private long mCurrentBalance = 0;
    private final Transaction[] mTransactions = new Transaction[MAX_TRANSACTION_COUNT];
    /** Index within {@link #mTransactions} where the next transaction should be placed. */
    private int mTransactionIndex = 0;
    private final RewardBucket[] mRewardBuckets = new RewardBucket[NUM_REWARD_BUCKET_WINDOWS];
    /** Index within {@link #mRewardBuckets} of the current active bucket. */
    private int mRewardBucketIndex = 0;

    Ledger() {
    }

    Ledger(long currentBalance, @NonNull List<Transaction> transactions,
            @NonNull List<RewardBucket> rewardBuckets) {
        mCurrentBalance = currentBalance;

        final int numTxs = transactions.size();
        for (int i = Math.max(0, numTxs - MAX_TRANSACTION_COUNT); i < numTxs; ++i) {
            mTransactions[mTransactionIndex++] = transactions.get(i);
        }
        mTransactionIndex %= MAX_TRANSACTION_COUNT;

        final int numBuckets = rewardBuckets.size();
        if (numBuckets > 0) {
            // Set the index to -1 so that we put the first bucket in index 0.
            mRewardBucketIndex = -1;
            for (int i = Math.max(0, numBuckets - NUM_REWARD_BUCKET_WINDOWS); i < numBuckets; ++i) {
                mRewardBuckets[++mRewardBucketIndex] = rewardBuckets.get(i);
            }
        }
    }

    long getCurrentBalance() {
        return mCurrentBalance;
    }

    @Nullable
    Transaction getEarliestTransaction() {
        for (int t = 0; t < mTransactions.length; ++t) {
            final Transaction transaction =
                    mTransactions[(mTransactionIndex + t) % mTransactions.length];
            if (transaction != null) {
                return transaction;
            }
        }
        return null;
    }

    @NonNull
    List<RewardBucket> getRewardBuckets() {
        final long cutoffMs = getCurrentTimeMillis() - TOTAL_REWARD_WINDOW_MS;
        final List<RewardBucket> list = new ArrayList<>(NUM_REWARD_BUCKET_WINDOWS);
        for (int i = 1; i <= NUM_REWARD_BUCKET_WINDOWS; ++i) {
            final int idx = (mRewardBucketIndex + i) % NUM_REWARD_BUCKET_WINDOWS;
            final RewardBucket rewardBucket = mRewardBuckets[idx];
            if (rewardBucket != null) {
                if (cutoffMs <= rewardBucket.startTimeMs) {
                    list.add(rewardBucket);
                } else {
                    rewardBucket.reset();
                }
            }
        }
        return list;
    }

    @NonNull
    List<Transaction> getTransactions() {
        final List<Transaction> list = new ArrayList<>(MAX_TRANSACTION_COUNT);
        for (int i = 0; i < MAX_TRANSACTION_COUNT; ++i) {
            final int idx = (mTransactionIndex + i) % MAX_TRANSACTION_COUNT;
            final Transaction transaction = mTransactions[idx];
            if (transaction != null) {
                list.add(transaction);
            }
        }
        return list;
    }

    void recordTransaction(@NonNull Transaction transaction) {
        mTransactions[mTransactionIndex] = transaction;
        mCurrentBalance += transaction.delta;
        mTransactionIndex = (mTransactionIndex + 1) % MAX_TRANSACTION_COUNT;

        if (EconomicPolicy.isReward(transaction.eventId)) {
            final RewardBucket bucket = getCurrentRewardBucket();
            bucket.cumulativeDelta.put(transaction.eventId,
                    bucket.cumulativeDelta.get(transaction.eventId, 0) + transaction.delta);
        }
    }

    @NonNull
    private RewardBucket getCurrentRewardBucket() {
        RewardBucket bucket = mRewardBuckets[mRewardBucketIndex];
        final long now = getCurrentTimeMillis();
        if (bucket == null) {
            bucket = new RewardBucket();
            bucket.startTimeMs = now;
            mRewardBuckets[mRewardBucketIndex] = bucket;
            return bucket;
        }

        if (now - bucket.startTimeMs < REWARD_BUCKET_WINDOW_SIZE_MS) {
            return bucket;
        }

        mRewardBucketIndex = (mRewardBucketIndex + 1) % NUM_REWARD_BUCKET_WINDOWS;
        bucket = mRewardBuckets[mRewardBucketIndex];
        if (bucket == null) {
            bucket = new RewardBucket();
            mRewardBuckets[mRewardBucketIndex] = bucket;
        }
        bucket.reset();
        // Using now as the start time means there will be some gaps between sequential buckets,
        // but makes processing of large gaps between events easier.
        bucket.startTimeMs = now;
        return bucket;
    }

    long get24HourSum(int eventId, final long now) {
        final long windowStartTime = now - 24 * HOUR_IN_MILLIS;
        long sum = 0;
        for (int i = 0; i < mRewardBuckets.length; ++i) {
            final RewardBucket bucket = mRewardBuckets[i];
            if (bucket != null
                    && bucket.startTimeMs >= windowStartTime && bucket.startTimeMs < now) {
                sum += bucket.cumulativeDelta.get(eventId, 0);
            }
        }
        return sum;
    }

    /**
     * Deletes transactions that are older than {@code minAgeMs}.
     * @return The earliest transaction in the ledger, or {@code null} if there are no more
     * transactions.
     */
    @Nullable
    Transaction removeOldTransactions(long minAgeMs) {
        final long cutoff = getCurrentTimeMillis() - minAgeMs;
        for (int t = 0; t < mTransactions.length; ++t) {
            final int idx = (mTransactionIndex + t) % mTransactions.length;
            final Transaction transaction = mTransactions[idx];
            if (transaction == null) {
                continue;
            }
            if (transaction.endTimeMs <= cutoff) {
                mTransactions[idx] = null;
            } else {
                // Everything we look at after this transaction will also be within the window,
                // so no need to go further.
                return transaction;
            }
        }
        return null;
    }

    void dump(IndentingPrintWriter pw, int numRecentTransactions) {
        pw.print("Current balance", cakeToString(getCurrentBalance())).println();
        pw.println();

        boolean printedTransactionTitle = false;
        for (int t = 0; t < Math.min(MAX_TRANSACTION_COUNT, numRecentTransactions); ++t) {
            final int idx = (mTransactionIndex + t) % MAX_TRANSACTION_COUNT;
            final Transaction transaction = mTransactions[idx];
            if (transaction == null) {
                continue;
            }

            if (!printedTransactionTitle) {
                pw.println("Transactions:");
                pw.increaseIndent();
                printedTransactionTitle = true;
            }

            dumpTime(pw, transaction.startTimeMs);
            pw.print("--");
            dumpTime(pw, transaction.endTimeMs);
            pw.print(": ");
            pw.print(EconomicPolicy.eventToString(transaction.eventId));
            if (transaction.tag != null) {
                pw.print("(");
                pw.print(transaction.tag);
                pw.print(")");
            }
            pw.print(" --> ");
            pw.print(cakeToString(transaction.delta));
            pw.print(" (ctp=");
            pw.print(cakeToString(transaction.ctp));
            pw.println(")");
        }
        if (printedTransactionTitle) {
            pw.decreaseIndent();
            pw.println();
        }

        final long now = getCurrentTimeMillis();
        boolean printedBucketTitle = false;
        for (int b = 0; b < NUM_REWARD_BUCKET_WINDOWS; ++b) {
            final int idx = (mRewardBucketIndex - b + NUM_REWARD_BUCKET_WINDOWS)
                    % NUM_REWARD_BUCKET_WINDOWS;
            final RewardBucket rewardBucket = mRewardBuckets[idx];
            if (rewardBucket == null || rewardBucket.startTimeMs == 0) {
                continue;
            }

            if (!printedBucketTitle) {
                pw.println("Reward buckets:");
                pw.increaseIndent();
                printedBucketTitle = true;
            }

            dumpTime(pw, rewardBucket.startTimeMs);
            pw.print(" (");
            TimeUtils.formatDuration(now - rewardBucket.startTimeMs, pw);
            pw.println(" ago):");
            pw.increaseIndent();
            for (int r = 0; r < rewardBucket.cumulativeDelta.size(); ++r) {
                pw.print(EconomicPolicy.eventToString(rewardBucket.cumulativeDelta.keyAt(r)));
                pw.print(": ");
                pw.println(cakeToString(rewardBucket.cumulativeDelta.valueAt(r)));
            }
            pw.decreaseIndent();
        }
        if (printedBucketTitle) {
            pw.decreaseIndent();
            pw.println();
        }
    }
}
