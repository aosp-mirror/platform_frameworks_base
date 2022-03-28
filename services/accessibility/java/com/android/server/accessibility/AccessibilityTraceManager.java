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

import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_INTERACTION_CLIENT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_MANAGER_CLIENT_STATES;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_SERVICE;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_LOGGING_ALL;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_LOGGING_NONE;
import static android.view.accessibility.AccessibilityManager.STATE_FLAG_TRACE_A11Y_INTERACTION_CLIENT_ENABLED;
import static android.view.accessibility.AccessibilityManager.STATE_FLAG_TRACE_A11Y_INTERACTION_CONNECTION_CB_ENABLED;
import static android.view.accessibility.AccessibilityManager.STATE_FLAG_TRACE_A11Y_INTERACTION_CONNECTION_ENABLED;
import static android.view.accessibility.AccessibilityManager.STATE_FLAG_TRACE_A11Y_SERVICE_ENABLED;

import android.accessibilityservice.AccessibilityTrace;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.os.Binder;
import android.os.ShellCommand;

import com.android.server.wm.WindowManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manager of accessibility trace.
 */
public class AccessibilityTraceManager implements AccessibilityTrace {
    private final WindowManagerInternal.AccessibilityControllerInternal mA11yController;
    private final AccessibilityManagerService mService;
    private final Object mA11yMSLock;

    private volatile long mEnabledLoggingFlags;

    private static AccessibilityTraceManager sInstance = null;

    @MainThread
    static AccessibilityTraceManager getInstance(
            @NonNull WindowManagerInternal.AccessibilityControllerInternal a11yController,
            @NonNull AccessibilityManagerService service,
            @NonNull Object lock) {
        if (sInstance == null) {
            sInstance = new AccessibilityTraceManager(a11yController, service, lock);
        }
        return sInstance;
    }

    private AccessibilityTraceManager(
            @NonNull WindowManagerInternal.AccessibilityControllerInternal a11yController,
            @NonNull AccessibilityManagerService service,
            @NonNull Object lock) {
        mA11yController = a11yController;
        mService = service;
        mA11yMSLock = lock;
        mEnabledLoggingFlags = FLAGS_LOGGING_NONE;
    }

    @Override
    public boolean isA11yTracingEnabled() {
        return mEnabledLoggingFlags != FLAGS_LOGGING_NONE;
    }

    @Override
    public boolean isA11yTracingEnabledForTypes(long typeIdFlags) {
        return ((typeIdFlags & mEnabledLoggingFlags) != FLAGS_LOGGING_NONE);
    }

    @Override
    public int getTraceStateForAccessibilityManagerClientState() {
        int state = 0x0;
        if (isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION)) {
            state |= STATE_FLAG_TRACE_A11Y_INTERACTION_CONNECTION_ENABLED;
        }
        if (isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK)) {
            state |= STATE_FLAG_TRACE_A11Y_INTERACTION_CONNECTION_CB_ENABLED;
        }
        if (isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_INTERACTION_CLIENT)) {
            state |= STATE_FLAG_TRACE_A11Y_INTERACTION_CLIENT_ENABLED;
        }
        if (isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_SERVICE)) {
            state |= STATE_FLAG_TRACE_A11Y_SERVICE_ENABLED;
        }
        return state;
    }

    @Override
    public void startTrace(long loggingTypes) {
        if (loggingTypes == FLAGS_LOGGING_NONE) {
            // Ignore start none request
            return;
        }

        long oldEnabled = mEnabledLoggingFlags;
        mEnabledLoggingFlags = loggingTypes;

        if (needToNotifyClients(oldEnabled)) {
            synchronized (mA11yMSLock) {
                mService.scheduleUpdateClientsIfNeededLocked(mService.getCurrentUserState());
            }
        }

        mA11yController.startTrace(loggingTypes);
    }

    @Override
    public void stopTrace() {
        boolean stop;
        stop = isA11yTracingEnabled();

        long oldEnabled = mEnabledLoggingFlags;
        mEnabledLoggingFlags = FLAGS_LOGGING_NONE;

        if (needToNotifyClients(oldEnabled)) {
            synchronized (mA11yMSLock) {
                mService.scheduleUpdateClientsIfNeededLocked(mService.getCurrentUserState());
            }
        }
        if (stop) {
            mA11yController.stopTrace();
        }
    }

    @Override
    public void logTrace(String where, long loggingTypes) {
        logTrace(where, loggingTypes, "");
    }

    @Override
    public void logTrace(String where, long loggingTypes, String callingParams) {
        if (isA11yTracingEnabledForTypes(loggingTypes)) {
            mA11yController.logTrace(where, loggingTypes, callingParams, "".getBytes(),
                    Binder.getCallingUid(), Thread.currentThread().getStackTrace(),
                    new HashSet<String>(Arrays.asList("logTrace")));
        }
    }

    @Override
    public void logTrace(long timestamp, String where, long loggingTypes, String callingParams,
            int processId, long threadId, int callingUid, StackTraceElement[] callStack,
            Set<String> ignoreElementList) {
        if (isA11yTracingEnabledForTypes(loggingTypes)) {
            mA11yController.logTrace(where, loggingTypes, callingParams, "".getBytes(), callingUid,
                    callStack, timestamp, processId, threadId,
                    ((ignoreElementList == null) ? new HashSet<String>() : ignoreElementList));
        }
    }

    private boolean needToNotifyClients(long otherTypesEnabled) {
        return (mEnabledLoggingFlags & FLAGS_ACCESSIBILITY_MANAGER_CLIENT_STATES)
                != (otherTypesEnabled & FLAGS_ACCESSIBILITY_MANAGER_CLIENT_STATES);
    }

    int onShellCommand(String cmd, ShellCommand shell) {
        switch (cmd) {
            case "start-trace": {
                String opt = shell.getNextOption();
                if (opt == null) {
                    startTrace(FLAGS_LOGGING_ALL);
                    return 0;
                }
                List<String> types = new ArrayList<String>();
                while (opt != null) {
                    switch (opt) {
                        case "-t": {
                            String type = shell.getNextArg();
                            while (type != null) {
                                types.add(type);
                                type = shell.getNextArg();
                            }
                            break;
                        }
                        default: {
                            shell.getErrPrintWriter().println(
                                    "Error: option not recognized " + opt);
                            stopTrace();
                            return -1;
                        }
                    }
                    opt = shell.getNextOption();
                }
                long enabledTypes = AccessibilityTrace.getLoggingFlagsFromNames(types);
                startTrace(enabledTypes);
                return 0;
            }
            case "stop-trace": {
                stopTrace();
                return 0;
            }
        }
        return -1;
    }

    void onHelp(PrintWriter pw) {
        pw.println("  start-trace [-t LOGGING_TYPE [LOGGING_TYPE...]]");
        pw.println("    Start the debug tracing. If no option is present, full trace will be");
        pw.println("    generated. Options are:");
        pw.println("      -t: Only generate tracing for the logging type(s) specified here.");
        pw.println("          LOGGING_TYPE can be any one of below:");
        pw.println("            IAccessibilityServiceConnection");
        pw.println("            IAccessibilityServiceClient");
        pw.println("            IAccessibilityManager");
        pw.println("            IAccessibilityManagerClient");
        pw.println("            IAccessibilityInteractionConnection");
        pw.println("            IAccessibilityInteractionConnectionCallback");
        pw.println("            IRemoteMagnificationAnimationCallback");
        pw.println("            IWindowMagnificationConnection");
        pw.println("            IWindowMagnificationConnectionCallback");
        pw.println("            WindowManagerInternal");
        pw.println("            WindowsForAccessibilityCallback");
        pw.println("            MagnificationCallbacks");
        pw.println("            InputFilter");
        pw.println("            Gesture");
        pw.println("            AccessibilityService");
        pw.println("            PMBroadcastReceiver");
        pw.println("            UserBroadcastReceiver");
        pw.println("            FingerprintGesture");
        pw.println("  stop-trace");
        pw.println("    Stop the debug tracing.");
    }
}
