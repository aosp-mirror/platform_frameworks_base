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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.AnyThread;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An class to consolidate completable object types supported by
 * {@link CancellationGroup}.
 */
public final class Completable {

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
         * {@link CountDownLatch} to be signaled to unblock
         * {@link #await(int, TimeUnit, CancellationGroup)}.
         */
        private final CountDownLatch mLatch = new CountDownLatch(1);

        /**
         * Lock {@link Object} to guard complete operations within this class.
         */
        protected final Object mStateLock = new Object();

        /**
         * Indicates the completion state of this object.
         */
        @GuardedBy("mStateLock")
        @CompletionState
        protected int mState = CompletionState.NOT_COMPLETED;

        /**
         * {@link Throwable} message passed to {@link #onError(ThrowableHolder)}.
         *
         * <p>This is not {@code null} only when {@link #mState} is
         * {@link CompletionState#COMPLETED_WITH_ERROR}.</p>
         */
        @GuardedBy("mStateLock")
        @Nullable
        protected String mMessage = null;

        @Retention(SOURCE)
        @IntDef({
                CompletionState.NOT_COMPLETED,
                CompletionState.COMPLETED_WITH_VALUE,
                CompletionState.COMPLETED_WITH_ERROR})
        protected @interface CompletionState {
            /**
             * This object is not completed yet.
             */
            int NOT_COMPLETED = 0;
            /**
             * This object is already completed with a value.
             */
            int COMPLETED_WITH_VALUE = 1;
            /**
             * This object is already completed with an error.
             */
            int COMPLETED_WITH_ERROR = 2;
        }

        /**
         * Converts the given {@link CompletionState} into a human-readable string.
         *
         * @param state {@link CompletionState} to be converted.
         * @return a human-readable {@link String} for the given {@code state}.
         */
        @AnyThread
        protected static String stateToString(@CompletionState int state) {
            switch (state) {
                case CompletionState.NOT_COMPLETED:
                    return "NOT_COMPLETED";
                case CompletionState.COMPLETED_WITH_VALUE:
                    return "COMPLETED_WITH_VALUE";
                case CompletionState.COMPLETED_WITH_ERROR:
                    return "COMPLETED_WITH_ERROR";
                default:
                    return "Unknown(value=" + state + ")";
            }
        }

        /**
         * @return {@code true} if {@link #onComplete()} gets called and {@link #mState} is
         *         {@link CompletionState#COMPLETED_WITH_VALUE}.
         */
        @AnyThread
        public boolean hasValue() {
            synchronized (mStateLock) {
                return mState == CompletionState.COMPLETED_WITH_VALUE;
            }
        }

        /**
         * Provides the base implementation of {@code getValue()} for derived classes.
         *
         * <p>Must be called after acquiring {@link #mStateLock}.</p>
         *
         * @throws RuntimeException when {@link #mState} is
         *                          {@link CompletionState#COMPLETED_WITH_ERROR}.
         * @throws UnsupportedOperationException when {@link #mState} is not
         *                                       {@link CompletionState#COMPLETED_WITH_VALUE} and
         *                                       {@link CompletionState#COMPLETED_WITH_ERROR}.
         */
        @GuardedBy("mStateLock")
        protected void enforceGetValueLocked() {
            switch (mState) {
                case CompletionState.NOT_COMPLETED:
                    throw new UnsupportedOperationException(
                            "getValue() is allowed only if hasValue() returns true");
                case CompletionState.COMPLETED_WITH_VALUE:
                    return;
                case CompletionState.COMPLETED_WITH_ERROR:
                    throw new RuntimeException(mMessage);
                default:
                    throw new UnsupportedOperationException(
                            "getValue() is not allowed on state=" + stateToString(mState));
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
         * Notify when exception happened.
         *
         * @param throwableHolder contains the {@link Throwable} object when exception happened.
         */
        @AnyThread
        protected void onError(ThrowableHolder throwableHolder) {
            synchronized (mStateLock) {
                switch (mState) {
                    case CompletionState.NOT_COMPLETED:
                        mMessage = throwableHolder.getMessage();
                        mState = CompletionState.COMPLETED_WITH_ERROR;
                        break;
                    default:
                        throw new UnsupportedOperationException(
                                "onError() is not allowed on state=" + stateToString(mState));
                }
            }
            onComplete();
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
         * @param cancellationGroup {@link CancellationGroup} to cancel completable objects.
         * @return {@code false} if and only if the given timeout period has passed. Otherwise
         *         {@code true}.
         */
        @AnyThread
        public boolean await(int timeout, @NonNull TimeUnit timeUnit,
                @Nullable CancellationGroup cancellationGroup) {
            if (cancellationGroup == null) {
                return awaitInner(timeout, timeUnit);
            }

            if (!cancellationGroup.registerLatch(mLatch)) {
                // Already canceled when this method gets called.
                return false;
            }
            try {
                return awaitInner(timeout, timeUnit);
            } finally {
                cancellationGroup.unregisterLatch(mLatch);
            }
        }

        private boolean awaitInner(int timeout, @NonNull TimeUnit timeUnit) {
            try {
                return mLatch.await(timeout, timeUnit);
            } catch (InterruptedException e) {
                return true;
            }
        }

        /**
         * Blocks the calling thread until this object becomes ready to return the value, even if
         * {@link InterruptedException} is thrown.
         */
        @AnyThread
        public void await() {
            boolean interrupted = false;
            while (true) {
                try {
                    mLatch.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                // Try to preserve the interrupt bit on this thread.
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Completable object of integer primitive.
     */
    public static final class Int extends ValueBase {
        @GuardedBy("mStateLock")
        private int mValue = 0;

        /**
         * Notify when a value is set to this completable object.
         *
         * @param value value to be set.
         */
        @AnyThread
        void onComplete(int value) {
            synchronized (mStateLock) {
                switch (mState) {
                    case CompletionState.NOT_COMPLETED:
                        mValue = value;
                        mState = CompletionState.COMPLETED_WITH_VALUE;
                        break;
                    default:
                        throw new UnsupportedOperationException(
                                "onComplete() is not allowed on state=" + stateToString(mState));
                }
            }
            onComplete();
        }

        /**
         * @return value associated with this object.
         * @throws RuntimeException when called while {@link #onError} happened.
         * @throws UnsupportedOperationException when called while {@link #hasValue()} returns
         *                                       {@code false}.
         */
        @AnyThread
        public int getValue() {
            synchronized (mStateLock) {
                enforceGetValueLocked();
                return mValue;
            }
        }
    }

    /**
     * Completable object of {@link java.lang.Void}.
     */
    public static final class Void extends ValueBase {
        /**
         * Notify when this completable object callback.
         */
        @AnyThread
        @Override
        protected void onComplete() {
            synchronized (mStateLock) {
                switch (mState) {
                    case CompletionState.NOT_COMPLETED:
                        mState = CompletionState.COMPLETED_WITH_VALUE;
                        break;
                    default:
                        throw new UnsupportedOperationException(
                                "onComplete() is not allowed on state=" + stateToString(mState));
                }
            }
            super.onComplete();
        }

        /**
         * @throws RuntimeException when called while {@link #onError} happened.
         * @throws UnsupportedOperationException when called while {@link #hasValue()} returns
         *                                       {@code false}.
         */
        @AnyThread
        public void getValue() {
            synchronized (mStateLock) {
                enforceGetValueLocked();
            }
        }
    }

    /**
     * Base class of completable object types.
     *
     * @param <T> type associated with this completable object.
     */
    public static class Values<T> extends ValueBase {
        @GuardedBy("mStateLock")
        @Nullable
        private T mValue = null;

        /**
         * Notify when a value is set to this completable value object.
         *
         * @param value value to be set.
         */
        @AnyThread
        void onComplete(@Nullable T value) {
            synchronized (mStateLock) {
                switch (mState) {
                    case CompletionState.NOT_COMPLETED:
                        mValue = value;
                        mState = CompletionState.COMPLETED_WITH_VALUE;
                        break;
                    default:
                        throw new UnsupportedOperationException(
                                "onComplete() is not allowed on state=" + stateToString(mState));
                }
            }
            onComplete();
        }

        /**
         * @return value associated with this object.
         * @throws RuntimeException when called while {@link #onError} happened
         * @throws UnsupportedOperationException when called while {@link #hasValue()} returns
         *                                       {@code false}.
         */
        @AnyThread
        @Nullable
        public T getValue() {
            synchronized (mStateLock) {
                enforceGetValueLocked();
                return mValue;
            }
        }
    }

    /**
     * @return an instance of {@link Completable.Int}.
     */
    public static Completable.Int createInt() {
        return new Completable.Int();
    }

    /**
     * @return an instance of {@link Completable.Boolean}.
     */
    public static Completable.Boolean createBoolean() {
        return new Completable.Boolean();
    }

    /**
     * @return an instance of {@link Completable.CharSequence}.
     */
    public static Completable.CharSequence createCharSequence() {
        return new Completable.CharSequence();
    }

    /**
     * @return an instance of {@link Completable.ExtractedText}.
     */
    public static Completable.ExtractedText createExtractedText() {
        return new Completable.ExtractedText();
    }

    /**
     * @return an instance of {@link Completable.SurroundingText}.
     */
    public static Completable.SurroundingText createSurroundingText() {
        return new Completable.SurroundingText();
    }

    /**
     * @return an instance of {@link Completable.InputBindResult}.
     */
    public static Completable.InputBindResult createInputBindResult() {
        return new Completable.InputBindResult();
    }

    /**
     * @return an instance of {@link Completable.InputMethodSubtype}.
     */
    public static Completable.InputMethodSubtype createInputMethodSubtype() {
        return new Completable.InputMethodSubtype();
    }

    /**
     * @return an instance of {@link Completable.InputMethodSubtypeList}.
     */
    public static Completable.InputMethodSubtypeList createInputMethodSubtypeList() {
        return new Completable.InputMethodSubtypeList();
    }

    /**
     * @return an instance of {@link Completable.InputMethodInfoList}.
     */
    public static Completable.InputMethodInfoList createInputMethodInfoList() {
        return new Completable.InputMethodInfoList();
    }

    /**
     * @return an instance of {@link Completable.IInputContentUriToken}.
     */
    public static Completable.IInputContentUriToken createIInputContentUriToken() {
        return new Completable.IInputContentUriToken();
    }

    /**
     * @return an instance of {@link Completable.Void}.
     */
    public static Completable.Void createVoid() {
        return new Completable.Void();
    }

    /**
     * Completable object of {@link java.lang.Boolean}.
     */
    public static final class Boolean extends Values<java.lang.Boolean> { }

    /**
     * Completable object of {@link java.lang.CharSequence}.
     */
    public static final class CharSequence extends Values<java.lang.CharSequence> { }

    /**
     * Completable object of {@link android.view.inputmethod.ExtractedText}.
     */
    public static final class ExtractedText
            extends Values<android.view.inputmethod.ExtractedText> { }

    /**
     * Completable object of {@link android.view.inputmethod.SurroundingText}.
     */
    public static final class SurroundingText
            extends Values<android.view.inputmethod.SurroundingText> { }

    /**
     * Completable object of {@link com.android.internal.view.InputBindResult}.
     */
    public static final class InputBindResult
            extends Values<com.android.internal.view.InputBindResult> { }

    /**
     * Completable object of {@link android.view.inputmethod.InputMethodSubtype}.
     */
    public static final class InputMethodSubtype
            extends Values<android.view.inputmethod.InputMethodSubtype> { }

    /**
     * Completable object of {@link List<android.view.inputmethod.InputMethodSubtype>}.
     */
    public static final class InputMethodSubtypeList
            extends Values<List<android.view.inputmethod.InputMethodSubtype>> { }

    /**
     * Completable object of {@link List<android.view.inputmethod.InputMethodInfo>}.
     */
    public static final class InputMethodInfoList
            extends Values<List<android.view.inputmethod.InputMethodInfo>> { }

    /**
     * Completable object of {@link IInputContentUriToken>}.
     */
    public static final class IInputContentUriToken
            extends Values<com.android.internal.inputmethod.IInputContentUriToken> { }

    /**
     * Await the result by the {@link Completable.Values}.
     *
     * @return the result once {@link ValueBase#onComplete()}.
     */
    @AnyThread
    @Nullable
    public static <T> T getResult(@NonNull Completable.Values<T> value) {
        value.await();
        return value.getValue();
    }

    /**
     * Await the int result by the {@link Completable.Int}.
     *
     * @return the result once {@link ValueBase#onComplete()}.
     */
    @AnyThread
    public static int getIntResult(@NonNull Completable.Int value) {
        value.await();
        return value.getValue();
    }

    /**
     * Await the result by the {@link Completable.Void}.
     *
     * Check the result once {@link ValueBase#onComplete()}
     */
    @AnyThread
    public static void getResult(@NonNull Completable.Void value) {
        value.await();
        value.getValue();
    }

    /**
     * Await the result by the {@link Completable.Int}, and log it if there is no result after
     * given timeout.
     *
     * @return the result once {@link ValueBase#onComplete()}
     */
    @AnyThread
    public static int getResultOrZero(@NonNull Completable.Int value, String tag,
            @NonNull String methodName, @Nullable CancellationGroup cancellationGroup,
            int maxWaitTime) {
        final boolean timedOut = value.await(maxWaitTime, TimeUnit.MILLISECONDS, cancellationGroup);
        if (value.hasValue()) {
            return value.getValue();
        }
        logInternal(tag, methodName, timedOut, maxWaitTime, 0);
        return 0;
    }

    /**
     * Await the result by the {@link Completable.Values}, and log it if there is no result after
     * given timeout.
     *
     * @return the result once {@link ValueBase#onComplete()}
     */
    @AnyThread
    @Nullable
    public static <T> T getResultOrNull(@NonNull Completable.Values<T> value, String tag,
            @NonNull String methodName, @Nullable CancellationGroup cancellationGroup,
            int maxWaitTime) {
        final boolean timedOut = value.await(maxWaitTime, TimeUnit.MILLISECONDS, cancellationGroup);
        if (value.hasValue()) {
            return value.getValue();
        }
        logInternal(tag, methodName, timedOut, maxWaitTime, null);
        return null;
    }

    @AnyThread
    private static void logInternal(String tag, @Nullable String methodName, boolean timedOut,
            int maxWaitTime, @Nullable Object defaultValue) {
        if (timedOut) {
            Log.w(tag, methodName + " didn't respond in " + maxWaitTime + " msec."
                    + " Returning default: " + defaultValue);
        } else {
            Log.w(tag, methodName + " was canceled before complete. Returning default: "
                    + defaultValue);
        }
    }
}
