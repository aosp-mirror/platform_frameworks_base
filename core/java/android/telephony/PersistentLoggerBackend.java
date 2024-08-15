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
 * Interface for logging backends to provide persistent log storage.
 *
 * @hide
 */
public interface PersistentLoggerBackend {

    /**
     * Persist a DEBUG log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    void debug(@NonNull String tag, @NonNull String msg);

    /**
     * Persist a INFO log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    void info(@NonNull String tag, @NonNull String msg);

    /**
     * Persist a WARN log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    void warn(@NonNull String tag, @NonNull String msg);

    /**
     * Persist a WARN log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @param t An exception to log.
     */
    void warn(@NonNull String tag, @NonNull String msg, @NonNull Throwable t);

    /**
     * Persist a ERROR log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    void error(@NonNull String tag, @NonNull String msg);

    /**
     * Persist a ERROR log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @param t An exception to log.
     */
    void error(@NonNull String tag, @NonNull String msg, @NonNull Throwable t);
}
