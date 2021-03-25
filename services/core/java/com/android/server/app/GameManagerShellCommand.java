/*
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

package com.android.server.app;

import android.compat.Compatibility;
import android.content.Context;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.util.ArraySet;

import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.server.compat.PlatformCompat;
import com.android.server.wm.CompatModePackages;

import java.io.PrintWriter;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ShellCommands for GameManagerService.
 *
 * Use with {@code adb shell cmd game ...}.
 */
public class GameManagerShellCommand extends ShellCommand {

    public GameManagerShellCommand() {}

    private static final ArraySet<Long> DOWNSCALE_CHANGE_IDS = new ArraySet<>(new Long[]{
            CompatModePackages.DOWNSCALED,
            CompatModePackages.DOWNSCALE_90,
            CompatModePackages.DOWNSCALE_80,
            CompatModePackages.DOWNSCALE_70,
            CompatModePackages.DOWNSCALE_60,
            CompatModePackages.DOWNSCALE_50});

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "downscale":
                    final String ratio = getNextArgRequired();
                    final String packageName = getNextArgRequired();

                    final long changeId;
                    switch (ratio) {
                        case "0.5":
                            changeId = CompatModePackages.DOWNSCALE_50;
                            break;
                        case "0.6":
                            changeId = CompatModePackages.DOWNSCALE_60;
                            break;
                        case "0.7":
                            changeId = CompatModePackages.DOWNSCALE_70;
                            break;
                        case "0.8":
                            changeId = CompatModePackages.DOWNSCALE_80;
                            break;
                        case "0.9":
                            changeId = CompatModePackages.DOWNSCALE_90;
                            break;
                        case "disable":
                            changeId = 0;
                            break;
                        default:
                            changeId = -1;
                            pw.println("Invalid scaling ratio '" + ratio + "'");
                            break;
                    }
                    if (changeId == -1) {
                        break;
                    }

                    Set<Long> enabled = new ArraySet<>();
                    Set<Long> disabled;
                    if (changeId == 0) {
                        disabled = DOWNSCALE_CHANGE_IDS;
                    } else {
                        enabled.add(CompatModePackages.DOWNSCALED);
                        enabled.add(changeId);
                        disabled = DOWNSCALE_CHANGE_IDS.stream()
                          .filter(it -> it != CompatModePackages.DOWNSCALED && it != changeId)
                          .collect(Collectors.toSet());
                    }

                    final PlatformCompat platformCompat = (PlatformCompat)
                            ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE);
                    final CompatibilityChangeConfig overrides =
                            new CompatibilityChangeConfig(
                                new Compatibility.ChangeConfig(enabled, disabled));

                    platformCompat.setOverrides(overrides, packageName);
                    if (changeId == 0) {
                        pw.println("Disable downscaling for " + packageName + ".");
                    } else {
                        pw.println("Enable downscaling ratio for " + packageName + " to " + ratio);
                    }

                    break;
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Error: " + e);
        }
        return -1;
    }


    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Game manager (game) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  downscale [0.5|0.6|0.7|0.8|0.9|disable] <PACKAGE_NAME>");
        pw.println("      Force app to run at the specified scaling ratio.");
    }
}
