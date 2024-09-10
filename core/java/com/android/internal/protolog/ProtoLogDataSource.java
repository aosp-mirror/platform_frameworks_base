/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.internal.perfetto.protos.ProtologConfig.ProtoLogConfig.DEFAULT;
import static android.internal.perfetto.protos.ProtologConfig.ProtoLogConfig.DEFAULT_LOG_FROM_LEVEL;
import static android.internal.perfetto.protos.ProtologConfig.ProtoLogConfig.ENABLE_ALL;
import static android.internal.perfetto.protos.ProtologConfig.ProtoLogConfig.GROUP_OVERRIDES;
import static android.internal.perfetto.protos.ProtologConfig.ProtoLogConfig.TRACING_MODE;
import static android.internal.perfetto.protos.ProtologConfig.ProtoLogGroup.COLLECT_STACKTRACE;
import static android.internal.perfetto.protos.ProtologConfig.ProtoLogGroup.GROUP_NAME;
import static android.internal.perfetto.protos.ProtologConfig.ProtoLogGroup.LOG_FROM;

import android.annotation.NonNull;
import android.internal.perfetto.protos.DataSourceConfigOuterClass.DataSourceConfig;
import android.internal.perfetto.protos.ProtologCommon;
import android.tracing.perfetto.CreateIncrementalStateArgs;
import android.tracing.perfetto.CreateTlsStateArgs;
import android.tracing.perfetto.DataSource;
import android.tracing.perfetto.DataSourceInstance;
import android.tracing.perfetto.FlushCallbackArguments;
import android.tracing.perfetto.StartCallbackArguments;
import android.tracing.perfetto.StopCallbackArguments;
import android.util.proto.ProtoInputStream;
import android.util.proto.WireTypeMismatchException;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.LogLevel;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ProtoLogDataSource extends DataSource<ProtoLogDataSource.Instance,
        ProtoLogDataSource.TlsState,
        ProtoLogDataSource.IncrementalState> {
    private static final String DATASOURCE_NAME = "android.protolog";

    @NonNull
    private final Instance.TracingInstanceStartCallback mOnStart;
    @NonNull
    private final Runnable mOnFlush;
    @NonNull
    private final Instance.TracingInstanceStopCallback mOnStop;

    public ProtoLogDataSource(
            @NonNull Instance.TracingInstanceStartCallback onStart,
            @NonNull Runnable onFlush,
            @NonNull Instance.TracingInstanceStopCallback onStop) {
        this(onStart, onFlush, onStop, DATASOURCE_NAME);
    }

    @VisibleForTesting
    public ProtoLogDataSource(
            @NonNull Instance.TracingInstanceStartCallback onStart,
            @NonNull Runnable onFlush,
            @NonNull Instance.TracingInstanceStopCallback onStop,
            @NonNull String dataSourceName) {
        super(dataSourceName);
        this.mOnStart = onStart;
        this.mOnFlush = onFlush;
        this.mOnStop = onStop;
    }

    @Override
    @NonNull
    public Instance createInstance(@NonNull ProtoInputStream configStream, int instanceIndex) {
        ProtoLogConfig config = null;

        try {
            while (configStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                try {
                    if (configStream.getFieldNumber() == (int) DataSourceConfig.PROTOLOG_CONFIG) {
                        if (config != null) {
                            throw new RuntimeException("ProtoLog config already set in loop");
                        }
                        config = readProtoLogConfig(configStream);
                    }
                } catch (WireTypeMismatchException e) {
                    throw new RuntimeException("Failed to parse ProtoLog DataSource config", e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read ProtoLog DataSource config", e);
        }

        if (config == null) {
            // No config found
            config = ProtoLogConfig.DEFAULT;
        }

        return new Instance(
                this, instanceIndex, config, mOnStart, mOnFlush, mOnStop);
    }

    @Override
    @NonNull
    public TlsState createTlsState(@NonNull CreateTlsStateArgs<Instance> args) {
        try (Instance dsInstance = args.getDataSourceInstanceLocked()) {
            if (dsInstance == null) {
                // Datasource instance has been removed
                return new TlsState(ProtoLogConfig.DEFAULT);
            }
            return new TlsState(dsInstance.mConfig);
        }
    }

    @Override
    @NonNull
    public IncrementalState createIncrementalState(
            @NonNull CreateIncrementalStateArgs<Instance> args) {
        return new IncrementalState();
    }

    public static class TlsState {
        @NonNull
        private final ProtoLogConfig mConfig;

        private TlsState(@NonNull ProtoLogConfig config) {
            this.mConfig = config;
        }

        /**
         * Get the log from level for a group.
         * @param groupTag The tag of the group to get the log from level.
         * @return The lowest LogLevel (inclusive) to log message from.
         */
        public LogLevel getLogFromLevel(String groupTag) {
            return getConfigFor(groupTag).logFrom;
        }

        /**
         * Get if the stacktrace for the log message should be collected for this group.
         * @param groupTag The tag of the group to get whether or not a stacktrace was requested.
         * @return True iff a stacktrace was requested to be collected from this group in the
         *         tracing config.
         */
        public boolean getShouldCollectStacktrace(String groupTag) {
            return getConfigFor(groupTag).collectStackTrace;
        }

        private GroupConfig getConfigFor(String groupTag) {
            return mConfig.getConfigFor(groupTag);
        }
    }

    public static class IncrementalState {
        public final Set<Integer> protologGroupInterningSet = new HashSet<>();
        public final Set<Long> protologMessageInterningSet = new HashSet<>();
        public final Map<String, Integer> argumentInterningMap = new HashMap<>();
        public final Map<String, Integer> stacktraceInterningMap = new HashMap<>();
        public boolean clearReported = false;
    }

    public static class ProtoLogConfig {
        private final LogLevel mDefaultLogFromLevel;
        private final Map<String, GroupConfig> mGroupConfigs;

        private static final ProtoLogConfig DEFAULT =
                new ProtoLogConfig(LogLevel.WTF, new HashMap<>());

        private ProtoLogConfig(
                LogLevel defaultLogFromLevel, Map<String, GroupConfig> groupConfigs) {
            this.mDefaultLogFromLevel = defaultLogFromLevel;
            this.mGroupConfigs = groupConfigs;
        }

        public GroupConfig getConfigFor(String groupTag) {
            return mGroupConfigs.getOrDefault(groupTag, getDefaultGroupConfig());
        }

        public GroupConfig getDefaultGroupConfig() {
            return new GroupConfig(mDefaultLogFromLevel, false);
        }

        public Set<String> getGroupTagsWithOverriddenConfigs() {
            return mGroupConfigs.keySet();
        }
    }

    public static class GroupConfig {
        public final LogLevel logFrom;
        public final boolean collectStackTrace;

        public GroupConfig(LogLevel logFromLevel, boolean collectStackTrace) {
            this.logFrom = logFromLevel;
            this.collectStackTrace = collectStackTrace;
        }
    }

    private ProtoLogConfig readProtoLogConfig(ProtoInputStream configStream)
            throws IOException {
        final long config_token = configStream.start(DataSourceConfig.PROTOLOG_CONFIG);

        LogLevel defaultLogFromLevel = LogLevel.WTF;
        final Map<String, GroupConfig> groupConfigs = new HashMap<>();

        while (configStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (configStream.getFieldNumber()) {
                case (int) DEFAULT_LOG_FROM_LEVEL:
                    int defaultLogFromLevelInt = configStream.readInt(DEFAULT_LOG_FROM_LEVEL);
                    if (defaultLogFromLevelInt < defaultLogFromLevel.ordinal()) {
                        defaultLogFromLevel =
                                logLevelFromInt(defaultLogFromLevelInt);
                    }
                    break;
                case (int) TRACING_MODE:
                    int tracingMode = configStream.readInt(TRACING_MODE);
                    switch (tracingMode) {
                        case DEFAULT:
                            break;
                        case ENABLE_ALL:
                            defaultLogFromLevel = LogLevel.DEBUG;
                            break;
                        default:
                            throw new RuntimeException("Unhandled ProtoLog tracing mode type");
                    }
                    break;
                case (int) GROUP_OVERRIDES:
                    final long group_overrides_token  = configStream.start(GROUP_OVERRIDES);

                    String tag = null;
                    LogLevel logFromLevel = defaultLogFromLevel;
                    boolean collectStackTrace = false;
                    while (configStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                        if (configStream.getFieldNumber() == (int) GROUP_NAME) {
                            tag = configStream.readString(GROUP_NAME);
                        }
                        if (configStream.getFieldNumber() == (int) LOG_FROM) {
                            final int logFromInt = configStream.readInt(LOG_FROM);
                            logFromLevel = logLevelFromInt(logFromInt);
                        }
                        if (configStream.getFieldNumber() == (int) COLLECT_STACKTRACE) {
                            collectStackTrace = configStream.readBoolean(COLLECT_STACKTRACE);
                        }
                    }

                    if (tag == null) {
                        throw new RuntimeException("Failed to decode proto config. "
                                + "Got a group override without a group tag.");
                    }

                    groupConfigs.put(tag, new GroupConfig(logFromLevel, collectStackTrace));

                    configStream.end(group_overrides_token);
                    break;
            }
        }

        configStream.end(config_token);

        return new ProtoLogConfig(defaultLogFromLevel, groupConfigs);
    }

    private LogLevel logLevelFromInt(int logFromInt) {
        return switch (logFromInt) {
            case (ProtologCommon.PROTOLOG_LEVEL_DEBUG) -> LogLevel.DEBUG;
            case (ProtologCommon.PROTOLOG_LEVEL_VERBOSE) -> LogLevel.VERBOSE;
            case (ProtologCommon.PROTOLOG_LEVEL_INFO) -> LogLevel.INFO;
            case (ProtologCommon.PROTOLOG_LEVEL_WARN) -> LogLevel.WARN;
            case (ProtologCommon.PROTOLOG_LEVEL_ERROR) -> LogLevel.ERROR;
            case (ProtologCommon.PROTOLOG_LEVEL_WTF) -> LogLevel.WTF;
            default -> throw new RuntimeException("Unhandled log level");
        };
    }

    public static class Instance extends DataSourceInstance {

        public interface TracingInstanceStartCallback {
            /**
             * Execute the tracing instance's onStart callback.
             * @param instanceIdx The index of the tracing instance we are executing the callback
             *                    for.
             * @param config The protolog configuration for the tracing instance we are executing
             *               the callback for.
             */
            void run(int instanceIdx, @NonNull ProtoLogConfig config);
        }

        public interface TracingInstanceStopCallback {
            /**
             * Execute the tracing instance's onStop callback.
             * @param instanceIdx The index of the tracing instance we are executing the callback
             *                    for.
             * @param config The protolog configuration for the tracing instance we are executing
             *               the callback for.
             */
            void run(int instanceIdx, @NonNull ProtoLogConfig config);
        }

        @NonNull
        private final TracingInstanceStartCallback mOnStart;
        @NonNull
        private final Runnable mOnFlush;
        @NonNull
        private final TracingInstanceStopCallback mOnStop;
        @NonNull
        private final ProtoLogConfig mConfig;
        private final int mInstanceIndex;

        public Instance(
                @NonNull DataSource<Instance, TlsState, IncrementalState> dataSource,
                int instanceIdx,
                @NonNull ProtoLogConfig config,
                @NonNull TracingInstanceStartCallback onStart,
                @NonNull Runnable onFlush,
                @NonNull TracingInstanceStopCallback onStop
        ) {
            super(dataSource, instanceIdx);
            this.mInstanceIndex = instanceIdx;
            this.mOnStart = onStart;
            this.mOnFlush = onFlush;
            this.mOnStop = onStop;
            this.mConfig = config;
        }

        @Override
        public void onStart(StartCallbackArguments args) {
            this.mOnStart.run(this.mInstanceIndex, this.mConfig);
        }

        @Override
        public void onFlush(FlushCallbackArguments args) {
            this.mOnFlush.run();
        }

        @Override
        public void onStop(StopCallbackArguments args) {
            this.mOnStop.run(this.mInstanceIndex, this.mConfig);
        }
    }
}
