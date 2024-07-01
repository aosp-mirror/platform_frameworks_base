/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;

/**
 * ProtoLog API - exposes static logging methods. Usage of this API is similar
 * to {@code android.utils.Log} class. Instead of plain text log messages each call consists of
 * a messageString, which is a format string for the log message (has to be a string literal or
 * a concatenation of string literals) and a vararg array of parameters for the formatter.
 *
 * The syntax for the message string depends on
 * {@link android.text.TextUtils#formatSimple(String, Object...)}}.
 * Supported conversions:
 * %b - boolean
 * %d %x - integral type (Short, Integer or Long)
 * %f - floating point type (Float or Double)
 * %s - string
 * %% - a literal percent character
 * The width and precision modifiers are supported, argument_index and flags are not.
 *
 * Methods in this class are stubs, that are replaced by optimised versions by the ProtoLogTool
 * during build.
 */
public class ProtoLog {

    // Needs to be set directly otherwise the protologtool tries to transform the method call
    public static boolean REQUIRE_PROTOLOGTOOL = true;

    /**
     * DEBUG level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void d(IProtoLogGroup group, String messageString, Object... args) {
        // Stub, replaced by the ProtoLogTool.
        if (REQUIRE_PROTOLOGTOOL) {
            throw new UnsupportedOperationException(
                    "ProtoLog calls MUST be processed with ProtoLogTool");
        }
    }

    /**
     * VERBOSE level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void v(IProtoLogGroup group, String messageString, Object... args) {
        // Stub, replaced by the ProtoLogTool.
        if (REQUIRE_PROTOLOGTOOL) {
            throw new UnsupportedOperationException(
                    "ProtoLog calls MUST be processed with ProtoLogTool");
        }
    }

    /**
     * INFO level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void i(IProtoLogGroup group, String messageString, Object... args) {
        // Stub, replaced by the ProtoLogTool.
        if (REQUIRE_PROTOLOGTOOL) {
            throw new UnsupportedOperationException(
                    "ProtoLog calls MUST be processed with ProtoLogTool");
        }
    }

    /**
     * WARNING level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void w(IProtoLogGroup group, String messageString, Object... args) {
        // Stub, replaced by the ProtoLogTool.
        if (REQUIRE_PROTOLOGTOOL) {
            throw new UnsupportedOperationException(
                    "ProtoLog calls MUST be processed with ProtoLogTool");
        }
    }

    /**
     * ERROR level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void e(IProtoLogGroup group, String messageString, Object... args) {
        // Stub, replaced by the ProtoLogTool.
        if (REQUIRE_PROTOLOGTOOL) {
            throw new UnsupportedOperationException(
                    "ProtoLog calls MUST be processed with ProtoLogTool");
        }
    }

    /**
     * WHAT A TERRIBLE FAILURE level log.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void wtf(IProtoLogGroup group, String messageString, Object... args) {
        // Stub, replaced by the ProtoLogTool.
        if (REQUIRE_PROTOLOGTOOL) {
            throw new UnsupportedOperationException(
                    "ProtoLog calls MUST be processed with ProtoLogTool");
        }
    }

    /**
     * Check if ProtoLog isEnabled for a target group.
     * @param group Group to check enable status of.
     * @return true iff this is being logged.
     */
    public static boolean isEnabled(IProtoLogGroup group, LogLevel level) {
        if (REQUIRE_PROTOLOGTOOL) {
            throw new UnsupportedOperationException(
                    "ProtoLog calls MUST be processed with ProtoLogTool");
        }
        return false;
    }

    /**
     * Get the single ProtoLog instance.
     * @return A singleton instance of ProtoLog.
     */
    public static IProtoLog getSingleInstance() {
        if (REQUIRE_PROTOLOGTOOL) {
            throw new UnsupportedOperationException(
                    "ProtoLog calls MUST be processed with ProtoLogTool");
        }
        return null;
    }
}
