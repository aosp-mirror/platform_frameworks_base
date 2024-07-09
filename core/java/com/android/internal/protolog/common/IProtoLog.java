/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.protolog.common;

/**
 * Interface for ProtoLog implementations.
 */
public interface IProtoLog {

    /**
     * Log a ProtoLog message
     * @param logLevel Log level of the proto message.
     * @param group The group this message belongs to.
     * @param messageHash The hash of the message.
     * @param paramsMask The parameters mask of the message.
     * @param messageString The message string.
     * @param args The arguments of the message.
     */
    void log(LogLevel logLevel, IProtoLogGroup group, long messageHash, int paramsMask,
             String messageString, Object[] args);

    /**
     * Check if ProtoLog is tracing.
     * @return true iff a ProtoLog tracing session is active.
     */
    boolean isProtoEnabled();

    /**
     * Start logging log groups to logcat
     * @param groups Groups to start text logging for
     * @return status code
     */
    int startLoggingToLogcat(String[] groups, ILogger logger);

    /**
     * Stop logging log groups to logcat
     * @param groups Groups to start text logging for
     * @return status code
     */
    int stopLoggingToLogcat(String[] groups, ILogger logger);

    /**
     * Should return true iff logging is enabled to ProtoLog or to Logcat for this group and level.
     * @param group ProtoLog group to check for.
     * @param level ProtoLog level to check for.
     * @return If we need to log this group and level to either ProtoLog or Logcat.
     */
    boolean isEnabled(IProtoLogGroup group, LogLevel level);
}
