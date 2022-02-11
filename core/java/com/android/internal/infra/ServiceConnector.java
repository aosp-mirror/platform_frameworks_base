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

import android.annotation.CheckResult;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Takes care of managing a {@link ServiceConnection} and auto-disconnecting from the service upon
 * a certain timeout.
 *
 * <p>
 * The requests are always processed in the order they are scheduled.
 *
 * <p>
 * Use {@link ServiceConnector.Impl} to construct an instance.
 *
 * @param <I> the type of the {@link IInterface ipc interface} for the remote service
 */
public interface ServiceConnector<I extends IInterface> {

    /**
     * Schedules to run a given job when service is connected, without providing any means to track
     * the job's completion.
     *
     * <p>
     * This is slightly more efficient than {@link #post(VoidJob)} as it doesn't require an extra
     * allocation of a {@link AndroidFuture} for progress tracking.
     *
     * @return whether a job was successfully scheduled
     */
    boolean run(@NonNull VoidJob<I> job);

    /**
     * Schedules to run a given job when service is connected.
     *
     * <p>
     * You can choose to wait for the job synchronously using {@link AndroidFuture#get} or
     * attach a listener to it using one of the options such as
     * {@link AndroidFuture#whenComplete}
     * You can also {@link AndroidFuture#cancel cancel} the pending job.
     *
     * @return a {@link AndroidFuture} tracking the job's completion
     *
     * @see #postForResult(Job) for a variant of this that also propagates an arbitrary result
     *                          back to the caller
     * @see CompletableFuture for more options on what you can do with a result of an asynchronous
     *                        operation, including more advanced operations such as
     *                        {@link CompletableFuture#thenApply transforming} its result,
     *                        {@link CompletableFuture#thenCombine joining}
     *                        results of multiple async operation into one,
     *                        {@link CompletableFuture#thenCompose composing} results of
     *                        multiple async operations that depend on one another, and more.
     */
    @CheckResult(suggest = "#fireAndForget")
    AndroidFuture<Void> post(@NonNull VoidJob<I> job);

    /**
     * Variant of {@link #post(VoidJob)} that also propagates an arbitrary result back to the
     * caller asynchronously.
     *
     * @param <R> the type of the result this job produces
     *
     * @see #post(VoidJob)
     */
    @CheckResult(suggest = "#fireAndForget")
    <R> AndroidFuture<R> postForResult(@NonNull Job<I, R> job);

    /**
     * Schedules a job that is itself asynchronous, that is job returns a result in the form of a
     * {@link CompletableFuture}
     *
     * <p>
     * This takes care of "flattening" the nested futures that would have resulted from 2
     * asynchronous operations performed in sequence.
     *
     * <p>
     * Like with other options, {@link AndroidFuture#cancel cancelling} the resulting future
     * will remove the job from the queue, preventing it from running if it hasn't yet started.
     *
     * @see #postForResult
     * @see #post
     */
    <R> AndroidFuture<R> postAsync(@NonNull Job<I, CompletableFuture<R>> job);

    /**
     * Requests to connect to the service without posting any meaningful job to run.
     *
     * <p>
     * This returns a {@link AndroidFuture} tracking the progress of binding to the service,
     * which can be used to schedule calls to the service once it's connected.
     *
     * <p>
     * Avoid caching the resulting future as the instance may change due to service disconnecting
     * and reconnecting.
     */
    AndroidFuture<I> connect();

    /**
     * Request to unbind from the service as soon as possible.
     *
     * <p>
     * If there are any pending jobs remaining they will be
     * {@link AndroidFuture#cancel cancelled}.
     */
    void unbind();

    /**
     * A request to be run when the service is
     * {@link ServiceConnection#onServiceConnected connected}.
     *
     * @param <II> type of the {@link IInterface ipc interface} to be used
     * @param <R> type of the return value
     *
     * @see VoidJob for a variant that doesn't produce any return value
     */
    @FunctionalInterface
    interface Job<II, R> {

        /**
         * Perform the remote call using the provided {@link IInterface ipc interface instance}.
         *
         * Avoid caching the provided {@code service} instance as it may become invalid when service
         * disconnects.
         *
         * @return the result of this operation to be propagated to the original caller.
         *         If you do not need to provide a result you can implement {@link VoidJob} instead
         */
        R run(@NonNull II service) throws Exception;

    }

    /**
     * Variant of {@link Job} that doesn't return a result
     *
     * @param <II> see {@link Job}
     */
    @FunctionalInterface
    interface VoidJob<II> extends Job<II, Void> {

        /** @see Job#run */
        void runNoResult(II service) throws Exception;

        @Override
        default Void run(II service) throws Exception {
            runNoResult(service);
            return null;
        }
    }


    /**
     * Implementation of {@link ServiceConnector}
     *
     * <p>
     * For allocation-efficiency reasons this implements a bunch of interfaces that are not meant to
     * be a public API of {@link ServiceConnector}.
     * For this reason prefer to use {@link ServiceConnector} instead of
     * {@link ServiceConnector.Impl} as the field type when storing an instance.
     *
     * <p>
     * In some rare cases you may want to extend this class, overriding certain methods for further
     * flexibility.
     * If you do, it would typically be one of the {@code protected} methods on this class.
     *
     * @param <I> see {@link ServiceConnector}
     */
    class Impl<I extends IInterface> extends ArrayDeque<Job<I, ?>>
            implements ServiceConnector<I>, ServiceConnection, IBinder.DeathRecipient, Runnable {

        static final boolean DEBUG = false;
        static final String LOG_TAG = "ServiceConnector.Impl";

        private static final long DEFAULT_DISCONNECT_TIMEOUT_MS = 15_000;
        private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000;

        private final @NonNull Queue<Job<I, ?>> mQueue = this;
        private final @NonNull List<CompletionAwareJob<I, ?>> mUnfinishedJobs = new ArrayList<>();

        private final @NonNull Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final @NonNull ServiceConnection mServiceConnection = this;
        private final @NonNull Runnable mTimeoutDisconnect = this;

        protected final @NonNull Context mContext;
        private final @NonNull Intent mIntent;
        private final int mBindingFlags;
        private final int mUserId;
        private final @Nullable Function<IBinder, I> mBinderAsInterface;

        private volatile I mService = null;
        private boolean mBinding = false;
        private boolean mUnbinding = false;

        private CompletionAwareJob<I, I> mServiceConnectionFutureCache = null;

        /**
         * Creates an instance of {@link ServiceConnector}
         *
         * See {@code protected} methods for optional parameters you can override.
         *
         * @param context to be used for {@link Context#bindServiceAsUser binding} and
         *                {@link Context#unbindService unbinding}
         * @param intent to be used for {@link Context#bindServiceAsUser binding}
         * @param bindingFlags to be used for {@link Context#bindServiceAsUser binding}
         * @param userId to be used for {@link Context#bindServiceAsUser binding}
         * @param binderAsInterface to be used for converting an {@link IBinder} provided in
         *                          {@link ServiceConnection#onServiceConnected} into a specific
         *                          {@link IInterface}.
         *                          Typically this is {@code IMyInterface.Stub::asInterface}
         */
        public Impl(@NonNull Context context, @NonNull Intent intent, int bindingFlags,
                @UserIdInt int userId, @Nullable Function<IBinder, I> binderAsInterface) {
            mContext = context;
            mIntent = intent;
            mBindingFlags = bindingFlags;
            mUserId = userId;
            mBinderAsInterface = binderAsInterface;
        }

        /**
         * {@link Handler} on which {@link Job}s will be called
         */
        protected Handler getJobHandler() {
            return mMainHandler;
        }

        /**
         * Gets the amount of time spent without any calls before the service is automatically
         * {@link Context#unbindService unbound}
         *
         * @return amount of time in ms, or non-positive (<=0) value to disable automatic unbinding
         */
        protected long getAutoDisconnectTimeoutMs() {
            return DEFAULT_DISCONNECT_TIMEOUT_MS;
        }

        /**
         * Gets the amount of time to wait for a request to complete, before finishing it with a
         * {@link java.util.concurrent.TimeoutException}
         *
         * <p>
         * This includes time spent connecting to the service, if any.
         *
         * @return amount of time in ms
         */
        protected long getRequestTimeoutMs() {
            return DEFAULT_REQUEST_TIMEOUT_MS;
        }

        /**
         * {@link Context#bindServiceAsUser Binds} to the service.
         *
         * <p>
         * If overridden, implementation must use at least the provided {@link ServiceConnection}
         */
        protected boolean bindService(
                @NonNull ServiceConnection serviceConnection, @NonNull Handler handler) {
            if (DEBUG) {
                logTrace();
            }
            return mContext.bindServiceAsUser(mIntent, serviceConnection,
                    Context.BIND_AUTO_CREATE | mBindingFlags,
                    handler, UserHandle.of(mUserId));
        }

        /**
         * Gets the binder interface.
         * Typically {@code IMyInterface.Stub.asInterface(service)}.
         *
         * <p>
         * Can be overridden instead of provided as a constructor parameter to save a singleton
         * allocation
         */
        protected I binderAsInterface(@NonNull IBinder service) {
            return mBinderAsInterface.apply(service);
        }

        /**
         * Called when service was {@link Context#unbindService unbound}
         *
         * <p>
         * Can be overridden to perform some cleanup on service disconnect
         */
        protected void onServiceUnbound() {
            if (DEBUG) {
                logTrace();
            }
        }

        /**
         * Called when the service just connected or is about to disconnect
         */
        protected void onServiceConnectionStatusChanged(@NonNull I service, boolean isConnected) {}

        @Override
        public boolean run(@NonNull VoidJob<I> job) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Wrapping fireAndForget job to take advantage of its mDebugName");
                return !post(job).isCompletedExceptionally();
            }
            return enqueue(job);
        }

        @Override
        public AndroidFuture<Void> post(@NonNull VoidJob<I> job) {
            return postForResult((Job) job);
        }

        @Override
        public <R> CompletionAwareJob<I, R> postForResult(@NonNull Job<I, R> job) {
            CompletionAwareJob<I, R> task = new CompletionAwareJob<>();
            task.mDelegate = Objects.requireNonNull(job);
            enqueue(task);
            return task;
        }

        @Override
        public <R> AndroidFuture<R> postAsync(@NonNull Job<I, CompletableFuture<R>> job) {
            CompletionAwareJob<I, R> task = new CompletionAwareJob<>();
            task.mDelegate = Objects.requireNonNull((Job) job);
            task.mAsync = true;
            enqueue(task);
            return task;
        }

        @Override
        public synchronized AndroidFuture<I> connect() {
            if (mServiceConnectionFutureCache == null) {
                mServiceConnectionFutureCache = new CompletionAwareJob<>();
                mServiceConnectionFutureCache.mDelegate = s -> s;
                I service = mService;
                if (service != null) {
                    mServiceConnectionFutureCache.complete(service);
                } else {
                    enqueue(mServiceConnectionFutureCache);
                }
            }
            return mServiceConnectionFutureCache;
        }

        private void enqueue(@NonNull CompletionAwareJob<I, ?> task) {
            if (!enqueue((Job<I, ?>) task)) {
                task.completeExceptionally(new IllegalStateException(
                        "Failed to post a job to handler. Likely "
                                + getJobHandler().getLooper() + " is exiting"));
            }
        }

        private boolean enqueue(@NonNull Job<I, ?> job) {
            cancelTimeout();
            return getJobHandler().post(() -> enqueueJobThread(job));
        }

        void enqueueJobThread(@NonNull Job<I, ?> job) {
            if (DEBUG) {
                Log.i(LOG_TAG, "post(" + job + ", this = " + this + ")");
            }
            cancelTimeout();
            if (mUnbinding) {
                completeExceptionally(job,
                        new IllegalStateException("Service is unbinding. Ignoring " + job));
            } else if (!mQueue.offer(job)) {
                completeExceptionally(job,
                        new IllegalStateException("Failed to add to queue: " + job));
            } else if (isBound()) {
                processQueue();
            } else if (!mBinding) {
                if (bindService(mServiceConnection, getJobHandler())) {
                    mBinding = true;
                } else {
                    completeExceptionally(job,
                            new IllegalStateException("Failed to bind to service " + mIntent));
                }
            }
        }

        private void cancelTimeout() {
            if (DEBUG) {
                logTrace();
            }
            mMainHandler.removeCallbacks(mTimeoutDisconnect);
        }

        void completeExceptionally(@NonNull Job<?, ?> job, @NonNull Throwable ex) {
            CompletionAwareJob task = castOrNull(job, CompletionAwareJob.class);
            boolean taskChanged = false;
            if (task != null) {
                taskChanged = task.completeExceptionally(ex);
            }
            if (task == null || (DEBUG && taskChanged)) {
                Log.e(LOG_TAG, "Job failed: " + job, ex);
            }
        }

        static @Nullable <BASE, T extends BASE> T castOrNull(
                @Nullable BASE instance, @NonNull Class<T> cls) {
            return cls.isInstance(instance) ? (T) instance : null;
        }

        private void processQueue() {
            if (DEBUG) {
                logTrace();
            }

            Job<I, ?> job;
            while ((job = mQueue.poll()) != null) {
                CompletionAwareJob task = castOrNull(job, CompletionAwareJob.class);
                try {
                    I service = mService;
                    if (service == null) {
                        return;
                    }
                    Object result = job.run(service);
                    if (DEBUG) {
                        Log.i(LOG_TAG, "complete(" + job + ", result = " + result + ")");
                    }
                    if (task != null) {
                        if (task.mAsync) {
                            mUnfinishedJobs.add(task);
                            ((CompletionStage) result).whenComplete(task);
                        } else {
                            task.complete(result);
                        }
                    }
                } catch (Throwable e) {
                    completeExceptionally(job, e);
                }
            }

            maybeScheduleUnbindTimeout();
        }

        private void maybeScheduleUnbindTimeout() {
            if (mUnfinishedJobs.isEmpty() && mQueue.isEmpty()) {
                scheduleUnbindTimeout();
            }
        }

        private void scheduleUnbindTimeout() {
            if (DEBUG) {
                logTrace();
            }
            long timeout = getAutoDisconnectTimeoutMs();
            if (timeout > 0) {
                mMainHandler.postDelayed(mTimeoutDisconnect, timeout);
            } else if (DEBUG) {
                Log.i(LOG_TAG, "Not scheduling unbind for permanently bound " + this);
            }
        }

        private boolean isBound() {
            return mService != null;
        }

        @Override
        public void unbind() {
            if (DEBUG) {
                logTrace();
            }
            mUnbinding = true;
            getJobHandler().post(this::unbindJobThread);
        }

        void unbindJobThread() {
            cancelTimeout();
            I service = mService;
            boolean wasBound = service != null;
            if (wasBound) {
                onServiceConnectionStatusChanged(service, false);
                mContext.unbindService(mServiceConnection);
                service.asBinder().unlinkToDeath(this, 0);
                mService = null;
            }
            mBinding = false;
            mUnbinding = false;
            synchronized (this) {
                if (mServiceConnectionFutureCache != null) {
                    mServiceConnectionFutureCache.cancel(true);
                    mServiceConnectionFutureCache = null;
                }
            }

            cancelPendingJobs();

            if (wasBound) {
                onServiceUnbound();
            }
        }

        protected void cancelPendingJobs() {
            Job<I, ?> job;
            while ((job = mQueue.poll()) != null) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "cancel(" + job + ")");
                }
                CompletionAwareJob task = castOrNull(job, CompletionAwareJob.class);
                if (task != null) {
                    task.cancel(/* mayInterruptWhileRunning= */ false);
                }
            }
        }

        @Override
        public void onServiceConnected(@NonNull ComponentName name, @NonNull IBinder binder) {
            if (mUnbinding) {
                Log.i(LOG_TAG, "Ignoring onServiceConnected due to ongoing unbinding: " + this);
                return;
            }
            if (DEBUG) {
                logTrace();
            }
            I service = binderAsInterface(binder);
            mService = service;
            mBinding = false;
            try {
                binder.linkToDeath(ServiceConnector.Impl.this, 0);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "onServiceConnected " + name + ": ", e);
            }
            onServiceConnectionStatusChanged(service, true);
            processQueue();
        }

        @Override
        public void onServiceDisconnected(@NonNull ComponentName name) {
            if (DEBUG) {
                logTrace();
            }
            mBinding = true;
            I service = mService;
            if (service != null) {
                onServiceConnectionStatusChanged(service, false);
                mService = null;
            }
        }

        @Override
        public void onBindingDied(@NonNull ComponentName name) {
            if (DEBUG) {
                logTrace();
            }
            binderDied();
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                logTrace();
            }
            mService = null;
            unbind();
        }

        @Override
        public void run() {
            onTimeout();
        }

        private void onTimeout() {
            if (DEBUG) {
                logTrace();
            }
            unbind();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ServiceConnector@")
                    .append(System.identityHashCode(this) % 1000).append("(")
                    .append(mIntent).append(", user: ").append(mUserId)
                    .append(")[").append(stateToString());
            if (!mQueue.isEmpty()) {
                sb.append(", ").append(mQueue.size()).append(" pending job(s)");
                if (DEBUG) {
                    sb.append(": ").append(super.toString());
                }
            }
            if (!mUnfinishedJobs.isEmpty()) {
                sb.append(", ").append(mUnfinishedJobs.size()).append(" unfinished async job(s)");
            }
            return sb.append("]").toString();
        }

        public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
            String tab = "  ";
            pw.append(prefix).append("ServiceConnector:").println();
            pw.append(prefix).append(tab).append(String.valueOf(mIntent)).println();
            pw.append(prefix).append(tab)
                    .append("userId: ").append(String.valueOf(mUserId)).println();
            pw.append(prefix).append(tab)
                    .append("State: ").append(stateToString()).println();
            pw.append(prefix).append(tab)
                    .append("Pending jobs: ").append(String.valueOf(mQueue.size())).println();
            if (DEBUG) {
                for (Job<I, ?> pendingJob : mQueue) {
                    pw.append(prefix).append(tab).append(tab)
                            .append(String.valueOf(pendingJob)).println();
                }
            }
            pw.append(prefix).append(tab)
                    .append("Unfinished async jobs: ")
                    .append(String.valueOf(mUnfinishedJobs.size())).println();
        }

        private String stateToString() {
            if (mBinding) {
                return "Binding...";
            } else if (mUnbinding) {
                return "Unbinding...";
            } else if (isBound()) {
                return "Bound";
            } else {
                return "Unbound";
            }
        }

        private void logTrace() {
            Log.i(LOG_TAG, "See stacktrace", new Throwable());
        }

        /**
         * {@link Job} + {@link AndroidFuture}
         */
        class CompletionAwareJob<II, R> extends AndroidFuture<R>
                implements Job<II, R>, BiConsumer<R, Throwable> {
            Job<II, R> mDelegate;
            boolean mAsync = false;
            private String mDebugName;
            {
                long requestTimeout = getRequestTimeoutMs();
                if (requestTimeout > 0) {
                    orTimeout(requestTimeout, TimeUnit.MILLISECONDS);
                }

                if (DEBUG) {
                    mDebugName = Arrays.stream(Thread.currentThread().getStackTrace())
                            .skip(2)
                            .filter(st ->
                                    !st.getClassName().contains(ServiceConnector.class.getName()))
                            .findFirst()
                            .get()
                            .getMethodName();
                }
            }

            @Override
            public R run(@NonNull II service) throws Exception {
                return mDelegate.run(service);
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (mayInterruptIfRunning) {
                    Log.w(LOG_TAG, "mayInterruptIfRunning not supported - ignoring");
                }
                boolean wasRemoved = mQueue.remove(this);
                return super.cancel(mayInterruptIfRunning) || wasRemoved;
            }

            @Override
            public String toString() {
                if (DEBUG) {
                    return mDebugName;
                }
                return mDelegate + " wrapped into " + super.toString();
            }

            @Override
            public void accept(@Nullable R res, @Nullable Throwable err) {
                if (err != null) {
                    completeExceptionally(err);
                } else {
                    complete(res);
                }
            }

            @Override
            protected void onCompleted(R res, Throwable err) {
                super.onCompleted(res, err);
                if (mUnfinishedJobs.remove(this)) {
                    maybeScheduleUnbindTimeout();
                }
            }
        }
    }
}
