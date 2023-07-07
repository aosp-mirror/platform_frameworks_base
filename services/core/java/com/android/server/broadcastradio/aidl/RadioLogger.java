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

package com.android.server.broadcastradio.aidl;

import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;
import android.util.Log;

import com.android.server.utils.Slogf;

/**
 * Event logger to log and dump events of radio module and tuner session
 * for AIDL broadcast radio HAL
 */
final class RadioLogger {
    private final String mTag;
    private final boolean mDebug;
    private final LocalLog mEventLogger;

    RadioLogger(String tag, int loggerQueueSize) {
        mTag = tag;
        mDebug = Log.isLoggable(mTag, Log.DEBUG);
        mEventLogger = new LocalLog(loggerQueueSize);
    }

    void logRadioEvent(String logFormat, Object... args) {
        String log = TextUtils.formatSimple(logFormat, args);
        mEventLogger.log(log);
        if (mDebug) {
            Slogf.d(mTag, logFormat, args);
        }
    }

    void dump(IndentingPrintWriter pw) {
        mEventLogger.dump(pw);
    }
}
