/*
 *  * Copyright (C) 2024 The Android Open Source Project
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

package android.utils;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.HandlerThread;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;

/**
 * Thread for asynchronous event processing. This thread is configured as
 * {@link android.os.Process#THREAD_PRIORITY_BACKGROUND}, which means fewer CPU
 * resources will be dedicated to it, and it will "have less chance of impacting
 * the responsiveness of the user interface."
 * <p>
 * This thread is best suited for tasks that the user is not actively waiting
 * for, or for tasks that the user expects to be executed eventually.
 *
 * @see com.android.internal.os.BackgroundThread
 *
 * TODO: b/326916057 depend on modules-utils-backgroundthread instead
 * @hide
 */
public final class BackgroundThread extends HandlerThread {
    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static BackgroundThread sInstance;
    @GuardedBy("sLock")
    private static Handler sHandler;
    @GuardedBy("sLock")
    private static HandlerExecutor sHandlerExecutor;

    private BackgroundThread() {
        super(BackgroundThread.class.getName(), android.os.Process.THREAD_PRIORITY_BACKGROUND);
    }

    @GuardedBy("sLock")
    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new BackgroundThread();
            sInstance.start();
            sHandler = new Handler(sInstance.getLooper());
            sHandlerExecutor = new HandlerExecutor(sHandler);
        }
    }

    /**
     * Get the singleton instance of this class.
     *
     * @return the singleton instance of this class
     */
    @NonNull
    public static BackgroundThread get() {
        synchronized (sLock) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    /**
     * Get the singleton {@link Handler} for this class.
     *
     * @return the singleton {@link Handler} for this class.
     */
    @NonNull
    public static Handler getHandler() {
        synchronized (sLock) {
            ensureThreadLocked();
            return sHandler;
        }
    }

    /**
     * Get the singleton {@link Executor} for this class.
     *
     * @return the singleton {@link Executor} for this class.
     */
    @NonNull
    public static Executor getExecutor() {
        synchronized (sLock) {
            ensureThreadLocked();
            return sHandlerExecutor;
        }
    }
}
