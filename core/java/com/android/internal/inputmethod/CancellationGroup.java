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
import java.util.concurrent.CountDownLatch;

/**
 * A utility class, which works as both a factory class of a cancellation signal to cancel
 * all the completable objects.
 */
public final class CancellationGroup {
    private final Object mLock = new Object();

    /**
     * List of {@link CountDownLatch}, which can be used to propagate {@link #cancelAll()} to
     * completable objects.
     *
     * <p>This will be lazily instantiated to avoid unnecessary object allocations.</p>
     */
    @Nullable
    @GuardedBy("mLock")
    private ArrayList<CountDownLatch> mLatchList = null;

    @GuardedBy("mLock")
    private boolean mCanceled = false;

    @AnyThread
    boolean registerLatch(@NonNull CountDownLatch latch) {
        synchronized (mLock) {
            if (mCanceled) {
                return false;
            }
            if (mLatchList == null) {
                // Set the initial capacity to 1 with an assumption that usually there is up to 1
                // on-going operation.
                mLatchList = new ArrayList<>(1);
            }
            mLatchList.add(latch);
            return true;
        }
    }

    @AnyThread
    void unregisterLatch(@NonNull CountDownLatch latch) {
        synchronized (mLock) {
            if (mLatchList != null) {
                mLatchList.remove(latch);
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
                if (mLatchList != null) {
                    mLatchList.forEach(CountDownLatch::countDown);
                    mLatchList.clear();
                    mLatchList = null;
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
