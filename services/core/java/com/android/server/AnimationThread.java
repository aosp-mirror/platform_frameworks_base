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

package com.android.server;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;

import android.os.Handler;
import android.os.Trace;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Thread for handling all legacy window animations, or anything that's directly impacting
 * animations like starting windows or traversals.
 */
public final class AnimationThread extends ServiceThread {
    private static AnimationThread sInstance;
    private static Handler sHandler;

    private AnimationThread() {
        super("android.anim", THREAD_PRIORITY_DISPLAY, false /*allowIo*/);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new AnimationThread();
            sInstance.start();
            sInstance.getLooper().setTraceTag(Trace.TRACE_TAG_WINDOW_MANAGER);
            sHandler = makeSharedHandler(sInstance.getLooper());
        }
    }

    public static AnimationThread get() {
        synchronized (AnimationThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    public static Handler getHandler() {
        synchronized (AnimationThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }

    /**
     * Disposes current animation thread if it's initialized. Should only be used in tests to set up
     * a new environment.
     */
    @VisibleForTesting
    public static void dispose() {
        synchronized (AnimationThread.class) {
            if (sInstance == null) {
                return;
            }

            getHandler().runWithScissors(() -> sInstance.quit(), 0 /* timeout */);
            sInstance = null;
        }
    }
}
