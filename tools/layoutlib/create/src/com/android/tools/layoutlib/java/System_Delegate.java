/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.tools.layoutlib.java;

import com.android.tools.layoutlib.create.ReplaceMethodCallsAdapter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides dummy implementation of methods that don't exist on the host VM.
 * This also providers a time control that allows to set a specific system time.
 *
 * @see ReplaceMethodCallsAdapter
 */
@SuppressWarnings("unused")
public class System_Delegate {
    // Current system time
    private static AtomicLong mNanosTime = new AtomicLong(System.nanoTime());
    // Time that the system booted up in nanos
    private static AtomicLong mBootNanosTime = new AtomicLong(System.nanoTime());

    public static void log(String message) {
        // ignore.
    }

    public static void log(String message, Throwable th) {
        // ignore.
    }

    public static void setNanosTime(long nanos) {
        mNanosTime.set(nanos);
    }

    public static void setBootTimeNanos(long nanos) {
        mBootNanosTime.set(nanos);
    }

    public static long nanoTime() {
        return mNanosTime.get();
    }

    public static long currentTimeMillis() {
        return TimeUnit.NANOSECONDS.toMillis(mNanosTime.get());
    }

    public static long bootTime() {
        return mBootNanosTime.get();
    }

    public static long bootTimeMillis() {
        return TimeUnit.NANOSECONDS.toMillis(mBootNanosTime.get());
    }
}
