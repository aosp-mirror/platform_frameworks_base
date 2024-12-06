/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.os.ServiceManager;
import android.tracing.perfetto.DataSourceParams;
import android.tracing.perfetto.InitArguments;
import android.tracing.perfetto.Producer;

import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;

import java.util.Arrays;
import java.util.HashSet;

/**
 * ProtoLog API - exposes static logging methods. Usage of this API is similar
 * to {@code android.utils.Log} class. Instead of plain text log messages each call consists of
 * a messageString, which is a format string for the log message (has to be a string literal or
 * a concatenation of string literals) and a vararg array of parameters for the formatter.
 *
 * The syntax for the message string depends on
 * {@link android.text.TextUtils#formatSimple(String, Object...)}}.
 * Supported conversions:
 * %b - boolean
 * %d %x - integral type (Short, Integer or Long)
 * %f - floating point type (Float or Double)
 * %s - string
 * %% - a literal percent character
 * The width and precision modifiers are supported, argument_index and flags are not.
 *
 * Methods in this class are stubs, that are replaced by optimised versions by the ProtoLogTool
 * during build.
 */
// LINT.IfChange
public class ProtoLog {
// LINT.ThenChange(frameworks/base/tools/protologtool/src/com/android/protolog/tool/ProtoLogTool.kt)

    // Needs to be set directly otherwise the protologtool tries to transform the method call
    @Deprecated
    public static boolean REQUIRE_PROTOLOGTOOL = true;

    private static IProtoLog sProtoLogInstance;

    private static ProtoLogDataSource sDataSource;

    private static final Object sInitLock = new Object();

    /**
     * Initialize ProtoLog in this process.
     * <p>
     * This method MUST be called before any protologging is performed in this process.
     * Ensure that all groups that will be used for protologging are registered.
     *
     * @param groups The ProtoLog groups that will be used in the process.
     */
    public static void init(IProtoLogGroup... groups) {
        // These tracing instances are only used when we cannot or do not preprocess the source
        // files to extract out the log strings. Otherwise, the trace calls are replaced with calls
        // directly to the generated tracing implementations.
        if (android.tracing.Flags.perfettoProtologTracing()) {
            initializePerfettoProtoLog(groups);
        } else {
            sProtoLogInstance = new LogcatOnlyProtoLogImpl();
        }
    }

    private static void initializePerfettoProtoLog(IProtoLogGroup... groups) {
        var datasource = getSharedSingleInstanceDataSource();

        synchronized (sInitLock) {
            final var allGroups = new HashSet<>(Arrays.stream(groups).toList());
            final var previousProtoLogImpl = sProtoLogInstance;
            if (previousProtoLogImpl != null) {
                // The ProtoLog instance has already been initialized in this process
                final var alreadyRegisteredGroups = previousProtoLogImpl.getRegisteredGroups();
                allGroups.addAll(alreadyRegisteredGroups);
            }

            sProtoLogInstance = createAndEnableNewPerfettoProtoLogImpl(
                    datasource, allGroups.toArray(new IProtoLogGroup[0]));
            if (previousProtoLogImpl instanceof PerfettoProtoLogImpl) {
                ((PerfettoProtoLogImpl) previousProtoLogImpl).disable();
            }
        }
    }

    private static PerfettoProtoLogImpl createAndEnableNewPerfettoProtoLogImpl(
            ProtoLogDataSource datasource, IProtoLogGroup[] groups) {
        try {
            var unprocessedPerfettoProtoLogImpl =
                    new UnprocessedPerfettoProtoLogImpl(datasource, groups);
            unprocessedPerfettoProtoLogImpl.enable();

            return unprocessedPerfettoProtoLogImpl;
        } catch (ServiceManager.ServiceNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * DEBUG level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     *
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     */
    public static void d(IProtoLogGroup group, String messageString, Object... args) {
        logStringMessage(LogLevel.DEBUG, group, messageString, args);
    }

    /**
     * VERBOSE level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     *
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     */
    public static void v(IProtoLogGroup group, String messageString, Object... args) {
        logStringMessage(LogLevel.VERBOSE, group, messageString, args);
    }

    /**
     * INFO level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     *
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     */
    public static void i(IProtoLogGroup group, String messageString, Object... args) {
        logStringMessage(LogLevel.INFO, group, messageString, args);
    }

    /**
     * WARNING level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     *
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     */
    public static void w(IProtoLogGroup group, String messageString, Object... args) {
        logStringMessage(LogLevel.WARN, group, messageString, args);
    }

    /**
     * ERROR level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     *
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     */
    public static void e(IProtoLogGroup group, String messageString, Object... args) {
        logStringMessage(LogLevel.ERROR, group, messageString, args);
    }

    /**
     * WHAT A TERRIBLE FAILURE level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     *
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     */
    public static void wtf(IProtoLogGroup group, String messageString, Object... args) {
        logStringMessage(LogLevel.WTF, group, messageString, args);
    }

    /**
     * Check if ProtoLog isEnabled for a target group.
     * @param group Group to check enable status of.
     * @return true iff this is being logged.
     */
    public static boolean isEnabled(IProtoLogGroup group, LogLevel level) {
        return sProtoLogInstance.isEnabled(group, level);
    }

    /**
     * Get the single ProtoLog instance.
     * @return A singleton instance of ProtoLog.
     */
    public static IProtoLog getSingleInstance() {
        return sProtoLogInstance;
    }

    /**
     * Gets or creates if it doesn't exist yet the protolog datasource to use in this process.
     * We should re-use the same datasource to avoid registering the datasource multiple times in
     * the same process, since there is no way to unregister the datasource after registration.
     *
     * @return The single ProtoLog datasource instance to be shared across all ProtoLog tracing
     *         objects.
     */
    public static synchronized ProtoLogDataSource getSharedSingleInstanceDataSource() {
        if (sDataSource == null) {
            Producer.init(InitArguments.DEFAULTS);
            sDataSource = new ProtoLogDataSource();
            DataSourceParams params =
                    new DataSourceParams.Builder()
                            .setBufferExhaustedPolicy(
                                    DataSourceParams
                                            .PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_DROP)
                            .build();
            // NOTE: Registering that datasource is an async operation, so there may be no data
            // traced for some messages logged right after the construction of this class.
            sDataSource.register(params);
        }

        return sDataSource;
    }

    private static void logStringMessage(LogLevel logLevel, IProtoLogGroup group,
            String stringMessage, Object... args) {
        if (sProtoLogInstance == null) {
            throw new IllegalStateException(
                    "Trying to use ProtoLog before it is initialized in this process.");
        }

        if (sProtoLogInstance.isEnabled(group, logLevel)) {
            sProtoLogInstance.log(logLevel, group, stringMessage, args);
        }
    }
}
