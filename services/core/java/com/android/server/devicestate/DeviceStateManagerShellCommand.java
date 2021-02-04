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

package com.android.server.devicestate;

import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.Optional;

/**
 * ShellCommands for {@link DeviceStateManagerService}.
 *
 * Use with {@code adb shell cmd device_state ...}.
 */
public class DeviceStateManagerShellCommand extends ShellCommand {
    private final DeviceStateManagerService mInternal;

    public DeviceStateManagerShellCommand(DeviceStateManagerService service) {
        mInternal = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();

        switch (cmd) {
            case "state":
                return runState(pw);
            case "print-states":
                return runPrintStates(pw);
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private void printState(PrintWriter pw) {
        DeviceState committedState = mInternal.getCommittedState();
        Optional<DeviceState> requestedState = mInternal.getRequestedState();
        Optional<DeviceState> requestedOverrideState = mInternal.getOverrideState();

        pw.println("Committed state: " + committedState);
        if (requestedOverrideState.isPresent()) {
            pw.println("----------------------");
            pw.println("Base state: " + requestedState.orElse(null));
            pw.println("Override state: " + requestedOverrideState.get());
        }
    }

    private int runState(PrintWriter pw) {
        final String nextArg = getNextArg();
        if (nextArg == null) {
            printState(pw);
        } else if ("reset".equals(nextArg)) {
            mInternal.clearOverrideState();
        } else {
            int requestedState;
            try {
                requestedState = Integer.parseInt(nextArg);
            } catch (NumberFormatException e) {
                getErrPrintWriter().println("Error: requested state should be an integer");
                return -1;
            }

            boolean success = mInternal.setOverrideState(requestedState);
            if (!success) {
                getErrPrintWriter().println("Error: failed to set override state. Run:");
                getErrPrintWriter().println("");
                getErrPrintWriter().println("    print-states");
                getErrPrintWriter().println("");
                getErrPrintWriter().println("to get the list of currently supported device states");
                return -1;
            }
        }
        return 0;
    }

    private int runPrintStates(PrintWriter pw) {
        DeviceState[] states = mInternal.getSupportedStates();
        pw.print("Supported states: [\n");
        for (int i = 0; i < states.length; i++) {
            pw.print("  " + states[i] + ",\n");
        }
        pw.println("]");
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Device state manager (device_state) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  state [reset|OVERRIDE_DEVICE_STATE]");
        pw.println("    Return or override device state.");
        pw.println("  print-states");
        pw.println("    Return list of currently supported device states.");
    }
}
