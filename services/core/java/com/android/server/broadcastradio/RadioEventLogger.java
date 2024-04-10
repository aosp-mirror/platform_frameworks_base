/**
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.broadcastradio;

import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;

import com.android.server.utils.Slogf;

/**
 * Event logger to log and dump events of broadcast radio service client for HIDL and AIDL
 * broadcast HAL.
 */
public final class RadioEventLogger {
    private final String mTag;
    private final boolean mDebug;
    private final LocalLog mEventLogger;

    public RadioEventLogger(String tag, int loggerQueueSize) {
        mTag = tag;
        mDebug = Log.isLoggable(mTag, Log.DEBUG);
        mEventLogger = new LocalLog(loggerQueueSize);
    }

    /**
     * Log broadcast radio service event
     * @param logFormat String format of log message
     * @param args Arguments of log message
     */
    public void logRadioEvent(String logFormat, Object... args) {
        String log = TextUtils.formatSimple(logFormat, args);
        mEventLogger.log(log);
        if (mDebug) {
            Slogf.d(mTag, logFormat, args);
        }
    }

    /**
     * Dump broadcast radio service event
     * @param pw Indenting print writer for dump
     */
    public void dump(android.util.IndentingPrintWriter pw) {
        mEventLogger.dump(pw);
    }
}
