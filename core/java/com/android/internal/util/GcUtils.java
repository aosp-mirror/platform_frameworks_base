/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.util;

import android.util.Slog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A helper class to handle gc'ing a process, mainly used for testing.
 *
 * @hide
 */
public final class GcUtils {
    private static final String TAG = GcUtils.class.getSimpleName();

    /**
     * Runs a GC and attempts to wait for finalization.
     */
    public static void runGcAndFinalizersSync() {
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();

        final CountDownLatch fence = new CountDownLatch(1);
        createFinalizationObserver(fence);
        try {
            do {
                Runtime.getRuntime().gc();
                Runtime.getRuntime().runFinalization();
            } while (!fence.await(100, TimeUnit.MILLISECONDS));
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
        Slog.v(TAG, "Running gc and finalizers");
    }

    /**
     * Create the observer in the scope of a method to minimize the chance that
     * it remains live in a DEX/machine register at the point of the fence guard.
     * This must be kept to avoid R8 inlining it.
     */
    private static void createFinalizationObserver(CountDownLatch fence) {
        new Object() {
            @Override
            protected void finalize() throws Throwable {
                try {
                    fence.countDown();
                } finally {
                    super.finalize();
                }
            }
        };
    }

    // Uninstantiable
    private GcUtils() {}
}
