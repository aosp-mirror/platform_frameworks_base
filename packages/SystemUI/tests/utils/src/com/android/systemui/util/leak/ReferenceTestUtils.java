/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.util.leak;

import android.os.SystemClock;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * Utilities for writing tests that manipulate weak or other references.
 */
public class ReferenceTestUtils {

    /** Returns a runnable that blocks until {@code o} has been collected. */
    public static CollectionWaiter createCollectionWaiter(Object o) {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        Reference<?> ref = new WeakReference<>(o, q);
        o = null; // Ensure this variable can't be referenced from the lambda.

        return () -> {
            Runtime.getRuntime().gc();
            while (true) {
                try {
                    if (q.remove(5_000) == ref) {
                        return;
                    } else {
                        throw new RuntimeException("timeout while waiting for object collection");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    public static void waitForCondition(Condition p) {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        while (!p.apply()) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw new RuntimeException("timeout while waiting for condition");
            }
            SystemClock.sleep(100);
        }
    }

    public interface Condition {
        boolean apply();
    }

    public interface CollectionWaiter {
        void waitForCollection();
    }
}
