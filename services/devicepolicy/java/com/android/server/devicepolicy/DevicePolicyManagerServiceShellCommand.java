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
package com.android.server.devicepolicy;

import android.app.admin.DevicePolicyManager;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.Objects;

final class DevicePolicyManagerServiceShellCommand extends ShellCommand {

    private static final String CMD_IS_SAFE_OPERATION = "is-operation-safe";
    private static final String CMD_SET_SAFE_OPERATION = "set-operation-safe";

    private final DevicePolicyManagerService mService;

    DevicePolicyManagerServiceShellCommand(DevicePolicyManagerService service) {
        mService = Objects.requireNonNull(service);
    }

    @Override
    public void onHelp() {
        try (PrintWriter pw = getOutPrintWriter();) {
            pw.printf("DevicePolicyManager Service (device_policy) commands:\n\n");
            showHelp(pw);
        }
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        try (PrintWriter pw = getOutPrintWriter();) {
            switch (cmd) {
                case CMD_IS_SAFE_OPERATION:
                    return runIsSafeOperation(pw);
                case CMD_SET_SAFE_OPERATION:
                    return runSetSafeOperation(pw);
                default:
                    return onInvalidCommand(pw, cmd);
            }
        }
    }

    private int onInvalidCommand(PrintWriter pw, String cmd) {
        if (super.handleDefaultCommands(cmd) == 0) {
            return 0;
        }

        pw.println("Usage: ");
        showHelp(pw);
        return -1;
    }


    private void showHelp(PrintWriter pw) {
        pw.printf("  help\n");
        pw.printf("    Prints this help text.\n\n");
        pw.printf("  %s <OPERATION_ID>\n", CMD_IS_SAFE_OPERATION);
        pw.printf("    Checks if the give operation is safe \n\n");
        pw.printf("  %s <OPERATION_ID> <REASON_ID>\n", CMD_SET_SAFE_OPERATION);
        pw.printf("    Emulates the result of the next call to check if the given operation is safe"
                + " \n\n");
    }

    private int runIsSafeOperation(PrintWriter pw) {
        int operation = Integer.parseInt(getNextArgRequired());
        int reason = mService.getUnsafeOperationReason(operation);
        boolean safe = reason == DevicePolicyManager.UNSAFE_OPERATION_REASON_NONE;
        pw.printf("Operation %s is %b. Reason: %s\n",
                DevicePolicyManager.operationToString(operation), safe,
                DevicePolicyManager.unsafeOperationReasonToString(reason));
        return 0;
    }

    private int runSetSafeOperation(PrintWriter pw) {
        int operation = Integer.parseInt(getNextArgRequired());
        int reason = Integer.parseInt(getNextArgRequired());
        mService.setNextOperationSafety(operation, reason);
        pw.printf("Next call to check operation %s will return %s\n",
                DevicePolicyManager.operationToString(operation),
                DevicePolicyManager.unsafeOperationReasonToString(reason));
        return 0;
    }
}
