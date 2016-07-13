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
 * limitations under the License.
 */
package com.android.server.pm;

import android.annotation.NonNull;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Handler;

/**
 * Used by {@link ShortcutService} to register tasks to be executed on Handler and also wait for
 * all pending tasks.
 *
 * Tasks can be registered with {@link #addTask(Runnable)}.  Call {@link #waitOnAllTasks()} to wait
 * on all tasks that have been registered.
 *
 * In order to avoid deadlocks, {@link #waitOnAllTasks} MUST NOT be called with any lock held, nor
 * on the handler thread.  These conditions are checked by {@link #mWaitThreadChecker} and wtf'ed.
 *
 * During unit tests, we can't run tasks asynchronously, so we just run Runnables synchronously,
 * which also means the "is lock held" check doesn't work properly during unit tests (e.g. normally
 * when a Runnable is executed on a Handler, the thread doesn't hold any lock, but during the tests
 * we just run a Runnable on the thread that registers it, so the thread may or may not hold locks.)
 * So unfortunately we have to disable {@link #mWaitThreadChecker} during unit tests.
 *
 * Because of the complications like those, this class should be used only for specific purposes:
 * - {@link #addTask(Runnable)} should only be used to register tasks on callbacks from lower level
 * services like the package manager or the activity manager.
 *
 * - {@link #waitOnAllTasks} should only be called at the entry point of RPC calls (or the test only
 * accessors}.
 */
public class ShortcutPendingTasks {
    private static final String TAG = "ShortcutPendingTasks";

    private static final boolean DEBUG = false || ShortcutService.DEBUG; // DO NOT SUBMIT WITH TRUE.

    private final Consumer<Runnable> mRunner;

    private final BooleanSupplier mWaitThreadChecker;

    private final Consumer<Throwable> mExceptionHandler;

    /** # of tasks in the queue, including the running one. */
    private final AtomicInteger mRunningTaskCount = new AtomicInteger();

    /** For dumpsys */
    private final AtomicLong mLastTaskStartTime = new AtomicLong();

    /**
     * Constructor.  In order to allow injection during unit tests, it doesn't take a
     * {@link Handler} directly, and instead takes {@code runner} which will post an argument
     * to a handler.
     */
    public ShortcutPendingTasks(Consumer<Runnable> runner, BooleanSupplier waitThreadChecker,
            Consumer<Throwable> exceptionHandler) {
        mRunner = runner;
        mWaitThreadChecker = waitThreadChecker;
        mExceptionHandler = exceptionHandler;
    }

    private static void dlog(String message) {
        if (DEBUG) {
            Slog.d(TAG, message);
        }
    }

    /**
     * Block until all tasks that are already queued finish.  DO NOT call it while holding any lock
     * or on the handler thread.
     */
    public boolean waitOnAllTasks() {
        dlog("waitOnAllTasks: enter");
        try {
            // Make sure it's not holding the lock.
            if (!mWaitThreadChecker.getAsBoolean()) {
                return false;
            }

            // Optimize for the no-task case.
            if (mRunningTaskCount.get() == 0) {
                return true;
            }

            final CountDownLatch latch = new CountDownLatch(1);

            addTask(latch::countDown);

            for (; ; ) {
                try {
                    if (latch.await(1, TimeUnit.SECONDS)) {
                        return true;
                    }
                    dlog("waitOnAllTasks: Task(s) still running...");
                } catch (InterruptedException ignore) {
                }
            }
        } finally {
            dlog("waitOnAllTasks: exit");
        }
    }

    /**
     * Add a new task.  This operation is lock-free.
     */
    public void addTask(Runnable task) {
        mRunningTaskCount.incrementAndGet();
        mLastTaskStartTime.set(System.currentTimeMillis());

        dlog("Task registered");

        mRunner.accept(() -> {
            try {
                dlog("Task started");

                task.run();
            } catch (Throwable th) {
                mExceptionHandler.accept(th);
            } finally {
                dlog("Task finished");
                mRunningTaskCount.decrementAndGet();
            }
        });
    }

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        pw.print(prefix);
        pw.print("Pending tasks:  # running tasks: ");
        pw.println(mRunningTaskCount.get());

        pw.print(prefix);
        pw.print("  Last task started time: ");
        final long lastStarted = mLastTaskStartTime.get();
        pw.print(" [");
        pw.print(lastStarted);
        pw.print("] ");
        pw.println(ShortcutService.formatTime(lastStarted));
    }
}
