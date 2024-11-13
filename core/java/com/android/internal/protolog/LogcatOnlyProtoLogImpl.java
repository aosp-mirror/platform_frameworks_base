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

import static com.android.internal.protolog.ProtoLog.REQUIRE_PROTOLOGTOOL;

import android.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.protolog.common.ILogger;
import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;

import java.util.Collections;
import java.util.List;

/**
 * Class only create and used to server temporarily for when there is source code pre-processing by
 * the ProtoLog tool, when the tracing to Perfetto flag is off, and the static REQUIRE_PROTOLOGTOOL
 * boolean is false. In which case we simply want to log protolog message to logcat. Note, that this
 * means that in such cases there is no real advantage of using protolog over logcat.
 *
 * @deprecated Should not be used. This is just a temporary class to support a legacy behavior.
 */
@Deprecated
public class LogcatOnlyProtoLogImpl implements IProtoLog {
    private static final String LOG_TAG = LogcatOnlyProtoLogImpl.class.getName();

    @Override
    public void log(LogLevel logLevel, IProtoLogGroup group, long messageHash, int paramsMask,
            Object[] args) {
        throw new RuntimeException("Not supported when using LogcatOnlyProtoLogImpl");
    }

    @Override
    public void log(LogLevel logLevel, IProtoLogGroup group, String messageString, Object[] args) {
        if (REQUIRE_PROTOLOGTOOL && group.isLogToProto()) {
            Log.w(LOG_TAG, "ProtoLog message not processed. Failed to log it to proto. "
                    + "Logging it below to logcat instead.");
        }

        if (group.isLogToLogcat() || group.isLogToProto()) {
            String formattedString = TextUtils.formatSimple(messageString, args);
            switch (logLevel) {
                case VERBOSE -> Log.v(group.getTag(), formattedString);
                case INFO -> Log.i(group.getTag(), formattedString);
                case DEBUG -> Log.d(group.getTag(), formattedString);
                case WARN -> Log.w(group.getTag(), formattedString);
                case ERROR -> Log.e(group.getTag(), formattedString);
                case WTF -> Log.wtf(group.getTag(), formattedString);
            }
        }
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
        return true;
    }

    @Override
    @NonNull
    public List<IProtoLogGroup> getRegisteredGroups() {
        return Collections.emptyList();
    }
}
