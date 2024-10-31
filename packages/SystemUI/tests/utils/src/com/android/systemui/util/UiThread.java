/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.util;

import android.os.Looper;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * A class to launch runnables on the UI thread explicitly.
 */
public class UiThread {
    private static final String TAG = "UiThread";

    /**
     * Run a runnable on the UI thread using instrumentation.runOnMainSync.
     *
     * @param runnable code to run on the UI thread.
     * @throws Throwable if the code threw an exception, so it can be reported
     * to the test.
     */
    public static void runOnUiThread(final Runnable runnable) throws Throwable {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(
                    TAG,
                    "UiThread.runOnUiThread() should not be called from the "
                        + "main application thread");
            runnable.run();
        } else {
            FutureTask<Void> task = new FutureTask<>(runnable, null);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
            try {
                task.get();
            } catch (ExecutionException e) {
                // Expose the original exception
                throw e.getCause();
            }
        }
    }
}
