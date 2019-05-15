/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.util;

import android.os.Process;
import android.util.Slog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility methods for common functionality using java.util.concurrent package
 *
 * @hide
 */
public class ConcurrentUtils {

    private ConcurrentUtils() {
    }

    public static final Executor DIRECT_EXECUTOR = new DirectExecutor();

    /**
     * Creates a thread pool using
     * {@link java.util.concurrent.Executors#newFixedThreadPool(int, ThreadFactory)}
     *
     * @param nThreads the number of threads in the pool
     * @param poolName base name of the threads in the pool
     * @param linuxThreadPriority a Linux priority level. see {@link Process#setThreadPriority(int)}
     * @return the newly created thread pool
     */
    public static ExecutorService newFixedThreadPool(int nThreads, String poolName,
            int linuxThreadPriority) {
        return Executors.newFixedThreadPool(nThreads,
                new ThreadFactory() {
                    private final AtomicInteger threadNum = new AtomicInteger(0);

                    @Override
                    public Thread newThread(final Runnable r) {
                        return new Thread(poolName + threadNum.incrementAndGet()) {
                            @Override
                            public void run() {
                                Process.setThreadPriority(linuxThreadPriority);
                                r.run();
                            }
                        };
                    }
                });
    }

    /**
     * Waits if necessary for the computation to complete, and then retrieves its result.
     * <p>If {@code InterruptedException} occurs, this method will interrupt the current thread
     * and throw {@code IllegalStateException}</p>
     *
     * @param future future to wait for result
     * @param description short description of the operation
     * @return the computed result
     * @throws IllegalStateException if interrupted during wait
     * @throws RuntimeException if an error occurs while waiting for {@link Future#get()}
     * @see Future#get()
     */
    public static <T> T waitForFutureNoInterrupt(Future<T> future, String description) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description + " interrupted");
        } catch (ExecutionException e) {
            throw new RuntimeException(description + " failed", e);
        }
    }

    /**
     * Waits for {@link CountDownLatch#countDown()} to be called on the {@param countDownLatch}.
     * <p>If {@link CountDownLatch#countDown()} doesn't occur within {@param timeoutMs}, this
     * method will throw {@code IllegalStateException}
     * <p>If {@code InterruptedException} occurs, this method will interrupt the current thread
     * and throw {@code IllegalStateException}
     *
     * @param countDownLatch the CountDownLatch which {@link CountDownLatch#countDown()} is
     *                       being waited on.
     * @param timeoutMs the maximum time waited for {@link CountDownLatch#countDown()}
     * @param description a short description of the operation
     */
    public static void waitForCountDownNoInterrupt(CountDownLatch countDownLatch, long timeoutMs,
            String description) {
        try {
            if (!countDownLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(description + " timed out.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description + " interrupted.");
        }
    }

    /**
     * Calls {@link Slog#wtf} if a given lock is held.
     */
    public static void wtfIfLockHeld(String tag, Object lock) {
        if (Thread.holdsLock(lock)) {
            Slog.wtf(tag, "Lock mustn't be held");
        }
    }

    /**
     * Calls {@link Slog#wtf} if a given lock is not held.
     */
    public static void wtfIfLockNotHeld(String tag, Object lock) {
        if (!Thread.holdsLock(lock)) {
            Slog.wtf(tag, "Lock must be held");
        }
    }

    private static class DirectExecutor implements Executor {

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public String toString() {
            return "DIRECT_EXECUTOR";
        }
    }
}
