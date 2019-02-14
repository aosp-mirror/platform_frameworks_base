/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.os.Looper;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton foreground thread for the system.  This is a thread for regular
 * foreground service operations, which shouldn't be blocked by anything running in
 * the background.  In particular, the shared background thread could be doing
 * relatively long-running operations like saving state to disk (in addition to
 * simply being a background priority), which can cause operations scheduled on it
 * to be delayed for a user-noticeable amount of time.
 */
public final class FgThread extends ServiceThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 100;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 200;

    private static FgThread sInstance;
    private static Handler sHandler;
    private static HandlerExecutor sHandlerExecutor;

    private FgThread() {
        super("android.fg", android.os.Process.THREAD_PRIORITY_DEFAULT, true /*allowIo*/);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new FgThread();
            sInstance.start();
            final Looper looper = sInstance.getLooper();
            looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            looper.setSlowLogThresholdMs(
                    SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
            sHandler = new Handler(sInstance.getLooper());
            sHandlerExecutor = new HandlerExecutor(sHandler);
        }
    }

    public static FgThread get() {
        synchronized (FgThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    public static Handler getHandler() {
        synchronized (FgThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }

    public static Executor getExecutor() {
        synchronized (FgThread.class) {
            ensureThreadLocked();
            return sHandlerExecutor;
        }
    }
}
