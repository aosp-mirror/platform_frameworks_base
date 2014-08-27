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

import android.annotation.Nullable;
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

    private static final boolean DEBUG = false;

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
        String log = updateLog(mWarningTimingCache, logMessage);
        if (!log.isEmpty()) {
            Slog.w(mTag, log);
        }
    }

    void error(String logMessage) {
        String log = updateLog(mErrorTimingCache, logMessage);
        if (!log.isEmpty()) {
            Slog.e(mTag, log);
        }
    }

    void debug(String logMessage) {
        if (!DEBUG) {
            return;
        }
        Slog.d(mTag, logMessage);
    }

    private static String updateLog(HashMap<String, Pair<Long, Integer>> cache, String logMessage) {
        long curTime = SystemClock.uptimeMillis();
        Pair<Long, Integer> timing = cache.get(logMessage);
        if (shouldLogNow(timing, curTime)) {
            String log = buildMessage(logMessage, timing);
            cache.put(logMessage, new Pair<>(curTime, 1));
            return log;
        } else {
            increaseLogCount(cache, logMessage);
        }
        return "";
    }

    private static String buildMessage(String message, @Nullable Pair<Long, Integer> timing) {
        return new StringBuilder()
                .append("[").append(timing == null ? 1 : timing.second).append("]:")
                .append(message).toString();
    }

    private static void increaseLogCount(HashMap<String, Pair<Long, Integer>> cache,
            String message) {
        Pair<Long, Integer> timing = cache.get(message);
        if (timing != null) {
            cache.put(message, new Pair<>(timing.first, timing.second + 1));
        }
    }

    private static boolean shouldLogNow(@Nullable Pair<Long, Integer> timing, long curTime) {
        return timing == null || curTime - timing.first > ERROR_LOG_DURATTION_MILLIS;
    }
}
