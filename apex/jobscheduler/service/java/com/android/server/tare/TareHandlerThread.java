/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Trace;

/**
 * Singleton thread for all of TARE.
 *
 * @see com.android.internal.os.BackgroundThread
 */
final class TareHandlerThread extends HandlerThread {

    private static TareHandlerThread sInstance;
    private static Handler sHandler;

    private TareHandlerThread() {
        super("tare");
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new TareHandlerThread();
            sInstance.start();
            final Looper looper = sInstance.getLooper();
            looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            sHandler = new Handler(sInstance.getLooper());
        }
    }

    static TareHandlerThread get() {
        synchronized (TareHandlerThread.class) {
            ensureThreadLocked();
        }
        return sInstance;
    }

    /** Returns the singleton handler for TareHandlerThread. */
    public static Handler getHandler() {
        synchronized (TareHandlerThread.class) {
            ensureThreadLocked();
        }
        return sHandler;
    }
}
