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

import android.annotation.NonNull;
import android.os.Binder;

import com.android.server.wm.WindowManagerInternal;

import java.io.PrintWriter;

/**
 * Manager of accessibility trace.
 */
class AccessibilityTraceManager implements AccessibilityTrace {
    private final WindowManagerInternal.AccessibilityControllerInternal mA11yController;
    private final AccessibilityManagerService mService;

    AccessibilityTraceManager(
            @NonNull WindowManagerInternal.AccessibilityControllerInternal a11yController,
            @NonNull AccessibilityManagerService service) {
        mA11yController = a11yController;
        mService = service;
    }

    @Override
    public boolean isA11yTracingEnabled() {
        return mA11yController.isAccessibilityTracingEnabled();
    }

    @Override
    public void startTrace() {
        if (!mA11yController.isAccessibilityTracingEnabled()) {
            mA11yController.startTrace();
            mService.scheduleUpdateClientsIfNeeded(mService.getCurrentUserState());
        }
    }

    @Override
    public void stopTrace() {
        if (mA11yController.isAccessibilityTracingEnabled()) {
            mA11yController.stopTrace();
            mService.scheduleUpdateClientsIfNeeded(mService.getCurrentUserState());
        }
    }

    @Override
    public void logTrace(String where) {
        logTrace(where, "");
    }

    @Override
    public void logTrace(String where, String callingParams) {
        mA11yController.logTrace(where, callingParams, "".getBytes(),
                Binder.getCallingUid(), Thread.currentThread().getStackTrace());
    }

    @Override
    public void logTrace(long timestamp, String where, String callingParams, int processId,
            long threadId, int callingUid, StackTraceElement[] callStack) {
        if (mA11yController.isAccessibilityTracingEnabled()) {
            mA11yController.logTrace(where, callingParams, "".getBytes(), callingUid, callStack,
                    timestamp, processId, threadId);
        }
    }

    int onShellCommand(String cmd) {
        switch (cmd) {
            case "start-trace": {
                startTrace();
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
        pw.println("  start-trace");
        pw.println("    Start the debug tracing.");
        pw.println("  stop-trace");
        pw.println("    Stop the debug tracing.");
    }
}
