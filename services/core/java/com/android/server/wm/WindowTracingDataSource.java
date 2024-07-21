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

package com.android.server.wm;

import static android.tracing.perfetto.DataSourceParams.PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_STALL_AND_ABORT;

import android.annotation.NonNull;
import android.internal.perfetto.protos.DataSourceConfigOuterClass.DataSourceConfig;
import android.internal.perfetto.protos.WindowmanagerConfig.WindowManagerConfig;
import android.tracing.perfetto.CreateTlsStateArgs;
import android.tracing.perfetto.DataSource;
import android.tracing.perfetto.DataSourceInstance;
import android.tracing.perfetto.DataSourceParams;
import android.tracing.perfetto.InitArguments;
import android.tracing.perfetto.Producer;
import android.tracing.perfetto.StartCallbackArguments;
import android.tracing.perfetto.StopCallbackArguments;
import android.util.Log;
import android.util.proto.ProtoInputStream;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class WindowTracingDataSource extends DataSource<WindowTracingDataSource.Instance,
        WindowTracingDataSource.TlsState, Void> {
    public static final String DATA_SOURCE_NAME = "android.windowmanager";

    public static class TlsState {
        public final Config mConfig;
        public final AtomicBoolean mIsStarting = new AtomicBoolean(true);

        private TlsState(Config config) {
            mConfig = config;
        }
    }

    public static class Config {
        public final @WindowTraceLogLevel int mLogLevel;
        public final boolean mLogOnFrame;

        private Config(@WindowTraceLogLevel int logLevel, boolean logOnFrame) {
            mLogLevel = logLevel;
            mLogOnFrame = logOnFrame;
        }
    }

    public abstract static class Instance extends DataSourceInstance {
        public final Config mConfig;

        public Instance(DataSource dataSource, int instanceIndex, Config config) {
            super(dataSource, instanceIndex);
            mConfig = config;
        }
    }

    private static final Config CONFIG_DEFAULT = new Config(WindowTraceLogLevel.TRIM, true);
    private static final int CONFIG_VALUE_UNSPECIFIED = 0;
    private static final String TAG = "WindowTracingDataSource";

    @NonNull
    private final Consumer<Config> mOnStartCallback;
    @NonNull
    private final Consumer<Config> mOnStopCallback;

    public WindowTracingDataSource(@NonNull Consumer<Config> onStart,
            @NonNull Consumer<Config> onStop) {
        super(DATA_SOURCE_NAME);
        mOnStartCallback = onStart;
        mOnStopCallback = onStop;

        Producer.init(InitArguments.DEFAULTS);
        DataSourceParams params =
                new DataSourceParams.Builder()
                        .setBufferExhaustedPolicy(
                                PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_STALL_AND_ABORT)
                        .build();
        register(params);
    }

    @Override
    public Instance createInstance(ProtoInputStream configStream, int instanceIndex) {
        final Config config = parseDataSourceConfig(configStream);

        return new Instance(this, instanceIndex, config != null ? config : CONFIG_DEFAULT) {
            @Override
            protected void onStart(StartCallbackArguments args) {
                mOnStartCallback.accept(mConfig);
            }

            @Override
            protected void onStop(StopCallbackArguments args) {
                mOnStopCallback.accept(mConfig);
            }
        };
    }

    @Override
    public TlsState createTlsState(
            CreateTlsStateArgs<Instance> args) {
        try (Instance dsInstance = args.getDataSourceInstanceLocked()) {
            if (dsInstance == null) {
                // Datasource instance has been removed
                return new TlsState(CONFIG_DEFAULT);
            }
            return new TlsState(dsInstance.mConfig);
        }
    }

    private Config parseDataSourceConfig(ProtoInputStream stream) {
        try {
            while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                if (stream.getFieldNumber() != (int) DataSourceConfig.WINDOWMANAGER_CONFIG) {
                    continue;
                }
                return parseWindowManagerConfig(stream);
            }
            Log.w(TAG, "Received start request without config parameters. Will use defaults.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse DataSourceConfig", e);
        }
        return null;
    }

    private Config parseWindowManagerConfig(ProtoInputStream stream) {
        int parsedLogLevel = CONFIG_VALUE_UNSPECIFIED;
        int parsedLogFrequency = CONFIG_VALUE_UNSPECIFIED;

        try {
            final long token = stream.start(DataSourceConfig.WINDOWMANAGER_CONFIG);
            while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                switch (stream.getFieldNumber()) {
                    case (int) WindowManagerConfig.LOG_LEVEL:
                        parsedLogLevel = stream.readInt(WindowManagerConfig.LOG_LEVEL);
                        break;
                    case (int) WindowManagerConfig.LOG_FREQUENCY:
                        parsedLogFrequency = stream.readInt(WindowManagerConfig.LOG_FREQUENCY);
                        break;
                    default:
                        Log.w(TAG, "Unrecognized WindowManagerConfig field number: "
                                + stream.getFieldNumber());
                }
            }
            stream.end(token);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse WindowManagerConfig", e);
        }

        @WindowTraceLogLevel int logLevel;
        switch(parsedLogLevel) {
            case CONFIG_VALUE_UNSPECIFIED:
                Log.w(TAG, "Unspecified log level. Defaulting to TRIM");
                logLevel = WindowTraceLogLevel.TRIM;
                break;
            case WindowManagerConfig.LOG_LEVEL_VERBOSE:
                logLevel = WindowTraceLogLevel.ALL;
                break;
            case WindowManagerConfig.LOG_LEVEL_DEBUG:
                logLevel = WindowTraceLogLevel.TRIM;
                break;
            case WindowManagerConfig.LOG_LEVEL_CRITICAL:
                logLevel = WindowTraceLogLevel.CRITICAL;
                break;
            default:
                Log.w(TAG, "Unrecognized log level. Defaulting to TRIM");
                logLevel = WindowTraceLogLevel.TRIM;
                break;
        }

        boolean logOnFrame;
        switch(parsedLogFrequency) {
            case CONFIG_VALUE_UNSPECIFIED:
                Log.w(TAG, "Unspecified log frequency. Defaulting to 'log on frame'");
                logOnFrame = true;
                break;
            case WindowManagerConfig.LOG_FREQUENCY_FRAME:
                logOnFrame = true;
                break;
            case WindowManagerConfig.LOG_FREQUENCY_TRANSACTION:
                logOnFrame = false;
                break;
            default:
                Log.w(TAG, "Unrecognized log frequency. Defaulting to 'log on frame'");
                logOnFrame = true;
                break;
        }

        return new Config(logLevel, logOnFrame);
    }
}
