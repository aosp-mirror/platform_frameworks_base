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

package com.android.server.display.color;

import android.content.pm.PackageManagerInternal;
import android.os.ShellCommand;

import com.android.server.LocalServices;

class ColorDisplayShellCommand extends ShellCommand {

    private static final String USAGE = "usage: cmd color_display SUBCOMMAND [ARGS]\n"
            + "    help\n"
            + "      Shows this message.\n"
            + "    set-saturation LEVEL\n"
            + "      Sets the device saturation to the given LEVEL, 0-100 inclusive.\n"
            + "    set-layer-saturation LEVEL CALLER_PACKAGE TARGET_PACKAGE\n"
            + "      Sets the saturation LEVEL for all layers of the TARGET_PACKAGE, attributed\n"
            + "      to the CALLER_PACKAGE. The lowest LEVEL from any CALLER_PACKAGE is applied.\n";

    private static final int ERROR = -1;
    private static final int SUCCESS = 0;

    private final ColorDisplayService mService;

    ColorDisplayShellCommand(ColorDisplayService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        switch (cmd) {
            case "set-saturation":
                return setSaturation();
            case "set-layer-saturation":
                return setLayerSaturation();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int setSaturation() {
        final int level = getLevel();
        if (level == ERROR) {
            return ERROR;
        }
        mService.setSaturationLevelInternal(level);
        return SUCCESS;
    }

    private int setLayerSaturation() {
        final int level = getLevel();
        if (level == ERROR) {
            return ERROR;
        }
        final String callerPackageName = getPackageName();
        if (callerPackageName == null) {
            getErrPrintWriter().println("Error: CALLER_PACKAGE must be an installed package name");
            return ERROR;
        }
        final String targetPackageName = getPackageName();
        if (targetPackageName == null) {
            getErrPrintWriter().println("Error: TARGET_PACKAGE must be an installed package name");
            return ERROR;
        }
        mService.setAppSaturationLevelInternal(callerPackageName, targetPackageName, level);
        return SUCCESS;
    }

    private String getPackageName() {
        final String packageNameArg = getNextArg();
        return LocalServices.getService(PackageManagerInternal.class).getPackage(packageNameArg)
                == null
                ? null : packageNameArg;
    }

    private int getLevel() {
        final String levelArg = getNextArg();
        if (levelArg == null) {
            getErrPrintWriter().println("Error: Required argument LEVEL is unspecified");
            return ERROR;
        }
        final int level;
        try {
            level = Integer.parseInt(levelArg);
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: LEVEL argument is not an integer");
            return ERROR;
        }
        if (level < 0 || level > 100) {
            getErrPrintWriter()
                    .println("Error: LEVEL argument must be an integer between 0 and 100");
            return ERROR;
        }
        return level;
    }

    @Override
    public void onHelp() {
        getOutPrintWriter().print(USAGE);
    }
}
