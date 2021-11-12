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

import static com.android.server.wm.CompatModePackages.DOWNSCALED;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_30;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_35;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_40;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_45;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_50;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_55;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_60;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_65;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_70;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_75;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_80;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_85;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_90;

import android.app.ActivityManager;
import android.app.GameManager;
import android.app.IGameManagerService;
import android.compat.Compatibility;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.ShellCommand;
import android.util.ArraySet;

import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.server.compat.PlatformCompat;

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
            DOWNSCALED,
            DOWNSCALE_90,
            DOWNSCALE_85,
            DOWNSCALE_80,
            DOWNSCALE_75,
            DOWNSCALE_70,
            DOWNSCALE_65,
            DOWNSCALE_60,
            DOWNSCALE_55,
            DOWNSCALE_50,
            DOWNSCALE_45,
            DOWNSCALE_40,
            DOWNSCALE_35,
            DOWNSCALE_30,
    });

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "downscale": {
                    final String ratio = getNextArgRequired();
                    final String packageName = getNextArgRequired();

                    final long changeId = GameManagerService.getCompatChangeId(ratio);
                    if (changeId == 0 && !ratio.equals("disable")) {
                        pw.println("Invalid scaling ratio '" + ratio + "'");
                        break;
                    }

                    Set<Long> enabled = new ArraySet<>();
                    Set<Long> disabled;
                    if (changeId == 0) {
                        disabled = DOWNSCALE_CHANGE_IDS;
                    } else {
                        enabled.add(DOWNSCALED);
                        enabled.add(changeId);
                        disabled = DOWNSCALE_CHANGE_IDS.stream()
                          .filter(it -> it != DOWNSCALED && it != changeId)
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

                    return 0;
                }
                case "mode": {
                    /** The "mode" command allows setting a package's current game mode outside of
                     * the game dashboard UI. This command requires that a mode already be supported
                     * by the package. Devs can forcibly support game modes via the manifest
                     * metadata flags: com.android.app.gamemode.performance.enabled,
                     *                 com.android.app.gamemode.battery.enabled
                     * OR by `adb shell device_config put game_overlay \
                     *          <PACKAGE_NAME> <CONFIG_STRING>`
                     * see: {@link GameManagerServiceTests#mockDeviceConfigAll()}
                     */
                    return runGameMode(pw);
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Error: " + e);
        }
        return -1;
    }

    private int runGameMode(PrintWriter pw) throws ServiceNotFoundException, RemoteException {
        final String option = getNextOption();
        String userIdStr = null;
        if (option != null && option.equals("--user")) {
            userIdStr = getNextArgRequired();
        }

        final String gameMode = getNextArgRequired();
        final String packageName = getNextArgRequired();
        final IGameManagerService service = IGameManagerService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.GAME_SERVICE));
        boolean batteryModeSupported = false;
        boolean perfModeSupported = false;
        int[] modes = service.getAvailableGameModes(packageName);
        for (int mode : modes) {
            if (mode == GameManager.GAME_MODE_PERFORMANCE) {
                perfModeSupported = true;
            } else if (mode == GameManager.GAME_MODE_BATTERY) {
                batteryModeSupported = true;
            }
        }
        int userId = userIdStr != null ? Integer.parseInt(userIdStr)
                : ActivityManager.getCurrentUser();
        switch (gameMode.toLowerCase()) {
            case "1":
            case "standard":
                // Standard should only be available if other game modes are.
                if (batteryModeSupported || perfModeSupported) {
                    service.setGameMode(packageName, GameManager.GAME_MODE_STANDARD,
                            userId);
                } else {
                    pw.println("Game mode: " + gameMode + " not supported by "
                            + packageName);
                    return -1;
                }
                break;
            case "2":
            case "performance":
                if (perfModeSupported) {
                    service.setGameMode(packageName, GameManager.GAME_MODE_PERFORMANCE,
                            userId);
                } else {
                    pw.println("Game mode: " + gameMode + " not supported by "
                            + packageName);
                    return -1;
                }
                break;
            case "3":
            case "battery":
                if (batteryModeSupported) {
                    service.setGameMode(packageName, GameManager.GAME_MODE_BATTERY,
                            userId);
                } else {
                    pw.println("Game mode: " + gameMode + " not supported by "
                            + packageName);
                    return -1;
                }
                break;
            default:
                pw.println("Invalid game mode: " + gameMode);
                return -1;
        }
        return 0;
    }


    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Game manager (game) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  downscale [0.3|0.35|0.4|0.45|0.5|0.55|0.6|0.65|0.7|0.75|0.8|0.85|0.9|disable] <PACKAGE_NAME>");
        pw.println("      Force app to run at the specified scaling ratio.");
        pw.println("  mode [--user <USER_ID>] [1|2|3|standard|performance|battery] <PACKAGE_NAME>");
        pw.println("      Force app to run in the specified game mode, if supported.");
        pw.println("      --user <USER_ID>: apply for the given user, the current user is used when unspecified.");
    }
}
