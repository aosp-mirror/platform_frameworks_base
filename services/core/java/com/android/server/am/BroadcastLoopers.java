/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;

/**
 * Collection of {@link Looper} that are known to be used for broadcast dispatch
 * within the system. This collection can be useful for callers interested in
 * confirming that all pending broadcasts have been successfully enqueued.
 */
public class BroadcastLoopers {
    private static final String TAG = "BroadcastLoopers";

    @GuardedBy("sLoopers")
    private static final ArraySet<Looper> sLoopers = new ArraySet<>();

    /**
     * Register the given {@link Looper} as possibly having messages that will
     * dispatch broadcasts.
     */
    public static void addLooper(@NonNull Looper looper) {
        synchronized (sLoopers) {
            sLoopers.add(Objects.requireNonNull(looper));
        }
    }

    /**
     * If the current thread is hosting a {@link Looper}, then register it as
     * possibly having messages that will dispatch broadcasts.
     */
    public static void addMyLooper() {
        final Looper looper = Looper.myLooper();
        if (looper != null) {
            synchronized (sLoopers) {
                if (sLoopers.add(looper)) {
                    Slog.w(TAG, "Found previously unknown looper " + looper.getThread());
                }
            }
        }
    }

    /**
     * Wait for all registered {@link Looper} instances to become idle, as
     * defined by {@link MessageQueue#isIdle()}. Note that {@link Message#when}
     * still in the future are ignored for the purposes of the idle test.
     */
    public static void waitForIdle(@Nullable PrintWriter pw) {
        waitForCondition(pw, (looper, latch) -> {
            final MessageQueue queue = looper.getQueue();
            queue.addIdleHandler(() -> {
                latch.countDown();
                return false;
            });
        });
    }

    /**
     * Wait for all registered {@link Looper} instances to handle currently waiting messages.
     * Note that {@link Message#when} still in the future are ignored for the purposes
     * of the idle test.
     */
    public static void waitForBarrier(@Nullable PrintWriter pw) {
        waitForCondition(pw, (looper, latch) -> {
            (new Handler(looper)).post(() -> {
                latch.countDown();
            });
        });
    }

    /**
     * Wait for all registered {@link Looper} instances to meet a certain condition.
     */
    private static void waitForCondition(@Nullable PrintWriter pw,
            @NonNull BiConsumer<Looper, CountDownLatch> condition) {
        final CountDownLatch latch;
        synchronized (sLoopers) {
            final int N = sLoopers.size();
            latch = new CountDownLatch(N);
            for (int i = 0; i < N; i++) {
                final Looper looper = sLoopers.valueAt(i);
                final MessageQueue queue = looper.getQueue();
                if (queue.isIdle()) {
                    latch.countDown();
                } else {
                    condition.accept(looper, latch);
                }
            }
        }

        long lastPrint = 0;
        while (latch.getCount() > 0) {
            final long now = SystemClock.uptimeMillis();
            if (now >= lastPrint + 1000) {
                lastPrint = now;
                logv("Waiting for " + latch.getCount() + " loopers to drain...", pw);
            }
            SystemClock.sleep(100);
        }
        logv("Loopers drained!", pw);
    }

    private static void logv(@NonNull String msg, @Nullable PrintWriter pw) {
        Slog.v(TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }
}
