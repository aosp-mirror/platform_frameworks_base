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
import android.os.Process;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton default priority thread for the app scheduling module.
 *
 * @see com.android.internal.os.BackgroundThread
 */
public final class AppSchedulingModuleThread extends HandlerThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 10_000;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 30_000;
    private static AppSchedulingModuleThread sInstance;
    private static Handler sHandler;
    private static Executor sHandlerExecutor;

    private AppSchedulingModuleThread() {
        super("appscheduling.default", Process.THREAD_PRIORITY_DEFAULT);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new AppSchedulingModuleThread();
            sInstance.start();
            final Looper looper = sInstance.getLooper();
            looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            looper.setSlowLogThresholdMs(
                    SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
            sHandler = new Handler(sInstance.getLooper());
            sHandlerExecutor = new HandlerExecutor(sHandler);
        }
    }

    /** Returns the AppSchedulingModuleThread singleton */
    public static AppSchedulingModuleThread get() {
        synchronized (AppSchedulingModuleThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    /** Returns the singleton handler for AppSchedulingModuleThread */
    public static Handler getHandler() {
        synchronized (AppSchedulingModuleThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }

    /** Returns the singleton handler executor for AppSchedulingModuleThread */
    public static Executor getExecutor() {
        synchronized (AppSchedulingModuleThread.class) {
            ensureThreadLocked();
            return sHandlerExecutor;
        }
    }
}
