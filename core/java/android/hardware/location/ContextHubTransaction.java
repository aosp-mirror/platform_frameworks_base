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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.os.Handler;
import android.os.HandlerExecutor;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A class describing a request sent to the Context Hub Service.
 *
 * This object is generated as a result of an asynchronous request sent to the Context Hub
 * through the ContextHubManager APIs. The caller can either retrieve the result
 * synchronously through a blocking call ({@link #waitForResponse(long, TimeUnit)}) or
 * asynchronously through a user-defined listener
 * ({@link #setOnCompleteListener(OnCompleteListener, Executor)} )}).
 *
 * @param <T> the type of the contents in the transaction response
 *
 * @hide
 */
@SystemApi
public class ContextHubTransaction<T> {
    private static final String TAG = "ContextHubTransaction";

    /**
     * Constants describing the type of a transaction through the Context Hub Service.
     * {@hide}
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_LOAD_NANOAPP,
            TYPE_UNLOAD_NANOAPP,
            TYPE_ENABLE_NANOAPP,
            TYPE_DISABLE_NANOAPP,
            TYPE_QUERY_NANOAPPS,
            TYPE_RELIABLE_MESSAGE,
    })
    public @interface Type { }

    public static final int TYPE_LOAD_NANOAPP = 0;
    public static final int TYPE_UNLOAD_NANOAPP = 1;
    public static final int TYPE_ENABLE_NANOAPP = 2;
    public static final int TYPE_DISABLE_NANOAPP = 3;
    public static final int TYPE_QUERY_NANOAPPS = 4;
    @FlaggedApi(Flags.FLAG_RELIABLE_MESSAGE)
    public static final int TYPE_RELIABLE_MESSAGE = 5;

    /**
     * Constants describing the result of a transaction or request through the Context Hub Service.
     * {@hide}
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_SUCCESS,
            RESULT_FAILED_UNKNOWN,
            RESULT_FAILED_BAD_PARAMS,
            RESULT_FAILED_UNINITIALIZED,
            RESULT_FAILED_BUSY,
            RESULT_FAILED_AT_HUB,
            RESULT_FAILED_TIMEOUT,
            RESULT_FAILED_SERVICE_INTERNAL_FAILURE,
            RESULT_FAILED_HAL_UNAVAILABLE,
            RESULT_FAILED_NOT_SUPPORTED,
    })
    public @interface Result {}
    public static final int RESULT_SUCCESS = 0;
    /**
     * Generic failure mode.
     */
    public static final int RESULT_FAILED_UNKNOWN = 1;
    /**
     * Failure mode when the request parameters were not valid.
     */
    public static final int RESULT_FAILED_BAD_PARAMS = 2;
    /**
     * Failure mode when the Context Hub is not initialized.
     */
    public static final int RESULT_FAILED_UNINITIALIZED = 3;
    /**
     * Failure mode when there are too many transactions pending.
     */
    public static final int RESULT_FAILED_BUSY = 4;
    /**
     * Failure mode when the request went through, but failed asynchronously at the hub.
     */
    public static final int RESULT_FAILED_AT_HUB = 5;
    /**
     * Failure mode when the transaction has timed out.
     */
    public static final int RESULT_FAILED_TIMEOUT = 6;
    /**
     * Failure mode when the transaction has failed internally at the service.
     */
    public static final int RESULT_FAILED_SERVICE_INTERNAL_FAILURE = 7;
    /**
     * Failure mode when the Context Hub HAL was not available.
     */
    public static final int RESULT_FAILED_HAL_UNAVAILABLE = 8;
    /**
     * Failure mode when the operation is not supported.
     */
    @FlaggedApi(Flags.FLAG_RELIABLE_MESSAGE)
    public static final int RESULT_FAILED_NOT_SUPPORTED = 9;

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
     * An interface describing the listener for a transaction completion.
     *
     * @param <L> the type of the contents in the transaction response
     */
    @FunctionalInterface
    public interface OnCompleteListener<L> {
        /**
         * The listener function to invoke when the transaction completes.
         *
         * @param transaction the transaction that this callback was attached to.
         * @param response the response of the transaction.
         */
        void onComplete(
                ContextHubTransaction<L> transaction, ContextHubTransaction.Response<L> response);
    }

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
     * The executor to invoke the onComplete async callback.
     */
    private Executor mExecutor = null;

    /*
     * The listener to be invoked when the transaction completes.
     */
    private ContextHubTransaction.OnCompleteListener<T> mListener = null;

    /*
     * Synchronization latch used to block on response.
     */
    private final CountDownLatch mDoneSignal = new CountDownLatch(1);

    /*
     * true if the response has been set throught setResponse, false otherwise.
     */
    private boolean mIsResponseSet = false;

    ContextHubTransaction(@Type int type) {
        mTransactionType = type;
    }

    /**
     * Converts a transaction type to a human-readable string
     *
     * @param type the type of a transaction
     * @param upperCase {@code true} if upper case the first letter, {@code false} otherwise
     * @return a string describing the transaction
     */
    public static String typeToString(@Type int type, boolean upperCase) {
        switch (type) {
            case ContextHubTransaction.TYPE_LOAD_NANOAPP:
                return upperCase ? "Load" : "load";
            case ContextHubTransaction.TYPE_UNLOAD_NANOAPP:
                return upperCase ? "Unload" : "unload";
            case ContextHubTransaction.TYPE_ENABLE_NANOAPP:
                return upperCase ? "Enable" : "enable";
            case ContextHubTransaction.TYPE_DISABLE_NANOAPP:
                return upperCase ? "Disable" : "disable";
            case ContextHubTransaction.TYPE_QUERY_NANOAPPS:
                return upperCase ? "Query" : "query";
            case ContextHubTransaction.TYPE_RELIABLE_MESSAGE: {
                if (Flags.reliableMessage()) {
                    return upperCase ? "Reliable Message" : "reliable message";
                }
            }
            default:
                return upperCase ? "Unknown" : "unknown";
        }
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
     * If the specified timeout has passed, a TimeoutException will be thrown and the caller may
     * retry the invocation of this method at a later time.
     *
     * @param timeout the timeout duration
     * @param unit the unit of the timeout
     *
     * @return the transaction response
     *
     * @throws InterruptedException if the current thread is interrupted while waiting for response
     * @throws TimeoutException if the timeout period has passed
     */
    public ContextHubTransaction.Response<T> waitForResponse(
            long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        boolean success = mDoneSignal.await(timeout, unit);

        if (!success) {
            throw new TimeoutException("Timed out while waiting for transaction");
        }

        return mResponse;
    }

    /**
     * Sets the listener to be invoked invoked when the transaction completes.
     *
     * This function provides an asynchronous approach to retrieve the result of the
     * transaction. When the transaction response has been provided by the Context Hub,
     * the given listener will be invoked.
     *
     * If the transaction has already completed at the time of invocation, the listener
     * will be immediately invoked. If the transaction has been invalidated,
     * the listener will never be invoked.
     *
     * A transaction can be invalidated if the process owning the transaction is no longer active
     * and the reference to this object is lost.
     *
     * This method or {@link #setOnCompleteListener(ContextHubTransaction.OnCompleteListener)} can
     * only be invoked once, or an IllegalStateException will be thrown.
     *
     * @param listener the listener to be invoked upon completion
     * @param executor the executor to invoke the callback
     *
     * @throws IllegalStateException if this method is called multiple times
     * @throws NullPointerException if the callback or handler is null
     */
    public void setOnCompleteListener(
            @NonNull ContextHubTransaction.OnCompleteListener<T> listener,
            @NonNull @CallbackExecutor Executor executor) {
        synchronized (this) {
            Objects.requireNonNull(listener, "OnCompleteListener cannot be null");
            Objects.requireNonNull(executor, "Executor cannot be null");
            if (mListener != null) {
                throw new IllegalStateException(
                        "Cannot set ContextHubTransaction listener multiple times");
            }

            mListener = listener;
            mExecutor = executor;

            if (mDoneSignal.getCount() == 0) {
                mExecutor.execute(() -> mListener.onComplete(this, mResponse));
            }
        }
    }

    /**
     * Sets the listener to be invoked invoked when the transaction completes.
     *
     * Equivalent to {@link #setOnCompleteListener(ContextHubTransaction.OnCompleteListener,
     * Executor)} with the executor using the main thread's Looper.
     *
     * This method or {@link #setOnCompleteListener(ContextHubTransaction.OnCompleteListener,
     * Executor)} can only be invoked once, or an IllegalStateException will be thrown.
     *
     * @param listener the listener to be invoked upon completion
     *
     * @throws IllegalStateException if this method is called multiple times
     * @throws NullPointerException if the callback is null
     */
    public void setOnCompleteListener(
            @NonNull ContextHubTransaction.OnCompleteListener<T> listener) {
        setOnCompleteListener(listener, new HandlerExecutor(Handler.getMain()));
    }

    /**
     * Sets the response of the transaction.
     *
     * This method should only be invoked by ContextHubManager as a result of a callback from
     * the Context Hub Service indicating the response from a transaction. This method should not be
     * invoked more than once.
     *
     * @param response the response to set
     *
     * @throws IllegalStateException if this method is invoked multiple times
     * @throws NullPointerException if the response is null
     */
    /* package */ void setResponse(ContextHubTransaction.Response<T> response) {
        synchronized (this) {
            Objects.requireNonNull(response, "Response cannot be null");
            if (mIsResponseSet) {
                throw new IllegalStateException(
                        "Cannot set response of ContextHubTransaction multiple times");
            }

            mResponse = response;
            mIsResponseSet = true;

            mDoneSignal.countDown();
            if (mListener != null) {
                mExecutor.execute(() -> mListener.onComplete(this, mResponse));
            }
        }
    }
}
