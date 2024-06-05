/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.usage;

import android.os.Looper;
import android.os.Trace;

import com.android.internal.annotations.GuardedBy;
import com.android.server.ServiceThread;

/**
 * Shared singleton default priority thread for usage stats message handling.
 */
public class UsageStatsHandlerThread extends ServiceThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 10_000;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 30_000;

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static UsageStatsHandlerThread sInstance;

    private UsageStatsHandlerThread() {
        super("android.usagestats", android.os.Process.THREAD_PRIORITY_DEFAULT,
                /* allowIo= */ true);
    }

    @GuardedBy("sLock")
    private static void ensureThreadLocked() {
        if (sInstance != null) {
            return;
        }

        sInstance = new UsageStatsHandlerThread();
        sInstance.start();
        final Looper looper = sInstance.getLooper();
        looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
        looper.setSlowLogThresholdMs(
                SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
    }

    /**
     * Obtain a singleton instance of the UsageStatsHandlerThread.
     */
    public static UsageStatsHandlerThread get() {
        synchronized (sLock) {
            ensureThreadLocked();
            return sInstance;
        }
    }
}
