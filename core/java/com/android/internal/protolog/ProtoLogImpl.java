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

import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.IProtoLogGroup;

import java.io.File;

/**
 * A service for the ProtoLog logging system.
 */
public class ProtoLogImpl extends BaseProtoLogImpl {
    private static final int BUFFER_CAPACITY = 1024 * 1024;
    private static final String LOG_FILENAME = "/data/misc/wmtrace/wm_log.winscope";
    private static final String VIEWER_CONFIG_FILENAME = "/system/etc/protolog.conf.json.gz";
    private static final int PER_CHUNK_SIZE = 1024;

    private static ProtoLogImpl sServiceInstance = null;

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

    /** Returns true iff logging is enabled for the given {@code IProtoLogGroup}. */
    public static boolean isEnabled(IProtoLogGroup group) {
        return group.isLogToLogcat()
                || (group.isLogToProto() && getSingleInstance().isProtoEnabled());
    }

    /**
     * Returns the single instance of the ProtoLogImpl singleton class.
     */
    public static synchronized ProtoLogImpl getSingleInstance() {
        if (sServiceInstance == null) {
            sServiceInstance = new ProtoLogImpl(
                    new File(LOG_FILENAME)
                    , BUFFER_CAPACITY
                    , new ProtoLogViewerConfigReader()
                    , PER_CHUNK_SIZE);
        }
        return sServiceInstance;
    }

    @VisibleForTesting
    public static synchronized void setSingleInstance(@Nullable ProtoLogImpl instance) {
        sServiceInstance = instance;
    }

    public ProtoLogImpl(File logFile, int bufferCapacity,
            ProtoLogViewerConfigReader viewConfigReader, int perChunkSize) {
        super(logFile, VIEWER_CONFIG_FILENAME, bufferCapacity, viewConfigReader, perChunkSize);
  }
}

