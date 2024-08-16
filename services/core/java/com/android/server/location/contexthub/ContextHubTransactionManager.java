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
 * <p>
 * This class maintains a queue of transaction requests made to the ContextHubService by clients,
 * and executes them through the Context Hub. At any point in time, either the transaction queue is
 * empty, or there is a pending transaction that is waiting for an asynchronous response from the
 * hub. This class also handles synchronous errors and timeouts of each transaction.
 *
 * @hide
 */
/* package */ class ContextHubTransactionManager {
    private static final String TAG = "ContextHubTransactionManager";

    public static final Duration RELIABLE_MESSAGE_TIMEOUT = Duration.ofSeconds(1);

    public static final Duration RELIABLE_MESSAGE_DUPLICATE_DETECTION_TIMEOUT =
            RELIABLE_MESSAGE_TIMEOUT.multipliedBy(3);

    private static final int MAX_PENDING_REQUESTS = 10000;

    private static final int RELIABLE_MESSAGE_MAX_NUM_RETRY = 3;

    private static final Duration RELIABLE_MESSAGE_RETRY_WAIT_TIME = Duration.ofMillis(250);

    private static final Duration RELIABLE_MESSAGE_MIN_WAIT_TIME = Duration.ofNanos(1000);

    private final IContextHubWrapper mContextHubProxy;

    private final ContextHubClientManager mClientManager;

    private final NanoAppStateManager mNanoAppStateManager;

    private final ArrayDeque<ContextHubServiceTransaction> mTransactionQueue = new ArrayDeque<>();

    private final Map<Integer, ContextHubServiceTransaction> mReliableMessageTransactionMap =
            new HashMap<>();

    /** A set of host endpoint IDs that have an active pending transaction. */
    private final Set<Short> mReliableMessageHostEndpointIdActiveSet = new HashSet<>();

    private final AtomicInteger mNextAvailableId = new AtomicInteger();

    /**
     * The next available message sequence number. We choose a random
     * number to start with to avoid collisions and limit the bound to
     * half of the max value to avoid overflow.
     */
    private final AtomicInteger mNextAvailableMessageSequenceNumber =
            new AtomicInteger(new Random().nextInt(Integer.MAX_VALUE / 2));

    /*
     * An executor and the future object for scheduling timeout timers and
     * for scheduling the processing of reliable message transactions.
     */
    private final ScheduledThreadPoolExecutor mExecutor = new ScheduledThreadPoolExecutor(1);
    private ScheduledFuture<?> mTimeoutFuture = null;
    private ScheduledFuture<?> mReliableMessageTransactionFuture = null;

    /*
     * The list of previous transaction records.
     */
    private static final int NUM_TRANSACTION_RECORDS = 20;
    private final ConcurrentLinkedEvictingDeque<TransactionRecord> mTransactionRecordDeque =
            new ConcurrentLinkedEvictingDeque<>(NUM_TRANSACTION_RECORDS);

    /**
     * A container class to store a record of transactions.
     */
    private class TransactionRecord {
        private final String mTransaction;
        private final long mTimestamp;

        TransactionRecord(String transaction) {
            mTransaction = transaction;
            mTimestamp = System.currentTimeMillis();
        }

        // TODO: Add dump to proto here

        @Override
        public String toString() {
            return ContextHubServiceUtil.formatDateFromTimestamp(mTimestamp) + " " + mTransaction;
        }
    }

    /* package */ ContextHubTransactionManager(
            IContextHubWrapper contextHubProxy, ContextHubClientManager clientManager,
            NanoAppStateManager nanoAppStateManager) {
        mContextHubProxy = contextHubProxy;
        mClientManager = clientManager;
        mNanoAppStateManager = nanoAppStateManager;
    }

    /**
     * Creates a transaction for loading a nanoapp.
     *
     * @param contextHubId       the ID of the hub to load the nanoapp to
     * @param nanoAppBinary      the binary of the nanoapp to load
     * @param onCompleteCallback the client on complete callback
     * @return the generated transaction
     */
    /* package */ ContextHubServiceTransaction createLoadTransaction(
            int contextHubId, NanoAppBinary nanoAppBinary,
            IContextHubTransactionCallback onCompleteCallback, String packageName) {
        return new ContextHubServiceTransaction(
                mNextAvailableId.getAndIncrement(), ContextHubTransaction.TYPE_LOAD_NANOAPP,
                nanoAppBinary.getNanoAppId(), packageName) {
            @Override
                /* package */ int onTransact() {
                try {
                    return mContextHubProxy.loadNanoapp(
                            contextHubId, nanoAppBinary, this.getTransactionId());
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while trying to load nanoapp with ID 0x" +
                            Long.toHexString(nanoAppBinary.getNanoAppId()), e);
                    return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
                }
            }

            @Override
                /* package */ void onTransactionComplete(@ContextHubTransaction.Result int result) {
                ContextHubStatsLog.write(
                        ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED,
                        nanoAppBinary.getNanoAppId(),
                        nanoAppBinary.getNanoAppVersion(),
                        ContextHubStatsLog
                            .CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_TYPE__TYPE_LOAD,
                        toStatsTransactionResult(result));

                ContextHubEventLogger.getInstance().logNanoappLoad(
                        contextHubId,
                        nanoAppBinary.getNanoAppId(),
                        nanoAppBinary.getNanoAppVersion(),
                        nanoAppBinary.getBinary().length,
                        result == ContextHubTransaction.RESULT_SUCCESS);

                if (result == ContextHubTransaction.RESULT_SUCCESS) {
                    // NOTE: The legacy JNI code used to do a query right after a load success
                    // to synchronize the service cache. Instead store the binary that was
                    // requested to load to update the cache later without doing a query.
                    mNanoAppStateManager.addNanoAppInstance(
                            contextHubId, nanoAppBinary.getNanoAppId(),
                            nanoAppBinary.getNanoAppVersion());
                }
                try {
                    onCompleteCallback.onTransactionComplete(result);
                    if (result == ContextHubTransaction.RESULT_SUCCESS) {
                        mClientManager.onNanoAppLoaded(contextHubId, nanoAppBinary.getNanoAppId());
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while calling client onTransactionComplete", e);
                }
            }
        };
    }

    /**
     * Creates a transaction for unloading a nanoapp.
     *
     * @param contextHubId       the ID of the hub to unload the nanoapp from
     * @param nanoAppId          the ID of the nanoapp to unload
     * @param onCompleteCallback the client on complete callback
     * @return the generated transaction
     */
    /* package */ ContextHubServiceTransaction createUnloadTransaction(
            int contextHubId, long nanoAppId, IContextHubTransactionCallback onCompleteCallback,
            String packageName) {
        return new ContextHubServiceTransaction(
                mNextAvailableId.getAndIncrement(), ContextHubTransaction.TYPE_UNLOAD_NANOAPP,
                nanoAppId, packageName) {
            @Override
                /* package */ int onTransact() {
                try {
                    return mContextHubProxy.unloadNanoapp(
                            contextHubId, nanoAppId, this.getTransactionId());
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while trying to unload nanoapp with ID 0x" +
                            Long.toHexString(nanoAppId), e);
                    return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
                }
            }

            @Override
                /* package */ void onTransactionComplete(@ContextHubTransaction.Result int result) {
                ContextHubStatsLog.write(
                        ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED, nanoAppId,
                        0 /* nanoappVersion */,
                        ContextHubStatsLog
                            .CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_TYPE__TYPE_UNLOAD,
                        toStatsTransactionResult(result));

                ContextHubEventLogger.getInstance().logNanoappUnload(
                        contextHubId,
                        nanoAppId,
                        result == ContextHubTransaction.RESULT_SUCCESS);

                if (result == ContextHubTransaction.RESULT_SUCCESS) {
                    mNanoAppStateManager.removeNanoAppInstance(contextHubId, nanoAppId);
                }
                try {
                    onCompleteCallback.onTransactionComplete(result);
                    if (result == ContextHubTransaction.RESULT_SUCCESS) {
                        mClientManager.onNanoAppUnloaded(contextHubId, nanoAppId);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while calling client onTransactionComplete", e);
                }
            }
        };
    }

    /**
     * Creates a transaction for enabling a nanoapp.
     *
     * @param contextHubId       the ID of the hub to enable the nanoapp on
     * @param nanoAppId          the ID of the nanoapp to enable
     * @param onCompleteCallback the client on complete callback
     * @return the generated transaction
     */
    /* package */ ContextHubServiceTransaction createEnableTransaction(
            int contextHubId, long nanoAppId, IContextHubTransactionCallback onCompleteCallback,
            String packageName) {
        return new ContextHubServiceTransaction(
                mNextAvailableId.getAndIncrement(), ContextHubTransaction.TYPE_ENABLE_NANOAPP,
                packageName) {
            @Override
                /* package */ int onTransact() {
                try {
                    return mContextHubProxy.enableNanoapp(
                            contextHubId, nanoAppId, this.getTransactionId());
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while trying to enable nanoapp with ID 0x" +
                            Long.toHexString(nanoAppId), e);
                    return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
                }
            }

            @Override
                /* package */ void onTransactionComplete(@ContextHubTransaction.Result int result) {
                try {
                    onCompleteCallback.onTransactionComplete(result);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while calling client onTransactionComplete", e);
                }
            }
        };
    }

    /**
     * Creates a transaction for disabling a nanoapp.
     *
     * @param contextHubId       the ID of the hub to disable the nanoapp on
     * @param nanoAppId          the ID of the nanoapp to disable
     * @param onCompleteCallback the client on complete callback
     * @return the generated transaction
     */
    /* package */ ContextHubServiceTransaction createDisableTransaction(
            int contextHubId, long nanoAppId, IContextHubTransactionCallback onCompleteCallback,
            String packageName) {
        return new ContextHubServiceTransaction(
                mNextAvailableId.getAndIncrement(), ContextHubTransaction.TYPE_DISABLE_NANOAPP,
                packageName) {
            @Override
                /* package */ int onTransact() {
                try {
                    return mContextHubProxy.disableNanoapp(
                            contextHubId, nanoAppId, this.getTransactionId());
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while trying to disable nanoapp with ID 0x" +
                            Long.toHexString(nanoAppId), e);
                    return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
                }
            }

            @Override
                /* package */ void onTransactionComplete(@ContextHubTransaction.Result int result) {
                try {
                    onCompleteCallback.onTransactionComplete(result);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while calling client onTransactionComplete", e);
                }
            }
        };
    }

    /**
     * Creates a transaction to send a reliable message.
     *
     * @param hostEndpointId      The ID of the host endpoint sending the message.
     * @param contextHubId        The ID of the hub to send the message to.
     * @param message             The message to send.
     * @param transactionCallback The callback of the transactions.
     * @param packageName         The host package associated with this transaction.
     * @return The generated transaction.
     */
    /* package */ ContextHubServiceTransaction createMessageTransaction(
            short hostEndpointId, int contextHubId, NanoAppMessage message,
            IContextHubTransactionCallback transactionCallback, String packageName) {
        return new ContextHubServiceTransaction(mNextAvailableId.getAndIncrement(),
                ContextHubTransaction.TYPE_RELIABLE_MESSAGE, packageName,
                mNextAvailableMessageSequenceNumber.getAndIncrement(), hostEndpointId) {
            @Override
            /* package */ int onTransact() {
                try {
                    message.setIsReliable(/* isReliable= */ true);
                    message.setMessageSequenceNumber(getMessageSequenceNumber());

                    return mContextHubProxy.sendMessageToContextHub(hostEndpointId, contextHubId,
                            message);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while trying to send a reliable message", e);
                    return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
                }
            }

            @Override
            /* package */ void onTransactionComplete(@ContextHubTransaction.Result int result) {
                try {
                    transactionCallback.onTransactionComplete(result);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while calling client onTransactionComplete", e);
                }
            }
        };
    }

    /**
     * Creates a transaction for querying for a list of nanoapps.
     *
     * @param contextHubId       the ID of the hub to query
     * @param onCompleteCallback the client on complete callback
     * @return the generated transaction
     */
    /* package */ ContextHubServiceTransaction createQueryTransaction(
            int contextHubId, IContextHubTransactionCallback onCompleteCallback,
            String packageName) {
        return new ContextHubServiceTransaction(
                mNextAvailableId.getAndIncrement(), ContextHubTransaction.TYPE_QUERY_NANOAPPS,
                packageName) {
            @Override
                /* package */ int onTransact() {
                try {
                    return mContextHubProxy.queryNanoapps(contextHubId);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while trying to query for nanoapps", e);
                    return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
                }
            }

            @Override
                /* package */ void onTransactionComplete(@ContextHubTransaction.Result int result) {
                onQueryResponse(result, Collections.emptyList());
            }

            @Override
                /* package */ void onQueryResponse(
                    @ContextHubTransaction.Result int result, List<NanoAppState> nanoAppStateList) {
                try {
                    onCompleteCallback.onQueryResponse(result, nanoAppStateList);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while calling client onQueryComplete", e);
                }
            }
        };
    }

    /**
     * Adds a new transaction to the queue.
     * <p>
     * If there was no pending transaction at the time, the transaction that was added will be
     * started in this method. If there were too many transactions in the queue, an exception will
     * be thrown.
     *
     * @param transaction the transaction to add
     * @throws IllegalStateException if the queue is full
     */
    /* package */
    synchronized void addTransaction(
            ContextHubServiceTransaction transaction) throws IllegalStateException {
        if (transaction == null) {
            return;
        }

        if (mTransactionQueue.size() >= MAX_PENDING_REQUESTS
                || mReliableMessageTransactionMap.size() >= MAX_PENDING_REQUESTS) {
            throw new IllegalStateException("Transaction queue is full (capacity = "
                    + MAX_PENDING_REQUESTS + ")");
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
     * @param success       true if the transaction succeeded
     */
    /* package */
    synchronized void onTransactionResponse(int transactionId, boolean success) {
        ContextHubServiceTransaction transaction = mTransactionQueue.peek();
        if (transaction == null) {
            Log.w(TAG, "Received unexpected transaction response (no transaction pending)");
            return;
        }
        if (transaction.getTransactionId() != transactionId) {
            Log.w(TAG, "Received unexpected transaction response (expected ID = "
                    + transaction.getTransactionId() + ", received ID = " + transactionId + ")");
            return;
        }

        transaction.onTransactionComplete(success ? ContextHubTransaction.RESULT_SUCCESS :
                        ContextHubTransaction.RESULT_FAILED_AT_HUB);
        removeTransactionAndStartNext();
    }

    /* package */
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
                Log.w(TAG, "Received unexpected message transaction response (expected message "
                        + "sequence number = "
                        + transaction.getMessageSequenceNumber()
                        + ", received messageSequenceNumber = " + messageSequenceNumber + ")");
                return;
            }

            transaction.onTransactionComplete(success ? ContextHubTransaction.RESULT_SUCCESS :
                            ContextHubTransaction.RESULT_FAILED_AT_HUB);
            removeTransactionAndStartNext();
            return;
        }

        ContextHubServiceTransaction transaction =
                mReliableMessageTransactionMap.get(messageSequenceNumber);
        if (transaction == null) {
            Log.w(TAG, "Could not find reliable message transaction with "
                    + "message sequence number = "
                    + messageSequenceNumber);
            return;
        }

        completeMessageTransaction(transaction,
                success ? ContextHubTransaction.RESULT_SUCCESS
                        : ContextHubTransaction.RESULT_FAILED_AT_HUB);
        mExecutor.execute(() -> processMessageTransactions());
    }

    /**
     * Handles a query response from a Context Hub.
     *
     * @param nanoAppStateList the list of nanoapps included in the response
     */
    /* package */
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

    /**
     * Handles a hub reset event by stopping a pending transaction and starting the next.
     */
    /* package */
    synchronized void onHubReset() {
        if (Flags.reliableMessageRetrySupportService()) {
            Iterator<Map.Entry<Integer, ContextHubServiceTransaction>> iter =
                    mReliableMessageTransactionMap.entrySet().iterator();
            while (iter.hasNext()) {
                completeMessageTransaction(iter.next().getValue(),
                        ContextHubTransaction.RESULT_FAILED_AT_HUB, iter);
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
     * <p>
     * Removing elements from the transaction queue must only be done through this method. When a
     * pending transaction is removed, the timeout timer is cancelled and the transaction is marked
     * complete.
     * <p>
     * It is assumed that the transaction queue is non-empty when this method is invoked, and that
     * the caller has obtained a lock on this ContextHubTransactionManager object.
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
     * <p>
     * Starting new transactions must only be done through this method. This method continues to
     * process the transaction queue as long as there are pending requests, and no transaction is
     * pending.
     * <p>
     * It is assumed that the caller has obtained a lock on this ContextHubTransactionManager
     * object.
     */
    private void startNextTransaction() {
        int result = ContextHubTransaction.RESULT_FAILED_UNKNOWN;
        while (result != ContextHubTransaction.RESULT_SUCCESS && !mTransactionQueue.isEmpty()) {
            ContextHubServiceTransaction transaction = mTransactionQueue.peek();
            result = transaction.onTransact();

            if (result == ContextHubTransaction.RESULT_SUCCESS) {
                Runnable onTimeoutFunc = () -> {
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
                    mTimeoutFuture = mExecutor.schedule(
                            onTimeoutFunc, timeoutMs, TimeUnit.MILLISECONDS);
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
     * <p>
     * This function is called when adding a message transaction or when a timer
     * expires for an existing message transaction's retry or timeout. The
     * internal processing loop will iterate at most twice as if one iteration
     * completes a transaction, the next iteration can only start new transactions.
     * If the first iteration does not complete any transaction, the loop will
     * only iterate once.
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
                boolean transactionHitMaxRetries = nextRetryTime <= now
                        && numCompletedStartCalls > RELIABLE_MESSAGE_MAX_NUM_RETRY;
                if (transactionTimedOut || transactionHitMaxRetries) {
                    completeMessageTransaction(transaction,
                            ContextHubTransaction.RESULT_FAILED_TIMEOUT, iter);
                    continueProcessing = true;
                } else {
                    if (nextRetryTime <= now || numCompletedStartCalls <= 0) {
                        startMessageTransaction(transaction, now);
                    }

                    nextExecutionTime = Math.min(nextExecutionTime,
                            transaction.getNextRetryTime());
                    nextExecutionTime = Math.min(nextExecutionTime,
                            transaction.getTimeoutTime());
                }
            }
        } while (continueProcessing);

        if (nextExecutionTime < Long.MAX_VALUE) {
            mReliableMessageTransactionFuture = mExecutor.schedule(
                    () -> processMessageTransactions(),
                    Math.max(nextExecutionTime - SystemClock.elapsedRealtimeNanos(),
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
    private void completeMessageTransaction(ContextHubServiceTransaction transaction,
            @ContextHubTransaction.Result int result) {
        completeMessageTransaction(transaction, result, /* iter= */ null);
    }

    /**
     * Completes a message transaction and removes it from the reliable message map using iter.
     *
     * @param transaction The transaction to complete.
     * @param result The result code.
     * @param iter The iterator for the reliable message map - used to remove the message directly.
     */
    private void completeMessageTransaction(ContextHubServiceTransaction transaction,
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

    private int toStatsTransactionResult(@ContextHubTransaction.Result int result) {
        switch (result) {
            case ContextHubTransaction.RESULT_SUCCESS:
                return ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_RESULT__TRANSACTION_RESULT_SUCCESS;
            case ContextHubTransaction.RESULT_FAILED_BAD_PARAMS:
                return ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_RESULT__TRANSACTION_RESULT_FAILED_BAD_PARAMS;
            case ContextHubTransaction.RESULT_FAILED_UNINITIALIZED:
                return ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_RESULT__TRANSACTION_RESULT_FAILED_UNINITIALIZED;
            case ContextHubTransaction.RESULT_FAILED_BUSY:
                return ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_RESULT__TRANSACTION_RESULT_FAILED_BUSY;
            case ContextHubTransaction.RESULT_FAILED_AT_HUB:
                return ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_RESULT__TRANSACTION_RESULT_FAILED_AT_HUB;
            case ContextHubTransaction.RESULT_FAILED_TIMEOUT:
                return ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_RESULT__TRANSACTION_RESULT_FAILED_TIMEOUT;
            case ContextHubTransaction.RESULT_FAILED_SERVICE_INTERNAL_FAILURE:
                return ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_RESULT__TRANSACTION_RESULT_FAILED_SERVICE_INTERNAL_FAILURE;
            case ContextHubTransaction.RESULT_FAILED_HAL_UNAVAILABLE:
                return ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_RESULT__TRANSACTION_RESULT_FAILED_HAL_UNAVAILABLE;
            case ContextHubTransaction.RESULT_FAILED_UNKNOWN:
            default: /* fall through */
                return ContextHubStatsLog.CHRE_CODE_DOWNLOAD_TRANSACTED__TRANSACTION_RESULT__TRANSACTION_RESULT_FAILED_UNKNOWN;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        synchronized (this) {
            for (ContextHubServiceTransaction transaction: mTransactionQueue) {
                sb.append(i);
                sb.append(": ");
                sb.append(transaction.toString());
                sb.append("\n");
                ++i;
            }

            if (Flags.reliableMessageRetrySupportService()) {
                for (ContextHubServiceTransaction transaction:
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
