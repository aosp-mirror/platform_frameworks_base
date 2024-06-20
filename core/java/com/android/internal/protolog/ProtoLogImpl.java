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

import static com.android.internal.protolog.common.ProtoLogToolInjected.Value.CACHE_UPDATER;
import static com.android.internal.protolog.common.ProtoLogToolInjected.Value.LEGACY_OUTPUT_FILE_PATH;
import static com.android.internal.protolog.common.ProtoLogToolInjected.Value.LEGACY_VIEWER_CONFIG_PATH;
import static com.android.internal.protolog.common.ProtoLogToolInjected.Value.LOG_GROUPS;
import static com.android.internal.protolog.common.ProtoLogToolInjected.Value.VIEWER_CONFIG_PATH;

import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;
import com.android.internal.protolog.common.ProtoLogToolInjected;

import java.util.TreeMap;

/**
 * A service for the ProtoLog logging system.
 */
public class ProtoLogImpl {
    private static IProtoLog sServiceInstance = null;

    @ProtoLogToolInjected(VIEWER_CONFIG_PATH)
    private static String sViewerConfigPath;

    @ProtoLogToolInjected(LEGACY_VIEWER_CONFIG_PATH)
    private static String sLegacyViewerConfigPath;

    @ProtoLogToolInjected(LEGACY_OUTPUT_FILE_PATH)
    private static String sLegacyOutputFilePath;

    @ProtoLogToolInjected(LOG_GROUPS)
    private static TreeMap<String, IProtoLogGroup> sLogGroups;

    @ProtoLogToolInjected(CACHE_UPDATER)
    private static Runnable sCacheUpdater;

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void d(IProtoLogGroup group, long messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance()
                .log(LogLevel.DEBUG, group, messageHash, paramsMask, messageString, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void v(IProtoLogGroup group, long messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance().log(LogLevel.VERBOSE, group, messageHash, paramsMask, messageString,
                args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void i(IProtoLogGroup group, long messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance().log(LogLevel.INFO, group, messageHash, paramsMask, messageString, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void w(IProtoLogGroup group, long messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance().log(LogLevel.WARN, group, messageHash, paramsMask, messageString, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void e(IProtoLogGroup group, long messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance()
                .log(LogLevel.ERROR, group, messageHash, paramsMask, messageString, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void wtf(IProtoLogGroup group, long messageHash, int paramsMask,
            @Nullable String messageString,
            Object... args) {
        getSingleInstance().log(LogLevel.WTF, group, messageHash, paramsMask, messageString, args);
    }

    /**
     * Should return true iff we should be logging to either protolog or logcat for this group
     * and log level.
     */
    public static boolean isEnabled(IProtoLogGroup group, LogLevel level) {
        return getSingleInstance().isEnabled(group, level);
    }

    /**
     * Returns the single instance of the ProtoLogImpl singleton class.
     */
    public static synchronized IProtoLog getSingleInstance() {
        if (sServiceInstance == null) {
            if (android.tracing.Flags.perfettoProtologTracing()) {
                sServiceInstance = new PerfettoProtoLogImpl(
                        sViewerConfigPath, sLogGroups, sCacheUpdater);
            } else {
                sServiceInstance = new LegacyProtoLogImpl(
                        sLegacyOutputFilePath, sLegacyViewerConfigPath, sLogGroups, sCacheUpdater);
            }

            sCacheUpdater.run();
        }
        return sServiceInstance;
    }

    @VisibleForTesting
    public static synchronized void setSingleInstance(@Nullable IProtoLog instance) {
        sServiceInstance = instance;
    }
}

