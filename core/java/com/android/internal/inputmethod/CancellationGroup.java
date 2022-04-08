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
import java.util.concurrent.TimeUnit;

/**
 * A utility class, which works as both a factory class of completable objects and a cancellation
 * signal to cancel all the completable objects created by this object.
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

    /**
     * An inner class to consolidate completable object types supported by
     * {@link CancellationGroup}.
     */
    public static final class Completable {

        /**
         * Not intended to be instantiated.
         */
        private Completable() {
        }

        /**
         * Base class of all the completable types supported by {@link CancellationGroup}.
         */
        protected static class ValueBase {
            /**
             * {@link CountDownLatch} to be signaled to unblock {@link #await(int, TimeUnit)}.
             */
            private final CountDownLatch mLatch = new CountDownLatch(1);

            /**
             * {@link CancellationGroup} to which this completable object belongs.
             */
            @NonNull
            private final CancellationGroup mParentGroup;

            /**
             * Lock {@link Object} to guard complete operations within this class.
             */
            protected final Object mValueLock = new Object();

            /**
             * {@code true} after {@link #onComplete()} gets called.
             */
            @GuardedBy("mValueLock")
            protected boolean mHasValue = false;

            /**
             * Base constructor.
             *
             * @param parentGroup {@link CancellationGroup} to which this completable object
             *                    belongs.
             */
            protected ValueBase(@NonNull CancellationGroup parentGroup) {
                mParentGroup = parentGroup;
            }

            /**
             * @return {@link true} if {@link #onComplete()} gets called already.
             */
            @AnyThread
            public boolean hasValue() {
                synchronized (mValueLock) {
                    return mHasValue;
                }
            }

            /**
             * Called by subclasses to signale {@link #mLatch}.
             */
            @AnyThread
            protected void onComplete() {
                mLatch.countDown();
            }

            /**
             * Blocks the calling thread until at least one of the following conditions is met.
             *
             * <p>
             *     <ol>
             *         <li>This object becomes ready to return the value.</li>
             *         <li>{@link CancellationGroup#cancelAll()} gets called.</li>
             *         <li>The given timeout period has passed.</li>
             *     </ol>
             * </p>
             *
             * <p>The caller can distinguish the case 1 and case 2 by calling {@link #hasValue()}.
             * Note that the return value of {@link #hasValue()} can change from {@code false} to
             * {@code true} at any time, even after this methods finishes with returning
             * {@code true}.</p>
             *
             * @param timeout length of the timeout.
             * @param timeUnit unit of {@code timeout}.
             * @return {@code false} if and only if the given timeout period has passed. Otherwise
             *         {@code true}.
             */
            @AnyThread
            public boolean await(int timeout, @NonNull TimeUnit timeUnit) {
                if (!mParentGroup.registerLatch(mLatch)) {
                    // Already canceled when this method gets called.
                    return false;
                }
                try {
                    return mLatch.await(timeout, timeUnit);
                } catch (InterruptedException e) {
                    return true;
                } finally {
                    mParentGroup.unregisterLatch(mLatch);
                }
            }
        }

        /**
         * Completable object of integer primitive.
         */
        public static final class Int extends ValueBase {
            @GuardedBy("mValueLock")
            private int mValue = 0;

            private Int(@NonNull CancellationGroup factory) {
                super(factory);
            }

            /**
             * Notify when a value is set to this completable object.
             *
             * @param value value to be set.
             */
            @AnyThread
            void onComplete(int value) {
                synchronized (mValueLock) {
                    if (mHasValue) {
                        throw new UnsupportedOperationException(
                                "onComplete() cannot be called multiple times");
                    }
                    mValue = value;
                    mHasValue = true;
                }
                onComplete();
            }

            /**
             * @return value associated with this object.
             * @throws UnsupportedOperationException when called while {@link #hasValue()} returns
             *                                       {@code false}.
             */
            @AnyThread
            public int getValue() {
                synchronized (mValueLock) {
                    if (!mHasValue) {
                        throw new UnsupportedOperationException(
                                "getValue() is allowed only if hasValue() returns true");
                    }
                    return mValue;
                }
            }
        }

        /**
         * Base class of completable object types.
         *
         * @param <T> type associated with this completable object.
         */
        public static class Values<T> extends ValueBase {
            @GuardedBy("mValueLock")
            @Nullable
            private T mValue = null;

            protected Values(@NonNull CancellationGroup factory) {
                super(factory);
            }

            /**
             * Notify when a value is set to this completable value object.
             *
             * @param value value to be set.
             */
            @AnyThread
            void onComplete(@Nullable T value) {
                synchronized (mValueLock) {
                    if (mHasValue) {
                        throw new UnsupportedOperationException(
                                "onComplete() cannot be called multiple times");
                    }
                    mValue = value;
                    mHasValue = true;
                }
                onComplete();
            }

            /**
             * @return value associated with this object.
             * @throws UnsupportedOperationException when called while {@link #hasValue()} returns
             *                                       {@code false}.
             */
            @AnyThread
            @Nullable
            public T getValue() {
                synchronized (mValueLock) {
                    if (!mHasValue) {
                        throw new UnsupportedOperationException(
                                "getValue() is allowed only if hasValue() returns true");
                    }
                    return mValue;
                }
            }
        }

        /**
         * Completable object of {@link java.lang.CharSequence}.
         */
        public static final class CharSequence extends Values<java.lang.CharSequence> {
            private CharSequence(@NonNull CancellationGroup factory) {
                super(factory);
            }
        }

        /**
         * Completable object of {@link android.view.inputmethod.ExtractedText}.
         */
        public static final class ExtractedText
                extends Values<android.view.inputmethod.ExtractedText> {
            private ExtractedText(@NonNull CancellationGroup factory) {
                super(factory);
            }
        }
    }

    /**
     * @return an instance of {@link Completable.Int} that is associated with this
     *         {@link CancellationGroup}.
     */
    @AnyThread
    public Completable.Int createCompletableInt() {
        return new Completable.Int(this);
    }

    /**
     * @return an instance of {@link Completable.CharSequence} that is associated with this
     *         {@link CancellationGroup}.
     */
    @AnyThread
    public Completable.CharSequence createCompletableCharSequence() {
        return new Completable.CharSequence(this);
    }

    /**
     * @return an instance of {@link Completable.ExtractedText} that is associated with this
     *         {@link CancellationGroup}.
     */
    @AnyThread
    public Completable.ExtractedText createCompletableExtractedText() {
        return new Completable.ExtractedText(this);
    }

    @AnyThread
    private boolean registerLatch(@NonNull CountDownLatch latch) {
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
    private void unregisterLatch(@NonNull CountDownLatch latch) {
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
