/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.protolog;

import static com.android.server.protolog.ProtoLogFileProto.LOG;
import static com.android.server.protolog.ProtoLogFileProto.MAGIC_NUMBER;
import static com.android.server.protolog.ProtoLogFileProto.MAGIC_NUMBER_H;
import static com.android.server.protolog.ProtoLogFileProto.MAGIC_NUMBER_L;
import static com.android.server.protolog.ProtoLogFileProto.REAL_TIME_TO_ELAPSED_TIME_OFFSET_MILLIS;
import static com.android.server.protolog.ProtoLogFileProto.VERSION;
import static com.android.server.protolog.ProtoLogMessage.BOOLEAN_PARAMS;
import static com.android.server.protolog.ProtoLogMessage.DOUBLE_PARAMS;
import static com.android.server.protolog.ProtoLogMessage.ELAPSED_REALTIME_NANOS;
import static com.android.server.protolog.ProtoLogMessage.MESSAGE_HASH;
import static com.android.server.protolog.ProtoLogMessage.SINT64_PARAMS;
import static com.android.server.protolog.ProtoLogMessage.STR_PARAMS;

import android.annotation.Nullable;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.protolog.common.IProtoLogGroup;
import com.android.server.protolog.common.LogDataType;
import com.android.server.utils.TraceBuffer;
import com.android.server.wm.ProtoLogGroup;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IllegalFormatConversionException;
import java.util.TreeMap;
import java.util.stream.Collectors;


/**
 * A service for the ProtoLog logging system.
 */
public class ProtoLogImpl {
    private static final TreeMap<String, IProtoLogGroup> LOG_GROUPS = new TreeMap<>();

    private static void addLogGroupEnum(IProtoLogGroup[] config) {
        Arrays.stream(config).forEach(group -> LOG_GROUPS.put(group.name(), group));
    }

    static {
        addLogGroupEnum(ProtoLogGroup.values());
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void d(IProtoLogGroup group, int messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance()
                .log(LogLevel.DEBUG, group, messageHash, paramsMask, messageString, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void v(IProtoLogGroup group, int messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance().log(LogLevel.VERBOSE, group, messageHash, paramsMask, messageString,
                args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void i(IProtoLogGroup group, int messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance().log(LogLevel.INFO, group, messageHash, paramsMask, messageString, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void w(IProtoLogGroup group, int messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance().log(LogLevel.WARN, group, messageHash, paramsMask, messageString, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void e(IProtoLogGroup group, int messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance()
                .log(LogLevel.ERROR, group, messageHash, paramsMask, messageString, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void wtf(IProtoLogGroup group, int messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance().log(LogLevel.WTF, group, messageHash, paramsMask, messageString, args);
    }

    private static final int BUFFER_CAPACITY = 1024 * 1024;
    private static final String LOG_FILENAME = "/data/misc/wmtrace/wm_log.pb";
    private static final String VIEWER_CONFIG_FILENAME = "/system/etc/protolog.conf.json.gz";
    private static final String TAG = "ProtoLog";
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;
    static final String PROTOLOG_VERSION = "1.0.0";

    private final File mLogFile;
    private final TraceBuffer mBuffer;
    private final ProtoLogViewerConfigReader mViewerConfig;

    private boolean mProtoLogEnabled;
    private boolean mProtoLogEnabledLockFree;
    private final Object mProtoLogEnabledLock = new Object();

    private static ProtoLogImpl sServiceInstance = null;

    /**
     * Returns the single instance of the ProtoLogImpl singleton class.
     */
    public static synchronized ProtoLogImpl getSingleInstance() {
        if (sServiceInstance == null) {
            sServiceInstance = new ProtoLogImpl(new File(LOG_FILENAME), BUFFER_CAPACITY,
                    new ProtoLogViewerConfigReader());
        }
        return sServiceInstance;
    }

    @VisibleForTesting
    public static synchronized void setSingleInstance(@Nullable ProtoLogImpl instance) {
        sServiceInstance = instance;
    }

    @VisibleForTesting
    public enum LogLevel {
        DEBUG, VERBOSE, INFO, WARN, ERROR, WTF
    }

    /**
     * Main log method, do not call directly.
     */
    @VisibleForTesting
    public void log(LogLevel level, IProtoLogGroup group, int messageHash, int paramsMask,
            @Nullable String messageString, Object[] args) {
        if (group.isLogToProto()) {
            logToProto(messageHash, paramsMask, args);
        }
        if (group.isLogToLogcat()) {
            logToLogcat(group.getTag(), level, messageHash, messageString, args);
        }
    }

    private void logToLogcat(String tag, LogLevel level, int messageHash,
            @Nullable String messageString, Object[] args) {
        String message = null;
        if (messageString == null) {
            messageString = mViewerConfig.getViewerString(messageHash);
        }
        if (messageString != null) {
            try {
                message = String.format(messageString, args);
            } catch (IllegalFormatConversionException ex) {
                Slog.w(TAG, "Invalid ProtoLog format string.", ex);
            }
        }
        if (message == null) {
            StringBuilder builder = new StringBuilder("UNKNOWN MESSAGE (" + messageHash + ")");
            for (Object o : args) {
                builder.append(" ").append(o);
            }
            message = builder.toString();
        }
        passToLogcat(tag, level, message);
    }

    /**
     * SLog wrapper.
     */
    @VisibleForTesting
    public void passToLogcat(String tag, LogLevel level, String message) {
        switch (level) {
            case DEBUG:
                Slog.d(tag, message);
                break;
            case VERBOSE:
                Slog.v(tag, message);
                break;
            case INFO:
                Slog.i(tag, message);
                break;
            case WARN:
                Slog.w(tag, message);
                break;
            case ERROR:
                Slog.e(tag, message);
                break;
            case WTF:
                Slog.wtf(tag, message);
                break;
        }
    }

    private void logToProto(int messageHash, int paramsMask, Object[] args) {
        if (!isProtoEnabled()) {
            return;
        }
        try {
            ProtoOutputStream os = new ProtoOutputStream();
            long token = os.start(LOG);
            os.write(MESSAGE_HASH, messageHash);
            os.write(ELAPSED_REALTIME_NANOS, SystemClock.elapsedRealtimeNanos());

            int argIndex = 0;
            ArrayList<Long> longParams = new ArrayList<>();
            ArrayList<Double> doubleParams = new ArrayList<>();
            ArrayList<Boolean> booleanParams = new ArrayList<>();
            for (Object o : args) {
                int type = LogDataType.bitmaskToLogDataType(paramsMask, argIndex);
                try {
                    switch (type) {
                        case LogDataType.STRING:
                            os.write(STR_PARAMS, o.toString());
                            break;
                        case LogDataType.LONG:
                            longParams.add(((Number) o).longValue());
                            break;
                        case LogDataType.DOUBLE:
                            doubleParams.add(((Number) o).doubleValue());
                            break;
                        case LogDataType.BOOLEAN:
                            booleanParams.add((boolean) o);
                            break;
                    }
                } catch (ClassCastException ex) {
                    // Should not happen unless there is an error in the ProtoLogTool.
                    os.write(STR_PARAMS, "(INVALID PARAMS_MASK) " + o.toString());
                    Slog.e(TAG, "Invalid ProtoLog paramsMask", ex);
                }
                argIndex++;
            }
            if (longParams.size() > 0) {
                os.writePackedSInt64(SINT64_PARAMS,
                        longParams.stream().mapToLong(i -> i).toArray());
            }
            if (doubleParams.size() > 0) {
                os.writePackedDouble(DOUBLE_PARAMS,
                        doubleParams.stream().mapToDouble(i -> i).toArray());
            }
            if (booleanParams.size() > 0) {
                boolean[] arr = new boolean[booleanParams.size()];
                for (int i = 0; i < booleanParams.size(); i++) {
                    arr[i] = booleanParams.get(i);
                }
                os.writePackedBool(BOOLEAN_PARAMS, arr);
            }
            os.end(token);
            mBuffer.add(os);
        } catch (Exception e) {
            Slog.e(TAG, "Exception while logging to proto", e);
        }
    }


    @VisibleForTesting
    ProtoLogImpl(File file, int bufferCapacity, ProtoLogViewerConfigReader viewerConfig) {
        mLogFile = file;
        mBuffer = new TraceBuffer(bufferCapacity);
        mViewerConfig = viewerConfig;
    }

    /**
     * Starts the logging a circular proto buffer.
     *
     * @param pw Print writer
     */
    public void startProtoLog(@Nullable PrintWriter pw) {
        if (isProtoEnabled()) {
            return;
        }
        synchronized (mProtoLogEnabledLock) {
            logAndPrintln(pw, "Start logging to " + mLogFile + ".");
            mBuffer.resetBuffer();
            mProtoLogEnabled = true;
            mProtoLogEnabledLockFree = true;
        }
    }

    /**
     * Stops logging to proto.
     *
     * @param pw          Print writer
     * @param writeToFile If the current buffer should be written to disk or not
     */
    public void stopProtoLog(@Nullable PrintWriter pw, boolean writeToFile) {
        if (!isProtoEnabled()) {
            return;
        }
        synchronized (mProtoLogEnabledLock) {
            logAndPrintln(pw, "Stop logging to " + mLogFile + ". Waiting for log to flush.");
            mProtoLogEnabled = mProtoLogEnabledLockFree = false;
            if (writeToFile) {
                writeProtoLogToFileLocked();
                logAndPrintln(pw, "Log written to " + mLogFile + ".");
            }
            if (mProtoLogEnabled) {
                logAndPrintln(pw, "ERROR: logging was re-enabled while waiting for flush.");
                throw new IllegalStateException("logging enabled while waiting for flush.");
            }
        }
    }

    /**
     * Returns {@code true} iff logging to proto is enabled.
     */
    public boolean isProtoEnabled() {
        return mProtoLogEnabledLockFree;
    }

    private int setLogging(ShellCommand shell, boolean setTextLogging, boolean value) {
        String group;
        while ((group = shell.getNextArg()) != null) {
            IProtoLogGroup g = LOG_GROUPS.get(group);
            if (g != null) {
                if (setTextLogging) {
                    g.setLogToLogcat(value);
                } else {
                    g.setLogToProto(value);
                }
            } else {
                logAndPrintln(shell.getOutPrintWriter(), "No IProtoLogGroup named " + group);
                return -1;
            }
        }
        return 0;
    }

    private int unknownCommand(PrintWriter pw) {
        pw.println("Unknown command");
        pw.println("Window manager logging options:");
        pw.println("  start: Start proto logging");
        pw.println("  stop: Stop proto logging");
        pw.println("  enable [group...]: Enable proto logging for given groups");
        pw.println("  disable [group...]: Disable proto logging for given groups");
        pw.println("  enable-text [group...]: Enable logcat logging for given groups");
        pw.println("  disable-text [group...]: Disable logcat logging for given groups");
        return -1;
    }

    /**
     * Responds to a shell command.
     */
    public int onShellCommand(ShellCommand shell) {
        PrintWriter pw = shell.getOutPrintWriter();
        String cmd = shell.getNextArg();
        if (cmd == null) {
            return unknownCommand(pw);
        }
        switch (cmd) {
            case "start":
                startProtoLog(pw);
                return 0;
            case "stop":
                stopProtoLog(pw, true);
                return 0;
            case "status":
                logAndPrintln(pw, getStatus());
                return 0;
            case "enable":
                return setLogging(shell, false, true);
            case "enable-text":
                mViewerConfig.loadViewerConfig(pw, VIEWER_CONFIG_FILENAME);
                return setLogging(shell, true, true);
            case "disable":
                return setLogging(shell, false, false);
            case "disable-text":
                return setLogging(shell, true, false);
            default:
                return unknownCommand(pw);
        }
    }

    /**
     * Returns a human-readable ProtoLog status text.
     */
    public String getStatus() {
        return "ProtoLog status: "
                + ((isProtoEnabled()) ? "Enabled" : "Disabled")
                + "\nEnabled log groups: \n  Proto: "
                + LOG_GROUPS.values().stream().filter(
                    it -> it.isEnabled() && it.isLogToProto())
                .map(IProtoLogGroup::name).collect(Collectors.joining(" "))
                + "\n  Logcat: "
                + LOG_GROUPS.values().stream().filter(
                    it -> it.isEnabled() && it.isLogToLogcat())
                .map(IProtoLogGroup::name).collect(Collectors.joining(" "))
                + "\nLogging definitions loaded: " + mViewerConfig.knownViewerStringsNumber();
    }

    /**
     * Writes the log buffer to a new file for the bugreport.
     *
     * This method is synchronized with {@code #startProtoLog(PrintWriter)} and
     * {@link #stopProtoLog(PrintWriter, boolean)}.
     */
    public void writeProtoLogToFile() {
        synchronized (mProtoLogEnabledLock) {
            writeProtoLogToFileLocked();
        }
    }

    private void writeProtoLogToFileLocked() {
        try {
            long offset =
                    (System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() / 1000000));
            ProtoOutputStream proto = new ProtoOutputStream();
            proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
            proto.write(VERSION, PROTOLOG_VERSION);
            proto.write(REAL_TIME_TO_ELAPSED_TIME_OFFSET_MILLIS, offset);
            mBuffer.writeTraceToFile(mLogFile, proto);
        } catch (IOException e) {
            Slog.e(TAG, "Unable to write buffer to file", e);
        }
    }


    static void logAndPrintln(@Nullable PrintWriter pw, String msg) {
        Slog.i(TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }
}

