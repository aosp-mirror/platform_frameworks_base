/**
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.accessibility;

/**
 * Interface to log accessibility trace.
 */
public interface AccessibilityTrace {
    /**
     * Whether the trace is enabled.
     */
    boolean isA11yTracingEnabled();

    /**
     * Log one trace entry.
     * @param where A string to identify this log entry, which can be used to filter/search
     *        through the tracing file.
     */
    void logTrace(String where);

    /**
     * Log one trace entry.
     * @param where A string to identify this log entry, which can be used to filter/search
     *        through the tracing file.
     * @param callingParams The parameters for the method to be logged.
     */
    void logTrace(String where, String callingParams);
}
