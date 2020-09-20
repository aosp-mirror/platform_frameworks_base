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
package com.android.server.location.timezone;

import android.os.ShellCommand;

import java.io.PrintWriter;

/** Implements the shell command interface for {@link LocationTimeZoneManagerService}. */
class LocationTimeZoneManagerShellCommand extends ShellCommand {

    private final LocationTimeZoneManagerService mService;

    LocationTimeZoneManagerShellCommand(LocationTimeZoneManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        switch (cmd) {
            case "simulate_binder": {
                return runSimulateBinderEvent();
            }
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    private int runSimulateBinderEvent() {
        PrintWriter outPrintWriter = getOutPrintWriter();

        SimulatedBinderProviderEvent simulatedProviderBinderEvent;
        try {
            simulatedProviderBinderEvent = SimulatedBinderProviderEvent.createFromArgs(this);
        } catch (IllegalArgumentException e) {
            outPrintWriter.println("Error: " + e.getMessage());
            return 1;
        }

        outPrintWriter.println("Injecting: " + simulatedProviderBinderEvent);
        try {
            mService.simulateBinderProviderEvent(simulatedProviderBinderEvent);
        } catch (IllegalStateException e) {
            outPrintWriter.println("Error: " + e.getMessage());
            return 2;
        }
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Location Time Zone Manager (location_time_zone_manager) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  simulate_binder");
        pw.println("    <simulated provider binder event>");
        pw.println();
        SimulatedBinderProviderEvent.printCommandLineOpts(pw);
        pw.println();
    }
}
