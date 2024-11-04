/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.location.contexthub;

import android.chre.flags.Flags;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubTransactionCallback;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages transactions at the Context Hub Service.
 *
 * <p>This class maintains a queue of transaction requests made to the ContextHubService by clients,
 * and executes them through the Context Hub. At any point in time, either the transaction queue is
 * empty, or there is a pending transaction that is waiting for an asynchronous response from the
 * hub. This class also handles synchronous errors and timeouts of each transaction.
 *
 * <p>This is the old version of ContextHubTransactionManager that uses global synchronization
 * instead of individual locks. This will be deleted when the
 * reduce_locking_context_hub_transaction_manager flag is cleaned up.
 *
 * @hide
 */
/* package */ class ContextHubTransactionManagerOld extends ContextHubTransactionManager {
    /* package */ ContextHubTransactionManagerOld(
            IContextHubWrapper contextHubProxy,
            ContextHubClientManager clientManager,
            NanoAppStateManager nanoAppStateManager) {
        super(contextHubProxy, clientManager, nanoAppStateManager);
    }

    /**
     * Adds a new transaction to the queue.
     *
     * <p>If there was no pending transaction at the time, the transaction that was added will be
     * started in this method. If there were too many transactions in the queue, an exception will
     * be thrown.
     *
     * @param transaction the transaction to add
     * @throws IllegalStateException if the queue is full
     */
    /* package */
    @Override
    synchronized void addTransaction(ContextHubServiceTransaction transaction)
            throws IllegalStateException {
        if (transaction == null) {
            return;
        }

        if (mTransactionQueue.size() >= MAX_PENDING_REQUESTS
                || mReliableMessageTransactionMap.size() >= MAX_PENDING_REQUESTS) {
            throw new IllegalStateException(
                    "Transaction queue is full (capacity = " + MAX_PENDING_REQUESTS + ")");
        }

        mTransactionRecordDeque.add(new TransactionRecord(transaction.toString()));
        if (Flags.reliableMessageRetrySupportService()
                && transaction.getTransactionType()
                        == ContextHubTransaction.TYPE_RELIABLE_MESSAGE) {
            mReliableMessageTransactionMap.put(transaction.getMessageSequenceNumber(), transaction);
            mExecutor.execute(() -> processMessageTransactions());
        } else {
            mTransactionQueue.add(transaction);
            if (mTransactionQueue.size() == 1) {
                startNextTransaction();
            }
        }
    }

    /**
     * Handles a transaction response from a Context Hub.
     *
     * @param transactionId the transaction ID of the response
     * @param success true if the transaction succeeded
     */
    /* package */
    @Override
    synchronized void onTransactionResponse(int transactionId, boolean success) {
        ContextHubServiceTransaction transaction = mTransactionQueue.peek();
        if (transaction == null) {
            Log.w(TAG, "Received unexpected transaction response (no transaction pending)");
            return;
        }
        if (transaction.getTransactionId() != transactionId) {
            Log.w(
                    TAG,
                    "Received unexpected transaction response (expected ID = "
                            + transaction.getTransactionId()
                            + ", received ID = "
                            + transactionId
                            + ")");
            return;
        }

        transaction.onTransactionComplete(
                success
                        ? ContextHubTransaction.RESULT_SUCCESS
                        : ContextHubTransaction.RESULT_FAILED_AT_HUB);
        removeTransactionAndStartNext();
    }

    /* package */
    @Override
    synchronized void onMessageDeliveryResponse(int messageSequenceNumber, boolean success) {
        if (!Flags.reliableMessageRetrySupportService()) {
            ContextHubServiceTransaction transaction = mTransactionQueue.peek();
            if (transaction == null) {
                Log.w(TAG, "Received unexpected transaction response (no transaction pending)");
                return;
            }

            int transactionMessageSequenceNumber = transaction.getMessageSequenceNumber();
            if (transaction.getTransactionType() != ContextHubTransaction.TYPE_RELIABLE_MESSAGE
                    || transactionMessageSequenceNumber != messageSequenceNumber) {
                Log.w(
                        TAG,
                        "Received unexpected message transaction response (expected message "
                                + "sequence number = "
                                + transaction.getMessageSequenceNumber()
                                + ", received messageSequenceNumber = "
                                + messageSequenceNumber
                                + ")");
                return;
            }

            transaction.onTransactionComplete(
                    success
                            ? ContextHubTransaction.RESULT_SUCCESS
                            : ContextHubTransaction.RESULT_FAILED_AT_HUB);
            removeTransactionAndStartNext();
            return;
        }

        ContextHubServiceTransaction transaction =
                mReliableMessageTransactionMap.get(messageSequenceNumber);
        if (transaction == null) {
            Log.w(
                    TAG,
                    "Could not find reliable message transaction with "
                            + "message sequence number = "
                            + messageSequenceNumber);
            return;
        }

        completeMessageTransaction(
                transaction,
                success
                        ? ContextHubTransaction.RESULT_SUCCESS
                        : ContextHubTransaction.RESULT_FAILED_AT_HUB);
        mExecutor.execute(() -> processMessageTransactions());
    }

    /**
     * Handles a query response from a Context Hub.
     *
     * @param nanoAppStateList the list of nanoapps included in the response
     */
    /* package */
    @Override
    synchronized void onQueryResponse(List<NanoAppState> nanoAppStateList) {
        ContextHubServiceTransaction transaction = mTransactionQueue.peek();
        if (transaction == null) {
            Log.w(TAG, "Received unexpected query response (no transaction pending)");
            return;
        }
        if (transaction.getTransactionType() != ContextHubTransaction.TYPE_QUERY_NANOAPPS) {
            Log.w(TAG, "Received unexpected query response (expected " + transaction + ")");
            return;
        }

        transaction.onQueryResponse(ContextHubTransaction.RESULT_SUCCESS, nanoAppStateList);
        removeTransactionAndStartNext();
    }

    /** Handles a hub reset event by stopping a pending transaction and starting the next. */
    /* package */
    @Override
    synchronized void onHubReset() {
        if (Flags.reliableMessageRetrySupportService()) {
            Iterator<Map.Entry<Integer, ContextHubServiceTransaction>> iter =
                    mReliableMessageTransactionMap.entrySet().iterator();
            while (iter.hasNext()) {
                completeMessageTransaction(
                        iter.next().getValue(), ContextHubTransaction.RESULT_FAILED_AT_HUB, iter);
            }
        }

        ContextHubServiceTransaction transaction = mTransactionQueue.peek();
        if (transaction == null) {
            return;
        }

        removeTransactionAndStartNext();
    }

    /**
     * Pops the front transaction from the queue and starts the next pending transaction request.
     *
     * <p>Removing elements from the transaction queue must only be done through this method. When a
     * pending transaction is removed, the timeout timer is cancelled and the transaction is marked
     * complete.
     *
     * <p>It is assumed that the transaction queue is non-empty when this method is invoked, and
     * that the caller has obtained a lock on this ContextHubTransactionManager object.
     */
    private void removeTransactionAndStartNext() {
        if (mTimeoutFuture != null) {
            mTimeoutFuture.cancel(/* mayInterruptIfRunning= */ false);
            mTimeoutFuture = null;
        }

        ContextHubServiceTransaction transaction = mTransactionQueue.remove();
        transaction.setComplete();

        if (!mTransactionQueue.isEmpty()) {
            startNextTransaction();
        }
    }

    /**
     * Starts the next pending transaction request.
     *
     * <p>Starting new transactions must only be done through this method. This method continues to
     * process the transaction queue as long as there are pending requests, and no transaction is
     * pending.
     *
     * <p>It is assumed that the caller has obtained a lock on this ContextHubTransactionManager
     * object.
     */
    private void startNextTransaction() {
        int result = ContextHubTransaction.RESULT_FAILED_UNKNOWN;
        while (result != ContextHubTransaction.RESULT_SUCCESS && !mTransactionQueue.isEmpty()) {
            ContextHubServiceTransaction transaction = mTransactionQueue.peek();
            result = transaction.onTransact();

            if (result == ContextHubTransaction.RESULT_SUCCESS) {
                Runnable onTimeoutFunc =
                        () -> {
                            synchronized (this) {
                                if (!transaction.isComplete()) {
                                    Log.d(TAG, transaction + " timed out");
                                    transaction.onTransactionComplete(
                                            ContextHubTransaction.RESULT_FAILED_TIMEOUT);

                                    removeTransactionAndStartNext();
                                }
                            }
                        };

                long timeoutMs = transaction.getTimeout(TimeUnit.MILLISECONDS);
                try {
                    mTimeoutFuture =
                            mExecutor.schedule(onTimeoutFunc, timeoutMs, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    Log.e(TAG, "Error when schedule a timer", e);
                }
            } else {
                transaction.onTransactionComplete(
                        ContextHubServiceUtil.toTransactionResult(result));
                mTransactionQueue.remove();
            }
        }
    }

    /**
     * Processes message transactions, starting and completing them as needed.
     *
     * <p>This function is called when adding a message transaction or when a timer expires for an
     * existing message transaction's retry or timeout. The internal processing loop will iterate at
     * most twice as if one iteration completes a transaction, the next iteration can only start new
     * transactions. If the first iteration does not complete any transaction, the loop will only
     * iterate once.
     *
     * <p>
     */
    private synchronized void processMessageTransactions() {
        if (!Flags.reliableMessageRetrySupportService()) {
            return;
        }

        if (mReliableMessageTransactionFuture != null) {
            mReliableMessageTransactionFuture.cancel(/* mayInterruptIfRunning= */ false);
            mReliableMessageTransactionFuture = null;
        }

        long now = SystemClock.elapsedRealtimeNanos();
        long nextExecutionTime = Long.MAX_VALUE;
        boolean continueProcessing;
        do {
            continueProcessing = false;
            Iterator<Map.Entry<Integer, ContextHubServiceTransaction>> iter =
                    mReliableMessageTransactionMap.entrySet().iterator();
            while (iter.hasNext()) {
                ContextHubServiceTransaction transaction = iter.next().getValue();
                short hostEndpointId = transaction.getHostEndpointId();
                int numCompletedStartCalls = transaction.getNumCompletedStartCalls();
                if (numCompletedStartCalls == 0
                        && mReliableMessageHostEndpointIdActiveSet.contains(hostEndpointId)) {
                    continue;
                }

                long nextRetryTime = transaction.getNextRetryTime();
                long timeoutTime = transaction.getTimeoutTime();
                boolean transactionTimedOut = timeoutTime <= now;
                boolean transactionHitMaxRetries =
                        nextRetryTime <= now
                                && numCompletedStartCalls > RELIABLE_MESSAGE_MAX_NUM_RETRY;
                if (transactionTimedOut || transactionHitMaxRetries) {
                    completeMessageTransaction(
                            transaction, ContextHubTransaction.RESULT_FAILED_TIMEOUT, iter);
                    continueProcessing = true;
                } else {
                    if (nextRetryTime <= now || numCompletedStartCalls <= 0) {
                        startMessageTransaction(transaction, now);
                    }

                    nextExecutionTime = Math.min(nextExecutionTime, transaction.getNextRetryTime());
                    nextExecutionTime = Math.min(nextExecutionTime, transaction.getTimeoutTime());
                }
            }
        } while (continueProcessing);

        if (nextExecutionTime < Long.MAX_VALUE) {
            mReliableMessageTransactionFuture =
                    mExecutor.schedule(
                            () -> processMessageTransactions(),
                            Math.max(
                                    nextExecutionTime - SystemClock.elapsedRealtimeNanos(),
                                    RELIABLE_MESSAGE_MIN_WAIT_TIME.toNanos()),
                            TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Completes a message transaction and removes it from the reliable message map.
     *
     * @param transaction The transaction to complete.
     * @param result The result code.
     */
    private void completeMessageTransaction(
            ContextHubServiceTransaction transaction, @ContextHubTransaction.Result int result) {
        completeMessageTransaction(transaction, result, /* iter= */ null);
    }

    /**
     * Completes a message transaction and removes it from the reliable message map using iter.
     *
     * @param transaction The transaction to complete.
     * @param result The result code.
     * @param iter The iterator for the reliable message map - used to remove the message directly.
     */
    private void completeMessageTransaction(
            ContextHubServiceTransaction transaction,
            @ContextHubTransaction.Result int result,
            Iterator<Map.Entry<Integer, ContextHubServiceTransaction>> iter) {
        transaction.onTransactionComplete(result);

        if (iter == null) {
            mReliableMessageTransactionMap.remove(transaction.getMessageSequenceNumber());
        } else {
            iter.remove();
        }
        mReliableMessageHostEndpointIdActiveSet.remove(transaction.getHostEndpointId());

        Log.d(
                TAG,
                "Successfully completed reliable message transaction with "
                        + "message sequence number = "
                        + transaction.getMessageSequenceNumber()
                        + " and result = "
                        + result);
    }

    /**
     * Starts a message transaction.
     *
     * @param transaction The transaction to start.
     * @param now The now time.
     */
    private void startMessageTransaction(ContextHubServiceTransaction transaction, long now) {
        int numCompletedStartCalls = transaction.getNumCompletedStartCalls();
        @ContextHubTransaction.Result int result = transaction.onTransact();
        if (result == ContextHubTransaction.RESULT_SUCCESS) {
            Log.d(
                    TAG,
                    "Successfully "
                            + (numCompletedStartCalls == 0 ? "started" : "retried")
                            + " reliable message transaction with message sequence number = "
                            + transaction.getMessageSequenceNumber());
        } else {
            Log.w(
                    TAG,
                    "Could not start reliable message transaction with "
                            + "message sequence number = "
                            + transaction.getMessageSequenceNumber()
                            + ", result = "
                            + result);
        }

        transaction.setNextRetryTime(now + RELIABLE_MESSAGE_RETRY_WAIT_TIME.toNanos());
        if (transaction.getTimeoutTime() == Long.MAX_VALUE) { // first time starting transaction
            transaction.setTimeoutTime(now + RELIABLE_MESSAGE_TIMEOUT.toNanos());
        }
        transaction.setNumCompletedStartCalls(numCompletedStartCalls + 1);
        mReliableMessageHostEndpointIdActiveSet.add(transaction.getHostEndpointId());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        synchronized (this) {
            for (ContextHubServiceTransaction transaction : mTransactionQueue) {
                sb.append(i);
                sb.append(": ");
                sb.append(transaction.toString());
                sb.append("\n");
                ++i;
            }

            if (Flags.reliableMessageRetrySupportService()) {
                for (ContextHubServiceTransaction transaction :
                        mReliableMessageTransactionMap.values()) {
                    sb.append(i);
                    sb.append(": ");
                    sb.append(transaction.toString());
                    sb.append("\n");
                    ++i;
                }
            }

            sb.append("Transaction History:\n");
            Iterator<TransactionRecord> iterator = mTransactionRecordDeque.descendingIterator();
            while (iterator.hasNext()) {
                sb.append(iterator.next());
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
