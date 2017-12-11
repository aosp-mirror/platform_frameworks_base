/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;

import android.os.Handler;
import android.os.Trace;

import com.android.server.ServiceThread;

/**
 * Thread for running {@link SurfaceAnimationRunner} that does not hold the window manager lock.
 */
public final class SurfaceAnimationThread extends ServiceThread {
    private static SurfaceAnimationThread sInstance;
    private static Handler sHandler;

    private SurfaceAnimationThread() {
        super("android.anim.lf", THREAD_PRIORITY_DISPLAY, false /*allowIo*/);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new SurfaceAnimationThread();
            sInstance.start();
            sInstance.getLooper().setTraceTag(Trace.TRACE_TAG_WINDOW_MANAGER);
            sHandler = new Handler(sInstance.getLooper());
        }
    }

    public static SurfaceAnimationThread get() {
        synchronized (SurfaceAnimationThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    public static Handler getHandler() {
        synchronized (SurfaceAnimationThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }
}
