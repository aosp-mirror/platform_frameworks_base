/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.protolog;

import static android.internal.perfetto.protos.InternedDataOuterClass.InternedData.PROTOLOG_STACKTRACE;
import static android.internal.perfetto.protos.InternedDataOuterClass.InternedData.PROTOLOG_STRING_ARGS;
import static android.internal.perfetto.protos.ProfileCommon.InternedString.IID;
import static android.internal.perfetto.protos.ProfileCommon.InternedString.STR;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.BOOLEAN_PARAMS;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.DOUBLE_PARAMS;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.MESSAGE_ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.SINT64_PARAMS;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.STACKTRACE_IID;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.STR_PARAM_IIDS;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.GROUPS;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.NAME;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.TAG;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MESSAGES;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.GROUP_ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.LEVEL;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.INTERNED_DATA;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.PROTOLOG_MESSAGE;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.PROTOLOG_VIEWER_CONFIG;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.SEQUENCE_FLAGS;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.SEQ_INCREMENTAL_STATE_CLEARED;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.SEQ_NEEDS_INCREMENTAL_STATE;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.TIMESTAMP;

import android.annotation.Nullable;
import android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.text.TextUtils;
import android.tracing.perfetto.DataSourceParams;
import android.tracing.perfetto.InitArguments;
import android.tracing.perfetto.Producer;
import android.tracing.perfetto.TracingContext;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongArray;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ILogger;
import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogDataType;
import com.android.internal.protolog.common.LogLevel;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A service for the ProtoLog logging system.
 */
public class PerfettoProtoLogImpl implements IProtoLog {
    private static final String LOG_TAG = "ProtoLog";
    private final AtomicInteger mTracingInstances = new AtomicInteger();

    private final ProtoLogDataSource mDataSource = new ProtoLogDataSource(
            this::onTracingInstanceStart,
            this::dumpTransitionTraceConfig,
            this::onTracingInstanceStop
    );
    private final ProtoLogViewerConfigReader mViewerConfigReader;
    private final ViewerConfigInputStreamProvider mViewerConfigInputStreamProvider;
    private final TreeMap<String, IProtoLogGroup> mLogGroups;
    private final Runnable mCacheUpdater;

    private final Map<LogLevel, Integer> mDefaultLogLevelCounts = new ArrayMap<>();
    private final Map<IProtoLogGroup, Map<LogLevel, Integer>> mLogLevelCounts = new ArrayMap<>();

    private final ExecutorService mBackgroundLoggingService = Executors.newSingleThreadExecutor();

    public PerfettoProtoLogImpl(String viewerConfigFilePath,
            TreeMap<String, IProtoLogGroup> logGroups, Runnable cacheUpdater) {
        this(() -> {
            try {
                return new ProtoInputStream(new FileInputStream(viewerConfigFilePath));
            } catch (FileNotFoundException e) {
                Slog.w(LOG_TAG, "Failed to load viewer config file " + viewerConfigFilePath, e);
                return null;
            }
        }, logGroups, cacheUpdater);
    }

    public PerfettoProtoLogImpl(
            ViewerConfigInputStreamProvider viewerConfigInputStreamProvider,
            TreeMap<String, IProtoLogGroup> logGroups,
            Runnable cacheUpdater
    ) {
        this(viewerConfigInputStreamProvider,
                new ProtoLogViewerConfigReader(viewerConfigInputStreamProvider), logGroups,
                cacheUpdater);
    }

    @VisibleForTesting
    public PerfettoProtoLogImpl(
            ViewerConfigInputStreamProvider viewerConfigInputStreamProvider,
            ProtoLogViewerConfigReader viewerConfigReader,
            TreeMap<String, IProtoLogGroup> logGroups,
            Runnable cacheUpdater
    ) {
        Producer.init(InitArguments.DEFAULTS);
        DataSourceParams params =
                new DataSourceParams.Builder()
                        .setBufferExhaustedPolicy(
                                DataSourceParams
                                        .PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_DROP)
                        .build();
        mDataSource.register(params);
        this.mViewerConfigInputStreamProvider = viewerConfigInputStreamProvider;
        this.mViewerConfigReader = viewerConfigReader;
        this.mLogGroups = logGroups;
        this.mCacheUpdater = cacheUpdater;
    }

    /**
     * Main log method, do not call directly.
     */
    @VisibleForTesting
    @Override
    public void log(LogLevel level, IProtoLogGroup group, long messageHash, int paramsMask,
            @Nullable String messageString, Object[] args) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "log");

        long tsNanos = SystemClock.elapsedRealtimeNanos();
        try {
            mBackgroundLoggingService.submit(() ->
                    logToProto(level, group.name(), messageHash, paramsMask, args, tsNanos));
            if (group.isLogToLogcat()) {
                logToLogcat(group.getTag(), level, messageHash, messageString, args);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    private void dumpTransitionTraceConfig() {
        ProtoInputStream pis = mViewerConfigInputStreamProvider.getInputStream();

        if (pis == null) {
            Slog.w(LOG_TAG, "Failed to get viewer input stream.");
            return;
        }

        mDataSource.trace(ctx -> {
            try {
                final ProtoOutputStream os = ctx.newTracePacket();

                os.write(TIMESTAMP, SystemClock.elapsedRealtimeNanos());

                final long outProtologViewerConfigToken = os.start(PROTOLOG_VIEWER_CONFIG);
                while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    if (pis.getFieldNumber() == (int) MESSAGES) {
                        writeViewerConfigMessage(pis, os);
                    }

                    if (pis.getFieldNumber() == (int) GROUPS) {
                        writeViewerConfigGroup(pis, os);
                    }
                }

                os.end(outProtologViewerConfigToken);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Failed to read ProtoLog viewer config to dump on tracing end", e);
            }
        });
    }

    private static void writeViewerConfigGroup(
            ProtoInputStream pis, ProtoOutputStream os) throws IOException {
        final long inGroupToken = pis.start(GROUPS);
        final long outGroupToken = os.start(GROUPS);

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) ID:
                    int id = pis.readInt(ID);
                    os.write(ID, id);
                    break;
                case (int) NAME:
                    String name = pis.readString(NAME);
                    os.write(NAME, name);
                    break;
                case (int) TAG:
                    String tag = pis.readString(TAG);
                    os.write(TAG, tag);
                    break;
                default:
                    throw new RuntimeException(
                            "Unexpected field id " + pis.getFieldNumber());
            }
        }

        pis.end(inGroupToken);
        os.end(outGroupToken);
    }

    private static void writeViewerConfigMessage(
            ProtoInputStream pis, ProtoOutputStream os) throws IOException {
        final long inMessageToken = pis.start(MESSAGES);
        final long outMessagesToken = os.start(MESSAGES);

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) MessageData.MESSAGE_ID:
                    os.write(MessageData.MESSAGE_ID,
                            pis.readLong(MessageData.MESSAGE_ID));
                    break;
                case (int) MESSAGE:
                    os.write(MESSAGE, pis.readString(MESSAGE));
                    break;
                case (int) LEVEL:
                    os.write(LEVEL, pis.readInt(LEVEL));
                    break;
                case (int) GROUP_ID:
                    os.write(GROUP_ID, pis.readInt(GROUP_ID));
                    break;
                default:
                    throw new RuntimeException(
                            "Unexpected field id " + pis.getFieldNumber());
            }
        }

        pis.end(inMessageToken);
        os.end(outMessagesToken);
    }

    private void logToLogcat(String tag, LogLevel level, long messageHash,
            @Nullable String messageString, Object[] args) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "logToLogcat");
        try {
            doLogToLogcat(tag, level, messageHash, messageString, args);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    private void doLogToLogcat(String tag, LogLevel level, long messageHash,
            @androidx.annotation.Nullable String messageString, Object[] args) {
        String message = null;
        if (messageString == null) {
            messageString = mViewerConfigReader.getViewerString(messageHash);
        }
        if (messageString != null) {
            if (args != null) {
                try {
                    message = TextUtils.formatSimple(messageString, args);
                } catch (Exception ex) {
                    Slog.w(LOG_TAG, "Invalid ProtoLog format string.", ex);
                }
            } else {
                message = messageString;
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

    private void logToProto(LogLevel level, String groupName, long messageHash, int paramsMask,
            Object[] args, long tsNanos) {
        if (!isProtoEnabled()) {
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "logToProto");
        try {
            doLogToProto(level, groupName, messageHash, paramsMask, args, tsNanos);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    private void doLogToProto(LogLevel level, String groupName, long messageHash, int paramsMask,
            Object[] args, long tsNanos) {
        mDataSource.trace(ctx -> {
            final ProtoLogDataSource.TlsState tlsState = ctx.getCustomTlsState();
            final LogLevel logFrom = tlsState.getLogFromLevel(groupName);

            if (level.ordinal() < logFrom.ordinal()) {
                return;
            }

            if (args != null) {
                // Intern all string params before creating the trace packet for the proto
                // message so that the interned strings appear before in the trace to make the
                // trace processing easier.
                int argIndex = 0;
                for (Object o : args) {
                    int type = LogDataType.bitmaskToLogDataType(paramsMask, argIndex);
                    if (type == LogDataType.STRING) {
                        internStringArg(ctx, o.toString());
                    }
                    argIndex++;
                }
            }

            int internedStacktrace = 0;
            if (tlsState.getShouldCollectStacktrace(groupName)) {
                // Intern stackstraces before creating the trace packet for the proto message so
                // that the interned stacktrace strings appear before in the trace to make the
                // trace processing easier.
                String stacktrace = collectStackTrace();
                internedStacktrace = internStacktraceString(ctx, stacktrace);
            }

            final ProtoOutputStream os = ctx.newTracePacket();
            os.write(TIMESTAMP, tsNanos);
            long token = os.start(PROTOLOG_MESSAGE);
            os.write(MESSAGE_ID, messageHash);

            boolean needsIncrementalState = false;

            if (args != null) {

                int argIndex = 0;
                LongArray longParams = new LongArray();
                ArrayList<Double> doubleParams = new ArrayList<>();
                ArrayList<Boolean> booleanParams = new ArrayList<>();
                for (Object o : args) {
                    int type = LogDataType.bitmaskToLogDataType(paramsMask, argIndex);
                    try {
                        switch (type) {
                            case LogDataType.STRING:
                                final int internedStringId = internStringArg(ctx, o.toString());
                                os.write(STR_PARAM_IIDS, internedStringId);
                                needsIncrementalState = true;
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
                        Slog.e(LOG_TAG, "Invalid ProtoLog paramsMask", ex);
                    }
                    argIndex++;
                }

                for (int i = 0; i < longParams.size(); ++i) {
                    os.write(SINT64_PARAMS, longParams.get(i));
                }
                doubleParams.forEach(it -> os.write(DOUBLE_PARAMS, it));
                // Converting booleans to int because Perfetto doesn't yet support repeated
                // booleans, so we use a repeated integers instead (b/313651412).
                booleanParams.forEach(it -> os.write(BOOLEAN_PARAMS, it ? 1 : 0));
            }

            if (tlsState.getShouldCollectStacktrace(groupName)) {
                os.write(STACKTRACE_IID, internedStacktrace);
            }

            os.end(token);

            if (needsIncrementalState) {
                os.write(SEQUENCE_FLAGS, SEQ_NEEDS_INCREMENTAL_STATE);
            }

        });
    }

    private static final int STACK_SIZE_TO_PROTO_LOG_ENTRY_CALL = 12;

    private String collectStackTrace() {
        StackTraceElement[] stackTrace =  Thread.currentThread().getStackTrace();
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            for (int i = STACK_SIZE_TO_PROTO_LOG_ENTRY_CALL; i < stackTrace.length; ++i) {
                pw.println("\tat " + stackTrace[i]);
            }
        }

        return sw.toString();
    }

    private int internStacktraceString(TracingContext<
            ProtoLogDataSource.Instance,
            ProtoLogDataSource.TlsState,
            ProtoLogDataSource.IncrementalState> ctx,
            String stacktrace) {
        final ProtoLogDataSource.IncrementalState incrementalState = ctx.getIncrementalState();
        return internString(ctx, incrementalState.stacktraceInterningMap,
                PROTOLOG_STACKTRACE, stacktrace);
    }

    private int internStringArg(
            TracingContext<ProtoLogDataSource.Instance, ProtoLogDataSource.TlsState,
                    ProtoLogDataSource.IncrementalState> ctx,
            String string
    ) {
        final ProtoLogDataSource.IncrementalState incrementalState = ctx.getIncrementalState();
        return internString(ctx, incrementalState.argumentInterningMap,
                PROTOLOG_STRING_ARGS, string);
    }

    private int internString(
            TracingContext<ProtoLogDataSource.Instance, ProtoLogDataSource.TlsState,
                    ProtoLogDataSource.IncrementalState> ctx,
            Map<String, Integer> internMap,
            long fieldId,
            String string
    ) {
        final ProtoLogDataSource.IncrementalState incrementalState = ctx.getIncrementalState();

        if (!incrementalState.clearReported) {
            final ProtoOutputStream os = ctx.newTracePacket();
            os.write(SEQUENCE_FLAGS, SEQ_INCREMENTAL_STATE_CLEARED);
            incrementalState.clearReported = true;
        }

        if (!internMap.containsKey(string)) {
            final int internedIndex = internMap.size() + 1;
            internMap.put(string, internedIndex);

            final ProtoOutputStream os = ctx.newTracePacket();
            final long token = os.start(INTERNED_DATA);
            final long innerToken = os.start(fieldId);
            os.write(IID, internedIndex);
            os.write(STR, string.getBytes());
            os.end(innerToken);
            os.end(token);
        }

        return internMap.get(string);
    }

    /**
     * Returns {@code true} iff logging to proto is enabled.
     */
    public boolean isProtoEnabled() {
        return mTracingInstances.get() > 0;
    }

    /**
     * Start text logging
     * @param groups Groups to start text logging for
     * @param logger A logger to write status updates to
     * @return status code
     */
    public int startLoggingToLogcat(String[] groups, ILogger logger) {
        mViewerConfigReader.loadViewerConfig(logger);
        return setTextLogging(true, logger, groups);
    }

    /**
     * Stop text logging
     * @param groups Groups to start text logging for
     * @param logger A logger to write status updates to
     * @return status code
     */
    public int stopLoggingToLogcat(String[] groups, ILogger logger) {
        mViewerConfigReader.unloadViewerConfig();
        return setTextLogging(false, logger, groups);
    }

    @Override
    public boolean isEnabled(IProtoLogGroup group, LogLevel level) {
        return group.isLogToLogcat() || getLogFromLevel(group).ordinal() <= level.ordinal();
    }

    private LogLevel getLogFromLevel(IProtoLogGroup group) {
        if (mLogLevelCounts.containsKey(group)) {
            for (LogLevel logLevel : LogLevel.values()) {
                if (mLogLevelCounts.get(group).getOrDefault(logLevel, 0) > 0) {
                    return logLevel;
                }
            }
        } else {
            for (LogLevel logLevel : LogLevel.values()) {
                if (mDefaultLogLevelCounts.getOrDefault(logLevel, 0) > 0) {
                    return logLevel;
                }
            }
        }

        return LogLevel.WTF;
    }

    /**
     * Start logging the stack trace of the when the log message happened for target groups
     * @return status code
     */
    public int startLoggingStackTrace(String[] groups, ILogger logger) {
        return -1;
    }

    /**
     * Stop logging the stack trace of the when the log message happened for target groups
     * @return status code
     */
    public int stopLoggingStackTrace() {
        return -1;
    }

    private int setTextLogging(boolean value, ILogger logger, String... groups) {
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            IProtoLogGroup g = mLogGroups.get(group);
            if (g != null) {
                g.setLogToLogcat(value);
            } else {
                logger.log("No IProtoLogGroup named " + group);
                return -1;
            }
        }

        mCacheUpdater.run();
        return 0;
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
        ArrayList<String> args = new ArrayList<>();
        String arg;
        while ((arg = shell.getNextArg()) != null) {
            args.add(arg);
        }
        final ILogger logger = (msg) -> logAndPrintln(pw, msg);
        String[] groups = args.toArray(new String[0]);
        switch (cmd) {
            case "start", "stop" -> {
                pw.println("Command not supported. "
                        + "Please start and stop ProtoLog tracing with Perfetto.");
                return -1;
            }
            case "enable-text" -> {
                mViewerConfigReader.loadViewerConfig(logger);
                return setTextLogging(true, logger, groups);
            }
            case "disable-text" -> {
                return setTextLogging(false, logger, groups);
            }
            default -> {
                return unknownCommand(pw);
            }
        }
    }

    private int unknownCommand(PrintWriter pw) {
        pw.println("Unknown command");
        pw.println("Window manager logging options:");
        pw.println("  enable-text [group...]: Enable logcat logging for given groups");
        pw.println("  disable-text [group...]: Disable logcat logging for given groups");
        return -1;
    }

    private synchronized void onTracingInstanceStart(ProtoLogDataSource.ProtoLogConfig config) {
        this.mTracingInstances.incrementAndGet();

        final LogLevel defaultLogFrom = config.getDefaultGroupConfig().logFrom;
        mDefaultLogLevelCounts.put(defaultLogFrom,
                mDefaultLogLevelCounts.getOrDefault(defaultLogFrom, 0) + 1);

        final Set<String> overriddenGroupTags = config.getGroupTagsWithOverriddenConfigs();

        for (String overriddenGroupTag : overriddenGroupTags) {
            IProtoLogGroup group = mLogGroups.get(overriddenGroupTag);

            mLogLevelCounts.putIfAbsent(group, new ArrayMap<>());
            final Map<LogLevel, Integer> logLevelsCountsForGroup = mLogLevelCounts.get(group);

            final LogLevel logFromLevel = config.getConfigFor(overriddenGroupTag).logFrom;
            logLevelsCountsForGroup.put(logFromLevel,
                    logLevelsCountsForGroup.getOrDefault(logFromLevel, 0) + 1);
        }

        mCacheUpdater.run();
    }

    private synchronized void onTracingInstanceStop(ProtoLogDataSource.ProtoLogConfig config) {
        this.mTracingInstances.decrementAndGet();

        final LogLevel defaultLogFrom = config.getDefaultGroupConfig().logFrom;
        mDefaultLogLevelCounts.put(defaultLogFrom,
                mDefaultLogLevelCounts.get(defaultLogFrom) - 1);
        if (mDefaultLogLevelCounts.get(defaultLogFrom) <= 0) {
            mDefaultLogLevelCounts.remove(defaultLogFrom);
        }

        final Set<String> overriddenGroupTags = config.getGroupTagsWithOverriddenConfigs();

        for (String overriddenGroupTag : overriddenGroupTags) {
            IProtoLogGroup group = mLogGroups.get(overriddenGroupTag);

            mLogLevelCounts.putIfAbsent(group, new ArrayMap<>());
            final Map<LogLevel, Integer> logLevelsCountsForGroup = mLogLevelCounts.get(group);

            final LogLevel logFromLevel = config.getConfigFor(overriddenGroupTag).logFrom;
            logLevelsCountsForGroup.put(logFromLevel,
                    logLevelsCountsForGroup.get(logFromLevel) - 1);
            if (logLevelsCountsForGroup.get(logFromLevel) <= 0) {
                logLevelsCountsForGroup.remove(logFromLevel);
            }
            if (logLevelsCountsForGroup.isEmpty()) {
                mLogLevelCounts.remove(group);
            }
        }

        mCacheUpdater.run();
    }

    static void logAndPrintln(@Nullable PrintWriter pw, String msg) {
        Slog.i(LOG_TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }
}

