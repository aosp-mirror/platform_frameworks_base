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
 * limitations under the License.
 */

package com.android.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;

public final class TestUtils {
    private TestUtils() { }

    /**
     * Block until the given Handler thread becomes idle, or until timeoutMs has passed.
     */
    public static void waitForIdleHandler(HandlerThread handlerThread, long timeoutMs) {
        waitForIdleLooper(handlerThread.getLooper(), timeoutMs);
    }

    /**
     * Block until the given Looper becomes idle, or until timeoutMs has passed.
     */
    public static void waitForIdleLooper(Looper looper, long timeoutMs) {
        waitForIdleHandler(new Handler(looper), timeoutMs);
    }

    /**
     * Block until the given Handler becomes idle, or until timeoutMs has passed.
     */
    public static void waitForIdleHandler(Handler handler, long timeoutMs) {
        final ConditionVariable cv = new ConditionVariable();
        handler.post(() -> cv.open());
        if (!cv.block(timeoutMs)) {
            fail(handler.toString() + " did not become idle after " + timeoutMs + " ms");
        }
    }

    /**
     * Block until the given Serial Executor becomes idle, or until timeoutMs has passed.
     */
    public static void waitForIdleSerialExecutor(@NonNull Executor executor, long timeoutMs) {
        final ConditionVariable cv = new ConditionVariable();
        executor.execute(() -> cv.open());
        if (!cv.block(timeoutMs)) {
            fail(executor.toString() + " did not become idle after " + timeoutMs + " ms");
        }
    }

    /**
     * Return a new instance of {@code T} after being parceled then unparceled.
     */
    public static <T extends Parcelable> T parcelingRoundTrip(T source) {
        final Parcelable.Creator<T> creator;
        try {
            creator = (Parcelable.Creator<T>) source.getClass().getField("CREATOR").get(null);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            fail("Missing CREATOR field: " + e.getMessage());
            return null;
        }
        Parcel p = Parcel.obtain();
        source.writeToParcel(p, /* flags */ 0);
        p.setDataPosition(0);
        final byte[] marshalled = p.marshall();
        p = Parcel.obtain();
        p.unmarshall(marshalled, 0, marshalled.length);
        p.setDataPosition(0);
        return creator.createFromParcel(p);
    }

    /**
     * Assert that after being parceled then unparceled, {@code source} is equal to the original
     * object.
     */
    public static <T extends Parcelable> void assertParcelingIsLossless(T source) {
        assertEquals(source, parcelingRoundTrip(source));
    }
}
