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

package com.android.server.utils;

import android.annotation.Nullable;
import android.os.Trace;
import android.util.Log;
import android.util.Slog;
import android.util.TimingsTraceLog;

import com.android.internal.annotations.GuardedBy;

import java.util.Formatter;
import java.util.Locale;

/**
 * Extends {@link Slog} by providing overloaded methods that take string formatting.
 *
 * <p><strong>Note: </strong>Like the other logging classes, e.g. {@link Log} and {@link Slog}, the
 * methods in this class log unconditionally regardless of {@link Log#isLoggable(String, int)}.
 * Therefore, these methods exist just for the convenience of handling formatting.  (Even if they
 * did check {@link Log#isLoggable(String, int)} before formatting and logging, calling a varargs
 * method in Java still involves an array allocation.)  If you need to avoid the overhead of logging
 * on a performance-critical path, either don't use logging in that place, or make the logging
 * conditional on a static boolean defaulting to false.
 */
public final class Slogf {

    @GuardedBy("sMessageBuilder")
    private static final StringBuilder sMessageBuilder;

    @GuardedBy("sMessageBuilder")
    private static final Formatter sFormatter;

    static {
        TimingsTraceLog t = new TimingsTraceLog("SLog", Trace.TRACE_TAG_SYSTEM_SERVER);
        t.traceBegin("static_init");
        sMessageBuilder = new StringBuilder();
        sFormatter = new Formatter(sMessageBuilder, Locale.ENGLISH);
        t.traceEnd();
    }

    private Slogf() {
        throw new UnsupportedOperationException("provides only static methods");
    }

    /** Same as {@link Slog#v(String, String)}. */
    public static int v(String tag, String msg) {
        return Slog.v(tag, msg);
    }

    /** Same as {@link Slog#v(String, String, Throwable)}. */
    public static int v(String tag, String msg, Throwable tr) {
        return Slog.v(tag, msg, tr);
    }

    /** Same as {@link Slog#d(String, String)}. */
    public static int d(String tag, String msg) {
        return Slog.d(tag, msg);
    }

    /** Same as {@link Slog#d(String, String, Throwable)}. */
    public static int d(String tag, String msg, Throwable tr) {
        return Slog.d(tag, msg, tr);
    }

    /** Same as {@link Slog#i(String, String)}. */
    public static int i(String tag, String msg) {
        return Slog.i(tag, msg);
    }

    /** Same as {@link Slog#i(String, String, Throwable)}. */
    public static int i(String tag, String msg, Throwable tr) {
        return Slog.i(tag, msg, tr);
    }

    /** Same as {@link Slog#w(String, String)}. */
    public static int w(String tag, String msg) {
        return Slog.w(tag, msg);
    }

    /** Same as {@link Slog#w(String, String, Throwable)}. */
    public static int w(String tag, String msg, Throwable tr) {
        return Slog.w(tag, msg, tr);
    }

    /** Same as {@link Slog#w(String, String)}. */
    public static int w(String tag, Throwable tr) {
        return Slog.w(tag, tr);
    }

    /** Same as {@link Slog#e(String, String)}. */
    public static int e(String tag, String msg) {
        return Slog.e(tag, msg);
    }

    /** Same as {@link Slog#e(String, String, Throwable)}. */
    public static int e(String tag, String msg, Throwable tr) {
        return Slog.e(tag, msg, tr);
    }

    /** Same as {@link Slog#wtf(String, String)}. */
    public static int wtf(String tag, String msg) {
        return Slog.wtf(tag, msg);
    }

    /** Same as {@link Slog#wtfQuiet(String, String)}. */
    public static void wtfQuiet(String tag, String msg) {
        Slog.wtfQuiet(tag, msg);
    }

    /** Same as {@link Slog#wtfStack(String, String). */
    public static int wtfStack(String tag, String msg) {
        return Slog.wtfStack(tag, msg);
    }

    /** Same as {@link Slog#wtf(String, Throwable). */
    public static int wtf(String tag, Throwable tr) {
        return Slog.wtf(tag, tr);
    }

    /** Same as {@link Slog#wtf(String, String, Throwable)}. */
    public static int wtf(String tag, String msg, Throwable tr) {
        return Slog.wtf(tag, msg, tr);
    }

    /** Same as {@link Slog#println(int, String, String)}. */
    public static int println(int priority, String tag, String msg) {
        return Slog.println(priority, tag, msg);
    }

    /** Logs a {@link Log.VERBOSE} message. */
    public static void v(String tag, String format, @Nullable Object... args) {
        v(tag, getMessage(format, args));
    }

    /** Logs a {@link Log.VERBOSE} message with a throwable. */
    public static void v(String tag, Throwable throwable, String format, @Nullable Object... args) {
        v(tag, getMessage(format, args), throwable);
    }

    /** Logs a {@link Log.DEBUG} message. */
    public static void d(String tag, String format, @Nullable Object... args) {
        d(tag, getMessage(format, args));
    }

    /** Logs a {@link Log.DEBUG} message with a throwable. */
    public static void d(String tag, Throwable throwable, String format, @Nullable Object... args) {
        d(tag, getMessage(format, args), throwable);
    }

    /** Logs a {@link Log.INFO} message. */
    public static void i(String tag, String format, @Nullable Object... args) {
        i(tag, getMessage(format, args));
    }

    /** Logs a {@link Log.INFO} message with a throwable. */
    public static void i(String tag, Throwable throwable, String format, @Nullable Object... args) {
        i(tag, getMessage(format, args), throwable);
    }

    /** Logs a {@link Log.WARN} message. */
    public static void w(String tag, String format, @Nullable Object... args) {
        w(tag, getMessage(format, args));
    }

    /** Logs a {@link Log.WARN} message with a throwable. */
    public static void w(String tag, Throwable throwable, String format, @Nullable Object... args) {
        w(tag, getMessage(format, args), throwable);
    }

    /** Logs a {@link Log.ERROR} message. */
    public static void e(String tag, String format, @Nullable Object... args) {
        e(tag, getMessage(format, args));
    }

    /** Logs a {@link Log.ERROR} message with a throwable. */
    public static void e(String tag, Throwable throwable, String format, @Nullable Object... args) {
        e(tag, getMessage(format, args), throwable);
    }

    /** Logs a {@code wtf} message. */
    public static void wtf(String tag, String format, @Nullable Object... args) {
        wtf(tag, getMessage(format, args));
    }

    /** Logs a {@code wtf} message with a throwable. */
    public static void wtf(String tag, Throwable throwable, String format,
            @Nullable Object... args) {
        wtf(tag, getMessage(format, args), throwable);
    }

    private static String getMessage(String format, @Nullable Object... args) {
        synchronized (sMessageBuilder) {
            sFormatter.format(format, args);
            String message = sMessageBuilder.toString();
            sMessageBuilder.setLength(0);
            return message;
        }
    }
}
