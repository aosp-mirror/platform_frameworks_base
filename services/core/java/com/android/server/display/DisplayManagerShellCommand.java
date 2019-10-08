/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.display;

import android.content.Intent;
import android.os.ShellCommand;

import java.io.PrintWriter;

class DisplayManagerShellCommand extends ShellCommand {
    private static final String TAG = "DisplayManagerShellCommand";

    private final DisplayManagerService.BinderService mService;

    DisplayManagerShellCommand(DisplayManagerService.BinderService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        switch(cmd) {
            case "set-brightness":
                return setBrightness();
            case "reset-brightness-configuration":
                return resetBrightnessConfiguration();
            case "ab-logging-enable":
                return setAutoBrightnessLoggingEnabled(true);
            case "ab-logging-disable":
                return setAutoBrightnessLoggingEnabled(false);
            case "dwb-logging-enable":
                return setDisplayWhiteBalanceLoggingEnabled(true);
            case "dwb-logging-disable":
                return setDisplayWhiteBalanceLoggingEnabled(false);
            case "dwb-set-cct":
                return setAmbientColorTemperatureOverride();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Display manager commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  set-brightness BRIGHTNESS");
        pw.println("    Sets the current brightness to BRIGHTNESS (a number between 0 and 1).");
        pw.println("  reset-brightness-configuration");
        pw.println("    Reset the brightness to its default configuration.");
        pw.println("  ab-logging-enable");
        pw.println("    Enable auto-brightness logging.");
        pw.println("  ab-logging-disable");
        pw.println("    Disable auto-brightness logging.");
        pw.println("  dwb-logging-enable");
        pw.println("    Enable display white-balance logging.");
        pw.println("  dwb-logging-disable");
        pw.println("    Disable display white-balance logging.");
        pw.println("  dwb-set-cct CCT");
        pw.println("    Sets the ambient color temperature override to CCT (use -1 to disable).");
        pw.println();
        Intent.printIntentArgsHelp(pw , "");
    }

    private int setBrightness() {
        String brightnessText = getNextArg();
        if (brightnessText == null) {
            getErrPrintWriter().println("Error: no brightness specified");
            return 1;
        }
        float brightness = -1.0f;
        try {
            brightness = Float.parseFloat(brightnessText);
        } catch (NumberFormatException e) {
        }
        if (brightness < 0 || brightness > 1) {
            getErrPrintWriter().println("Error: brightness should be a number between 0 and 1");
            return 1;
        }
        mService.setBrightness((int) (brightness * 255));
        return 0;
    }

    private int resetBrightnessConfiguration() {
        mService.resetBrightnessConfiguration();
        return 0;
    }

    private int setAutoBrightnessLoggingEnabled(boolean enabled) {
        mService.setAutoBrightnessLoggingEnabled(enabled);
        return 0;
    }

    private int setDisplayWhiteBalanceLoggingEnabled(boolean enabled) {
        mService.setDisplayWhiteBalanceLoggingEnabled(enabled);
        return 0;
    }

    private int setAmbientColorTemperatureOverride() {
        String cctText = getNextArg();
        if (cctText == null) {
            getErrPrintWriter().println("Error: no cct specified");
            return 1;
        }
        float cct;
        try {
            cct = Float.parseFloat(cctText);
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: cct should be a number");
            return 1;
        }
        mService.setAmbientColorTemperatureOverride(cct);
        return 0;
    }
}
