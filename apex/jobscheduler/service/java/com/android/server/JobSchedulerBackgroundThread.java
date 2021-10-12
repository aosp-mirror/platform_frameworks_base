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

package com.android.server;

import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton background thread.
 *
 * @see com.android.internal.os.BackgroundThread
 */
public final class JobSchedulerBackgroundThread extends HandlerThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 10_000;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 30_000;
    private static JobSchedulerBackgroundThread sInstance;
    private static Handler sHandler;
    private static Executor sHandlerExecutor;

    private JobSchedulerBackgroundThread() {
        super("jobscheduler.bg", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new JobSchedulerBackgroundThread();
            sInstance.start();
            final Looper looper = sInstance.getLooper();
            looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            looper.setSlowLogThresholdMs(
                    SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
            sHandler = new Handler(sInstance.getLooper());
            sHandlerExecutor = new HandlerExecutor(sHandler);
        }
    }

    /** Returns the JobSchedulerBackgroundThread singleton */
    public static JobSchedulerBackgroundThread get() {
        synchronized (JobSchedulerBackgroundThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    /** Returns the singleton handler for JobSchedulerBackgroundThread */
    public static Handler getHandler() {
        synchronized (JobSchedulerBackgroundThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }

    /** Returns the singleton handler executor for JobSchedulerBackgroundThread */
    public static Executor getExecutor() {
        synchronized (JobSchedulerBackgroundThread.class) {
            ensureThreadLocked();
            return sHandlerExecutor;
        }
    }
}
