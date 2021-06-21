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

package com.android.wm.shell.protolog;

import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.BaseProtoLogImpl;
import com.android.internal.protolog.ProtoLogViewerConfigReader;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.wm.shell.R;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

import org.json.JSONException;


/**
 * A service for the ProtoLog logging system.
 */
public class ShellProtoLogImpl extends BaseProtoLogImpl {
    private static final String TAG = "ProtoLogImpl";
    private static final int BUFFER_CAPACITY = 1024 * 1024;
    // TODO: Get the right path for the proto log file when we initialize the shell components
    private static final String LOG_FILENAME = new File("wm_shell_log.pb").getAbsolutePath();

    private static ShellProtoLogImpl sServiceInstance = null;

    static {
        addLogGroupEnum(ShellProtoLogGroup.values());
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
    public static synchronized ShellProtoLogImpl getSingleInstance() {
        if (sServiceInstance == null) {
            sServiceInstance = new ShellProtoLogImpl();
        }
        return sServiceInstance;
    }

    public int startTextLogging(String[] groups, PrintWriter pw) {
        try (InputStream is =
                     getClass().getClassLoader().getResourceAsStream("wm_shell_protolog.json")){
            mViewerConfig.loadViewerConfig(is);
            return setLogging(true /* setTextLogging */, true, pw, groups);
        } catch (IOException e) {
            Log.i(TAG, "Unable to load log definitions: IOException while reading "
                    + "wm_shell_protolog. " + e);
        } catch (JSONException e) {
            Log.i(TAG, "Unable to load log definitions: JSON parsing exception while reading "
                    + "wm_shell_protolog. " + e);
        }
        return -1;
    }

    public int stopTextLogging(String[] groups, PrintWriter pw) {
        return setLogging(true /* setTextLogging */, false, pw, groups);
    }

    private ShellProtoLogImpl() {
        super(new File(LOG_FILENAME), null, BUFFER_CAPACITY, new ProtoLogViewerConfigReader());
    }
}

