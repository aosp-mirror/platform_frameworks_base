/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.testing.shadows;

import android.util.Log;
import android.util.Slog;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLog;

@Implements(Slog.class)
public class ShadowSlog {
    @Implementation
    public static int v(String tag, String msg) {
        return Log.v(tag, msg);
    }

    @Implementation
    public static int v(String tag, String msg, Throwable tr) {
        return Log.v(tag, msg, tr);
    }

    @Implementation
    public static int d(String tag, String msg) {
        return Log.d(tag, msg);
    }

    @Implementation
    public static int d(String tag, String msg, Throwable tr) {
        return Log.d(tag, msg, tr);
    }

    @Implementation
    public static int i(String tag, String msg) {
        return Log.i(tag, msg);
    }

    @Implementation
    public static int i(String tag, String msg, Throwable tr) {
        return Log.i(tag, msg, tr);
    }

    @Implementation
    public static int w(String tag, String msg) {
        return Log.w(tag, msg);
    }

    @Implementation
    public static int w(String tag, String msg, Throwable tr) {
        return Log.w(tag, msg, tr);
    }

    @Implementation
    public static int w(String tag, Throwable tr) {
        return Log.w(tag, tr);
    }

    @Implementation
    public static int e(String tag, String msg) {
        return Log.e(tag, msg);
    }

    @Implementation
    public static int e(String tag, String msg, Throwable tr) {
        return Log.e(tag, msg, tr);
    }

    @Implementation
    public static int wtf(String tag, String msg) {
        return Log.wtf(tag, msg);
    }

    @Implementation
    public static void wtfQuiet(String tag, String msg) {
        Log.wtf(tag, msg);
    }

    @Implementation
    public static int wtfStack(String tag, String msg) {
        return Log.wtf(tag, msg);
    }

    @Implementation
    public static int wtf(String tag, Throwable tr) {
        return Log.wtf(tag, tr);
    }

    @Implementation
    public static int wtf(String tag, String msg, Throwable tr) {
        return Log.wtf(tag, msg, tr);
    }

    @Implementation
    public static int println(int priority, String tag, String msg) {
        return Log.println(priority, tag, msg);
    }
}
