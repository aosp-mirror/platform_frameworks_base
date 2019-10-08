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

package com.android.server;

import android.os.Build;
import android.os.Process;
import android.util.Slog;

import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.Preconditions;
import com.android.server.am.ActivityManagerService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Thread pool used during initialization of system server.
 * <p>System services can {@link #submit(Runnable)} tasks for execution during boot.
 * The pool will be shut down after {@link SystemService#PHASE_BOOT_COMPLETED}.
 * New tasks <em>should not</em> be submitted afterwards.
 *
 * @hide
 */
public class SystemServerInitThreadPool {
    private static final String TAG = SystemServerInitThreadPool.class.getSimpleName();
    private static final int SHUTDOWN_TIMEOUT_MILLIS = 20000;
    private static final boolean IS_DEBUGGABLE = Build.IS_DEBUGGABLE;

    private static SystemServerInitThreadPool sInstance;

    private ExecutorService mService = ConcurrentUtils.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            "system-server-init-thread", Process.THREAD_PRIORITY_FOREGROUND);

    private List<String> mPendingTasks = new ArrayList<>();

    public static synchronized SystemServerInitThreadPool get() {
        if (sInstance == null) {
            sInstance = new SystemServerInitThreadPool();
        }
        Preconditions.checkState(sInstance.mService != null, "Cannot get " + TAG
                + " - it has been shut down");
        return sInstance;
    }

    public Future<?> submit(Runnable runnable, String description) {
        synchronized (mPendingTasks) {
            mPendingTasks.add(description);
        }
        return mService.submit(() -> {
            if (IS_DEBUGGABLE) {
                Slog.d(TAG, "Started executing " + description);
            }
            try {
                runnable.run();
            } catch (RuntimeException e) {
                Slog.e(TAG, "Failure in " + description + ": " + e, e);
                throw e;
            }
            synchronized (mPendingTasks) {
                mPendingTasks.remove(description);
            }
            if (IS_DEBUGGABLE) {
                Slog.d(TAG, "Finished executing " + description);
            }
        });
    }

    static synchronized void shutdown() {
        if (sInstance != null && sInstance.mService != null) {
            sInstance.mService.shutdown();
            boolean terminated;
            try {
                terminated = sInstance.mService.awaitTermination(SHUTDOWN_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dumpStackTraces();
                throw new IllegalStateException(TAG + " init interrupted");
            }
            if (!terminated) {
                // dump stack must be called before shutdownNow() to collect stacktrace of threads
                // in the thread pool.
                dumpStackTraces();
            }
            List<Runnable> unstartedRunnables = sInstance.mService.shutdownNow();
            if (!terminated) {
                final List<String> copy = new ArrayList<>();
                synchronized (sInstance.mPendingTasks) {
                    copy.addAll(sInstance.mPendingTasks);
                }
                throw new IllegalStateException("Cannot shutdown. Unstarted tasks "
                        + unstartedRunnables + " Unfinished tasks " + copy);
            }
            sInstance.mService = null; // Make mService eligible for GC
            sInstance.mPendingTasks = null;
            Slog.d(TAG, "Shutdown successful");
        }
    }

    /**
     * A helper function to call ActivityManagerService.dumpStackTraces().
     */
    private static void dumpStackTraces() {
        final ArrayList<Integer> pids = new ArrayList<>();
        pids.add(Process.myPid());
        ActivityManagerService.dumpStackTraces(pids, null, null,
                Watchdog.getInterestingNativePids());
    }
}
