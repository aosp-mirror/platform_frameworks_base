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

package com.android.internal.protolog.common;

/**
 * Defines a log group configuration object for ProtoLog. Should be implemented as en enum.
 */
public interface IProtoLogGroup {
    /**
     * if false all log statements for this group are excluded from compilation,
     */
    boolean isEnabled();

    /**
     * @deprecated TODO(b/324128613) remove once we migrate fully to Perfetto
     * is binary logging enabled for the group.
     */
    boolean isLogToProto();

    /**
     * is text logging enabled for the group.
     */
    boolean isLogToLogcat();

    /**
     * returns true is any logging is enabled for this group.
     * @deprecated TODO(b/324128613) remove once we migrate fully to Perfetto
     */
    default boolean isLogToAny() {
        return isLogToLogcat() || isLogToProto();
    }

    /**
     * returns the name of the source of the logged message
     */
    String getTag();

    /**
     * set binary logging for this group.
     * @deprecated TODO(b/324128613) remove once we migrate fully to Perfetto
     */
    void setLogToProto(boolean logToProto);

    /**
     * set text logging for this group.
     */
    void setLogToLogcat(boolean logToLogcat);

    /**
     * returns name of the logging group.
     */
    String name();

    /**
     * returns the id of the logging group (unique for each group).
     */
    int getId();
}
