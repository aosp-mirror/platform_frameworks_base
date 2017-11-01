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
import android.os.Looper;
import android.os.Process;
import android.os.Trace;

/**
 * Shared singleton thread for showing UI.  This is a foreground thread, and in
 * additional should not have operations that can take more than a few ms scheduled
 * on it to avoid UI jank.
 */
public final class UiThread extends ServiceThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 100;
    private static UiThread sInstance;
    private static Handler sHandler;

    private UiThread() {
        super("android.ui", Process.THREAD_PRIORITY_FOREGROUND, false /*allowIo*/);
    }

    @Override
    public void run() {
        // Make sure UiThread is in the fg stune boost group
        Process.setThreadGroup(Process.myTid(), Process.THREAD_GROUP_TOP_APP);
        super.run();
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new UiThread();
            sInstance.start();
            final Looper looper = sInstance.getLooper();
            looper.setTraceTag(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            looper.setSlowDispatchThresholdMs(SLOW_DISPATCH_THRESHOLD_MS);
            sHandler = new Handler(sInstance.getLooper());
        }
    }

    public static UiThread get() {
        synchronized (UiThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    public static Handler getHandler() {
        synchronized (UiThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }
}
