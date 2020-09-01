/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * Similar to {@link com.android.internal.os.BackgroundThread}, this is a shared singleton
 * foreground thread for each process for updating PIP.
 */
public final class PipUpdateThread extends HandlerThread {
    private static PipUpdateThread sInstance;
    private static Handler sHandler;

    private PipUpdateThread() {
        super("pip");
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new PipUpdateThread();
            sInstance.start();
            sHandler = new Handler(sInstance.getLooper());
        }
    }

    /**
     * @return the static update thread instance
     */
    public static PipUpdateThread get() {
        synchronized (PipUpdateThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }
    /**
     * @return the static update thread handler instance
     */
    public static Handler getHandler() {
        synchronized (PipUpdateThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }
}
