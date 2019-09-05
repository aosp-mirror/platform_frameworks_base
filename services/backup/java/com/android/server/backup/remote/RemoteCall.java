/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.remote;

import android.annotation.WorkerThread;
import android.app.backup.IBackupCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A wrapper that encapsulates an outbound call from the system process, converting an asynchronous
 * operation into a synchronous operation with time-out and cancellation built-in. This was built to
 * be able to call one-way binder methods that accept a {@link IBackupCallback} as a callback and
 * handle the result inline.
 *
 * <p>Create one {@link RemoteCall} object providing the actual call in the form of a {@link
 * RemoteCallable} that accepts a {@link IBackupCallback}. Perform the call by calling {@link
 * #call()}, at which point {@link RemoteCall} will execute the callable providing an implementation
 * of the callback that communicates the result back to this object. Even if the call returns
 * straight away (which is the case for one-way methods) the method will only return when either the
 * callback is called, time-out happens, or someone calls {@link #cancel()}.
 *
 * <p>This class was designed to have the method {@link #call()} called only once.
 */
// TODO: Kick-off callable in dedicated thread (because of local calls, which are synchronous)
public class RemoteCall {
    /**
     * Creates a {@link RemoteCall} object with {@code callable} and {@code timeoutMs} and calls
     * {@link #call()} on it immediately after.
     *
     * <p>Note that you won't be able to cancel the call, to do that construct an object regularly
     * first, then use {@link #call()}.
     *
     * @see #RemoteCall(RemoteCallable, long)
     * @see #call()
     */
    public static RemoteResult execute(RemoteCallable<IBackupCallback> callable, long timeoutMs)
            throws RemoteException {
        return new RemoteCall(callable, timeoutMs).call();
    }

    private final RemoteCallable<IBackupCallback> mCallable;
    private final CompletableFuture<RemoteResult> mFuture;
    private final long mTimeoutMs;

    /**
     * Creates a new {@link RemoteCall} object for a given callable.
     *
     * @param callable A function that signals its completion by calling {@link
     *     IBackupCallback#operationComplete(long)} on the object provided as a parameter.
     * @param timeoutMs The time in milliseconds after which {@link #call()} will return with {@link
     *     RemoteResult#FAILED_TIMED_OUT} if the callable hasn't completed and no one canceled. The
     *     time starts to be counted in {@link #call()}.
     */
    public RemoteCall(RemoteCallable<IBackupCallback> callable, long timeoutMs) {
        this(false, callable, timeoutMs);
    }

    /**
     * Same as {@link #RemoteCall(RemoteCallable, long)} but with parameter {@code cancelled}.
     *
     * @param cancelled Whether the call has already been canceled. It has the same effect of
     *     calling {@link #cancel()} before {@link #call()}.
     * @see #RemoteCall(RemoteCallable, long)
     */
    public RemoteCall(boolean cancelled, RemoteCallable<IBackupCallback> callable, long timeoutMs) {
        mCallable = callable;
        mTimeoutMs = timeoutMs;
        mFuture = new CompletableFuture<>();
        if (cancelled) {
            cancel();
        }
    }

    /**
     * Kicks-off the callable provided in the constructor and blocks before returning, waiting for
     * the first of these to happen:
     *
     * <ul>
     *   <li>The callback passed to {@link RemoteCallable} is called with the result. We return a
     *       present {@link RemoteResult} with the result.
     *   <li>Time-out happens. We return {@link RemoteResult#FAILED_TIMED_OUT}.
     *   <li>Someone calls {@link #cancel()} on this object. We return {@link
     *       RemoteResult#FAILED_CANCELLED}.
     * </ul>
     *
     * <p>This method can't be called from the main thread and was designed to be called only once.
     *
     * @return A {@link RemoteResult} with the result of the operation.
     * @throws RemoteException If the callable throws it.
     */
    @WorkerThread
    public RemoteResult call() throws RemoteException {
        // If called on the main-thread we would never get a time-out != 0
        Preconditions.checkState(
                !Looper.getMainLooper().isCurrentThread(), "Can't call call() on main thread");

        if (!mFuture.isDone()) {
            if (mTimeoutMs == 0L) {
                timeOut();
            } else {
                Handler.getMain().postDelayed(this::timeOut, mTimeoutMs);
                mCallable.call(new FutureBackupCallback(mFuture));
            }
        }
        try {
            return mFuture.get();
        } catch (InterruptedException e) {
            return RemoteResult.FAILED_THREAD_INTERRUPTED;
        } catch (ExecutionException e) {
            throw new IllegalStateException("Future unexpectedly completed with an exception");
        }
    }

    /**
     * Attempts to cancel the operation. It will only be successful if executed before the callback
     * is called and before the time-out.
     *
     * <p>This method can be called from any thread, any time, including the same thread that called
     * {@link #call()} (which is obviously only possible if the former is called before the latter).
     */
    public void cancel() {
        mFuture.complete(RemoteResult.FAILED_CANCELLED);
    }

    private void timeOut() {
        mFuture.complete(RemoteResult.FAILED_TIMED_OUT);
    }
}
