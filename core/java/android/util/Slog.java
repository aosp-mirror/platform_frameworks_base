/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.util;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

import com.android.internal.annotations.GuardedBy;

import java.util.Formatter;
import java.util.Locale;

/**
 * API for sending log output to the {@link Log#LOG_ID_SYSTEM} buffer.
 *
 * <p>Should be used by system components. Use {@code adb logcat --buffer=system} to fetch the logs.
 *
 * @hide
 */
public final class Slog {

    @GuardedBy("Slog.class")
    private static StringBuilder sMessageBuilder;

    @GuardedBy("Slog.class")
    private static Formatter sFormatter;

    private Slog() {
    }

    @UnsupportedAppUsage
    public static int v(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.VERBOSE, tag, msg);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.VERBOSE, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Logs a {@link Log.VERBOSE} message.
     *
     * <p><strong>Note: </strong>the message will only be formatted if {@link Log#WARN} logging is
     * enabled for the given {@code tag}, but the compiler will still create an intermediate array
     * of the objects for the {@code vargars}, which could affect garbage collection. So, if you're
     * calling this method in a critical path, make sure to explicitly do the check before calling
     * it.
     */
    public static void v(String tag, String format, @Nullable Object... args) {
        if (!Log.isLoggable(tag, Log.VERBOSE)) return;

        v(tag, getMessage(format, args));
    }

    @UnsupportedAppUsage
    public static int d(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.DEBUG, tag, msg);
    }

    @UnsupportedAppUsage
    public static int d(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.DEBUG, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Logs a {@link Log.DEBUG} message.
     *
     * <p><strong>Note: </strong>the message will only be formatted if {@link Log#WARN} logging is
     * enabled for the given {@code tag}, but the compiler will still create an intermediate array
     * of the objects for the {@code vargars}, which could affect garbage collection. So, if you're
     * calling this method in a critical path, make sure to explicitly do the check before calling
     * it.
     */
    public static void d(String tag, String format, @Nullable Object... args) {
        if (!Log.isLoggable(tag, Log.DEBUG)) return;

        d(tag, getMessage(format, args));
    }

    @UnsupportedAppUsage
    public static int i(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.INFO, tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.INFO, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Logs a {@link Log.INFO} message.
     *
     * <p><strong>Note: </strong>the message will only be formatted if {@link Log#WARN} logging is
     * enabled for the given {@code tag}, but the compiler will still create an intermediate array
     * of the objects for the {@code vargars}, which could affect garbage collection. So, if you're
     * calling this method in a critical path, make sure to explicitly do the check before calling
     * it.
     */
    public static void i(String tag, String format, @Nullable Object... args) {
        if (!Log.isLoggable(tag, Log.INFO)) return;

        i(tag, getMessage(format, args));
    }

    @UnsupportedAppUsage
    public static int w(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.WARN, tag, msg);
    }

    @UnsupportedAppUsage
    public static int w(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.WARN, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.WARN, tag, Log.getStackTraceString(tr));
    }

    /**
     * Logs a {@link Log.WARN} message.
     *
     * <p><strong>Note: </strong>the message will only be formatted if {@link Log#WARN} logging is
     * enabled for the given {@code tag}, but the compiler will still create an intermediate array
     * of the objects for the {@code vargars}, which could affect garbage collection. So, if you're
     * calling this method in a critical path, make sure to explicitly do the check before calling
     * it.
     */
    public static void w(String tag, String format, @Nullable Object... args) {
        if (!Log.isLoggable(tag, Log.WARN)) return;

        w(tag, getMessage(format, args));
    }

    /**
     * Logs a {@link Log.WARN} message with an exception
     *
     * <p><strong>Note: </strong>the message will only be formatted if {@link Log#WARN} logging is
     * enabled for the given {@code tag}, but the compiler will still create an intermediate array
     * of the objects for the {@code vargars}, which could affect garbage collection. So, if you're
     * calling this method in a critical path, make sure to explicitly do the check before calling
     * it.
     */
    public static void w(String tag, Exception exception, String format, @Nullable Object... args) {
        if (!Log.isLoggable(tag, Log.WARN)) return;

        w(tag, getMessage(format, args), exception);
    }

    @UnsupportedAppUsage
    public static int e(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.ERROR, tag, msg);
    }

    @UnsupportedAppUsage
    public static int e(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.ERROR, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Logs a {@link Log.ERROR} message.
     *
     * <p><strong>Note: </strong>the message will only be formatted if {@link Log#WARN} logging is
     * enabled for the given {@code tag}, but the compiler will still create an intermediate array
     * of the objects for the {@code vargars}, which could affect garbage collection. So, if you're
     * calling this method in a critical path, make sure to explicitly do the check before calling
     * it.
     */
    public static void e(String tag, String format, @Nullable Object... args) {
        if (!Log.isLoggable(tag, Log.ERROR)) return;

        e(tag, getMessage(format, args));
    }

    /**
     * Logs a {@link Log.ERROR} message with an exception
     *
     * <p><strong>Note: </strong>the message will only be formatted if {@link Log#WARN} logging is
     * enabled for the given {@code tag}, but the compiler will still create an intermediate array
     * of the objects for the {@code vargars}, which could affect garbage collection. So, if you're
     * calling this method in a critical path, make sure to explicitly do the check before calling
     * it.
     */
    public static void e(String tag, Exception exception, String format, @Nullable Object... args) {
        if (!Log.isLoggable(tag, Log.ERROR)) return;

        e(tag, getMessage(format, args), exception);
    }

    /**
     * Like {@link Log#wtf(String, String)}, but will never cause the caller to crash, and
     * will always be handled asynchronously.  Primarily for use by coding running within
     * the system process.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static int wtf(String tag, String msg) {
        return Log.wtf(Log.LOG_ID_SYSTEM, tag, msg, null, false, true);
    }

    /**
     * Logs a {@code wtf} message.
     */
    public static void wtf(String tag, String format, @Nullable Object... args) {
        wtf(tag, getMessage(format, args));
    }

    /**
     * Logs a {@code wtf} message with an exception.
     */
    public static void wtf(String tag, Exception exception, String format,
            @Nullable Object... args) {
        wtf(tag, getMessage(format, args), exception);
    }

    /**
     * Like {@link #wtf(String, String)}, but does not output anything to the log.
     */
    public static void wtfQuiet(String tag, String msg) {
        Log.wtfQuiet(Log.LOG_ID_SYSTEM, tag, msg, true);
    }

    /**
     * Like {@link Log#wtfStack(String, String)}, but will never cause the caller to crash, and
     * will always be handled asynchronously.  Primarily for use by coding running within
     * the system process.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static int wtfStack(String tag, String msg) {
        return Log.wtf(Log.LOG_ID_SYSTEM, tag, msg, null, true, true);
    }

    /**
     * Like {@link Log#wtf(String, Throwable)}, but will never cause the caller to crash,
     * and will always be handled asynchronously.  Primarily for use by coding running within
     * the system process.
     */
    public static int wtf(String tag, Throwable tr) {
        return Log.wtf(Log.LOG_ID_SYSTEM, tag, tr.getMessage(), tr, false, true);
    }

    /**
     * Like {@link Log#wtf(String, String, Throwable)}, but will never cause the caller to crash,
     * and will always be handled asynchronously.  Primarily for use by coding running within
     * the system process.
     */
    @UnsupportedAppUsage
    public static int wtf(String tag, String msg, Throwable tr) {
        return Log.wtf(Log.LOG_ID_SYSTEM, tag, msg, tr, false, true);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static int println(int priority, String tag, String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, priority, tag, msg);
    }

    private static String getMessage(String format, @Nullable Object... args) {
        synchronized (Slog.class) {
            if (sMessageBuilder == null) {
                // Lazy load so they're not created if not used by the process
                sMessageBuilder = new StringBuilder();
                sFormatter = new Formatter(sMessageBuilder, Locale.ENGLISH);
            }
            sFormatter.format(format, args);
            String message = sMessageBuilder.toString();
            sMessageBuilder.setLength(0);
            return message;
        }
    }
}
