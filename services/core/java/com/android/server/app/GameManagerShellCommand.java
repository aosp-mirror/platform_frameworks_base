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

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.GameManager;
import android.app.IGameManagerService;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * ShellCommands for GameManagerService.
 *
 * Use with {@code adb shell cmd game ...}.
 */
public class GameManagerShellCommand extends ShellCommand {
    private static final String STANDARD_MODE_STR = "standard";
    private static final String STANDARD_MODE_NUM = "1";
    private static final String PERFORMANCE_MODE_STR = "performance";
    private static final String PERFORMANCE_MODE_NUM = "2";
    private static final String BATTERY_MODE_STR = "battery";
    private static final String BATTERY_MODE_NUM = "3";
    private static final String CUSTOM_MODE_STR = "custom";
    private static final String CUSTOM_MODE_NUM = "4";
    private static final String UNSUPPORTED_MODE_STR = "unsupported";
    private static final String UNSUPPORTED_MODE_NUM = String.valueOf(
            GameManager.GAME_MODE_UNSUPPORTED);

    private PackageManager mPackageManager;

    public GameManagerShellCommand(@NonNull PackageManager packageManager) {
        mPackageManager = packageManager;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "set": {
                    return runSetGameModeConfig(pw);
                }
                case "reset": {
                    return runResetGameModeConfig(pw);
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
                    return runSetGameMode(pw);
                }
                case "list-modes": {
                    return runListGameModes(pw);
                }
                case "list-configs": {
                    return runListGameModeConfigs(pw);
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Error: " + e);
        }
        return -1;
    }

    private boolean isPackageGame(String packageName, int userId, PrintWriter pw) {
        try {
            final ApplicationInfo applicationInfo = mPackageManager
                    .getApplicationInfoAsUser(packageName, PackageManager.MATCH_ALL, userId);
            boolean isGame = applicationInfo.category == ApplicationInfo.CATEGORY_GAME;
            if (!isGame) {
                pw.println("Package " + packageName + " is not of game type, to use the game "
                        + "mode commands, it must specify game category in the manifest as "
                        + "android:appCategory=\"game\"");
            }
            return isGame;
        } catch (PackageManager.NameNotFoundException e) {
            pw.println("Package " + packageName + " is not found for user " + userId);
            return false;
        }
    }

    private int runListGameModes(PrintWriter pw) throws ServiceNotFoundException, RemoteException {
        final String packageName = getNextArgRequired();
        final int userId = ActivityManager.getCurrentUser();
        if (!isPackageGame(packageName, userId, pw)) {
            return -1;
        }
        final GameManagerService gameManagerService = (GameManagerService)
                ServiceManager.getService(Context.GAME_SERVICE);
        final String currentMode = gameModeIntToString(
                gameManagerService.getGameMode(packageName, userId));
        final StringJoiner sj = new StringJoiner(",");
        for (int mode : gameManagerService.getAvailableGameModes(packageName, userId)) {
            sj.add(gameModeIntToString(mode));
        }
        pw.println(packageName + " current mode: " + currentMode + ", available game modes: [" + sj
                + "]");
        return 0;
    }

    private int runListGameModeConfigs(PrintWriter pw)
            throws ServiceNotFoundException, RemoteException {
        final String packageName = getNextArgRequired();
        final int userId = ActivityManager.getCurrentUser();
        if (!isPackageGame(packageName, userId, pw)) {
            return -1;
        }
        final GameManagerService gameManagerService = (GameManagerService)
                ServiceManager.getService(Context.GAME_SERVICE);

        final String listStr = gameManagerService.getInterventionList(packageName,
                userId);

        if (listStr == null) {
            pw.println("No interventions found for " + packageName);
        } else {
            pw.println(packageName + " interventions: " + listStr);
        }
        return 0;
    }

    private int runSetGameMode(PrintWriter pw) throws ServiceNotFoundException, RemoteException {
        final String option = getNextOption();
        String userIdStr = null;
        if (option != null && option.equals("--user")) {
            userIdStr = getNextArgRequired();
        }
        final String gameMode = getNextArgRequired();
        final String packageName = getNextArgRequired();
        int userId = userIdStr != null ? Integer.parseInt(userIdStr)
                : ActivityManager.getCurrentUser();
        if (!isPackageGame(packageName, userId, pw)) {
            return -1;
        }
        final IGameManagerService service = IGameManagerService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.GAME_SERVICE));
        boolean batteryModeSupported = false;
        boolean perfModeSupported = false;
        int[] modes = service.getAvailableGameModes(packageName, userId);
        for (int mode : modes) {
            if (mode == GameManager.GAME_MODE_PERFORMANCE) {
                perfModeSupported = true;
            } else if (mode == GameManager.GAME_MODE_BATTERY) {
                batteryModeSupported = true;
            }
        }
        switch (gameMode.toLowerCase()) {
            case STANDARD_MODE_NUM:
            case STANDARD_MODE_STR:
                // Standard mode can be used to specify loading ANGLE as the default OpenGL ES
                // driver, so it should always be available.
                service.setGameMode(packageName, GameManager.GAME_MODE_STANDARD, userId);
                pw.println("Set game mode to `STANDARD` for user `" + userId + "` in game `"
                        + packageName + "`");
                break;
            case PERFORMANCE_MODE_NUM:
            case PERFORMANCE_MODE_STR:
                if (perfModeSupported) {
                    service.setGameMode(packageName, GameManager.GAME_MODE_PERFORMANCE,
                            userId);
                    pw.println("Set game mode to `PERFORMANCE` for user `" + userId + "` in game `"
                            + packageName + "`");
                } else {
                    pw.println("Game mode: " + gameMode + " not supported by "
                            + packageName);
                    return -1;
                }
                break;
            case BATTERY_MODE_NUM:
            case BATTERY_MODE_STR:
                if (batteryModeSupported) {
                    service.setGameMode(packageName, GameManager.GAME_MODE_BATTERY,
                            userId);
                    pw.println("Set game mode to `BATTERY` for user `" + userId + "` in game `"
                            + packageName + "`");
                } else {
                    pw.println("Game mode: " + gameMode + " not supported by "
                            + packageName);
                    return -1;
                }
                break;
            case CUSTOM_MODE_NUM:
            case CUSTOM_MODE_STR:
                service.setGameMode(packageName, GameManager.GAME_MODE_CUSTOM, userId);
                pw.println("Set game mode to `CUSTOM` for user `" + userId + "` in game `"
                        + packageName + "`");
                break;
            default:
                pw.println("Invalid game mode: " + gameMode);
                return -1;
        }
        return 0;
    }

    private int runSetGameModeConfig(PrintWriter pw)
            throws ServiceNotFoundException, RemoteException {
        String option;
        /**
         * handling optional input
         * "--user", "--downscale" and "--fps" can come in any order
         */
        String userIdStr = null;
        String fpsStr = null;
        String downscaleRatio = null;
        int gameMode = GameManager.GAME_MODE_CUSTOM;
        while ((option = getNextOption()) != null) {
            switch (option) {
                case "--mode":
                    gameMode = Integer.parseInt(getNextArgRequired());
                    break;
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
                        if ("disable".equals(downscaleRatio)) {
                            downscaleRatio = "-1";
                        } else {
                            try {
                                Float.parseFloat(downscaleRatio);
                            } catch (NumberFormatException e) {
                                pw.println("Invalid scaling ratio '" + downscaleRatio + "'");
                                return -1;
                            }
                        }
                    } else {
                        pw.println("Duplicate option '" + option + "'");
                        return -1;
                    }
                    break;
                case "--fps":
                    if (fpsStr == null) {
                        fpsStr = getNextArgRequired();
                        try {
                            Integer.parseInt(fpsStr);
                        } catch (NumberFormatException e) {
                            pw.println("Invalid frame rate: '" + fpsStr + "'");
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
        if (!isPackageGame(packageName, userId, pw)) {
            return -1;
        }

        final GameManagerService gameManagerService = (GameManagerService)
                ServiceManager.getService(Context.GAME_SERVICE);
        if (gameManagerService == null) {
            pw.println("Failed to find GameManagerService on device");
            return -1;
        }
        gameManagerService.setGameModeConfigOverride(packageName, userId, gameMode,
                fpsStr, downscaleRatio);
        pw.println("Set custom mode intervention config for user `" + userId + "` in game `"
                + packageName + "` as: `"
                + "downscaling-ratio: " + downscaleRatio + ";"
                + "fps-override: " + fpsStr + "`");
        return 0;
    }

    private int runResetGameModeConfig(PrintWriter pw)
            throws ServiceNotFoundException, RemoteException {
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
        int userId = userIdStr != null ? Integer.parseInt(userIdStr)
                : ActivityManager.getCurrentUser();
        if (!isPackageGame(packageName, userId, pw)) {
            return -1;
        }

        final GameManagerService gameManagerService = (GameManagerService)
                ServiceManager.getService(Context.GAME_SERVICE);
        if (gameMode == null) {
            gameManagerService.resetGameModeConfigOverride(packageName, userId, -1);
            return 0;
        }

        switch (gameMode.toLowerCase(Locale.getDefault())) {
            case PERFORMANCE_MODE_NUM:
            case PERFORMANCE_MODE_STR:
                gameManagerService.resetGameModeConfigOverride(packageName, userId,
                        GameManager.GAME_MODE_PERFORMANCE);
                break;
            case BATTERY_MODE_NUM:
            case BATTERY_MODE_STR:
                gameManagerService.resetGameModeConfigOverride(packageName, userId,
                        GameManager.GAME_MODE_BATTERY);
                break;
            default:
                pw.println("Invalid game mode: " + gameMode);
                return -1;
        }
        return 0;
    }

    private static String gameModeIntToString(@GameManager.GameMode int gameMode) {
        switch (gameMode) {
            case GameManager.GAME_MODE_BATTERY:
                return BATTERY_MODE_STR;
            case GameManager.GAME_MODE_PERFORMANCE:
                return PERFORMANCE_MODE_STR;
            case GameManager.GAME_MODE_CUSTOM:
                return CUSTOM_MODE_STR;
            case GameManager.GAME_MODE_STANDARD:
                return STANDARD_MODE_STR;
            case GameManager.GAME_MODE_UNSUPPORTED:
                return UNSUPPORTED_MODE_STR;
        }
        return "";
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Game manager (game) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  downscale");
        pw.println("      Deprecated. Please use `custom` command.");
        pw.println("  list-configs <PACKAGE_NAME>");
        pw.println("      Lists the current intervention configs of an app.");
        pw.println("  list-modes <PACKAGE_NAME>");
        pw.println("      Lists the current selected and available game modes of an app.");
        pw.println("  mode [--user <USER_ID>] [1|2|3|4|standard|performance|battery|custom] "
                + "<PACKAGE_NAME>");
        pw.println("      Set app to run in the specified game mode, if supported.");
        pw.println("      --user <USER_ID>: apply for the given user,");
        pw.println("                        the current user is used when unspecified.");
        pw.println("  set [intervention configs] <PACKAGE_NAME>");
        pw.println("      Set app to run at custom mode using provided intervention configs");
        pw.println("      Intervention configs consists of:");
        pw.println("      --downscale [0.3|0.35|0.4|0.45|0.5|0.55|0.6|0.65");
        pw.println("                  |0.7|0.75|0.8|0.85|0.9|disable]: Set app to run at the");
        pw.println("                                                   specified scaling ratio.");
        pw.println("      --fps: Integer value to set app to run at the specified fps,");
        pw.println("             if supported. 0 to disable.");
        pw.println("  reset [--mode [2|3|performance|battery] --user <USER_ID>] <PACKAGE_NAME>");
        pw.println("      Resets the game mode of the app to device configuration.");
        pw.println("      This should only be used to reset any override to non custom game mode");
        pw.println("      applied using the deprecated `set` command");
        pw.println("      --mode [2|3|performance|battery]: apply for the given mode,");
        pw.println("                                        resets all modes when unspecified.");
        pw.println("      --user <USER_ID>: apply for the given user,");
        pw.println("                        the current user is used when unspecified.");
    }
}
