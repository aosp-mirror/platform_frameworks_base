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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Message;
import android.util.ExceptionUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A customized {@link CompletableFuture} with focus on reducing the number of allocations involved
 * in a typical future usage scenario for Android.
 *
 * In particular this involves allocations optimizations in:
 * <ul>
 *     <li>{@link #thenCompose(Function)}</li>
 *     <li>{@link #orTimeout(long, TimeUnit)}</li>
 *     <li>{@link #whenComplete(BiConsumer)}</li>
 * </ul>
 *
 * @param <T> see {@link CompletableFuture}
 */
public class AndroidFuture<T> extends CompletableFuture<T> {

    private static final String LOG_TAG = AndroidFuture.class.getSimpleName();

    @GuardedBy("this")
    private @Nullable BiConsumer<? super T, ? super Throwable> mListener;
    private @NonNull Handler mTimeoutHandler = Handler.getMain();

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
        return super.completeExceptionally(ex);
    }

    private void onCompleted(@Nullable T res, @Nullable Throwable err) {
        cancelTimeout();

        BiConsumer<? super T, ? super Throwable> listener;
        synchronized (this) {
            listener = mListener;
            mListener = null;
        }

        if (listener != null) {
            callListener(listener, res, err);
        }
    }

    @Override
    public AndroidFuture<T> whenComplete(
            @NonNull BiConsumer<? super T, ? super Throwable> action) {
        Preconditions.checkNotNull(action);
        synchronized (this) {
            if (!isDone()) {
                BiConsumer<? super T, ? super Throwable> oldListener = mListener;
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
        callListener(action, res, err);
        return this;
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
                    ExceptionUtils.getRootCause(t).initCause(err);
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
        Message msg = PooledLambda.obtainMessage(AndroidFuture::triggerTimeout, this);
        msg.obj = this;
        mTimeoutHandler.sendMessageDelayed(msg, unit.toMillis(timeout));
        return this;
    }

    void triggerTimeout() {
        cancelTimeout();
        if (!isDone()) {
            completeExceptionally(new TimeoutException());
        }
    }

    protected void cancelTimeout() {
        mTimeoutHandler.removeCallbacksAndMessages(this);
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
        return (AndroidFuture<U>) new ThenCompose<>(this, fn);
    }

    private static class ThenCompose<T, U> extends AndroidFuture<Object>
            implements BiConsumer<Object, Throwable> {
        private final AndroidFuture<T> mSource;
        private Function<? super T, ? extends CompletionStage<U>> mFn;

        ThenCompose(@NonNull AndroidFuture<T> source,
                @NonNull Function<? super T, ? extends CompletionStage<U>> fn) {
            mSource = source;
            mFn = Preconditions.checkNotNull(fn);
            // subscribe to first job completion
            source.whenComplete(this);
        }

        @Override
        public void accept(Object res, Throwable err) {
            Function<? super T, ? extends CompletionStage<U>> fn;
            synchronized (this) {
                fn = mFn;
                mFn = null;
            }
            if (fn != null) {
                // first job completed
                CompletionStage<U> secondJob;
                try {
                    secondJob = Preconditions.checkNotNull(fn.apply((T) res));
                } catch (Throwable t) {
                    completeExceptionally(t);
                    return;
                }
                // subscribe to second job completion
                secondJob.whenComplete(this);
            } else {
                // second job completed
                if (err != null) {
                    completeExceptionally(err);
                } else {
                    complete(res);
                }
            }
        }
    }
}
