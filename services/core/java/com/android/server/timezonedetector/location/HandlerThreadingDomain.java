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

package com.android.server.timezonedetector.location;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.os.Handler;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real implementation of {@link ThreadingDomain} that uses a {@link Handler}.
 */
final class HandlerThreadingDomain extends ThreadingDomain {

    @NonNull private final Handler mHandler;

    HandlerThreadingDomain(Handler handler) {
        mHandler = Objects.requireNonNull(handler);
    }

    /**
     * Returns the {@link Handler} associated with this threading domain. The same {@link Handler}
     * may be associated with multiple threading domains, e.g. multiple threading domains could
     * choose to use the {@link com.android.server.FgThread} handler.
     *
     * <p>If you find yourself making this public because you need a {@link Handler}, then it may
     * cause problems with testability. Try to avoid using this method and use methods like {@link
     * #post(Runnable)} instead.
     */
    @NonNull
    Handler getHandler() {
        return mHandler;
    }

    @NonNull
    Thread getThread() {
        return getHandler().getLooper().getThread();
    }

    @Override
    void post(@NonNull Runnable r) {
        getHandler().post(r);
    }

    @Override
    <V> V postAndWait(@NonNull Callable<V> callable, @DurationMillisLong long durationMillis)
            throws Exception {
        // Calling this on this domain's thread would lead to deadlock.
        assertNotCurrentThread();

        AtomicReference<V> resultReference = new AtomicReference<>();
        AtomicReference<Exception> exceptionReference = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        post(() -> {
            try {
                resultReference.set(callable.call());
            } catch (Exception e) {
                exceptionReference.set(e);
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(durationMillis, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Timed out");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (exceptionReference.get() != null) {
            throw exceptionReference.get();
        }
        return resultReference.get();
    }

    @Override
    void postDelayed(@NonNull Runnable r, @DurationMillisLong long delayMillis) {
        getHandler().postDelayed(r, delayMillis);
    }

    @Override
    void postDelayed(Runnable r, Object token, @DurationMillisLong long delayMillis) {
        getHandler().postDelayed(r, token, delayMillis);
    }

    @Override
    void removeQueuedRunnables(Object token) {
        getHandler().removeCallbacksAndMessages(token);
    }
}
