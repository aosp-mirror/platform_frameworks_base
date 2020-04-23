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

package com.android.systemui.onehanded;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * Similar to {@link com.android.internal.os.BackgroundThread}, this is a shared singleton
 * foreground thread for each process for updating one handed.
 */
public class OneHandedThread extends HandlerThread {
    private static OneHandedThread sInstance;
    private static Handler sHandler;

    private OneHandedThread() {
        super("OneHanded");
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new OneHandedThread();
            sInstance.start();
            sHandler = new Handler(sInstance.getLooper());
        }
    }

    /**
     * @return the static update thread instance
     */
    public static OneHandedThread get() {
        synchronized (OneHandedThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    /**
     * @return the static update thread handler instance
     */
    public static Handler getHandler() {
        synchronized (OneHandedThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }

}
