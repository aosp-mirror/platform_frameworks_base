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

package android.view;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;

/**
 * Thread to be used for inset animations to be running off the main thread.
 * @hide
 */
public class InsetsAnimationThread extends HandlerThread {

    private static InsetsAnimationThread sInstance;
    private static Handler sHandler;

    private InsetsAnimationThread() {
        // TODO: Should this use higher priority?
        super("InsetsAnimations");
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new InsetsAnimationThread();
            sInstance.start();
            sInstance.getLooper().setTraceTag(Trace.TRACE_TAG_VIEW);
            sHandler = new Handler(sInstance.getLooper());
        }
    }

    public static void release() {
        synchronized (InsetsAnimationThread.class) {
            if (sInstance == null) {
                return;
            }
            sInstance.getLooper().quitSafely();
            sInstance = null;
            sHandler = null;
        }
    }

    public static InsetsAnimationThread get() {
        synchronized (InsetsAnimationThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    public static Handler getHandler() {
        synchronized (InsetsAnimationThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }
}
