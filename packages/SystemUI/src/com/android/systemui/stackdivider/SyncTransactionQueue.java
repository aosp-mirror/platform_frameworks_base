/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import android.os.Handler;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;
import android.window.WindowOrganizer;

import androidx.annotation.NonNull;

import com.android.systemui.TransactionPool;

import java.util.ArrayList;

/**
 * Helper for serializing sync-transactions and corresponding callbacks.
 */
class SyncTransactionQueue {
    private static final boolean DEBUG = Divider.DEBUG;
    private static final String TAG = "SyncTransactionQueue";

    // Just a little longer than the sync-engine timeout of 5s
    private static final int REPLY_TIMEOUT = 5300;

    private final TransactionPool mTransactionPool;
    private final Handler mHandler;

    // Sync Transactions currently don't support nesting or interleaving properly, so
    // queue up transactions to run them serially.
    private final ArrayList<SyncCallback> mQueue = new ArrayList<>();

    private SyncCallback mInFlight = null;
    private final ArrayList<TransactionRunnable> mRunnables = new ArrayList<>();

    private final Runnable mOnReplyTimeout = () -> {
        synchronized (mQueue) {
            if (mInFlight != null && mQueue.contains(mInFlight)) {
                Slog.w(TAG, "Sync Transaction timed-out: " + mInFlight.mWCT);
                mInFlight.onTransactionReady(mInFlight.mId, new SurfaceControl.Transaction());
            }
        }
    };

    SyncTransactionQueue(TransactionPool pool, Handler handler) {
        mTransactionPool = pool;
        mHandler = handler;
    }

    /**
     * Queues a sync transaction to be sent serially to WM.
     */
    void queue(WindowContainerTransaction wct) {
        SyncCallback cb = new SyncCallback(wct);
        synchronized (mQueue) {
            if (DEBUG) Slog.d(TAG, "Queueing up " + wct);
            mQueue.add(cb);
            if (mQueue.size() == 1) {
                cb.send();
            }
        }
    }

    /**
     * Queues a sync transaction only if there are already sync transaction(s) queued or in flight.
     * Otherwise just returns without queueing.
     * @return {@code true} if queued, {@code false} if not.
     */
    boolean queueIfWaiting(WindowContainerTransaction wct) {
        synchronized (mQueue) {
            if (mQueue.isEmpty()) {
                if (DEBUG) Slog.d(TAG, "Nothing in queue, so skip queueing up " + wct);
                return false;
            }
            if (DEBUG) Slog.d(TAG, "Queue is non-empty, so queueing up " + wct);
            SyncCallback cb = new SyncCallback(wct);
            mQueue.add(cb);
            if (mQueue.size() == 1) {
                cb.send();
            }
        }
        return true;
    }

    /**
     * Runs a runnable in sync with sync transactions (ie. when the current in-flight transaction
     * returns. If there are no transactions in-flight, runnable executes immediately.
     */
    void runInSync(TransactionRunnable runnable) {
        synchronized (mQueue) {
            if (DEBUG) Slog.d(TAG, "Run in sync. mInFlight=" + mInFlight);
            if (mInFlight != null) {
                mRunnables.add(runnable);
                return;
            }
        }
        SurfaceControl.Transaction t = mTransactionPool.acquire();
        runnable.runWithTransaction(t);
        t.apply();
        mTransactionPool.release(t);
    }

    // Synchronized on mQueue
    private void onTransactionReceived(@NonNull SurfaceControl.Transaction t) {
        if (DEBUG) Slog.d(TAG, "  Running " + mRunnables.size() + " sync runnables");
        for (int i = 0, n = mRunnables.size(); i < n; ++i) {
            mRunnables.get(i).runWithTransaction(t);
        }
        mRunnables.clear();
        t.apply();
        t.close();
    }

    interface TransactionRunnable {
        void runWithTransaction(SurfaceControl.Transaction t);
    }

    private class SyncCallback extends WindowContainerTransactionCallback {
        int mId = -1;
        final WindowContainerTransaction mWCT;

        SyncCallback(WindowContainerTransaction wct) {
            mWCT = wct;
        }

        // Must be sychronized on mQueue
        void send() {
            if (mInFlight != null) {
                throw new IllegalStateException("Sync Transactions must be serialized. In Flight: "
                        + mInFlight.mId + " - " + mInFlight.mWCT);
            }
            mInFlight = this;
            if (DEBUG) Slog.d(TAG, "Sending sync transaction: " + mWCT);
            mId = new WindowOrganizer().applySyncTransaction(mWCT, this);
            if (DEBUG) Slog.d(TAG, " Sent sync transaction. Got id=" + mId);
            mHandler.postDelayed(mOnReplyTimeout, REPLY_TIMEOUT);
        }

        @Override
        public void onTransactionReady(int id,
                @androidx.annotation.NonNull SurfaceControl.Transaction t) {
            mHandler.post(() -> {
                synchronized (mQueue) {
                    if (mId != id) {
                        Slog.e(TAG, "Got an unexpected onTransactionReady. Expected "
                                + mId + " but got " + id);
                        return;
                    }
                    mInFlight = null;
                    mHandler.removeCallbacks(mOnReplyTimeout);
                    if (DEBUG) Slog.d(TAG, "onTransactionReady id=" + mId);
                    mQueue.remove(this);
                    onTransactionReceived(t);
                    if (!mQueue.isEmpty()) {
                        mQueue.get(0).send();
                    }
                }
            });
        }
    }
}
