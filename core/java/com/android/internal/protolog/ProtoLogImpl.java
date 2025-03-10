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
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;
import com.android.internal.protolog.common.ProtoLogToolInjected;

import java.io.File;
import java.util.TreeMap;

/**
 * A service for the ProtoLog logging system.
 */
public class ProtoLogImpl {
    private static final String LOG_TAG = "ProtoLogImpl";

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
    private static ProtoLogCacheUpdater sCacheUpdater;

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void d(IProtoLogGroup group, long messageHash, int paramsMask, Object... args) {
        getSingleInstance().log(LogLevel.DEBUG, group, messageHash, paramsMask, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void v(IProtoLogGroup group, long messageHash, int paramsMask, Object... args) {
        getSingleInstance().log(LogLevel.VERBOSE, group, messageHash, paramsMask, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void i(IProtoLogGroup group, long messageHash, int paramsMask, Object... args) {
        getSingleInstance().log(LogLevel.INFO, group, messageHash, paramsMask, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void w(IProtoLogGroup group, long messageHash, int paramsMask, Object... args) {
        getSingleInstance().log(LogLevel.WARN, group, messageHash, paramsMask, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void e(IProtoLogGroup group, long messageHash, int paramsMask, Object... args) {
        getSingleInstance().log(LogLevel.ERROR, group, messageHash, paramsMask, args);
    }

    /** Used by the ProtoLogTool, do not call directly - use {@code ProtoLog} class instead. */
    public static void wtf(IProtoLogGroup group, long messageHash, int paramsMask, Object... args) {
        getSingleInstance().log(LogLevel.WTF, group, messageHash, paramsMask, args);
    }

    /**
     * Should return true iff we should be logging to either protolog or logcat for this group
     * and log level.
     */
    public static boolean isEnabled(IProtoLogGroup group, LogLevel level) {
        return isEnabled(getSingleInstance(), group, level);
    }

    private static boolean isEnabled(
            IProtoLog protoLogInstance, IProtoLogGroup group, LogLevel level) {
        return protoLogInstance.isEnabled(group, level);
    }

    /**
     * Returns the single instance of the ProtoLogImpl singleton class.
     */
    public static synchronized IProtoLog getSingleInstance() {
        if (sServiceInstance == null) {
            Log.i(LOG_TAG, "Setting up " + ProtoLogImpl.class.getSimpleName() + " with "
                    + "viewerConfigPath = " + sViewerConfigPath);

            final var groups = sLogGroups.values().toArray(new IProtoLogGroup[0]);
            if (android.tracing.Flags.perfettoProtologTracing()) {
                var viewerConfigFile = new File(sViewerConfigPath);
                if (!viewerConfigFile.exists()) {
                    // TODO(b/353530422): Remove - temporary fix to unblock b/352290057
                    // In robolectric tests the viewer config file isn't current available, so we
                    // cannot use the ProcessedPerfettoProtoLogImpl.
                    Log.e(LOG_TAG, "Failed to find viewer config file " + sViewerConfigPath
                            + " when setting up " + ProtoLogImpl.class.getSimpleName() + ". "
                            + "ProtoLog will not work here!");

                    sServiceInstance = new NoViewerConfigProtoLogImpl();
                } else {
                    var datasource = ProtoLog.getSharedSingleInstanceDataSource();
                    try {
                        var processedProtoLogImpl =
                                new ProcessedPerfettoProtoLogImpl(datasource, sViewerConfigPath,
                                        sCacheUpdater, groups);
                        sServiceInstance = processedProtoLogImpl;
                        processedProtoLogImpl.enable();
                    } catch (ServiceManager.ServiceNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
            } else {
                sServiceInstance = createLegacyProtoLogImpl(groups);
            }

            sCacheUpdater.update(sServiceInstance);
        }
        return sServiceInstance;
    }

    private static LegacyProtoLogImpl createLegacyProtoLogImpl(IProtoLogGroup[] groups) {
        var protologImpl = new LegacyProtoLogImpl(
                sLegacyOutputFilePath, sLegacyViewerConfigPath, sCacheUpdater);
        protologImpl.registerGroups(groups);

        return protologImpl;
    }

    @VisibleForTesting
    public static synchronized void setSingleInstance(@Nullable IProtoLog instance) {
        sServiceInstance = instance;
    }
}

