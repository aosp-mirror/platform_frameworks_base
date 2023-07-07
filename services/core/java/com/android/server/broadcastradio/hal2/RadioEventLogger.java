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

package com.android.server.broadcastradio.hal2;

import android.util.IndentingPrintWriter;
import android.util.LocalLog;
import android.util.Log;
import android.util.Slog;

final class RadioEventLogger {
    private final String mTag;
    private final LocalLog mEventLogger;

    RadioEventLogger(String tag, int loggerQueueSize) {
        mTag = tag;
        mEventLogger = new LocalLog(loggerQueueSize);
    }

    void logRadioEvent(String logFormat, Object... args) {
        String log = String.format(logFormat, args);
        mEventLogger.log(log);
        if (Log.isLoggable(mTag, Log.DEBUG)) {
            Slog.d(mTag, log);
        }
    }

    void dump(IndentingPrintWriter pw) {
        mEventLogger.dump(pw);
    }
}
