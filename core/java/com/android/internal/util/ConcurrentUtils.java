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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility methods for common functionality using java.util.concurrent package
 *
 * @hide
 */
public class ConcurrentUtils {

    private ConcurrentUtils() {
    }

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

}
