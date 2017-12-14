/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.location;

import android.hardware.location.ContextHubTransaction;
import android.hardware.location.NanoAppState;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * An abstract class representing transactions requested to the Context Hub Service.
 *
 * @hide
 */
/* package */ abstract class ContextHubServiceTransaction {
    private final int mTransactionId;
    @ContextHubTransaction.Type
    private final int mTransactionType;

    /*
     * true if the transaction has already completed, false otherwise
     */
    private boolean mIsComplete = false;

    /* package */ ContextHubServiceTransaction(int id, int type) {
        mTransactionId = id;
        mTransactionType = type;
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
    /* package */ void onTransactionComplete(@ContextHubTransaction.Result int result) {
    }

    /**
     * A function to invoke when a query transaction completes.
     *
     * Only relevant for query transactions.
     *
     * @param result           the result of the query
     * @param nanoAppStateList the list of nanoapps given by the query response
     */
    /* package */ void onQueryResponse(
            @ContextHubTransaction.Result int result, List<NanoAppState> nanoAppStateList) {
    }

    /**
     * @return the ID of this transaction
     */
    /* package */ int getTransactionId() {
        return mTransactionId;
    }

    /**
     * @return the type of this transaction
     * @see ContextHubTransaction.Type
     */
    @ContextHubTransaction.Type
    /* package */ int getTransactionType() {
        return mTransactionType;
    }

    /**
     * Gets the timeout period as defined in IContexthub.hal
     *
     * @return the timeout of this transaction in the specified time unit
     */
    /* package */ long getTimeout(TimeUnit unit) {
        switch (mTransactionType) {
            case ContextHubTransaction.TYPE_LOAD_NANOAPP:
                return unit.convert(30L, TimeUnit.SECONDS);
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
    /* package */ void setComplete() {
        mIsComplete = true;
    }

    /**
     * @return true if the transaction has already completed, false otherwise
     */
    /* package */ boolean isComplete() {
        return mIsComplete;
    }

    @Override
    public String toString() {
        return ContextHubTransaction.typeToString(mTransactionType, true /* upperCase */)
                + " transaction (ID = " + mTransactionId + ")";
    }
}
