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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

/**
 * API for sending log output to the {@link Log#LOG_ID_SYSTEM} buffer.
 *
 * <p>Should be used by system components. Use {@code adb logcat --buffer=system} to fetch the logs.
 *
 * @see Log
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class Slog {

    private Slog() {
    }

    /**
     * Logs {@code msg} at {@link Log#VERBOSE} level.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     *
     * @see Log#v(String, String)
     */
    @UnsupportedAppUsage
    public static int v(@Nullable String tag, @NonNull String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.VERBOSE, tag, msg);
    }

    /**
     * Logs {@code msg} at {@link Log#VERBOSE} level, attaching stack trace of the {@code tr} to
     * the end of the log statement.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     * @param tr an exception to log.
     *
     * @see Log#v(String, String, Throwable)
     */
    public static int v(@Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.VERBOSE, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Logs {@code msg} at {@link Log#DEBUG} level.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     *
     * @see Log#d(String, String)
     */
    @UnsupportedAppUsage
    public static int d(@Nullable String tag, @NonNull String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.DEBUG, tag, msg);
    }

    /**
     * Logs {@code msg} at {@link Log#DEBUG} level, attaching stack trace of the {@code tr} to
     * the end of the log statement.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     * @param tr an exception to log.
     *
     * @see Log#d(String, String, Throwable)
     */
    @UnsupportedAppUsage
    public static int d(@Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.DEBUG, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Logs {@code msg} at {@link Log#INFO} level.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     *
     * @see Log#i(String, String)
     */
    @UnsupportedAppUsage
    public static int i(@Nullable String tag, @NonNull String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.INFO, tag, msg);
    }

    /**
     * Logs {@code msg} at {@link Log#INFO} level, attaching stack trace of the {@code tr} to
     * the end of the log statement.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     * @param tr an exception to log.
     *
     * @see Log#i(String, String, Throwable)
     */
    public static int i(@Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.INFO, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Logs {@code msg} at {@link Log#WARN} level.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     *
     * @see Log#w(String, String)
     */
    @UnsupportedAppUsage
    public static int w(@Nullable String tag, @NonNull String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.WARN, tag, msg);
    }

    /**
     * Logs {@code msg} at {@link Log#WARN} level, attaching stack trace of the {@code tr} to
     * the end of the log statement.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     * @param tr an exception to log.
     *
     * @see Log#w(String, String, Throwable)
     */
    @UnsupportedAppUsage
    public static int w(@Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.WARN, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Logs stack trace of {@code tr} at {@link Log#WARN} level.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param tr an exception to log.
     *
     * @see Log#w(String, Throwable)
     */
    public static int w(@Nullable String tag, @Nullable Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.WARN, tag, Log.getStackTraceString(tr));
    }

    /**
     * Logs {@code msg} at {@link Log#ERROR} level.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     *
     * @see Log#e(String, String)
     */
    @UnsupportedAppUsage
    public static int e(@Nullable String tag, @NonNull String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.ERROR, tag, msg);
    }

    /**
     * Logs {@code msg} at {@link Log#ERROR} level, attaching stack trace of the {@code tr} to
     * the end of the log statement.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     * @param tr an exception to log.
     *
     * @see Log#e(String, String, Throwable)
     */
    @UnsupportedAppUsage
    public static int e(@Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Log.println_native(Log.LOG_ID_SYSTEM, Log.ERROR, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Logs a condition that should never happen.
     *
     * <p>
     * Similar to {@link Log#wtf(String, String)}, but will never cause the caller to crash, and
     * will always be handled asynchronously. Primarily to be used by the system server.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     *
     * @see Log#wtf(String, String)
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static int wtf(@Nullable String tag, @NonNull String msg) {
        return Log.wtf(Log.LOG_ID_SYSTEM, tag, msg, null, false, true);
    }

    /**
     * Similar to {@link #wtf(String, String)}, but does not output anything to the log.
     */
    public static void wtfQuiet(@Nullable String tag, @NonNull String msg) {
        Log.wtfQuiet(Log.LOG_ID_SYSTEM, tag, msg, true);
    }

    /**
     * Logs a condition that should never happen, attaching the full call stack to the log.
     *
     * <p>
     * Similar to {@link Log#wtfStack(String, String)}, but will never cause the caller to crash,
     * and will always be handled asynchronously. Primarily to be used by the system server.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     *
     * @see Log#wtfStack(String, String)
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static int wtfStack(@Nullable String tag, @NonNull String msg) {
        return Log.wtf(Log.LOG_ID_SYSTEM, tag, msg, null, true, true);
    }

    /**
     * Logs a condition that should never happen, attaching stack trace of the {@code tr} to the
     * end of the log statement.
     *
     * <p>
     * Similar to {@link Log#wtf(String, Throwable)}, but will never cause the caller to crash,
     * and will always be handled asynchronously. Primarily to be used by the system server.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param tr an exception to log.
     *
     * @see Log#wtf(String, Throwable)
     */
    public static int wtf(@Nullable String tag, @Nullable Throwable tr) {
        return Log.wtf(Log.LOG_ID_SYSTEM, tag, tr.getMessage(), tr, false, true);
    }

    /**
     * Logs a condition that should never happen, attaching stack trace of the {@code tr} to the
     * end of the log statement.
     *
     * <p>
     * Similar to {@link Log#wtf(String, String, Throwable)}, but will never cause the caller to
     * crash, and will always be handled asynchronously. Primarily to be used by the system server.
     *
     * @param tag identifies the source of a log message.  It usually represents system service,
     *            e.g. {@code PackageManager}.
     * @param msg the message to log.
     * @param tr an exception to log.
     *
     * @see Log#wtf(String, String, Throwable)
     */
    @UnsupportedAppUsage
    public static int wtf(@Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Log.wtf(Log.LOG_ID_SYSTEM, tag, msg, tr, false, true);
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static int println(@Log.Level int priority, @Nullable String tag, @NonNull String msg) {
        return Log.println_native(Log.LOG_ID_SYSTEM, priority, tag, msg);
    }
}
