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

package com.android.server.pm.utils;

import android.annotation.NonNull;
import android.os.Handler;

import com.android.server.IoThread;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Loose throttle latest behavior for success/fail requests, with options to schedule or force a
 * request through. Throttling is implicit and not configurable. This means requests are dispatched
 * to the {@link Handler} immediately when received, and only batched while waiting on the next
 * message execution or running request.
 *
 * This also means there is no explicit debouncing. Implicit debouncing is available through the
 * same runtime delays in the {@link Handler} instance and the request execution, where multiple
 * requests prior to the execution point are collapsed.
 *
 * Callers provide a {@link Handler} with which to schedule tasks on. This may be a highly
 * contentious thread like {@link IoThread#getHandler()}, but note that there are no guarantees
 * that the request will be handled before the system server dies. Ideally callers should handle
 * re-initialization from stale state with no consequences to the user.
 *
 * This class will retry requests if they don't succeed, as provided by a true/false response from
 * the block provided to run the request. This uses an exponential backoff mechanism, assuming that
 * state write should be attempted immediately, but not retried so heavily as to potentially block
 * other system server callers. Exceptions are not considered and will not result in a retry if
 * thrown from inside the block. Caller should wrap with try-catch and rollback and transaction
 * state before returning false to signal a retry.
 *
 * The caller is strictly responsible for data synchronization, as this class will not synchronize
 * the request block, potentially running it multiple times or on multiple threads simultaneously
 * if requests come in asynchronously.
 */
public class RequestThrottle {

    private static final int DEFAULT_RETRY_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_DELAY_MS = 1000;
    private static final int DEFAULT_BACKOFF_BASE = 2;

    private final AtomicInteger mLastRequest = new AtomicInteger(0);
    private final AtomicInteger mLastCommitted = new AtomicInteger(-1);

    private final int mMaxAttempts;
    private final int mFirstDelay;
    private final int mBackoffBase;

    private final AtomicInteger mCurrentRetry = new AtomicInteger(0);

    @NonNull
    private final Handler mHandler;

    @NonNull
    private final Supplier<Boolean> mBlock;

    @NonNull
    private final Runnable mRunnable;

    /**
     * @see #RequestThrottle(Handler, int, int, int, Supplier)
     */
    public RequestThrottle(@NonNull Handler handler, @NonNull Supplier<Boolean> block) {
        this(handler, DEFAULT_RETRY_MAX_ATTEMPTS, DEFAULT_DELAY_MS, DEFAULT_BACKOFF_BASE,
                block);
    }

    /**
     * Backoff timing is calculated as firstDelay * (backoffBase ^ retryAttempt).
     *
     * @param handler     Representing the thread to run the provided block.
     * @param block       The action to run when scheduled, returning whether or not the request was
     *                    successful. Note that any thrown exceptions will be ignored and not
     *                    retried, since it's not easy to tell how destructive or retry-able an
     *                    exception is.
     * @param maxAttempts Number of times to re-attempt any single request.
     * @param firstDelay  The first delay used after the initial attempt.
     * @param backoffBase The base of the backoff calculation, where retry attempt count is the
     *                    exponent.
     */
    public RequestThrottle(@NonNull Handler handler, int maxAttempts, int firstDelay,
            int backoffBase, @NonNull Supplier<Boolean> block) {
        mHandler = handler;
        mBlock = block;
        mMaxAttempts = maxAttempts;
        mFirstDelay = firstDelay;
        mBackoffBase = backoffBase;
        mRunnable = this::runInternal;
    }

    /**
     * Schedule the intended action on the provided {@link Handler}.
     */
    public void schedule() {
        // To avoid locking the Handler twice by pre-checking hasCallbacks, instead just queue
        // the Runnable again. It will no-op if the request has already been written to disk.
        mLastRequest.incrementAndGet();
        mHandler.post(mRunnable);
    }

    /**
     * Run the intended action immediately on the calling thread. Note that synchronization and
     * deadlock between threads is not handled. This will immediately call the request block, and
     * also potentially schedule a retry. The caller must not block itself.
     *
     * @return true if the write succeeded or the last request was already written
     */
    public boolean runNow() {
        mLastRequest.incrementAndGet();
        return runInternal();
    }

    private boolean runInternal() {
        int lastRequest = mLastRequest.get();
        int lastCommitted = mLastCommitted.get();
        if (lastRequest == lastCommitted) {
            return true;
        }

        if (mBlock.get()) {
            mCurrentRetry.set(0);
            mLastCommitted.set(lastRequest);
            return true;
        } else {
            int currentRetry = mCurrentRetry.getAndIncrement();
            if (currentRetry < mMaxAttempts) {
                long nextDelay =
                        (long) (mFirstDelay * Math.pow(mBackoffBase, currentRetry));
                mHandler.postDelayed(mRunnable, nextDelay);
            } else {
                mCurrentRetry.set(0);
            }

            return false;
        }
    }
}
