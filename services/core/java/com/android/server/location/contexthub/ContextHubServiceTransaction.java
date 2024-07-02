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

import android.hardware.location.ContextHubTransaction;
import android.hardware.location.NanoAppState;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * An abstract class representing transactions requested to the Context Hub Service.
 *
 * @hide
 */
abstract class ContextHubServiceTransaction {
    private final int mTransactionId;

    @ContextHubTransaction.Type
    private final int mTransactionType;

    private final Long mNanoAppId;

    private final String mPackage;

    private final Integer mMessageSequenceNumber;

    private long mNextRetryTime;

    private long mTimeoutTime;

    /** The number of times the transaction has been started (start function called). */
    private int mNumCompletedStartCalls;

    private final short mHostEndpointId;

    private boolean mIsComplete = false;

    ContextHubServiceTransaction(int id, int type, String packageName) {
        mTransactionId = id;
        mTransactionType = type;
        mNanoAppId = null;
        mPackage = packageName;
        mMessageSequenceNumber = null;
        mNextRetryTime = Long.MAX_VALUE;
        mTimeoutTime = Long.MAX_VALUE;
        mNumCompletedStartCalls = 0;
        mHostEndpointId = Short.MAX_VALUE;
    }

    ContextHubServiceTransaction(int id, int type, long nanoAppId,
            String packageName) {
        mTransactionId = id;
        mTransactionType = type;
        mNanoAppId = nanoAppId;
        mPackage = packageName;
        mMessageSequenceNumber = null;
        mNextRetryTime = Long.MAX_VALUE;
        mTimeoutTime = Long.MAX_VALUE;
        mNumCompletedStartCalls = 0;
        mHostEndpointId = Short.MAX_VALUE;
    }

    ContextHubServiceTransaction(int id, int type, String packageName,
            int messageSequenceNumber, short hostEndpointId) {
        mTransactionId = id;
        mTransactionType = type;
        mNanoAppId = null;
        mPackage = packageName;
        mMessageSequenceNumber = messageSequenceNumber;
        mNextRetryTime = Long.MAX_VALUE;
        mTimeoutTime = Long.MAX_VALUE;
        mNumCompletedStartCalls = 0;
        mHostEndpointId = hostEndpointId;
    }

    /**
     * Starts this transaction with a Context Hub.
     *
     * All instances of this class must implement this method by making an asynchronous request to
     * a hub.
     *
     * @return the synchronous error code of the transaction start
     */
    /* package */
    abstract int onTransact();

    /**
     * A function to invoke when the transaction completes.
     *
     * For transactions with expected contents (such as a query), the class instance should
     * implement the appropriate behavior (e.g. invoke onQueryResponse with an empty list).
     *
     * @param result the result of the transaction
     */
    void onTransactionComplete(@ContextHubTransaction.Result int result) {
    }

    /**
     * A function to invoke when a query transaction completes.
     *
     * Only relevant for query transactions.
     *
     * @param result           the result of the query
     * @param nanoAppStateList the list of nanoapps given by the query response
     */
    void onQueryResponse(
            @ContextHubTransaction.Result int result, List<NanoAppState> nanoAppStateList) {
    }

    int getTransactionId() {
        return mTransactionId;
    }

    @ContextHubTransaction.Type
    int getTransactionType() {
        return mTransactionType;
    }

    Integer getMessageSequenceNumber() {
        return mMessageSequenceNumber;
    }

    long getNextRetryTime() {
        return mNextRetryTime;
    }

    long getTimeoutTime() {
        return mTimeoutTime;
    }

    int getNumCompletedStartCalls() {
        return mNumCompletedStartCalls;
    }

    short getHostEndpointId() {
        return mHostEndpointId;
    }

    /**
     * Gets the timeout period as defined in IContexthub.hal
     *
     * @return the timeout of this transaction in the specified time unit
     */
    long getTimeout(TimeUnit unit) {
        switch (mTransactionType) {
            case ContextHubTransaction.TYPE_LOAD_NANOAPP:
                return unit.convert(30L, TimeUnit.SECONDS);
            case ContextHubTransaction.TYPE_RELIABLE_MESSAGE:
                return unit.convert(ContextHubTransactionManager.RELIABLE_MESSAGE_TIMEOUT.toNanos(),
                        TimeUnit.NANOSECONDS);
            case ContextHubTransaction.TYPE_UNLOAD_NANOAPP:
            case ContextHubTransaction.TYPE_ENABLE_NANOAPP:
            case ContextHubTransaction.TYPE_DISABLE_NANOAPP:
            case ContextHubTransaction.TYPE_QUERY_NANOAPPS:
                // Note: query timeout is not specified at the HAL
            default: /* fall through */
                return unit.convert(5L, TimeUnit.SECONDS);
        }
    }

    /**
     * Marks the transaction as complete.
     *
     * Should only be called as a result of a response from a Context Hub callback
     */
    void setComplete() {
        mIsComplete = true;
    }

    void setNextRetryTime(long nextRetryTime) {
        mNextRetryTime = nextRetryTime;
    }

    void setTimeoutTime(long timeoutTime) {
        mTimeoutTime = timeoutTime;
    }

    void setNumCompletedStartCalls(int numCompletedStartCalls) {
        mNumCompletedStartCalls = numCompletedStartCalls;
    }

    boolean isComplete() {
        return mIsComplete;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append(ContextHubTransaction.typeToString(mTransactionType,
                /* upperCase= */ true));
        out.append(" (");
        if (mNanoAppId != null) {
            out.append("appId = 0x");
            out.append(Long.toHexString(mNanoAppId));
            out.append(", ");
        }
        out.append("package = ");
        out.append(mPackage);
        if (mMessageSequenceNumber != null) {
            out.append(", messageSequenceNumber = ");
            out.append(mMessageSequenceNumber);
        }
        if (mTransactionType == ContextHubTransaction.TYPE_RELIABLE_MESSAGE) {
            out.append(", nextRetryTime = ");
            out.append(mNextRetryTime);
            out.append(", timeoutTime = ");
            out.append(mTimeoutTime);
            out.append(", numCompletedStartCalls = ");
            out.append(mNumCompletedStartCalls);
            out.append(", hostEndpointId = ");
            out.append(mHostEndpointId);
        }
        out.append(")");

        return out.toString();
    }
}
