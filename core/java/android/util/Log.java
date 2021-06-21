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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.DeadSystemException;

import com.android.internal.os.RuntimeInit;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.LineBreakBufferedWriter;

import dalvik.annotation.optimization.FastNative;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.UnknownHostException;

/**
 * API for sending log output.
 *
 * <p>Generally, you should use the {@link #v Log.v()}, {@link #d Log.d()},
 * {@link #i Log.i()}, {@link #w Log.w()}, and {@link #e Log.e()} methods to write logs.
 * You can then <a href="{@docRoot}studio/debug/am-logcat.html">view the logs in logcat</a>.
 *
 * <p>The order in terms of verbosity, from least to most is
 * ERROR, WARN, INFO, DEBUG, VERBOSE.
 *
 * <p><b>Tip:</b> A good convention is to declare a <code>TAG</code> constant
 * in your class:
 *
 * <pre>private static final String TAG = "MyActivity";</pre>
 *
 * and use that in subsequent calls to the log methods.
 * </p>
 *
 * <p><b>Tip:</b> Don't forget that when you make a call like
 * <pre>Log.v(TAG, "index=" + i);</pre>
 * that when you're building the string to pass into Log.d, the compiler uses a
 * StringBuilder and at least three allocations occur: the StringBuilder
 * itself, the buffer, and the String object.  Realistically, there is also
 * another buffer allocation and copy, and even more pressure on the gc.
 * That means that if your log message is filtered out, you might be doing
 * significant work and incurring significant overhead.
 */
public final class Log {
    /** @hide */
    @IntDef({ASSERT, ERROR, WARN, INFO, DEBUG, VERBOSE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Level {}

    /**
     * Priority constant for the println method; use Log.v.
     */
    public static final int VERBOSE = 2;

    /**
     * Priority constant for the println method; use Log.d.
     */
    public static final int DEBUG = 3;

    /**
     * Priority constant for the println method; use Log.i.
     */
    public static final int INFO = 4;

    /**
     * Priority constant for the println method; use Log.w.
     */
    public static final int WARN = 5;

    /**
     * Priority constant for the println method; use Log.e.
     */
    public static final int ERROR = 6;

    /**
     * Priority constant for the println method.
     */
    public static final int ASSERT = 7;

    /**
     * Exception class used to capture a stack trace in {@link #wtf}.
     * @hide
     */
    public static class TerribleFailure extends Exception {
        TerribleFailure(String msg, Throwable cause) { super(msg, cause); }
    }

    /**
     * Interface to handle terrible failures from {@link #wtf}.
     *
     * @hide
     */
    public interface TerribleFailureHandler {
        void onTerribleFailure(String tag, TerribleFailure what, boolean system);
    }

    private static TerribleFailureHandler sWtfHandler = new TerribleFailureHandler() {
            public void onTerribleFailure(String tag, TerribleFailure what, boolean system) {
                RuntimeInit.wtf(tag, what, system);
            }
        };

    private Log() {
    }

    /**
     * Send a {@link #VERBOSE} log message.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static int v(@Nullable String tag, @NonNull String msg) {
        return println_native(LOG_ID_MAIN, VERBOSE, tag, msg);
    }

    /**
     * Send a {@link #VERBOSE} log message and log the exception.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     * @param tr An exception to log
     */
    public static int v(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) {
        return printlns(LOG_ID_MAIN, VERBOSE, tag, msg, tr);
    }

    /**
     * Send a {@link #DEBUG} log message.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static int d(@Nullable String tag, @NonNull String msg) {
        return println_native(LOG_ID_MAIN, DEBUG, tag, msg);
    }

    /**
     * Send a {@link #DEBUG} log message and log the exception.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     * @param tr An exception to log
     */
    public static int d(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) {
        return printlns(LOG_ID_MAIN, DEBUG, tag, msg, tr);
    }

    /**
     * Send an {@link #INFO} log message.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static int i(@Nullable String tag, @NonNull String msg) {
        return println_native(LOG_ID_MAIN, INFO, tag, msg);
    }

    /**
     * Send a {@link #INFO} log message and log the exception.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     * @param tr An exception to log
     */
    public static int i(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) {
        return printlns(LOG_ID_MAIN, INFO, tag, msg, tr);
    }

    /**
     * Send a {@link #WARN} log message.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static int w(@Nullable String tag, @NonNull String msg) {
        return println_native(LOG_ID_MAIN, WARN, tag, msg);
    }

    /**
     * Send a {@link #WARN} log message and log the exception.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     * @param tr An exception to log
     */
    public static int w(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) {
        return printlns(LOG_ID_MAIN, WARN, tag, msg, tr);
    }

    /**
     * Checks to see whether or not a log for the specified tag is loggable at the specified level.
     *
     *  The default level of any tag is set to INFO. This means that any level above and including
     *  INFO will be logged. Before you make any calls to a logging method you should check to see
     *  if your tag should be logged. You can change the default level by setting a system property:
     *      'setprop log.tag.&lt;YOUR_LOG_TAG> &lt;LEVEL>'
     *  Where level is either VERBOSE, DEBUG, INFO, WARN, ERROR, or ASSERT.
     *  You can also create a local.prop file that with the following in it:
     *      'log.tag.&lt;YOUR_LOG_TAG>=&lt;LEVEL>'
     *  and place that in /data/local.prop.
     *
     * @param tag The tag to check.
     * @param level The level to check.
     * @return Whether or not that this is allowed to be logged.
     * @throws IllegalArgumentException is thrown if the tag.length() > 23
     *         for Nougat (7.0) and prior releases (API <= 25), there is no
     *         tag limit of concern after this API level.
     */
    @FastNative
    public static native boolean isLoggable(@Nullable String tag, @Level int level);

    /**
     * Send a {@link #WARN} log message and log the exception.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param tr An exception to log
     */
    public static int w(@Nullable String tag, @Nullable Throwable tr) {
        return printlns(LOG_ID_MAIN, WARN, tag, "", tr);
    }

    /**
     * Send an {@link #ERROR} log message.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static int e(@Nullable String tag, @NonNull String msg) {
        return println_native(LOG_ID_MAIN, ERROR, tag, msg);
    }

    /**
     * Send a {@link #ERROR} log message and log the exception.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     * @param tr An exception to log
     */
    public static int e(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) {
        return printlns(LOG_ID_MAIN, ERROR, tag, msg, tr);
    }

    /**
     * What a Terrible Failure: Report a condition that should never happen.
     * The error will always be logged at level ASSERT with the call stack.
     * Depending on system configuration, a report may be added to the
     * {@link android.os.DropBoxManager} and/or the process may be terminated
     * immediately with an error dialog.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public static int wtf(@Nullable String tag, @Nullable String msg) {
        return wtf(LOG_ID_MAIN, tag, msg, null, false, false);
    }

    /**
     * Like {@link #wtf(String, String)}, but also writes to the log the full
     * call stack.
     * @hide
     */
    public static int wtfStack(@Nullable String tag, @Nullable String msg) {
        return wtf(LOG_ID_MAIN, tag, msg, null, true, false);
    }

    /**
     * What a Terrible Failure: Report an exception that should never happen.
     * Similar to {@link #wtf(String, String)}, with an exception to log.
     * @param tag Used to identify the source of a log message.
     * @param tr An exception to log.
     */
    public static int wtf(@Nullable String tag, @NonNull Throwable tr) {
        return wtf(LOG_ID_MAIN, tag, tr.getMessage(), tr, false, false);
    }

    /**
     * What a Terrible Failure: Report an exception that should never happen.
     * Similar to {@link #wtf(String, Throwable)}, with a message as well.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @param tr An exception to log.  May be null.
     */
    public static int wtf(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) {
        return wtf(LOG_ID_MAIN, tag, msg, tr, false, false);
    }

    @UnsupportedAppUsage
    static int wtf(int logId, @Nullable String tag, @Nullable String msg, @Nullable Throwable tr,
            boolean localStack, boolean system) {
        TerribleFailure what = new TerribleFailure(msg, tr);
        // Only mark this as ERROR, do not use ASSERT since that should be
        // reserved for cases where the system is guaranteed to abort.
        // The onTerribleFailure call does not always cause a crash.
        int bytes = printlns(logId, ERROR, tag, msg, localStack ? what : tr);
        sWtfHandler.onTerribleFailure(tag, what, system);
        return bytes;
    }

    static void wtfQuiet(int logId, @Nullable String tag, @Nullable String msg, boolean system) {
        TerribleFailure what = new TerribleFailure(msg, null);
        sWtfHandler.onTerribleFailure(tag, what, system);
    }

    /**
     * Sets the terrible failure handler, for testing.
     *
     * @return the old handler
     *
     * @hide
     */
    @NonNull
    public static TerribleFailureHandler setWtfHandler(@NonNull TerribleFailureHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler == null");
        }
        TerribleFailureHandler oldHandler = sWtfHandler;
        sWtfHandler = handler;
        return oldHandler;
    }

    /**
     * Handy function to get a loggable stack trace from a Throwable
     * @param tr An exception to log
     */
    @NonNull
    public static String getStackTraceString(@Nullable Throwable tr) {
        if (tr == null) {
            return "";
        }

        // This is to reduce the amount of log spew that apps do in the non-error
        // condition of the network being unavailable.
        Throwable t = tr;
        while (t != null) {
            if (t instanceof UnknownHostException) {
                return "";
            }
            t = t.getCause();
        }

        StringWriter sw = new StringWriter();
        PrintWriter pw = new FastPrintWriter(sw, false, 256);
        tr.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * Low-level logging call.
     * @param priority The priority/type of this log message
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     * @return The number of bytes written.
     */
    public static int println(@Level int priority, @Nullable String tag, @NonNull String msg) {
        return println_native(LOG_ID_MAIN, priority, tag, msg);
    }

    /** @hide */ public static final int LOG_ID_MAIN = 0;
    /** @hide */ public static final int LOG_ID_RADIO = 1;
    /** @hide */ public static final int LOG_ID_EVENTS = 2;
    /** @hide */ public static final int LOG_ID_SYSTEM = 3;
    /** @hide */ public static final int LOG_ID_CRASH = 4;

    /** @hide */
    @UnsupportedAppUsage
    public static native int println_native(int bufID, int priority, String tag, String msg);

    /**
     * Send a log message to the "radio" log buffer, which can be dumped with
     * {@code adb logcat -b radio}.
     *
     * <p>Only the telephony mainline module should use it.
     *
     * <p>Note ART will protect {@code MODULE_LIBRARIES} system APIs from regular app code.
     *
     * @param priority Log priority.
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param message The message you would like logged.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static int logToRadioBuffer(@Level int priority, @Nullable String tag,
            @Nullable String message) {
        return println_native(LOG_ID_RADIO, priority, tag, message);
    }

    /**
     * Return the maximum payload the log daemon accepts without truncation.
     * @return LOGGER_ENTRY_MAX_PAYLOAD.
     */
    private static native int logger_entry_max_payload_native();

    /**
     * Helper function for long messages. Uses the LineBreakBufferedWriter to break
     * up long messages and stacktraces along newlines, but tries to write in large
     * chunks. This is to avoid truncation.
     * @hide
     */
    public static int printlns(int bufID, int priority, @Nullable String tag, @NonNull String msg,
            @Nullable Throwable tr) {
        ImmediateLogWriter logWriter = new ImmediateLogWriter(bufID, priority, tag);
        // Acceptable buffer size. Get the native buffer size, subtract two zero terminators,
        // and the length of the tag.
        // Note: we implicitly accept possible truncation for Modified-UTF8 differences. It
        //       is too expensive to compute that ahead of time.
        int bufferSize = PreloadHolder.LOGGER_ENTRY_MAX_PAYLOAD    // Base.
                - 2                                                // Two terminators.
                - (tag != null ? tag.length() : 0)                 // Tag length.
                - 32;                                              // Some slack.
        // At least assume you can print *some* characters (tag is not too large).
        bufferSize = Math.max(bufferSize, 100);

        LineBreakBufferedWriter lbbw = new LineBreakBufferedWriter(logWriter, bufferSize);

        lbbw.println(msg);

        if (tr != null) {
            // This is to reduce the amount of log spew that apps do in the non-error
            // condition of the network being unavailable.
            Throwable t = tr;
            while (t != null) {
                if (t instanceof UnknownHostException) {
                    break;
                }
                if (t instanceof DeadSystemException) {
                    lbbw.println("DeadSystemException: The system died; "
                            + "earlier logs will point to the root cause");
                    break;
                }
                t = t.getCause();
            }
            if (t == null) {
                tr.printStackTrace(lbbw);
            }
        }

        lbbw.flush();

        return logWriter.getWritten();
    }

    /**
     * PreloadHelper class. Caches the LOGGER_ENTRY_MAX_PAYLOAD value to avoid
     * a JNI call during logging.
     */
    static class PreloadHolder {
        public final static int LOGGER_ENTRY_MAX_PAYLOAD =
                logger_entry_max_payload_native();
    }

    /**
     * Helper class to write to the logcat. Different from LogWriter, this writes
     * the whole given buffer and does not break along newlines.
     */
    private static class ImmediateLogWriter extends Writer {

        private int bufID;
        private int priority;
        private String tag;

        private int written = 0;

        /**
         * Create a writer that immediately writes to the log, using the given
         * parameters.
         */
        public ImmediateLogWriter(int bufID, int priority, String tag) {
            this.bufID = bufID;
            this.priority = priority;
            this.tag = tag;
        }

        public int getWritten() {
            return written;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            // Note: using String here has a bit of overhead as a Java object is created,
            //       but using the char[] directly is not easier, as it needs to be translated
            //       to a C char[] for logging.
            written += println_native(bufID, priority, tag, new String(cbuf, off, len));
        }

        @Override
        public void flush() {
            // Ignored.
        }

        @Override
        public void close() {
            // Ignored.
        }
    }
}
