/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.telephony;

import com.android.internal.os.RuntimeInit;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * A class to log strings to the RADIO LOG.
 *
 * @hide
 */
public final class Rlog {

    private Rlog() {
    }

    public static int v(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.VERBOSE, tag, msg);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.VERBOSE, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int d(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.DEBUG, tag, msg);
    }

    public static int d(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.DEBUG, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int i(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.INFO, tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.INFO, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.WARN, tag, msg);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.WARN, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.WARN, tag, Log.getStackTraceString(tr));
    }

    public static int e(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.ERROR, tag, msg);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.ERROR, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int println(int priority, String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, priority, tag, msg);
    }

    public static boolean isLoggable(String tag, int level) {
        return Log.isLoggable(tag, level);
    }

}

