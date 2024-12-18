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

import static android.content.Context.PROTOLOG_CONFIGURATION_SERVICE;
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.SystemClock;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * A service for the ProtoLog logging system.
 */
public class PerfettoProtoLogImpl extends IProtoLogClient.Stub implements IProtoLog {
    private static final String LOG_TAG = "ProtoLog";
    public static final String NULL_STRING = "null";
    private final AtomicInteger mTracingInstances = new AtomicInteger();

    @NonNull
    private final ProtoLogDataSource mDataSource;
    @Nullable
    private final ProtoLogViewerConfigReader mViewerConfigReader;
    @Deprecated
    @Nullable
    private final ViewerConfigInputStreamProvider mViewerConfigInputStreamProvider;
    @NonNull
    private final TreeMap<String, IProtoLogGroup> mLogGroups = new TreeMap<>();
    @NonNull
    private final Runnable mCacheUpdater;

    @Nullable // null when the flag android.tracing.client_side_proto_logging is not flipped
    private final IProtoLogConfigurationService mProtoLogConfigurationService;

    @NonNull
    private final int[] mDefaultLogLevelCounts = new int[LogLevel.values().length];
    @NonNull
    private final Map<String, int[]> mLogLevelCounts = new ArrayMap<>();
    @NonNull
    private final Map<String, Integer> mCollectStackTraceGroupCounts = new ArrayMap<>();

    private final Lock mBackgroundServiceLock = new ReentrantLock();
    private ExecutorService mBackgroundLoggingService = Executors.newSingleThreadExecutor();

    public PerfettoProtoLogImpl(@NonNull IProtoLogGroup[] groups)
            throws ServiceManager.ServiceNotFoundException {
        this(null, null, null, () -> {}, groups);
    }

    public PerfettoProtoLogImpl(@NonNull Runnable cacheUpdater, @NonNull IProtoLogGroup[] groups)
            throws ServiceManager.ServiceNotFoundException {
        this(null, null, null, cacheUpdater, groups);
    }

    public PerfettoProtoLogImpl(
            @NonNull String viewerConfigFilePath,
            @NonNull Runnable cacheUpdater,
            @NonNull IProtoLogGroup[] groups) throws ServiceManager.ServiceNotFoundException {
        this(viewerConfigFilePath,
                null,
                new ProtoLogViewerConfigReader(() -> {
                    try {
                        return new ProtoInputStream(new FileInputStream(viewerConfigFilePath));
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(
                                "Failed to load viewer config file " + viewerConfigFilePath, e);
                    }
                }),
                cacheUpdater, groups);
    }

    @Deprecated
    @VisibleForTesting
    public PerfettoProtoLogImpl(
            @Nullable ViewerConfigInputStreamProvider viewerConfigInputStreamProvider,
            @Nullable ProtoLogViewerConfigReader viewerConfigReader,
            @NonNull Runnable cacheUpdater,
            @NonNull IProtoLogGroup[] groups,
            @NonNull ProtoLogDataSourceBuilder dataSourceBuilder,
            @NonNull ProtoLogConfigurationService configurationService) {
        this(null, viewerConfigInputStreamProvider, viewerConfigReader, cacheUpdater,
                groups, dataSourceBuilder, configurationService);
    }

    @VisibleForTesting
    public PerfettoProtoLogImpl(
            @Nullable String viewerConfigFilePath,
            @Nullable ProtoLogViewerConfigReader viewerConfigReader,
            @NonNull Runnable cacheUpdater,
            @NonNull IProtoLogGroup[] groups,
            @NonNull ProtoLogDataSourceBuilder dataSourceBuilder,
            @NonNull ProtoLogConfigurationService configurationService) {
        this(viewerConfigFilePath, null, viewerConfigReader, cacheUpdater,
                groups, dataSourceBuilder, configurationService);
    }

    private PerfettoProtoLogImpl(
            @Nullable String viewerConfigFilePath,
            @Nullable ViewerConfigInputStreamProvider viewerConfigInputStreamProvider,
            @Nullable ProtoLogViewerConfigReader viewerConfigReader,
            @NonNull Runnable cacheUpdater,
            @NonNull IProtoLogGroup[] groups) throws ServiceManager.ServiceNotFoundException {
        this(viewerConfigFilePath, viewerConfigInputStreamProvider, viewerConfigReader,
                cacheUpdater, groups,
                ProtoLogDataSource::new,
                android.tracing.Flags.clientSideProtoLogging() ?
                    IProtoLogConfigurationService.Stub.asInterface(
                        ServiceManager.getServiceOrThrow(PROTOLOG_CONFIGURATION_SERVICE)
                    ) : null
        );
    }

    private PerfettoProtoLogImpl(
            @Nullable String viewerConfigFilePath,
            @Nullable ViewerConfigInputStreamProvider viewerConfigInputStreamProvider,
            @Nullable ProtoLogViewerConfigReader viewerConfigReader,
            @NonNull Runnable cacheUpdater,
            @NonNull IProtoLogGroup[] groups,
            @NonNull ProtoLogDataSourceBuilder dataSourceBuilder,
            @Nullable IProtoLogConfigurationService configurationService) {
        if (viewerConfigFilePath != null && viewerConfigInputStreamProvider != null) {
            throw new RuntimeException("Only one of viewerConfigFilePath and "
                    + "viewerConfigInputStreamProvider can be set");
        }

        mDataSource = dataSourceBuilder.build(
                this::onTracingInstanceStart,
                this::onTracingFlush,
                this::onTracingInstanceStop);
        Producer.init(InitArguments.DEFAULTS);
        DataSourceParams params =
                new DataSourceParams.Builder()
                        .setBufferExhaustedPolicy(
                                DataSourceParams
                                        .PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_DROP)
                        .build();
        // NOTE: Registering that datasource is an async operation, so there may be no data traced
        // for some messages logged right after the construction of this class.
        mDataSource.register(params);
        this.mViewerConfigInputStreamProvider = viewerConfigInputStreamProvider;
        this.mViewerConfigReader = viewerConfigReader;
        this.mCacheUpdater = cacheUpdater;

        registerGroupsLocally(groups);

        if (android.tracing.Flags.clientSideProtoLogging()) {
            mProtoLogConfigurationService = configurationService;
            Objects.requireNonNull(mProtoLogConfigurationService,
                    "A null ProtoLog Configuration Service was provided!");

            try {
                var args = new ProtoLogConfigurationServiceImpl.RegisterClientArgs();

                if (viewerConfigFilePath != null) {
                    args.setViewerConfigFile(viewerConfigFilePath);
                }

                final var groupArgs = Stream.of(groups)
                        .map(group -> new ProtoLogConfigurationServiceImpl.RegisterClientArgs
                                .GroupConfig(group.name(), group.isLogToLogcat()))
                        .toArray(ProtoLogConfigurationServiceImpl
                                .RegisterClientArgs.GroupConfig[]::new);
                args.setGroups(groupArgs);

                mProtoLogConfigurationService.registerClient(this, args);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed to register ProtoLog client");
            }
        } else {
            mProtoLogConfigurationService = null;
        }
    }

    /**
     * Main log method, do not call directly.
     */
    @VisibleForTesting
    @Override
    public void log(LogLevel logLevel, IProtoLogGroup group, long messageHash, int paramsMask,
            @Nullable Object[] args) {
        log(logLevel, group, new Message(messageHash, paramsMask), args);
    }

    @Override
    public void log(LogLevel logLevel, IProtoLogGroup group, String messageString, Object... args) {
        log(logLevel, group, new Message(messageString), args);
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

    /**
     * Returns {@code true} iff logging to proto is enabled.
     */
    public boolean isProtoEnabled() {
        return mTracingInstances.get() > 0;
    }

    @Override
    public void toggleLogcat(boolean enabled, String[] groups) {
        final ILogger logger = (message) -> Log.d(LOG_TAG, message);
        if (enabled) {
            startLoggingToLogcat(groups, logger);
        } else {
            stopLoggingToLogcat(groups, logger);
        }
    }

    /**
     * Start text logging
     * @param groups Groups to start text logging for
     * @param logger A logger to write status updates to
     * @return status code
     */
    public int startLoggingToLogcat(String[] groups, @NonNull ILogger logger) {
        if (mViewerConfigReader != null) {
            mViewerConfigReader.loadViewerConfig(groups, logger);
        }
        return setTextLogging(true, logger, groups);
    }

    /**
     * Stop text logging
     * @param groups Groups to start text logging for
     * @param logger A logger to write status updates to
     * @return status code
     */
    public int stopLoggingToLogcat(String[] groups, @NonNull ILogger logger) {
        if (mViewerConfigReader != null) {
            mViewerConfigReader.unloadViewerConfig(groups, logger);
        }
        return setTextLogging(false, logger, groups);
    }

    @Override
    public boolean isEnabled(IProtoLogGroup group, LogLevel level) {
        final int[] groupLevelCount = mLogLevelCounts.get(group.name());
        return (groupLevelCount == null && mDefaultLogLevelCounts[level.ordinal()] > 0)
                || (groupLevelCount != null && groupLevelCount[level.ordinal()] > 0)
                || group.isLogToLogcat();
    }

    @Override
    @NonNull
    public List<IProtoLogGroup> getRegisteredGroups() {
        return mLogGroups.values().stream().toList();
    }

    private void registerGroupsLocally(@NonNull IProtoLogGroup[] protoLogGroups) {
        final var groupsLoggingToLogcat = new ArrayList<String>();
        for (IProtoLogGroup protoLogGroup : protoLogGroups) {
            mLogGroups.put(protoLogGroup.name(), protoLogGroup);

            if (protoLogGroup.isLogToLogcat()) {
                groupsLoggingToLogcat.add(protoLogGroup.name());
            }
        }

        if (mViewerConfigReader != null) {
            // Load in background to avoid delay in boot process.
            // The caveat is that any log message that is also logged to logcat will not be
            // successfully decoded until this completes.
            mBackgroundLoggingService.execute(() -> mViewerConfigReader
                    .loadViewerConfig(groupsLoggingToLogcat.toArray(new String[0])));
        }
    }

    /**
     * Responds to a shell command.
     */
    @Deprecated
    public int onShellCommand(ShellCommand shell) {
        PrintWriter pw = shell.getOutPrintWriter();

        if (android.tracing.Flags.clientSideProtoLogging()) {
            pw.println("Command deprecated. Please use 'cmd protolog' instead.");
            return -1;
        }

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
                if (mViewerConfigReader != null) {
                    mViewerConfigReader.loadViewerConfig(groups, logger);
                }
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

    private void log(LogLevel logLevel, IProtoLogGroup group, Message message,
            @Nullable Object[] args) {
        if (isProtoEnabled()) {
            long tsNanos = SystemClock.elapsedRealtimeNanos();
            final String stacktrace;
            if (mCollectStackTraceGroupCounts.getOrDefault(group.name(), 0) > 0) {
                stacktrace = collectStackTrace();
            } else {
                stacktrace = null;
            }
            try {
                mBackgroundServiceLock.lock();
                mBackgroundLoggingService.execute(() ->
                        logToProto(logLevel, group, message, args, tsNanos,
                                stacktrace));
            } finally {
                mBackgroundServiceLock.unlock();
            }
        }
        if (group.isLogToLogcat()) {
            logToLogcat(group.getTag(), logLevel, message, args);
        }
    }

    private void onTracingFlush() {
        Log.d(LOG_TAG, "Executing onTracingFlush");

        final ExecutorService loggingService;
        try {
            mBackgroundServiceLock.lock();
            loggingService = mBackgroundLoggingService;
            mBackgroundLoggingService = Executors.newSingleThreadExecutor();
        } finally {
            mBackgroundServiceLock.unlock();
        }

        try {
            loggingService.shutdown();
            boolean finished = loggingService.awaitTermination(10, TimeUnit.SECONDS);

            if (!finished) {
                Log.e(LOG_TAG, "ProtoLog background tracing service didn't finish gracefully.");
            }
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "Failed to wait for tracing to finish", e);
        }

        if (!android.tracing.Flags.clientSideProtoLogging()) {
            dumpViewerConfig();
        }

        Log.d(LOG_TAG, "Finished onTracingFlush");
    }

    @Deprecated
    private void dumpViewerConfig() {
        if (mViewerConfigInputStreamProvider == null) {
            // No viewer config available
            return;
        }

        Log.d(LOG_TAG, "Dumping viewer config to trace");

        Utils.dumpViewerConfig(mDataSource, mViewerConfigInputStreamProvider);

        Log.d(LOG_TAG, "Dumped viewer config to trace");
    }

    private void logToLogcat(String tag, LogLevel level, Message message,
            @Nullable Object[] args) {
        String messageString;
        if (mViewerConfigReader == null) {
            messageString = message.getMessage();

            if (messageString == null) {
                Log.e(LOG_TAG, "Failed to decode message for logcat. "
                        + "Message not available without ViewerConfig to decode the hash.");
            }
        } else {
            messageString = message.getMessage(mViewerConfigReader);

            if (messageString == null) {
                Log.e(LOG_TAG, "Failed to decode message for logcat. "
                        + "Message hash either not available in viewerConfig file or "
                        + "not loaded into memory from file before decoding.");
            }
        }

        if (messageString == null) {
            StringBuilder builder = new StringBuilder("UNKNOWN MESSAGE");
            if (args != null) {
                builder.append(" args = (");
                builder.append(String.join(", ", Arrays.stream(args)
                        .map(it -> {
                            if (it == null) {
                                return "null";
                            } else {
                                return it.toString();
                            }
                        }).toList()));
                builder.append(")");
            }
            messageString = builder.toString();
            args = new Object[0];
        }

        logToLogcat(tag, level, messageString, args);
    }

    private void logToLogcat(String tag, LogLevel level, String messageString,
            @Nullable Object[] args) {
        String message;
        if (args != null) {
            try {
                message = TextUtils.formatSimple(messageString, args);
            } catch (IllegalArgumentException e) {
                message = "FORMAT_ERROR \"" + messageString + "\", args=("
                        + String.join(
                        ", ", Arrays.stream(args).map(Object::toString).toList()) + ")";
            }
        } else {
            message = messageString;
        }
        passToLogcat(tag, level, message);
    }

    private void logToProto(LogLevel level, IProtoLogGroup logGroup, Message message, Object[] args,
            long tsNanos, @Nullable String stacktrace) {
        mDataSource.trace(ctx -> {
            final ProtoLogDataSource.TlsState tlsState = ctx.getCustomTlsState();
            final LogLevel logFrom = tlsState.getLogFromLevel(logGroup.name());

            if (level.ordinal() < logFrom.ordinal()) {
                return;
            }

            if (args != null) {
                // Intern all string params before creating the trace packet for the proto
                // message so that the interned strings appear before in the trace to make the
                // trace processing easier.
                int argIndex = 0;
                for (Object o : args) {
                    int type = LogDataType.bitmaskToLogDataType(message.getMessageMask(), argIndex);
                    if (type == LogDataType.STRING) {
                        if (o == null) {
                            internStringArg(ctx, NULL_STRING);
                        } else {
                            internStringArg(ctx, o.toString());
                        }
                    }
                    argIndex++;
                }
            }

            int internedStacktrace = 0;
            if (tlsState.getShouldCollectStacktrace(logGroup.name())) {
                // Intern stackstraces before creating the trace packet for the proto message so
                // that the interned stacktrace strings appear before in the trace to make the
                // trace processing easier.
                internedStacktrace = internStacktraceString(ctx, stacktrace);
            }

            boolean needsIncrementalState = false;

            long messageHash = 0;
            if (message.mMessageHash != null) {
                messageHash = message.mMessageHash;
            }
            if (message.mMessageString != null) {
                needsIncrementalState = true;
                messageHash =
                        internProtoMessage(ctx, level, logGroup, message.mMessageString);
            }

            final ProtoOutputStream os = ctx.newTracePacket();
            os.write(TIMESTAMP, tsNanos);
            long token = os.start(PROTOLOG_MESSAGE);

            os.write(MESSAGE_ID, messageHash);

            if (args != null) {

                int argIndex = 0;
                LongArray longParams = new LongArray();
                ArrayList<Double> doubleParams = new ArrayList<>();
                ArrayList<Boolean> booleanParams = new ArrayList<>();
                for (Object o : args) {
                    int type = LogDataType.bitmaskToLogDataType(message.getMessageMask(), argIndex);
                    try {
                        switch (type) {
                            case LogDataType.STRING:
                                final int internedStringId;
                                if (o == null) {
                                    internedStringId = internStringArg(ctx, NULL_STRING);
                                } else {
                                    internedStringId = internStringArg(ctx, o.toString());
                                }
                                os.write(STR_PARAM_IIDS, internedStringId);
                                needsIncrementalState = true;
                                break;
                            case LogDataType.LONG:
                                if (o == null) {
                                    longParams.add(0);
                                } else {
                                    longParams.add(((Number) o).longValue());
                                }
                                break;
                            case LogDataType.DOUBLE:
                                if (o == null) {
                                    doubleParams.add(0d);
                                } else {
                                    doubleParams.add(((Number) o).doubleValue());
                                }
                                break;
                            case LogDataType.BOOLEAN:
                                if (o == null) {
                                    booleanParams.add(false);
                                } else {
                                    booleanParams.add((boolean) o);
                                }
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

            if (tlsState.getShouldCollectStacktrace(logGroup.name())) {
                os.write(STACKTRACE_IID, internedStacktrace);
            }

            os.end(token);

            if (needsIncrementalState) {
                os.write(SEQUENCE_FLAGS, SEQ_NEEDS_INCREMENTAL_STATE);
            }

        });
    }

    private long internProtoMessage(
            TracingContext<ProtoLogDataSource.Instance, ProtoLogDataSource.TlsState,
                    ProtoLogDataSource.IncrementalState> ctx, LogLevel level,
            IProtoLogGroup logGroup, String message) {
        final ProtoLogDataSource.IncrementalState incrementalState = ctx.getIncrementalState();

        if (!incrementalState.clearReported) {
            final ProtoOutputStream os = ctx.newTracePacket();
            os.write(SEQUENCE_FLAGS, SEQ_INCREMENTAL_STATE_CLEARED);
            incrementalState.clearReported = true;
        }


        if (!incrementalState.protologGroupInterningSet.contains(logGroup.getId())) {
            incrementalState.protologGroupInterningSet.add(logGroup.getId());

            final ProtoOutputStream os = ctx.newTracePacket();
            final long protologViewerConfigToken = os.start(PROTOLOG_VIEWER_CONFIG);
            final long groupConfigToken = os.start(GROUPS);

            os.write(ID, logGroup.getId());
            os.write(NAME, logGroup.name());
            os.write(TAG, logGroup.getTag());

            os.end(groupConfigToken);
            os.end(protologViewerConfigToken);
        }

        final Long messageHash = hash(level, logGroup.name(), message);
        if (!incrementalState.protologMessageInterningSet.contains(messageHash)) {
            incrementalState.protologMessageInterningSet.add(messageHash);

            final ProtoOutputStream os = ctx.newTracePacket();

            // Dependent on the ProtoLog viewer config packet that contains the group information.
            os.write(SEQUENCE_FLAGS, SEQ_NEEDS_INCREMENTAL_STATE);

            final long protologViewerConfigToken = os.start(PROTOLOG_VIEWER_CONFIG);
            final long messageConfigToken = os.start(MESSAGES);

            os.write(MessageData.MESSAGE_ID, messageHash);
            os.write(MESSAGE, message);
            os.write(LEVEL, level.id);
            os.write(GROUP_ID, logGroup.getId());

            os.end(messageConfigToken);
            os.end(protologViewerConfigToken);
        }

        return messageHash;
    }

    private Long hash(
            LogLevel logLevel,
            String logGroup,
            String messageString
    ) {
        final String fullStringIdentifier =  messageString + logLevel + logGroup;
        return UUID.nameUUIDFromBytes(fullStringIdentifier.getBytes()).getMostSignificantBits();
    }

    private static final int STACK_SIZE_TO_PROTO_LOG_ENTRY_CALL = 6;

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
            @NonNull String string
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

    private int unknownCommand(PrintWriter pw) {
        pw.println("Unknown command");
        pw.println("Window manager logging options:");
        pw.println("  enable-text [group...]: Enable logcat logging for given groups");
        pw.println("  disable-text [group...]: Disable logcat logging for given groups");
        return -1;
    }

    private synchronized void onTracingInstanceStart(
            int instanceIdx, ProtoLogDataSource.ProtoLogConfig config) {
        Log.d(LOG_TAG, "Executing onTracingInstanceStart");

        final LogLevel defaultLogFrom = config.getDefaultGroupConfig().logFrom;
        for (int i = defaultLogFrom.ordinal(); i < LogLevel.values().length; i++) {
            mDefaultLogLevelCounts[i]++;
        }

        final Set<String> overriddenGroupTags = config.getGroupTagsWithOverriddenConfigs();

        for (String overriddenGroupTag : overriddenGroupTags) {
            mLogLevelCounts.putIfAbsent(overriddenGroupTag, new int[LogLevel.values().length]);
            final int[] logLevelsCountsForGroup = mLogLevelCounts.get(overriddenGroupTag);

            final LogLevel logFromLevel = config.getConfigFor(overriddenGroupTag).logFrom;
            for (int i = logFromLevel.ordinal(); i < LogLevel.values().length; i++) {
                logLevelsCountsForGroup[i]++;
            }

            if (config.getConfigFor(overriddenGroupTag).collectStackTrace) {
                mCollectStackTraceGroupCounts.put(overriddenGroupTag,
                        mCollectStackTraceGroupCounts.getOrDefault(overriddenGroupTag, 0) + 1);
            }

            if (config.getConfigFor(overriddenGroupTag).collectStackTrace) {
                mCollectStackTraceGroupCounts.put(overriddenGroupTag,
                        mCollectStackTraceGroupCounts.getOrDefault(overriddenGroupTag, 0) + 1);
            }
        }

        mCacheUpdater.run();

        this.mTracingInstances.incrementAndGet();

        Log.d(LOG_TAG, "Finished onTracingInstanceStart");
    }

    private synchronized void onTracingInstanceStop(
            int instanceIdx, ProtoLogDataSource.ProtoLogConfig config) {
        Log.d(LOG_TAG, "Executing onTracingInstanceStop");
        this.mTracingInstances.decrementAndGet();

        final LogLevel defaultLogFrom = config.getDefaultGroupConfig().logFrom;
        for (int i = defaultLogFrom.ordinal(); i < LogLevel.values().length; i++) {
            mDefaultLogLevelCounts[i]--;
        }

        final Set<String> overriddenGroupTags = config.getGroupTagsWithOverriddenConfigs();

        for (String overriddenGroupTag : overriddenGroupTags) {
            final int[] logLevelsCountsForGroup = mLogLevelCounts.get(overriddenGroupTag);

            final LogLevel logFromLevel = config.getConfigFor(overriddenGroupTag).logFrom;
            for (int i = logFromLevel.ordinal(); i < LogLevel.values().length; i++) {
                logLevelsCountsForGroup[i]--;
            }
            if (Arrays.stream(logLevelsCountsForGroup).allMatch(it -> it == 0)) {
                mLogLevelCounts.remove(overriddenGroupTag);
            }

            if (config.getConfigFor(overriddenGroupTag).collectStackTrace) {
                mCollectStackTraceGroupCounts.put(overriddenGroupTag,
                        mCollectStackTraceGroupCounts.get(overriddenGroupTag) - 1);

                if (mCollectStackTraceGroupCounts.get(overriddenGroupTag) == 0) {
                    mCollectStackTraceGroupCounts.remove(overriddenGroupTag);
                }
            }
        }

        mCacheUpdater.run();
        Log.d(LOG_TAG, "Finished onTracingInstanceStop");
    }

    private static void logAndPrintln(@Nullable PrintWriter pw, String msg) {
        Slog.i(LOG_TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }

    private static class Message {
        @Nullable
        private final Long mMessageHash;
        private final int mMessageMask;
        @Nullable
        private final String mMessageString;

        private Message(long messageHash, int messageMask) {
            this.mMessageHash = messageHash;
            this.mMessageMask = messageMask;
            this.mMessageString = null;
        }

        private Message(@NonNull String messageString) {
            this.mMessageHash = null;
            final List<Integer> argTypes = LogDataType.parseFormatString(messageString);
            this.mMessageMask = LogDataType.logDataTypesToBitMask(argTypes);
            this.mMessageString = messageString;
        }

        private int getMessageMask() {
            return mMessageMask;
        }

        @Nullable
        private String getMessage() {
            return mMessageString;
        }

        @Nullable
        private String getMessage(@NonNull ProtoLogViewerConfigReader viewerConfigReader) {
            if (mMessageString != null) {
                return mMessageString;
            }

            if (mMessageHash != null) {
                return viewerConfigReader.getViewerString(mMessageHash);
            }

            throw new RuntimeException("Both mMessageString and mMessageHash should never be null");
        }
    }
}

