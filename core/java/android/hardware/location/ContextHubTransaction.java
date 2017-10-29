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
package android.hardware.location;

import android.annotation.IntDef;
import android.os.Handler;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.TimeUnit;

/**
 * A class describing a request sent to the Context Hub Service.
 *
 * This object is generated as a result of an asynchronous request sent to the Context Hub
 * through the ContextHubManager APIs. The caller can either retrieve the result
 * synchronously through a blocking call ({@link #waitForResponse(long, TimeUnit)}) or
 * asynchronously through a user-defined callback
 * ({@link #onComplete(ContextHubTransaction.Callback<T>, Handler)}).
 *
 * A transaction can be invalidated if the caller of the transaction is no longer active
 * and the reference to this object is lost, or if timeout period has passed in
 * {@link #waitForResponse(long, TimeUnit)}.
 *
 * @param <T> the type of the contents in the transaction response
 *
 * @hide
 */
public class ContextHubTransaction<T> {
    /**
     * Constants describing the type of a transaction through the Context Hub Service.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TYPE_LOAD_NANOAPP,
            TYPE_UNLOAD_NANOAPP,
            TYPE_ENABLE_NANOAPP,
            TYPE_DISABLE_NANOAPP,
            TYPE_QUERY_NANOAPPS})
    public @interface Type {}
    public static final int TYPE_LOAD_NANOAPP = 0;
    public static final int TYPE_UNLOAD_NANOAPP = 1;
    public static final int TYPE_ENABLE_NANOAPP = 2;
    public static final int TYPE_DISABLE_NANOAPP = 3;
    public static final int TYPE_QUERY_NANOAPPS = 4;

    /**
     * Constants describing the result of a transaction or request through the Context Hub Service.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TRANSACTION_SUCCESS,
            TRANSACTION_FAILED_UNKNOWN,
            TRANSACTION_FAILED_BAD_PARAMS,
            TRANSACTION_FAILED_UNINITIALIZED,
            TRANSACTION_FAILED_PENDING,
            TRANSACTION_FAILED_AT_HUB,
            TRANSACTION_FAILED_TIMEOUT})
    public @interface Result {}
    public static final int TRANSACTION_SUCCESS = 0;
    /**
     * Generic failure mode.
     */
    public static final int TRANSACTION_FAILED_UNKNOWN = 1;
    /**
     * Failure mode when the request parameters were not valid.
     */
    public static final int TRANSACTION_FAILED_BAD_PARAMS = 2;
    /**
     * Failure mode when the Context Hub is not initialized.
     */
    public static final int TRANSACTION_FAILED_UNINITIALIZED = 3;
    /**
     * Failure mode when there are too many transactions pending.
     */
    public static final int TRANSACTION_FAILED_PENDING = 4;
    /**
     * Failure mode when the request went through, but failed asynchronously at the hub.
     */
    public static final int TRANSACTION_FAILED_AT_HUB = 5;
    /**
     * Failure mode when the transaction has timed out.
     */
    public static final int TRANSACTION_FAILED_TIMEOUT = 6;

    /**
     * A class describing the response for a ContextHubTransaction.
     *
     * @param <R> the type of the contents in the response
     */
    public static class Response<R> {
        /*
         * The result of the transaction.
         */
        @ContextHubTransaction.Result
        private int mResult;

        /*
         * The contents of the response from the Context Hub.
         */
        private R mContents;

        Response(@ContextHubTransaction.Result int result, R contents) {
            mResult = result;
            mContents = contents;
        }

        @ContextHubTransaction.Result
        public int getResult() {
            return mResult;
        }

        public R getContents() {
            return mContents;
        }
    }

    /**
     * An interface describing the callback to be invoked when a transaction completes.
     *
     * @param <C> the type of the contents in the transaction response
     */
    @FunctionalInterface
    public interface Callback<C> {
        /**
         * The callback to invoke when the transaction completes.
         *
         * @param transaction the transaction that this callback was attached to.
         * @param response the response of the transaction.
         */
        void onComplete(
                ContextHubTransaction<C> transaction, ContextHubTransaction.Response<C> response);
    }

    /*
     * The unique identifier representing the transaction.
     */
    private int mTransactionId;

    /*
     * The type of the transaction.
     */
    @Type
    private int mTransactionType;

    /*
     * The response of the transaction.
     */
    private ContextHubTransaction.Response<T> mResponse;

    /*
     * The handler to invoke the aynsc response supplied by onComplete.
     */
    private Handler mHandler = null;

    /*
     * The callback to invoke when the transaction completes.
     */
    private ContextHubTransaction.Callback<T> mCallback = null;

    ContextHubTransaction(int id, @Type int type) {
        mTransactionId = id;
        mTransactionType = type;
    }

    /**
     * @return the type of the transaction
     */
    @Type
    public int getType() {
        return mTransactionType;
    }

    /**
     * Waits to receive the asynchronous transaction result.
     *
     * This function blocks until the Context Hub Service has received a response
     * for the transaction represented by this object by the Context Hub, or a
     * specified timeout period has elapsed.
     *
     * If the specified timeout has passed, the transaction represented by this object
     * is invalidated by the Context Hub Service (resulting in a timeout failure in the
     * response).
     *
     * @param timeout the timeout duration
     * @param unit the unit of the timeout
     *
     * @return the transaction response
     */
    public ContextHubTransaction.Response<T> waitForResponse(long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException("TODO: Implement this");
    }

    /**
     * Sets a callback to be invoked when the transaction completes.
     *
     * This function provides an asynchronous approach to retrieve the result of the
     * transaction. When the transaction response has been provided by the Context Hub,
     * the given callback will be posted by the provided handler.
     *
     * If the transaction has already completed at the time of invocation, the callback
     * will be immediately posted by the handler. If the transaction has been invalidated,
     * the callback will never be invoked.
     *
     * @param callback the callback to be invoked upon completion
     * @param handler the handler to post the callback
     */
    public void onComplete(ContextHubTransaction.Callback<T> callback, Handler handler) {
        throw new UnsupportedOperationException("TODO: Implement this");
    }

    private void setResponse(ContextHubTransaction.Response<T> response) {
        mResponse = response;
        throw new UnsupportedOperationException("TODO: Unblock waitForResponse");
    }
}
