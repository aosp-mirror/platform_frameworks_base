/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.telephony.util;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

/**
 * Shared singleton worker thread for each process.
 *
 * This thread should be used for work that needs to be executed at standard priority
 * but not on the main thread. This is suitable for handling asynchronous tasks that
 * are ephemeral or require enough work that they shouldn't block the main thread, but
 * should not block each other for more than around 100ms.
 */
public final class WorkerThread extends HandlerThread {
    private static volatile WorkerThread sInstance;
    private static volatile Handler sHandler;
    private static volatile HandlerExecutor sHandlerExecutor;
    private static final Object sLock = new Object();

    private CountDownLatch mInitLock = new CountDownLatch(1);


    private WorkerThread() {
        super("android.telephony.worker");
    }

    private static void ensureThread() {
        if (sInstance != null) return;
        synchronized (sLock) {
            if (sInstance != null) return;

            final WorkerThread tmpThread = new WorkerThread();
            tmpThread.start();

            try {
                tmpThread.mInitLock.await();
            } catch (InterruptedException ignored) {
            }


            sHandler = new Handler(
                    tmpThread.getLooper(),
                    /* callback= */ null,
                    /* async= */ false,
                    /* shared= */ true);
            sHandlerExecutor = new HandlerExecutor(sHandler);
            sInstance = tmpThread; // Note: order matters here. sInstance must be assigned last.

        }
    }

    @Override
    protected void onLooperPrepared() {
        mInitLock.countDown();
    }

    /**
     * Get the worker thread directly.
     *
     * Users of this thread should take care not to block it for extended periods of
     * time.
     *
     * @return a HandlerThread, never null
     */
    @NonNull public static HandlerThread get() {
        ensureThread();
        return sInstance;
    }

    /**
     * Get a Handler that can process Runnables.
     *
     * @return a Handler, never null
     */
    @NonNull public static Handler getHandler() {
        ensureThread();
        return sHandler;
    }

    /**
     * Get an Executor that can process Runnables
     *
     * @return an Executor, never null
     */
    @NonNull public static Executor getExecutor() {
        ensureThread();
        return sHandlerExecutor;
    }

    /**
     * A method to reset the WorkerThread from scratch.
     *
     * This method should only be used for unit testing. In production it would have
     * catastrophic consequences. Do not ever use this outside of tests.
     */
    @VisibleForTesting
    public static void reset() {
        synchronized (sLock) {
            if (sInstance == null) return;
            sInstance.quitSafely();
            sInstance = null;
            sHandler = null;
            sHandlerExecutor = null;
            ensureThread();
        }
    }
}
