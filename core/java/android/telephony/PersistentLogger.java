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

package android.telephony;

import android.annotation.NonNull;

/**
 * A persistent logging client. Intended for persisting critical debug logs in situations where
 * standard Android logcat logs may not be retained long enough.
 *
 * @hide
 */
public class PersistentLogger {
    private final PersistentLoggerBackend mPersistentLoggerBackend;

    public PersistentLogger(@NonNull PersistentLoggerBackend persistentLoggerBackend) {
        mPersistentLoggerBackend = persistentLoggerBackend;
    }

    /**
     * Persist a DEBUG log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public void debug(@NonNull String tag, @NonNull String msg) {
        mPersistentLoggerBackend.debug(tag, msg);
    }

    /**
     * Persist a INFO log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public void info(@NonNull String tag, @NonNull String msg) {
        mPersistentLoggerBackend.info(tag, msg);
    }

    /**
     * Persist a WARN log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public void warn(@NonNull String tag, @NonNull String msg) {
        mPersistentLoggerBackend.warn(tag, msg);
    }

    /**
     * Persist a WARN log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @param t An exception to log.
     */
    public void warn(@NonNull String tag, @NonNull String msg, @NonNull Throwable t) {
        mPersistentLoggerBackend.warn(tag, msg, t);
    }

    /**
     * Persist a ERROR log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public void error(@NonNull String tag, @NonNull String msg) {
        mPersistentLoggerBackend.error(tag, msg);
    }

    /**
     * Persist a ERROR log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @param t An exception to log.
     */
    public void error(@NonNull String tag, @NonNull String msg, @NonNull Throwable t) {
        mPersistentLoggerBackend.error(tag, msg, t);
    }
}
