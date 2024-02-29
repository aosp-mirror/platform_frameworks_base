/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.Manifest;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.ShellCommand;
import android.os.UserHandle;

import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Shell command implementation for the accessibility manager service
 */
final class AccessibilityShellCommand extends ShellCommand {
    @NonNull
    final Context mContext;
    @NonNull
    final AccessibilityManagerService mService;
    @NonNull
    final SystemActionPerformer mSystemActionPerformer;
    @NonNull
    final WindowManagerInternal mWindowManagerService;

    AccessibilityShellCommand(@NonNull Context context,
            @NonNull AccessibilityManagerService service,
            @NonNull SystemActionPerformer systemActionPerformer) {
        mContext = context;
        mService = service;
        mSystemActionPerformer = systemActionPerformer;
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        switch (cmd) {
            case "get-bind-instant-service-allowed": {
                return runGetBindInstantServiceAllowed();
            }
            case "set-bind-instant-service-allowed": {
                return runSetBindInstantServiceAllowed();
            }
            case "call-system-action": {
                return runCallSystemAction();
            }
            case "start-trace":
            case "stop-trace":
                return mService.getTraceManager().onShellCommand(cmd, this);
            case "check-hidraw":
                return checkHidraw();
        }
        return -1;
    }

    private int runGetBindInstantServiceAllowed() {
        final Integer userId = parseUserId();
        if (userId == null) {
            return -1;
        }
        getOutPrintWriter().println(Boolean.toString(
                mService.getBindInstantServiceAllowed(userId)));
        return 0;
    }

    private int runSetBindInstantServiceAllowed() {
        final Integer userId = parseUserId();
        if (userId == null) {
            return -1;
        }
        final String allowed = getNextArgRequired();
        if (allowed == null) {
            getErrPrintWriter().println("Error: no true/false specified");
            return -1;
        }
        mService.setBindInstantServiceAllowed(userId,
                Boolean.parseBoolean(allowed));
        return 0;
    }

    private int runCallSystemAction() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID
                && callingUid != Process.SYSTEM_UID
                && callingUid != Process.SHELL_UID) {
            return -1;
        }
        final String option = getNextArg();
        if (option != null) {
            int actionId = Integer.parseInt(option);
            mSystemActionPerformer.performSystemAction(actionId);
            return 0;
        }
        return -1;
    }

    private int checkHidraw() {
        mContext.enforceCallingPermission(Manifest.permission.MANAGE_ACCESSIBILITY,
                "Missing MANAGE_ACCESSIBILITY permission");
        String subcommand = getNextArgRequired();
        File hidrawNode = new File(getNextArgRequired());
        switch (subcommand) {
            case "read" -> {
                return checkHidrawRead(hidrawNode);
            }
            case "write" -> {
                return checkHidrawWrite(hidrawNode);
            }
            case "descriptor" -> {
                return checkHidrawDescriptor(hidrawNode);
            }
            default -> {
                getErrPrintWriter().print("Unknown subcommand " + subcommand);
                return -1;
            }
        }
    }

    private int checkHidrawRead(File hidrawNode) {
        if (!hidrawNode.canRead()) {
            getErrPrintWriter().println("Unable to read from " + hidrawNode);
            return -1;
        }
        // Tests executing this command using UiAutomation#executeShellCommand will not receive
        // the command's exit value, so print the path to stdout to indicate success.
        getOutPrintWriter().print(hidrawNode.getAbsolutePath());
        return 0;
    }

    private int checkHidrawWrite(File hidrawNode) {
        if (!hidrawNode.canWrite()) {
            getErrPrintWriter().println("Unable to write to " + hidrawNode);
            return -1;
        }
        // Tests executing this command using UiAutomation#executeShellCommand will not receive
        // the command's exit value, so print the path to stdout to indicate success.
        getOutPrintWriter().print(hidrawNode.getAbsolutePath());
        return 0;
    }

    private int checkHidrawDescriptor(File hidrawNode) {
        BrailleDisplayConnection.BrailleDisplayScanner scanner =
                BrailleDisplayConnection.createScannerForShell();
        byte[] descriptor = scanner.getDeviceReportDescriptor(hidrawNode.toPath());
        if (descriptor == null) {
            getErrPrintWriter().println("Unable to read descriptor for " + hidrawNode);
            return -1;
        }
        try {
            // Print the descriptor bytes to stdout.
            getRawOutputStream().write(descriptor);
            return 0;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Integer parseUserId() {
        final String option = getNextOption();
        if (option != null) {
            if (option.equals("--user")) {
                return UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Unknown option: " + option);
                return null;
            }
        }
        return ActivityManager.getCurrentUser();
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Accessibility service (accessibility) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set-bind-instant-service-allowed [--user <USER_ID>] true|false ");
        pw.println("    Set whether binding to services provided by instant apps is allowed.");
        pw.println("  get-bind-instant-service-allowed [--user <USER_ID>]");
        pw.println("    Get whether binding to services provided by instant apps is allowed.");
        pw.println("  call-system-action <ACTION_ID>");
        pw.println("    Calls the system action with the given action id.");
        pw.println("  check-hidraw [read|write|descriptor] <HIDRAW_NODE_PATH>");
        pw.println("    Checks if the system can perform various actions on the HIDRAW node.");
        mService.getTraceManager().onHelp(pw);
    }
}
