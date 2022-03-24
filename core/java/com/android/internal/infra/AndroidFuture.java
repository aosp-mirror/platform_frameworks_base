/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.infra;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.lang.reflect.Constructor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A customized {@link CompletableFuture} with focus on reducing the number of allocations involved
 * in a typical future usage scenario for Android.
 *
 * <p>
 * In particular this involves allocations optimizations in:
 * <ul>
 *     <li>{@link #thenCompose(Function)}</li>
 *     <li>{@link #thenApply(Function)}</li>
 *     <li>{@link #thenCombine(CompletionStage, BiFunction)}</li>
 *     <li>{@link #orTimeout(long, TimeUnit)}</li>
 *     <li>{@link #whenComplete(BiConsumer)}</li>
 * </ul>
 * As well as their *Async versions.
 *
 * <p>
 * You can pass {@link AndroidFuture} across an IPC.
 * When doing so, completing the future on the other side will propagate the completion back,
 * effectively acting as an error-aware remote callback.
 *
 * <p>
 * {@link AndroidFuture} is {@link Parcelable} iff its wrapped type {@code T} is
 * effectively parcelable, i.e. is supported by {@link Parcel#readValue}/{@link Parcel#writeValue}.
 *
 * @param <T> see {@link CompletableFuture}
 */
public class AndroidFuture<T> extends CompletableFuture<T> implements Parcelable {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = AndroidFuture.class.getSimpleName();
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];
    private static @Nullable Handler sMainHandler;

    private final @NonNull Object mLock = new Object();
    @GuardedBy("mLock")
    private @Nullable BiConsumer<? super T, ? super Throwable> mListener;
    @GuardedBy("mLock")
    private @Nullable Executor mListenerExecutor = DIRECT_EXECUTOR;
    private @NonNull Handler mTimeoutHandler = getMainHandler();
    private final @Nullable IAndroidFuture mRemoteOrigin;

    public AndroidFuture() {
        super();
        mRemoteOrigin = null;
    }

    AndroidFuture(Parcel in) {
        super();
        if (in.readBoolean()) {
            // Done
            if (in.readBoolean()) {
                // Failed
                completeExceptionally(readThrowable(in));
            } else {
                // Success
                complete((T) in.readValue(null));
            }
            mRemoteOrigin = null;
        } else {
            // Not done
            mRemoteOrigin = IAndroidFuture.Stub.asInterface(in.readStrongBinder());
        }
    }

    @NonNull
    private static Handler getMainHandler() {
        // This isn't thread-safe but we are okay with it.
        if (sMainHandler == null) {
            sMainHandler = new Handler(Looper.getMainLooper());
        }
        return sMainHandler;
    }

    /**
     * Create a completed future with the given value.
     *
     * @param value the value for the completed future
     * @param <U> the type of the value
     * @return the completed future
     */
    @NonNull
    public static <U> AndroidFuture<U> completedFuture(U value) {
        AndroidFuture<U> future = new AndroidFuture<>();
        future.complete(value);
        return future;
    }

    @Override
    public boolean complete(@Nullable T value) {
        boolean changed = super.complete(value);
        if (changed) {
            onCompleted(value, null);
        }
        return changed;
    }

    @Override
    public boolean completeExceptionally(@NonNull Throwable ex) {
        boolean changed = super.completeExceptionally(ex);
        if (changed) {
            onCompleted(null, ex);
        }
        return changed;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean changed = super.cancel(mayInterruptIfRunning);
        if (changed) {
            try {
                get();
                throw new IllegalStateException("Expected CancellationException");
            } catch (CancellationException ex) {
                onCompleted(null, ex);
            } catch (Throwable e) {
                throw new IllegalStateException("Expected CancellationException", e);
            }
        }
        return changed;
    }

    @CallSuper
    protected void onCompleted(@Nullable T res, @Nullable Throwable err) {
        cancelTimeout();

        if (DEBUG) {
            Log.i(LOG_TAG, this + " completed with result " + (err == null ? res : err),
                    new RuntimeException());
        }

        BiConsumer<? super T, ? super Throwable> listener;
        synchronized (mLock) {
            listener = mListener;
            mListener = null;
        }

        if (listener != null) {
            callListenerAsync(listener, res, err);
        }

        if (mRemoteOrigin != null) {
            try {
                mRemoteOrigin.complete(this /* resultContainer */);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Failed to propagate completion", e);
            }
        }
    }

    @Override
    public AndroidFuture<T> whenComplete(@NonNull BiConsumer<? super T, ? super Throwable> action) {
        return whenCompleteAsync(action, DIRECT_EXECUTOR);
    }

    @Override
    public AndroidFuture<T> whenCompleteAsync(
            @NonNull BiConsumer<? super T, ? super Throwable> action,
            @NonNull Executor executor) {
        Preconditions.checkNotNull(action);
        Preconditions.checkNotNull(executor);
        synchronized (mLock) {
            if (!isDone()) {
                BiConsumer<? super T, ? super Throwable> oldListener = mListener;

                if (oldListener != null && executor != mListenerExecutor) {
                    // 2 listeners with different executors
                    // Too complex - give up on saving allocations and delegate to superclass
                    super.whenCompleteAsync(action, executor);
                    return this;
                }

                mListenerExecutor = executor;
                mListener = oldListener == null
                        ? action
                        : (res, err) -> {
                            callListener(oldListener, res, err);
                            callListener(action, res, err);
                        };
                return this;
            }
        }

        // isDone() == true at this point
        T res = null;
        Throwable err = null;
        try {
            res = get();
        } catch (ExecutionException e) {
            err = e.getCause();
        } catch (Throwable e) {
            err = e;
        }
        callListenerAsync(action, res, err);
        return this;
    }

    private void callListenerAsync(BiConsumer<? super T, ? super Throwable> listener,
            @Nullable T res, @Nullable Throwable err) {
        if (mListenerExecutor == DIRECT_EXECUTOR) {
            callListener(listener, res, err);
        } else {
            mListenerExecutor.execute(() -> callListener(listener, res, err));
        }
    }

    /**
     * Calls the provided listener, handling any exceptions that may arise.
     */
    // package-private to avoid synthetic method when called from lambda
    static <TT> void callListener(
            @NonNull BiConsumer<? super TT, ? super Throwable> listener,
            @Nullable TT res, @Nullable Throwable err) {
        try {
            try {
                listener.accept(res, err);
            } catch (Throwable t) {
                if (err == null) {
                    // listener happy-case threw, but exception case might not throw, so report the
                    // same exception thrown by listener's happy-path to it again
                    listener.accept(null, t);
                } else {
                    // listener exception-case threw
                    // give up on listener but preserve the original exception when throwing up
                    t.addSuppressed(err);
                    throw t;
                }
            }
        } catch (Throwable t2) {
            // give up on listener and log the result & exception to logcat
            Log.e(LOG_TAG, "Failed to call whenComplete listener. res = " + res, t2);
        }
    }

    /** @inheritDoc */
    //@Override //TODO uncomment once java 9 APIs are exposed to frameworks
    public AndroidFuture<T> orTimeout(long timeout, @NonNull TimeUnit unit) {
        mTimeoutHandler.postDelayed(this::triggerTimeout, this, unit.toMillis(timeout));
        return this;
    }

    void triggerTimeout() {
        cancelTimeout();
        if (!isDone()) {
            completeExceptionally(new TimeoutException());
        }
    }

    /**
     * Cancel all timeouts previously set with {@link #orTimeout}, if any.
     *
     * @return {@code this} for chaining
     */
    public AndroidFuture<T> cancelTimeout() {
        mTimeoutHandler.removeCallbacksAndMessages(this);
        return this;
    }

    /**
     * Specifies the handler on which timeout is to be triggered
     */
    public AndroidFuture<T> setTimeoutHandler(@NonNull Handler h) {
        cancelTimeout();
        mTimeoutHandler = Preconditions.checkNotNull(h);
        return this;
    }

    @Override
    public <U> AndroidFuture<U> thenCompose(
            @NonNull Function<? super T, ? extends CompletionStage<U>> fn) {
        return thenComposeAsync(fn, DIRECT_EXECUTOR);
    }

    @Override
    public <U> AndroidFuture<U> thenComposeAsync(
            @NonNull Function<? super T, ? extends CompletionStage<U>> fn,
            @NonNull Executor executor) {
        return new ThenComposeAsync<>(this, fn, executor);
    }

    private static class ThenComposeAsync<T, U> extends AndroidFuture<U>
            implements BiConsumer<Object, Throwable>, Runnable {
        private volatile T mSourceResult = null;
        private final Executor mExecutor;
        private volatile Function<? super T, ? extends CompletionStage<U>> mFn;

        ThenComposeAsync(@NonNull AndroidFuture<T> source,
                @NonNull Function<? super T, ? extends CompletionStage<U>> fn,
                @NonNull Executor executor) {
            mFn = Preconditions.checkNotNull(fn);
            mExecutor = Preconditions.checkNotNull(executor);

            // subscribe to first job completion
            source.whenComplete(this);
        }

        @Override
        public void accept(Object res, Throwable err) {
            if (err != null) {
                // first or second job failed
                completeExceptionally(err);
            } else if (mFn != null) {
                // first job completed
                mSourceResult = (T) res;
                // subscribe to second job completion asynchronously
                mExecutor.execute(this);
            } else {
                // second job completed
                complete((U) res);
            }
        }

        @Override
        public void run() {
            CompletionStage<U> secondJob;
            try {
                secondJob = Preconditions.checkNotNull(mFn.apply(mSourceResult));
            } catch (Throwable t) {
                completeExceptionally(t);
                return;
            } finally {
                // Marks first job complete
                mFn = null;
            }
            // subscribe to second job completion
            secondJob.whenComplete(this);
        }
    }

    @Override
    public <U> AndroidFuture<U> thenApply(@NonNull Function<? super T, ? extends U> fn) {
        return thenApplyAsync(fn, DIRECT_EXECUTOR);
    }

    @Override
    public <U> AndroidFuture<U> thenApplyAsync(@NonNull Function<? super T, ? extends U> fn,
            @NonNull Executor executor) {
        return new ThenApplyAsync<>(this, fn, executor);
    }

    private static class ThenApplyAsync<T, U> extends AndroidFuture<U>
            implements BiConsumer<T, Throwable>, Runnable {
        private volatile T mSourceResult = null;
        private final Executor mExecutor;
        private final Function<? super T, ? extends U> mFn;

        ThenApplyAsync(@NonNull AndroidFuture<T> source,
                @NonNull Function<? super T, ? extends U> fn,
                @NonNull Executor executor) {
            mExecutor = Preconditions.checkNotNull(executor);
            mFn = Preconditions.checkNotNull(fn);

            // subscribe to job completion
            source.whenComplete(this);
        }

        @Override
        public void accept(T res, Throwable err) {
            if (err != null) {
                completeExceptionally(err);
            } else {
                mSourceResult = res;
                mExecutor.execute(this);
            }
        }

        @Override
        public void run() {
            try {
                complete(mFn.apply(mSourceResult));
            } catch (Throwable t) {
                completeExceptionally(t);
            }
        }
    }

    @Override
    public <U, V> AndroidFuture<V> thenCombine(
            @NonNull CompletionStage<? extends U> other,
            @NonNull BiFunction<? super T, ? super U, ? extends V> combineResults) {
        return new ThenCombine<T, U, V>(this, other, combineResults);
    }

    /** @see CompletionStage#thenCombine */
    public AndroidFuture<T> thenCombine(@NonNull CompletionStage<Void> other) {
        return thenCombine(other, (res, aVoid) -> res);
    }

    private static class ThenCombine<T, U, V> extends AndroidFuture<V>
            implements BiConsumer<Object, Throwable> {
        private volatile @Nullable T mResultT = null;
        private volatile @NonNull CompletionStage<? extends U> mSourceU;
        private final @NonNull BiFunction<? super T, ? super U, ? extends V> mCombineResults;

        ThenCombine(CompletableFuture<T> sourceT,
                CompletionStage<? extends U> sourceU,
                BiFunction<? super T, ? super U, ? extends V> combineResults) {
            mSourceU = Preconditions.checkNotNull(sourceU);
            mCombineResults = Preconditions.checkNotNull(combineResults);

            sourceT.whenComplete(this);
        }

        @Override
        public void accept(Object res, Throwable err) {
            if (err != null) {
                completeExceptionally(err);
                return;
            }

            if (mSourceU != null) {
                // T done
                mResultT = (T) res;

                // Subscribe to the second job completion.
                mSourceU.whenComplete((r, e) -> {
                    // Mark the first job completion by setting mSourceU to null, so that next time
                    // the execution flow goes to the else case below.
                    mSourceU = null;
                    accept(r, e);
                });
            } else {
                // U done
                try {
                    complete(mCombineResults.apply(mResultT, (U) res));
                } catch (Throwable t) {
                    completeExceptionally(t);
                }
            }
        }
    }

    /**
     * Similar to {@link CompletableFuture#supplyAsync} but
     * runs the given action directly.
     *
     * The resulting future is immediately completed.
     */
    public static <T> AndroidFuture<T> supply(Supplier<T> supplier) {
        return supplyAsync(supplier, DIRECT_EXECUTOR);
    }

    /**
     * @see CompletableFuture#supplyAsync(Supplier, Executor)
     */
    public static <T> AndroidFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
        return new SupplyAsync<>(supplier, executor);
    }

    private static class SupplyAsync<T> extends AndroidFuture<T> implements Runnable {
        private final @NonNull Supplier<T> mSupplier;

        SupplyAsync(Supplier<T> supplier, Executor executor) {
            mSupplier = supplier;
            executor.execute(this);
        }

        @Override
        public void run() {
            try {
                complete(mSupplier.get());
            } catch (Throwable t) {
                completeExceptionally(t);
            }
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        boolean done = isDone();
        dest.writeBoolean(done);
        if (done) {
            T result;
            try {
                result = get();
            } catch (Throwable t) {
                dest.writeBoolean(true);
                writeThrowable(dest, unwrapExecutionException(t));
                return;
            }
            dest.writeBoolean(false);
            dest.writeValue(result);
        } else {
            dest.writeStrongBinder(new IAndroidFuture.Stub() {
                @Override
                public void complete(AndroidFuture resultContainer) {
                    boolean changed;
                    try {
                        changed = AndroidFuture.this.complete((T) resultContainer.get());
                    } catch (Throwable t) {
                        changed = completeExceptionally(unwrapExecutionException(t));
                    }
                    if (!changed) {
                        Log.w(LOG_TAG, "Remote result " + resultContainer
                                + " ignored, as local future is already completed: "
                                + AndroidFuture.this);
                    }
                }
            }.asBinder());
        }
    }

    /**
     * Exceptions coming out of {@link #get} are wrapped in {@link ExecutionException}
     */
    Throwable unwrapExecutionException(Throwable t) {
        return t instanceof ExecutionException
                ? t.getCause()
                : t;
    }

    /**
     * Alternative to {@link Parcel#writeException} that stores the stack trace, in a
     * way consistent with the binder IPC exception propagation behavior.
     */
    private static void writeThrowable(@NonNull Parcel parcel, @Nullable Throwable throwable) {
        boolean hasThrowable = throwable != null;
        parcel.writeBoolean(hasThrowable);
        if (!hasThrowable) {
            return;
        }

        boolean isFrameworkParcelable = throwable instanceof Parcelable
                && throwable.getClass().getClassLoader() == Parcelable.class.getClassLoader();
        parcel.writeBoolean(isFrameworkParcelable);
        if (isFrameworkParcelable) {
            parcel.writeParcelable((Parcelable) throwable,
                    Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            return;
        }

        parcel.writeString(throwable.getClass().getName());
        parcel.writeString(throwable.getMessage());
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        StringBuilder stackTraceBuilder = new StringBuilder();
        int truncatedStackTraceLength = Math.min(stackTrace != null ? stackTrace.length : 0, 5);
        for (int i = 0; i < truncatedStackTraceLength; i++) {
            if (i > 0) {
                stackTraceBuilder.append('\n');
            }
            stackTraceBuilder.append("\tat ").append(stackTrace[i]);
        }
        parcel.writeString(stackTraceBuilder.toString());
        writeThrowable(parcel, throwable.getCause());
    }

    /**
     * @see #writeThrowable
     */
    @SuppressWarnings("UnsafeParcelApi")
    private static @Nullable Throwable readThrowable(@NonNull Parcel parcel) {
        final boolean hasThrowable = parcel.readBoolean();
        if (!hasThrowable) {
            return null;
        }

        boolean isFrameworkParcelable = parcel.readBoolean();
        if (isFrameworkParcelable) {
            return parcel.readParcelable(Parcelable.class.getClassLoader());
        }

        String className = parcel.readString();
        String message = parcel.readString();
        String stackTrace = parcel.readString();
        String messageWithStackTrace = message + '\n' + stackTrace;
        Throwable throwable;
        try {
            Class<?> clazz = Class.forName(className, true, Parcelable.class.getClassLoader());
            if (Throwable.class.isAssignableFrom(clazz)) {
                Constructor<?> constructor = clazz.getConstructor(String.class);
                throwable = (Throwable) constructor.newInstance(messageWithStackTrace);
            } else {
                android.util.EventLog.writeEvent(0x534e4554, "186530450", -1, "");
                throwable = new RuntimeException(className + ": " + messageWithStackTrace);
            }
        } catch (Throwable t) {
            throwable = new RuntimeException(className + ": " + messageWithStackTrace);
            throwable.addSuppressed(t);
        }
        throwable.setStackTrace(EMPTY_STACK_TRACE);
        Throwable cause = readThrowable(parcel);
        if (cause != null) {
            throwable.initCause(cause);
        }
        return throwable;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Parcelable.Creator<AndroidFuture> CREATOR =
            new Parcelable.Creator<AndroidFuture>() {
                public AndroidFuture createFromParcel(Parcel parcel) {
                    return new AndroidFuture(parcel);
                }

                public AndroidFuture[] newArray(int size) {
                    return new AndroidFuture[size];
                }
            };
}
