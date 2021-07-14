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

import static android.Manifest.permission.CONTROL_DEVICE_STATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateRequest;
import android.os.Binder;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.Optional;

/**
 * ShellCommands for {@link DeviceStateManagerService}.
 *
 * Use with {@code adb shell cmd device_state ...}.
 */
public class DeviceStateManagerShellCommand extends ShellCommand {
    @Nullable
    private static DeviceStateRequest sLastRequest;

    private final DeviceStateManagerService mService;
    private final DeviceStateManager mClient;

    public DeviceStateManagerShellCommand(DeviceStateManagerService service) {
        mService = service;
        mClient = service.getContext().getSystemService(DeviceStateManager.class);
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
        Optional<DeviceState> committedState = mService.getCommittedState();
        Optional<DeviceState> baseState = mService.getBaseState();
        Optional<DeviceState> overrideState = mService.getOverrideState();

        pw.println("Committed state: " + toString(committedState));
        if (overrideState.isPresent()) {
            pw.println("----------------------");
            pw.println("Base state: " + toString(baseState));
            pw.println("Override state: " + overrideState.get());
        }
    }

    private int runState(PrintWriter pw) {
        final String nextArg = getNextArg();
        if (nextArg == null) {
            printState(pw);
        }

        final Context context = mService.getContext();
        context.enforceCallingOrSelfPermission(
                CONTROL_DEVICE_STATE,
                "Permission required to request device state.");
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            if ("reset".equals(nextArg)) {
                if (sLastRequest != null) {
                    mClient.cancelRequest(sLastRequest);
                    sLastRequest = null;
                }
            } else {
                int requestedState = Integer.parseInt(nextArg);
                DeviceStateRequest request = DeviceStateRequest.newBuilder(requestedState).build();

                mClient.requestState(request, null /* executor */, null /* callback */);
                if (sLastRequest != null) {
                    mClient.cancelRequest(sLastRequest);
                }

                sLastRequest = request;
            }
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: requested state should be an integer");
            return -1;
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println("Error: " + e.getMessage());
            getErrPrintWriter().println("-------------------");
            getErrPrintWriter().println("Run:");
            getErrPrintWriter().println("");
            getErrPrintWriter().println("    print-states");
            getErrPrintWriter().println("");
            getErrPrintWriter().println("to get the list of currently supported device states");
            return -1;
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }

        return 0;
    }

    private int runPrintStates(PrintWriter pw) {
        DeviceState[] states = mService.getSupportedStates();
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

    private static String toString(@NonNull Optional<DeviceState> state) {
        return state.isPresent() ? state.get().toString() : "(none)";
    }
}
