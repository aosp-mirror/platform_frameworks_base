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

import android.text.TextUtils;
import android.util.Log;

import com.android.internal.protolog.common.ILogger;
import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Class should only be used as a temporary solution to missing viewer config file on device.
 * In particular this class should only be initialized in Robolectric tests, if it's being used
 * otherwise please report it.
 *
 * @deprecated
 */
@Deprecated
public class NoViewerConfigProtoLogImpl implements IProtoLog {
    private static final String LOG_TAG = "ProtoLog";

    @Override
    public void log(LogLevel logLevel, IProtoLogGroup group, long messageHash, int paramsMask,
            Object[] args) {
        Log.w(LOG_TAG, "ProtoLogging is not available due to missing viewer config file...");
        logMessage(logLevel, group.getTag(), "PROTOLOG#" + messageHash + "("
                + Arrays.stream(args).map(Object::toString).collect(Collectors.joining()) + ")");
    }

    @Override
    public void log(LogLevel logLevel, IProtoLogGroup group, String messageString, Object... args) {
        logMessage(logLevel, group.getTag(), TextUtils.formatSimple(messageString, args));
    }

    @Override
    public boolean isProtoEnabled() {
        return false;
    }

    @Override
    public int startLoggingToLogcat(String[] groups, ILogger logger) {
        return 0;
    }

    @Override
    public int stopLoggingToLogcat(String[] groups, ILogger logger) {
        return 0;
    }

    @Override
    public boolean isEnabled(IProtoLogGroup group, LogLevel level) {
        return false;
    }

    @Override
    public List<IProtoLogGroup> getRegisteredGroups() {
        return List.of();
    }

    private void logMessage(LogLevel logLevel, String tag, String message) {
        switch (logLevel) {
            case VERBOSE -> Log.v(tag, message);
            case INFO -> Log.i(tag, message);
            case DEBUG -> Log.d(tag, message);
            case WARN -> Log.w(tag, message);
            case ERROR -> Log.e(tag, message);
            case WTF -> Log.wtf(tag, message);
        }
    }
}
