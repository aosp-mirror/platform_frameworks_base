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

package androidx.window.extensions.embedding;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_NONE;

import android.os.IBinder;
import android.view.WindowManager.TransitionType;
import android.window.TaskFragmentOrganizer;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Responsible for managing the current {@link WindowContainerTransaction} as a response to device
 * state changes and app interactions.
 *
 * A typical use flow:
 * 1. Call {@link #startNewTransaction} to start tracking the changes.
 * 2. Use {@link TransactionRecord#setOriginType(int)} (int)} to record the type of operation that
 *    will start a new transition on system server.
 * 3. Use {@link #getCurrentTransactionRecord()} to get current {@link TransactionRecord} for
 *    changes.
 * 4. Call {@link TransactionRecord#apply(boolean)} to request the system server to apply changes in
 *    the current {@link WindowContainerTransaction}, or call {@link TransactionRecord#abort()} to
 *    dispose the current one.
 *
 * Note:
 * There should be only one transaction at a time. The caller should not call
 * {@link #startNewTransaction} again before calling {@link TransactionRecord#apply(boolean)} or
 * {@link TransactionRecord#abort()} to the previous transaction.
 */
class TransactionManager {

    @NonNull
    private final TaskFragmentOrganizer mOrganizer;

    @Nullable
    private TransactionRecord mCurrentTransaction;

    TransactionManager(@NonNull TaskFragmentOrganizer organizer) {
        mOrganizer = organizer;
    }

    @NonNull
    TransactionRecord startNewTransaction() {
        return startNewTransaction(null /* taskFragmentTransactionToken */);
    }

    /**
     * Starts tracking the changes in a new {@link WindowContainerTransaction}. Caller can call
     * {@link #getCurrentTransactionRecord()} later to continue adding change to the current
     * transaction until {@link TransactionRecord#apply(boolean)} or
     * {@link TransactionRecord#abort()} is called.
     * @param taskFragmentTransactionToken  {@link android.window.TaskFragmentTransaction
     *                                      #getTransactionToken()} if this is a response to a
     *                                      {@link android.window.TaskFragmentTransaction}.
     */
    @NonNull
    TransactionRecord startNewTransaction(@Nullable IBinder taskFragmentTransactionToken) {
        if (mCurrentTransaction != null) {
            mCurrentTransaction = null;
            throw new IllegalStateException(
                    "The previous transaction has not been applied or aborted,");
        }
        mCurrentTransaction = new TransactionRecord(taskFragmentTransactionToken);
        return mCurrentTransaction;
    }

    /**
     * Gets the current {@link TransactionRecord} started from {@link #startNewTransaction}.
     */
    @NonNull
    TransactionRecord getCurrentTransactionRecord() {
        if (mCurrentTransaction == null) {
            throw new IllegalStateException("startNewTransaction() is not invoked before calling"
                    + " getCurrentTransactionRecord().");
        }
        return mCurrentTransaction;
    }

    /** The current transaction. The manager should only handle one transaction at a time. */
    class TransactionRecord {
        /**
         * {@link WindowContainerTransaction} containing the current change.
         * @see #startNewTransaction(IBinder)
         * @see #apply (boolean)
         */
        @NonNull
        private final WindowContainerTransaction mTransaction = new WindowContainerTransaction();

        /**
         * If the current transaction is a response to a
         * {@link android.window.TaskFragmentTransaction}, this is the
         * {@link android.window.TaskFragmentTransaction#getTransactionToken()}.
         * @see #startNewTransaction(IBinder)
         */
        @Nullable
        private final IBinder mTaskFragmentTransactionToken;

        /**
         * To track of the origin type of the current {@link #mTransaction}. When
         * {@link #apply (boolean)} to start a new transition, this is the type to request.
         * @see #setOriginType(int)
         * @see #getTransactionTransitionType()
         */
        @TransitionType
        private int mOriginType = TRANSIT_NONE;

        TransactionRecord(@Nullable IBinder taskFragmentTransactionToken) {
            mTaskFragmentTransactionToken = taskFragmentTransactionToken;
        }

        @NonNull
        WindowContainerTransaction getTransaction() {
            ensureCurrentTransaction();
            return mTransaction;
        }

        /**
         * Sets the {@link TransitionType} that triggers this transaction. If there are multiple
         * calls, only the first call will be respected as the "origin" type.
         */
        void setOriginType(@TransitionType int type) {
            ensureCurrentTransaction();
            if (mOriginType != TRANSIT_NONE) {
                // Skip if the origin type has already been set.
                return;
            }
            mOriginType = type;
        }

        /**
         * Requests the system server to apply the current transaction started from
         * {@link #startNewTransaction}.
         * @param shouldApplyIndependently  If {@code true}, the {@link #mCurrentTransaction} will
         *                                  request a new transition, which will be queued until the
         *                                  sync engine is free if there is any other active sync.
         *                                  If {@code false}, the {@link #startNewTransaction} will
         *                                  be directly applied to the active sync.
         */
        void apply(boolean shouldApplyIndependently) {
            ensureCurrentTransaction();
            if (mTaskFragmentTransactionToken != null) {
                // If this is a response to a TaskFragmentTransaction.
                mOrganizer.onTransactionHandled(mTaskFragmentTransactionToken, mTransaction,
                        getTransactionTransitionType(), shouldApplyIndependently);
            } else {
                mOrganizer.applyTransaction(mTransaction, getTransactionTransitionType(),
                        shouldApplyIndependently);
            }
            dispose();
        }

        /** Called when there is no need to {@link #apply(boolean)} the current transaction. */
        void abort() {
            ensureCurrentTransaction();
            dispose();
        }

        private void dispose() {
            TransactionManager.this.mCurrentTransaction = null;
        }

        private void ensureCurrentTransaction() {
            if (TransactionManager.this.mCurrentTransaction != this) {
                throw new IllegalStateException(
                        "This transaction has already been apply() or abort().");
            }
        }

        /**
         * Gets the {@link TransitionType} that we will request transition with for the
         * current {@link WindowContainerTransaction}.
         */
        @VisibleForTesting
        @TransitionType
        int getTransactionTransitionType() {
            // Use TRANSIT_CHANGE as default if there is not opening/closing window.
            return mOriginType != TRANSIT_NONE ? mOriginType : TRANSIT_CHANGE;
        }
    }
}
