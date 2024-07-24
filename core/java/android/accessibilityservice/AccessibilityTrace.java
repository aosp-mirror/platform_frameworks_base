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
package android.accessibilityservice;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface to log accessibility trace.
 *
 * @hide
 */
public interface AccessibilityTrace {
    String NAME_ACCESSIBILITY_SERVICE_CONNECTION = "IAccessibilityServiceConnection";
    String NAME_ACCESSIBILITY_SERVICE_CLIENT = "IAccessibilityServiceClient";
    String NAME_ACCESSIBILITY_MANAGER = "IAccessibilityManager";
    String NAME_ACCESSIBILITY_MANAGER_CLIENT = "IAccessibilityManagerClient";
    String NAME_ACCESSIBILITY_INTERACTION_CONNECTION = "IAccessibilityInteractionConnection";
    String NAME_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK =
            "IAccessibilityInteractionConnectionCallback";
    String NAME_REMOTE_MAGNIFICATION_ANIMATION_CALLBACK = "IRemoteMagnificationAnimationCallback";
    String NAME_MAGNIFICATION_CONNECTION = "IMagnificationConnection";
    String NAME_MAGNIFICATION_CONNECTION_CALLBACK = "IMagnificationConnectionCallback";
    String NAME_WINDOW_MANAGER_INTERNAL = "WindowManagerInternal";
    String NAME_WINDOWS_FOR_ACCESSIBILITY_CALLBACK = "WindowsForAccessibilityCallback";
    String NAME_MAGNIFICATION_CALLBACK = "MagnificationCallbacks";
    String NAME_INPUT_FILTER = "InputFilter";
    String NAME_GESTURE = "Gesture";
    String NAME_ACCESSIBILITY_SERVICE = "AccessibilityService";
    String NAME_PACKAGE_BROADCAST_RECEIVER = "PMBroadcastReceiver";
    String NAME_USER_BROADCAST_RECEIVER = "UserBroadcastReceiver";
    String NAME_FINGERPRINT = "FingerprintGesture";
    String NAME_ACCESSIBILITY_INTERACTION_CLIENT = "AccessibilityInteractionClient";

    String NAME_ALL_LOGGINGS = "AllLoggings";
    String NAME_NONE = "None";

    long FLAGS_ACCESSIBILITY_SERVICE_CONNECTION = 0x0000000000000001L;
    long FLAGS_ACCESSIBILITY_SERVICE_CLIENT = 0x0000000000000002L;
    long FLAGS_ACCESSIBILITY_MANAGER = 0x0000000000000004L;
    long FLAGS_ACCESSIBILITY_MANAGER_CLIENT = 0x0000000000000008L;
    long FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION = 0x0000000000000010L;
    long FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK = 0x0000000000000020L;
    long FLAGS_REMOTE_MAGNIFICATION_ANIMATION_CALLBACK = 0x0000000000000040L;
    long FLAGS_MAGNIFICATION_CONNECTION = 0x0000000000000080L;
    long FLAGS_MAGNIFICATION_CONNECTION_CALLBACK = 0x0000000000000100L;
    long FLAGS_WINDOW_MANAGER_INTERNAL = 0x0000000000000200L;
    long FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK = 0x0000000000000400L;
    long FLAGS_MAGNIFICATION_CALLBACK = 0x0000000000000800L;
    long FLAGS_INPUT_FILTER = 0x0000000000001000L;
    long FLAGS_GESTURE = 0x0000000000002000L;
    long FLAGS_ACCESSIBILITY_SERVICE = 0x0000000000004000L;
    long FLAGS_PACKAGE_BROADCAST_RECEIVER = 0x0000000000008000L;
    long FLAGS_USER_BROADCAST_RECEIVER = 0x0000000000010000L;
    long FLAGS_FINGERPRINT = 0x0000000000020000L;
    long FLAGS_ACCESSIBILITY_INTERACTION_CLIENT = 0x0000000000040000L;

    long FLAGS_LOGGING_NONE = 0x0000000000000000L;
    long FLAGS_LOGGING_ALL = 0xFFFFFFFFFFFFFFFFL;

    long FLAGS_ACCESSIBILITY_MANAGER_CLIENT_STATES = FLAGS_ACCESSIBILITY_INTERACTION_CLIENT
            | FLAGS_ACCESSIBILITY_SERVICE
            | FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION
            | FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK;

    Map<String, Long> sNamesToFlags = Map.ofEntries(
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_ACCESSIBILITY_SERVICE_CONNECTION, FLAGS_ACCESSIBILITY_SERVICE_CONNECTION),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_ACCESSIBILITY_SERVICE_CLIENT, FLAGS_ACCESSIBILITY_SERVICE_CLIENT),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_ACCESSIBILITY_MANAGER, FLAGS_ACCESSIBILITY_MANAGER),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_ACCESSIBILITY_MANAGER_CLIENT, FLAGS_ACCESSIBILITY_MANAGER_CLIENT),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_ACCESSIBILITY_INTERACTION_CONNECTION,
                    FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK,
                    FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_REMOTE_MAGNIFICATION_ANIMATION_CALLBACK,
                    FLAGS_REMOTE_MAGNIFICATION_ANIMATION_CALLBACK),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_MAGNIFICATION_CONNECTION, FLAGS_MAGNIFICATION_CONNECTION),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_MAGNIFICATION_CONNECTION_CALLBACK,
                    FLAGS_MAGNIFICATION_CONNECTION_CALLBACK),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_WINDOW_MANAGER_INTERNAL, FLAGS_WINDOW_MANAGER_INTERNAL),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_MAGNIFICATION_CALLBACK, FLAGS_MAGNIFICATION_CALLBACK),
            new AbstractMap.SimpleEntry<String, Long>(NAME_INPUT_FILTER, FLAGS_INPUT_FILTER),
            new AbstractMap.SimpleEntry<String, Long>(NAME_GESTURE, FLAGS_GESTURE),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_ACCESSIBILITY_SERVICE, FLAGS_ACCESSIBILITY_SERVICE),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_PACKAGE_BROADCAST_RECEIVER, FLAGS_PACKAGE_BROADCAST_RECEIVER),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_USER_BROADCAST_RECEIVER, FLAGS_USER_BROADCAST_RECEIVER),
            new AbstractMap.SimpleEntry<String, Long>(NAME_FINGERPRINT, FLAGS_FINGERPRINT),
            new AbstractMap.SimpleEntry<String, Long>(
                    NAME_ACCESSIBILITY_INTERACTION_CLIENT, FLAGS_ACCESSIBILITY_INTERACTION_CLIENT),
            new AbstractMap.SimpleEntry<String, Long>(NAME_NONE, FLAGS_LOGGING_NONE),
            new AbstractMap.SimpleEntry<String, Long>(NAME_ALL_LOGGINGS, FLAGS_LOGGING_ALL));

    /**
     * Get the flags of the logging types by the given names.
     * The names list contains logging type names in lower case.
     */
    static long getLoggingFlagsFromNames(List<String> names) {
        long types = FLAGS_LOGGING_NONE;
        for (String name : names) {
            long flag = sNamesToFlags.get(name);
            types |= flag;
        }
        return types;
    }

    /**
     * Get the list of the names of logging types by the given flags.
     */
    static List<String> getNamesOfLoggingTypes(long flags) {
        List<String> list = new ArrayList<String>();

        for (Map.Entry<String, Long> entry : sNamesToFlags.entrySet()) {
            if ((entry.getValue() & flags) != FLAGS_LOGGING_NONE) {
                list.add(entry.getKey());
            }
        }

        return list;
    }

    /**
     * Whether the trace is enabled for any logging type.
     */
    boolean isA11yTracingEnabled();

    /**
     * Whether the trace is enabled for any of the given logging type.
     */
    boolean isA11yTracingEnabledForTypes(long typeIdFlags);

    /**
     * Get trace state to be sent to AccessibilityManager.
     */
    int getTraceStateForAccessibilityManagerClientState();

    /**
     * Start tracing for the given logging types.
     */
    void startTrace(long flagss);

    /**
     * Stop tracing.
     */
    void stopTrace();

    /**
     * Log one trace entry.
     * @param where A string to identify this log entry, which can be used to search through the
     *        tracing file.
     * @param loggingFlags Flags to identify which logging types this entry belongs to. This
     *        can be used to filter the log entries when generating tracing file.
     */
    void logTrace(String where, long loggingFlags);

    /**
     * Log one trace entry.
     * @param where A string to identify this log entry, which can be used to filter/search
     *        through the tracing file.
     * @param loggingFlags Flags to identify which logging types this entry belongs to. This
     *        can be used to filter the log entries when generating tracing file.
     * @param callingParams The parameters for the method to be logged.
     */
    void logTrace(String where, long loggingFlags, String callingParams);

    /**
     * Log one trace entry. Accessibility services using AccessibilityInteractionClient to
     * make screen content related requests use this API to log entry when receive callback.
     * @param timestamp The timestamp when a callback is received.
     * @param where A string to identify this log entry, which can be used to filter/search
     *        through the tracing file.
     * @param loggingFlags Flags to identify which logging types this entry belongs to. This
     *        can be used to filter the log entries when generating tracing file.
     * @param callingParams The parameters for the callback.
     * @param processId The process id of the calling component.
     * @param threadId The threadId of the calling component.
     * @param callingUid The calling uid of the callback.
     * @param callStack The call stack of the callback.
     * @param ignoreStackElements ignore these call stack element
     */
    void logTrace(long timestamp, String where, long loggingFlags, String callingParams,
            int processId, long threadId, int callingUid, StackTraceElement[] callStack,
            Set<String> ignoreStackElements);
}
