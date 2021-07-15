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
     * Start tracing.
     */
    void startTrace();

    /**
     * Stop tracing.
     */
    void stopTrace();

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

    /**
     * Log one trace entry. Accessibility services using AccessibilityInteractionClient to
     * make screen content related requests use this API to log entry when receive callback.
     * @param timestamp The timestamp when a callback is received.
     * @param where A string to identify this log entry, which can be used to filter/search
     *        through the tracing file.
     * @param callingParams The parameters for the callback.
     * @param processId The process id of the calling component.
     * @param threadId The threadId of the calling component.
     * @param callingUid The calling uid of the callback.
     * @param callStack The call stack of the callback.
     */
    void logTrace(long timestamp, String where, String callingParams, int processId,
            long threadId, int callingUid, StackTraceElement[] callStack);
}
