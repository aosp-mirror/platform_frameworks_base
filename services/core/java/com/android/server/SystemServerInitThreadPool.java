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

import android.annotation.NonNull;
import android.os.Build;
import android.os.Process;
import android.util.Dumpable;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.Preconditions;
import com.android.server.am.StackTracesDumpHelper;
import com.android.server.utils.TimingsTraceAndSlog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Thread pool used during initialization of system server.
 *
 * <p>System services can {@link #submit(Runnable, String)} tasks for execution during boot.
 * The pool will be shut down after {@link SystemService#PHASE_BOOT_COMPLETED}.
 *
 * <p>New tasks <em>should not</em> be submitted afterwards.
 *
 * @hide
 */
public final class SystemServerInitThreadPool implements Dumpable {
    private static final String TAG = SystemServerInitThreadPool.class.getSimpleName();
    private static final int SHUTDOWN_TIMEOUT_MILLIS = 20000;
    private static final boolean IS_DEBUGGABLE = Build.IS_DEBUGGABLE;
    private static final Object LOCK = new Object();

    @GuardedBy("LOCK")
    private static SystemServerInitThreadPool sInstance;

    private final int mSize; // used by dump() only
    private final ExecutorService mService;

    @GuardedBy("mPendingTasks")
    private final List<String> mPendingTasks = new ArrayList<>();

    @GuardedBy("mPendingTasks")
    private boolean mShutDown;

    private SystemServerInitThreadPool() {
        mSize = Runtime.getRuntime().availableProcessors();
        Slog.i(TAG, "Creating instance with " + mSize + " threads");
        mService = ConcurrentUtils.newFixedThreadPool(mSize,
                "system-server-init-thread", Process.THREAD_PRIORITY_FOREGROUND);
    }

    /**
     * Submits a task for execution.
     *
     * @throws IllegalStateException if it hasn't been started or has been shut down already.
     */
    public static @NonNull Future<?> submit(@NonNull Runnable runnable,
            @NonNull String description) {
        Objects.requireNonNull(description, "description cannot be null");

        SystemServerInitThreadPool instance;
        synchronized (LOCK) {
            Preconditions.checkState(sInstance != null, "Cannot get " + TAG
                    + " - it has been shut down");
            instance = sInstance;
        }

        return instance.submitTask(runnable, description);
    }

    private @NonNull Future<?> submitTask(@NonNull Runnable runnable,
            @NonNull String description) {
        synchronized (mPendingTasks) {
            Preconditions.checkState(!mShutDown, TAG + " already shut down");
            mPendingTasks.add(description);
        }
        return mService.submit(() -> {
            TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
            traceLog.traceBegin("InitThreadPoolExec:" + description);
            if (IS_DEBUGGABLE) {
                Slog.d(TAG, "Started executing " + description);
            }
            try {
                runnable.run();
            } catch (RuntimeException e) {
                Slog.e(TAG, "Failure in " + description + ": " + e, e);
                traceLog.traceEnd();
                throw e;
            }
            synchronized (mPendingTasks) {
                mPendingTasks.remove(description);
            }
            if (IS_DEBUGGABLE) {
                Slog.d(TAG, "Finished executing " + description);
            }
            traceLog.traceEnd();
        });
    }

    /**
     * Starts it.
     *
     * <p>Note:</p> should only be called by {@link SystemServer}.
     *
     * @throws IllegalStateException if it has been started already without being shut down yet.
     */
    static SystemServerInitThreadPool start() {
        SystemServerInitThreadPool instance;
        synchronized (LOCK) {
            Preconditions.checkState(sInstance == null, TAG + " already started");
            instance = sInstance = new SystemServerInitThreadPool();
        }
        return instance;
    }

    /**
     * Shuts it down.
     *
     * <p>Note:</p> should only be called *after* {@code PHASE_BOOT_COMPLETED} is sent to the
     * {@link SystemService system services}.
     */
    static void shutdown() {
        Slog.d(TAG, "Shutdown requested");
        synchronized (LOCK) {
            TimingsTraceAndSlog t = new TimingsTraceAndSlog();
            t.traceBegin("WaitInitThreadPoolShutdown");
            if (sInstance == null) {
                t.traceEnd();
                Slog.wtf(TAG, "Already shutdown", new Exception());
                return;
            }
            synchronized (sInstance.mPendingTasks) {
                sInstance.mShutDown = true;
            }
            sInstance.mService.shutdown();
            final boolean terminated;
            try {
                terminated = sInstance.mService.awaitTermination(SHUTDOWN_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dumpStackTraces();
                t.traceEnd();
                throw new IllegalStateException(TAG + " init interrupted");
            }
            if (!terminated) {
                // dump stack must be called before shutdownNow() to collect stacktrace of threads
                // in the thread pool.
                dumpStackTraces();
            }
            final List<Runnable> unstartedRunnables = sInstance.mService.shutdownNow();
            if (!terminated) {
                final List<String> copy = new ArrayList<>();
                synchronized (sInstance.mPendingTasks) {
                    copy.addAll(sInstance.mPendingTasks);
                }
                t.traceEnd();
                throw new IllegalStateException("Cannot shutdown. Unstarted tasks "
                        + unstartedRunnables + " Unfinished tasks " + copy);
            }
            sInstance = null; // Make eligible for GC
            Slog.d(TAG, "Shutdown successful");
            t.traceEnd();
        }
    }

    /**
     * A helper function to call StackTracesDumpHelper.dumpStackTraces().
     */
    private static void dumpStackTraces() {
        final ArrayList<Integer> pids = new ArrayList<>();
        pids.add(Process.myPid());
        StackTracesDumpHelper.dumpStackTraces(pids,
                /* processCpuTracker= */null, /* lastPids= */null,
                CompletableFuture.completedFuture(Watchdog.getInterestingNativePids()),
                /* logExceptionCreatingFile= */null, /* subject= */null,
                /* criticalEventSection= */null, /* extraHeaders= */ null,
                Runnable::run, /* latencyTracker= */null);
    }

    @Override
    public String getDumpableName() {
        return SystemServerInitThreadPool.class.getSimpleName();
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        synchronized (LOCK) {
            pw.printf("has instance: %b\n", (sInstance != null));
        }
        pw.printf("number of threads: %d\n", mSize);
        pw.printf("service: %s\n", mService);
        synchronized (mPendingTasks) {
            pw.printf("is shutdown: %b\n", mShutDown);
            final int pendingTasks = mPendingTasks.size();
            if (pendingTasks == 0) {
                pw.println("no pending tasks");
            } else {
                pw.printf("%d pending tasks: %s\n", pendingTasks, mPendingTasks);
            }
        }
    }
}
