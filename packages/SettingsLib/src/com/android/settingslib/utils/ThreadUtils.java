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
 * limitations under the License.
 */
package com.android.settingslib.utils;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class ThreadUtils {

    private static volatile Thread sMainThread;
    private static volatile Handler sMainThreadHandler;
    private static volatile ListeningExecutorService sListeningService;

    /**
     * Returns true if the current thread is the UI thread.
     */
    public static boolean isMainThread() {
        if (sMainThread == null) {
            sMainThread = Looper.getMainLooper().getThread();
        }
        return Thread.currentThread() == sMainThread;
    }

    /**
     * Returns a shared UI thread handler.
     */
    @NonNull
    public static Handler getUiThreadHandler() {
        if (sMainThreadHandler == null) {
            sMainThreadHandler = new Handler(Looper.getMainLooper());
        }

        return sMainThreadHandler;
    }

    /**
     * Checks that the current thread is the UI thread. Otherwise throws an exception.
     */
    public static void ensureMainThread() {
        if (!isMainThread()) {
            throw new RuntimeException("Must be called on the UI thread");
        }
    }

    /**
     * Posts runnable in background using shared background thread pool.
     *
     * @return A future of the task that can be monitored for updates or cancelled.
     */
    @SuppressWarnings("rawtypes")
    @NonNull
    public static ListenableFuture postOnBackgroundThread(@NonNull Runnable runnable) {
        return getBackgroundExecutor().submit(runnable);
    }

    /**
     * Posts callable in background using shared background thread pool.
     *
     * @return A future of the task that can be monitored for updates or cancelled.
     */
    @NonNull
    public static <T> ListenableFuture<T> postOnBackgroundThread(@NonNull Callable<T> callable) {
        return getBackgroundExecutor().submit(callable);
    }

    /**
     * Posts the runnable on the main thread.
     *
     * @deprecated moving work to the main thread should be done via the main executor provided to
     * {@link com.google.common.util.concurrent.FutureCallback} via
     * {@link android.content.Context#getMainExecutor()} or by calling an SDK method such as
     * {@link android.app.Activity#runOnUiThread(Runnable)} or
     * {@link android.content.Context#getMainThreadHandler()} where appropriate.
     */
    @Deprecated
    public static void postOnMainThread(@NonNull Runnable runnable) {
        getUiThreadHandler().post(runnable);
    }

    /**
     * Provides a shared {@link ListeningExecutorService} created using a fixed thread pool executor
     */
    @NonNull
    public static synchronized ListeningExecutorService getBackgroundExecutor() {
        if (sListeningService == null) {
            sListeningService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors()));
        }
        return sListeningService;
    }
}
