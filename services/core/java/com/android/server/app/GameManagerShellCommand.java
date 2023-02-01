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
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.ShellCommand;
import android.util.ArraySet;

import java.io.PrintWriter;
import java.util.Locale;

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
                case "set": {
                    return runSetGameMode(pw);
                }
                case "reset": {
                    return runResetGameMode(pw);
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
                case "list": {
                    return runGameList(pw);
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Error: " + e);
        }
        return -1;
    }

    private int runGameList(PrintWriter pw) throws ServiceNotFoundException, RemoteException {
        final String packageName = getNextArgRequired();

        final GameManagerService gameManagerService = (GameManagerService)
                ServiceManager.getService(Context.GAME_SERVICE);

        final String listStr = gameManagerService.getInterventionList(packageName);

        if (listStr == null) {
            pw.println("No interventions found for " + packageName);
        } else {
            pw.println(packageName + " interventions: " + listStr);
        }
        return 0;
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
                // Standard mode can be used to specify loading ANGLE as the default OpenGL ES
                // driver, so it should always be available.
                service.setGameMode(packageName, GameManager.GAME_MODE_STANDARD, userId);
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

    private int runSetGameMode(PrintWriter pw) throws ServiceNotFoundException, RemoteException {
        String option = getNextArgRequired();
        if (!option.equals("--mode")) {
            pw.println("Invalid option '" + option + "'");
            return -1;
        }

        final String gameMode = getNextArgRequired();

        /**
         * handling optional input
         * "--user", "--downscale" and "--fps" can come in any order
         */
        String userIdStr = null;
        String fpsStr = null;
        String downscaleRatio = null;
        while ((option = getNextOption()) != null) {
            switch (option) {
                case "--user":
                    if (userIdStr == null) {
                        userIdStr = getNextArgRequired();
                    } else {
                        pw.println("Duplicate option '" + option + "'");
                        return -1;
                    }
                    break;
                case "--downscale":
                    if (downscaleRatio == null) {
                        downscaleRatio = getNextArgRequired();
                        if (downscaleRatio != null
                                && GameManagerService.getCompatChangeId(downscaleRatio) == 0
                                && !downscaleRatio.equals("disable")) {
                            pw.println("Invalid scaling ratio '" + downscaleRatio + "'");
                            return -1;
                        }
                    } else {
                        pw.println("Duplicate option '" + option + "'");
                        return -1;
                    }
                    break;
                case "--fps":
                    if (fpsStr == null) {
                        fpsStr = getNextArgRequired();
                        if (fpsStr != null && GameManagerService.getFpsInt(fpsStr) == -1) {
                            pw.println("Invalid frame rate '" + fpsStr + "'");
                            return -1;
                        }
                    } else {
                        pw.println("Duplicate option '" + option + "'");
                        return -1;
                    }
                    break;
                default:
                    pw.println("Invalid option '" + option + "'");
                    return -1;
            }
        }

        final String packageName = getNextArgRequired();

        int userId = userIdStr != null ? Integer.parseInt(userIdStr)
                : ActivityManager.getCurrentUser();

        final GameManagerService gameManagerService = (GameManagerService)
                ServiceManager.getService(Context.GAME_SERVICE);

        boolean batteryModeSupported = false;
        boolean perfModeSupported = false;
        int [] modes = gameManagerService.getAvailableGameModes(packageName);

        for (int mode : modes) {
            if (mode == GameManager.GAME_MODE_PERFORMANCE) {
                perfModeSupported = true;
            } else if (mode == GameManager.GAME_MODE_BATTERY) {
                batteryModeSupported = true;
            }
        }

        switch (gameMode.toLowerCase(Locale.getDefault())) {
            case "2":
            case "performance":
                if (perfModeSupported) {
                    gameManagerService.setGameModeConfigOverride(packageName, userId,
                            GameManager.GAME_MODE_PERFORMANCE, fpsStr, downscaleRatio);
                } else {
                    pw.println("Game mode: " + gameMode + " not supported by "
                            + packageName);
                    return -1;
                }
                break;
            case "3":
            case "battery":
                if (batteryModeSupported) {
                    gameManagerService.setGameModeConfigOverride(packageName, userId,
                            GameManager.GAME_MODE_BATTERY, fpsStr, downscaleRatio);
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

    private int runResetGameMode(PrintWriter pw) throws ServiceNotFoundException, RemoteException {
        String option = null;
        String gameMode = null;
        String userIdStr = null;
        while ((option = getNextOption()) != null) {
            switch (option) {
                case "--user":
                    if (userIdStr == null) {
                        userIdStr = getNextArgRequired();
                    } else {
                        pw.println("Duplicate option '" + option + "'");
                        return -1;
                    }
                    break;
                case "--mode":
                    if (gameMode == null) {
                        gameMode = getNextArgRequired();
                    } else {
                        pw.println("Duplicate option '" + option + "'");
                        return -1;
                    }
                    break;
                default:
                    pw.println("Invalid option '" + option + "'");
                    return -1;
            }
        }

        final String packageName = getNextArgRequired();

        final GameManagerService gameManagerService = (GameManagerService)
                ServiceManager.getService(Context.GAME_SERVICE);

        int userId = userIdStr != null ? Integer.parseInt(userIdStr)
                : ActivityManager.getCurrentUser();

        if (gameMode == null) {
            gameManagerService.resetGameModeConfigOverride(packageName, userId, -1);
            return 0;
        }

        switch (gameMode.toLowerCase(Locale.getDefault())) {
            case "2":
            case "performance":
                gameManagerService.resetGameModeConfigOverride(packageName, userId,
                        GameManager.GAME_MODE_PERFORMANCE);
                break;
            case "3":
            case "battery":
                gameManagerService.resetGameModeConfigOverride(packageName, userId,
                        GameManager.GAME_MODE_BATTERY);
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
        pw.println("  downscale");
        pw.println("      Deprecated. Please use `set` command.");
        pw.println("  mode [--user <USER_ID>] [1|2|3|standard|performance|battery] <PACKAGE_NAME>");
        pw.println("      Set app to run in the specified game mode, if supported.");
        pw.println("      --user <USER_ID>: apply for the given user,");
        pw.println("                        the current user is used when unspecified.");
        pw.println("  set --mode [2|3|performance|battery] [intervention configs] <PACKAGE_NAME>");
        pw.println("      Set app to run at given game mode with configs, if supported.");
        pw.println("      Intervention configs consists of:");
        pw.println("      --downscale [0.3|0.35|0.4|0.45|0.5|0.55|0.6|0.65");
        pw.println("                  |0.7|0.75|0.8|0.85|0.9|disable]");
        pw.println("      Set app to run at the specified scaling ratio.");
        pw.println("      --fps [30|45|60|90|120|disable]");
        pw.println("      Set app to run at the specified fps, if supported.");
        pw.println("  reset [--mode [2|3|performance|battery] --user <USER_ID>] <PACKAGE_NAME>");
        pw.println("      Resets the game mode of the app to device configuration.");
        pw.println("      --mode [2|3|performance|battery]: apply for the given mode,");
        pw.println("                                        resets all modes when unspecified.");
        pw.println("      --user <USER_ID>: apply for the given user,");
        pw.println("                        the current user is used when unspecified.");
    }
}
