/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.util;

import android.os.Looper;

import androidx.annotation.VisibleForTesting;

/**
 * Helper providing common assertions.
 */
public class Assert {
    private static final Looper sMainLooper = Looper.getMainLooper();
    private static Thread sTestThread = null;

    @VisibleForTesting
    public static void setTestableLooper(Looper testLooper) {
        setTestThread(testLooper == null ? null : testLooper.getThread());
    }

    @VisibleForTesting
    public static void setTestThread(Thread thread) {
        sTestThread = thread;
    }

    public static void isMainThread() {
        if (!sMainLooper.isCurrentThread()
                && (sTestThread == null || sTestThread != Thread.currentThread())) {
            throw new IllegalStateException("should be called from the main thread."
                    + " sMainLooper.threadName=" + sMainLooper.getThread().getName()
                    + " Thread.currentThread()=" + Thread.currentThread().getName());
        }
    }

    public static void isNotMainThread() {
        if (sMainLooper.isCurrentThread()
                && (sTestThread == null || sTestThread == Thread.currentThread())) {
            throw new IllegalStateException("should not be called from the main thread.");
        }
    }
}
