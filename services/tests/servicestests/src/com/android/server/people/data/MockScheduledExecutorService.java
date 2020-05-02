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

package com.android.server.people.data;

import com.android.internal.util.Preconditions;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Mock implementation of ScheduledExecutorService for testing. All commands will run
 * synchronously. Commands passed to {@link #submit(Runnable)} and {@link #execute(Runnable)} will
 * run immediately. Commands scheduled via {@link #schedule(Runnable, long, TimeUnit)} will run
 * after calling {@link #fastForwardTime(long)}.
 */
class MockScheduledExecutorService implements ScheduledExecutorService {

    private final List<Runnable> mExecutes = new ArrayList<>();
    private final List<MockScheduledFuture<?>> mFutures = new ArrayList<>();
    private long mTimeElapsedMillis = 0;

    /**
     * Advances fake time, runs all the commands for which the delay has expired.
     */
    long fastForwardTime(long millis) {
        mTimeElapsedMillis += millis;
        ImmutableList<MockScheduledFuture<?>> futuresCopy = ImmutableList.copyOf(mFutures);
        mFutures.clear();
        long totalExecuted = 0;
        for (MockScheduledFuture<?> future : futuresCopy) {
            if (future.getDelay() < mTimeElapsedMillis) {
                future.getRunnable().run();
                mExecutes.add(future.getRunnable());
                totalExecuted += 1;
            } else {
                mFutures.add(future);
            }
        }
        return totalExecuted;
    }

    List<Runnable> getExecutes() {
        return mExecutes;
    }

    List<MockScheduledFuture<?>> getFutures() {
        return mFutures;
    }

    void resetTimeElapsedMillis() {
        mTimeElapsedMillis = 0;
    }

    /**
     * Fakes a schedule execution of {@link Runnable}. The command will be executed by an explicit
     * call to {@link #fastForwardTime(long)}.
     */
    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Preconditions.checkState(unit == TimeUnit.MILLISECONDS);
        MockScheduledFuture<?> future = new MockScheduledFuture<>(command, delay, unit);
        mFutures.add(future);
        return future;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
            TimeUnit unit) {
        return new MockScheduledFuture<>(command, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
            long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Runnable> shutdownNow() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        MockScheduledFuture<T> future = new MockScheduledFuture<>(task, 0, TimeUnit.MILLISECONDS);
        try {
            future.getCallable().call();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return future;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<?> submit(Runnable runnable) {
        mExecutes.add(runnable);
        MockScheduledFuture<?> future = new MockScheduledFuture<>(runnable, 0,
                TimeUnit.MILLISECONDS);
        future.getRunnable().run();
        return future;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout,
            TimeUnit unit) throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws ExecutionException, InterruptedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws ExecutionException, InterruptedException, TimeoutException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
        mExecutes.add(command);
        command.run();
    }

    class MockScheduledFuture<V> implements ScheduledFuture<V> {

        private final Runnable mRunnable;
        private final Callable<V> mCallable;
        private final long mDelay;
        private boolean mCancelled = false;

        MockScheduledFuture(Runnable runnable, long delay, TimeUnit timeUnit) {
            this(runnable, null, delay);
        }

        MockScheduledFuture(Callable<V> callable, long delay, TimeUnit timeUnit) {
            this(null, callable, delay);
        }

        private MockScheduledFuture(Runnable runnable, Callable<V> callable, long delay) {
            mCallable = callable;
            mRunnable = runnable;
            mDelay = delay;
        }

        public long getDelay() {
            return mDelay;
        }

        public Runnable getRunnable() {
            return mRunnable;
        }

        public Callable<V> getCallable() {
            return mCallable;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int compareTo(Delayed o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            mCancelled = true;
            return mFutures.remove(this);
        }

        @Override
        public boolean isCancelled() {
            return mCancelled;
        }

        @Override
        public boolean isDone() {
            return !mFutures.contains(this);
        }

        @Override
        public V get() throws ExecutionException, InterruptedException {
            return null;
        }

        @Override
        public V get(long timeout, TimeUnit unit)
                throws ExecutionException, InterruptedException, TimeoutException {
            return null;
        }
    }
}
