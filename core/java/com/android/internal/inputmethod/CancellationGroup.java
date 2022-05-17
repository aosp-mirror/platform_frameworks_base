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

package com.android.internal.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * A utility class, which works as both a factory class of a cancellation signal to cancel
 * all the completable objects.
 *
 * <p>TODO: Make this lock-free.</p>
 */
public final class CancellationGroup {
    private final Object mLock = new Object();

    /**
     * List of {@link CompletableFuture}, which can be used to propagate {@link #cancelAll()} to
     * completable objects.
     *
     * <p>This will be lazily instantiated to avoid unnecessary object allocations.</p>
     */
    @Nullable
    @GuardedBy("mLock")
    private ArrayList<CompletableFuture<?>> mFutureList = null;

    @GuardedBy("mLock")
    private boolean mCanceled = false;

    /**
     * Tries to register the given {@link CompletableFuture} into the callback list if this
     * {@link CancellationGroup} is not yet cancelled.
     *
     * <p>If this {@link CancellationGroup} is already cancelled, then this method will immediately
     * call {@link CompletableFuture#cancel(boolean)} then return {@code false}.</p>
     *
     * <p>When this method returns {@code true}, call {@link #unregisterFuture(CompletableFuture)}
     * to remove the unnecessary object reference.</p>
     *
     * @param future {@link CompletableFuture} to be added to the cancellation callback list.
     * @return {@code true} if the given {@code future} is added to the callback list.
     *         {@code false} otherwise.
     */
    @AnyThread
    boolean tryRegisterFutureOrCancelImmediately(@NonNull CompletableFuture<?> future) {
        synchronized (mLock) {
            if (mCanceled) {
                future.cancel(false);
                return false;
            }
            if (mFutureList == null) {
                // Set the initial capacity to 1 with an assumption that usually there is up to 1
                // on-going operation.
                mFutureList = new ArrayList<>(1);
            }
            mFutureList.add(future);
            return true;
        }
    }

    @AnyThread
    void unregisterFuture(@NonNull CompletableFuture<?> future) {
        synchronized (mLock) {
            if (mFutureList != null) {
                mFutureList.remove(future);
            }
        }
    }

    /**
     * Cancel all the completable objects created from this {@link CancellationGroup}.
     *
     * <p>Secondary calls will be silently ignored.</p>
     */
    @AnyThread
    public void cancelAll() {
        synchronized (mLock) {
            if (!mCanceled) {
                mCanceled = true;
                if (mFutureList != null) {
                    mFutureList.forEach(future -> future.cancel(false));
                    mFutureList.clear();
                    mFutureList = null;
                }
            }
        }
    }

    /**
     * @return {@code true} if {@link #cancelAll()} is already called. {@code false} otherwise.
     */
    @AnyThread
    public boolean isCanceled() {
        synchronized (mLock) {
            return mCanceled;
        }
    }
}
