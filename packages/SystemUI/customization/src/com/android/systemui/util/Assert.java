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

    /**
     * Run {@code mainThreadWork} synchronously, ensuring that {@link #isMainThread()} will return
     * {@code true} while it is running.
     * <ol>
     * <li>If {@link #isMainThread()} already passes, the work is simply run.
     * <li>If the test thread is {@code null}, it will be set, the work run, and then cleared.
     * <li>If the test thread is already set to a different thread, this call will fail the test to
     * avoid causing spurious errors on other thread
     * </ol>
     */
    @VisibleForTesting
    public static void runWithCurrentThreadAsMainThread(Runnable mainThreadWork) {
        if (sMainLooper.isCurrentThread()) {
            // Already on the main thread; just run
            mainThreadWork.run();
            return;
        }
        Thread currentThread = Thread.currentThread();
        Thread originalThread = sTestThread;
        if (originalThread == currentThread) {
            // test thread is already set; just run
            mainThreadWork.run();
            return;
        }
        if (originalThread != null) {
            throw new AssertionError("Can't run with current thread (" + currentThread
                    + ") as main thread; test thread is already set to " + originalThread);
        }
        sTestThread = currentThread;
        mainThreadWork.run();
        sTestThread = null;
    }

    public static void isMainThread() {
        if (!sMainLooper.isCurrentThread()
                && (sTestThread == null || sTestThread != Thread.currentThread())) {
            throw new IllegalStateException("should be called from the main thread."
                    + " sMainLooper.threadName=" + sMainLooper.getThread().getName()
                    + " Thread.currentThread()=" + Thread.currentThread().getName());
        }
    }

    /**
     * Asserts that the current thread is the same as the given thread, or that the current thread
     * is the test thread.
     * @param expected The looper we expected to be running on
     */
    public static void isCurrentThread(Looper expected) {
        if (!expected.isCurrentThread()
                && (sTestThread == null || sTestThread != Thread.currentThread())) {
            throw new IllegalStateException("Called on wrong thread thread."
                    + " wanted " + expected.getThread().getName()
                    + " but instead got Thread.currentThread()="
                    + Thread.currentThread().getName());
        }
    }

    public static void isNotMainThread() {
        if (sMainLooper.isCurrentThread()
                && (sTestThread == null || sTestThread == Thread.currentThread())) {
            throw new IllegalStateException("should not be called from the main thread.");
        }
    }
}
