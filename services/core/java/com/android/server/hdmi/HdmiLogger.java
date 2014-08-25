/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import android.os.SystemClock;
import android.util.Pair;
import android.util.Slog;

import java.util.HashMap;

/**
 * A logger that prevents spammy log. For the same log message, it logs once every 20seconds.
 * This class is not thread-safe.
 */
final class HdmiLogger {
    // Logging duration for same error message.
    private static final long ERROR_LOG_DURATTION_MILLIS = 20 * 1000;  // 20s

    // Key (String): log message.
    // Value (Pair(Long, Integer)): a pair of last log time millis and the number of logMessage.
    // Cache for warning.
    private final HashMap<String, Pair<Long, Integer>> mWarningTimingCache = new HashMap<>();
    // Cache for error.
    private final HashMap<String, Pair<Long, Integer>> mErrorTimingCache = new HashMap<>();

    private final String mTag;

    HdmiLogger(String tag) {
        mTag = tag;
    }

    void warning(String logMessage) {
        long curTime = SystemClock.uptimeMillis();
        Pair<Long, Integer> timing = mWarningTimingCache.get(logMessage);
        if (shouldLogNow(timing, curTime)) {
            Slog.w(mTag, buildMessage(logMessage, timing, curTime));
            mWarningTimingCache.put(logMessage, new Pair<>(curTime, 1));
        } else {
            increaseLogCount(mWarningTimingCache, logMessage);
        }
    }

    void error(String logMessage) {
        long curTime = SystemClock.uptimeMillis();
        Pair<Long, Integer> timing = mErrorTimingCache.get(logMessage);
        if (shouldLogNow(timing, curTime)) {
            Slog.e(mTag, buildMessage(logMessage, timing, curTime));
            mErrorTimingCache.put(logMessage, new Pair<>(curTime, 1));
        } else {
            increaseLogCount(mErrorTimingCache, logMessage);
        }
    }

    private String buildMessage(String message, Pair<Long, Integer> timing, long curTime) {
        return new StringBuilder()
            .append("[").append(timing == null ? curTime : timing.second).append("]:")
            .append(message).toString();
    }

    private void increaseLogCount(HashMap<String, Pair<Long, Integer>> cache, String message) {
        Pair<Long, Integer> timing = cache.get(message);
        if (timing != null) {
            cache.put(message, new Pair<>(timing.first, timing.second + 1));
        }
    }

    private boolean shouldLogNow(Pair<Long, Integer> timing, long curTime) {
        return timing == null || curTime - timing.first > ERROR_LOG_DURATTION_MILLIS;
    }
}
