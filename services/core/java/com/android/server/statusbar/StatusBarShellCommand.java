/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.statusbar;

import static android.app.StatusBarManager.DEFAULT_SETUP_DISABLE2_FLAGS;
import static android.app.StatusBarManager.DEFAULT_SETUP_DISABLE_FLAGS;
import static android.app.StatusBarManager.DISABLE2_NONE;
import static android.app.StatusBarManager.DISABLE_NONE;

import android.app.StatusBarManager.DisableInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.service.quicksettings.TileService;
import android.util.Pair;

import java.io.PrintWriter;

public class StatusBarShellCommand extends ShellCommand {

    private static final IBinder sToken = new StatusBarShellCommandToken();

    private final StatusBarManagerService mInterface;
    private final Context mContext;

    public StatusBarShellCommand(StatusBarManagerService service, Context context) {
        mInterface = service;
        mContext = context;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            onHelp();
            return 1;
        }
        try {
            switch (cmd) {
                case "expand-notifications":
                    return runExpandNotifications();
                case "expand-settings":
                    return runExpandSettings();
                case "collapse":
                    return runCollapse();
                case "add-tile":
                    return runAddTile();
                case "remove-tile":
                    return runRemoveTile();
                case "set-tiles":
                    return runSetTiles();
                case "click-tile":
                    return runClickTile();
                case "check-support":
                    final PrintWriter pw = getOutPrintWriter();
                    pw.println(String.valueOf(TileService.isQuickSettingsSupported()));
                    return 0;
                case "get-status-icons":
                    return runGetStatusIcons();
                case "disable-for-setup":
                    return runDisableForSetup();
                case "send-disable-flag":
                    return runSendDisableFlag();
                case "tracing":
                    return runTracing();
                case "run-gc":
                    return runGc();
                // Handle everything that would be handled in `handleDefaultCommand()` explicitly,
                // so the default can be to pass all args to StatusBar
                case "-h":
                case "help":
                    onHelp();
                    return 0;
                case "dump":
                    return super.handleDefaultCommands(cmd);
                default:
                    return runPassArgsToStatusBar();
            }
        } catch (RemoteException e) {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int runAddTile() throws RemoteException {
        mInterface.addTile(ComponentName.unflattenFromString(getNextArgRequired()));
        return 0;
    }

    private int runRemoveTile() throws RemoteException {
        mInterface.remTile(ComponentName.unflattenFromString(getNextArgRequired()));
        return 0;
    }

    private int runSetTiles() throws RemoteException {
        mInterface.setTiles(getNextArgRequired());
        return 0;
    }

    private int runClickTile() throws RemoteException {
        mInterface.clickTile(ComponentName.unflattenFromString(getNextArgRequired()));
        return 0;
    }

    private int runCollapse() throws RemoteException {
        mInterface.collapsePanels();
        return 0;
    }

    private int runExpandSettings() throws RemoteException {
        mInterface.expandSettingsPanel(null);
        return 0;
    }

    private int runExpandNotifications() throws RemoteException {
        mInterface.expandNotificationsPanel();
        return 0;
    }

    private int runGetStatusIcons() {
        final PrintWriter pw = getOutPrintWriter();
        for (String icon : mInterface.getStatusBarIcons()) {
            pw.println(icon);
        }
        return 0;
    }

    private int runDisableForSetup() {
        String arg = getNextArgRequired();
        String pkg = mContext.getPackageName();
        boolean disable = Boolean.parseBoolean(arg);

        if (disable) {
            mInterface.disable(DEFAULT_SETUP_DISABLE_FLAGS, sToken, pkg);
            mInterface.disable2(DEFAULT_SETUP_DISABLE2_FLAGS, sToken, pkg);
        } else {
            mInterface.disable(DISABLE_NONE, sToken, pkg);
            mInterface.disable2(DISABLE2_NONE, sToken, pkg);
        }

        return 0;
    }

    private int runSendDisableFlag() {
        String pkg = mContext.getPackageName();
        int disable1 = DISABLE_NONE;
        int disable2 = DISABLE2_NONE;

        DisableInfo info = new DisableInfo();

        String arg = getNextArg();
        while (arg != null) {
            switch (arg) {
                case "search":
                    info.setSearchDisabled(true);
                    break;
                case "home":
                    info.setNagivationHomeDisabled(true);
                    break;
                case "recents":
                    info.setRecentsDisabled(true);
                    break;
                case "notification-alerts":
                    info.setNotificationPeekingDisabled(true);
                    break;
                case "statusbar-expansion":
                    info.setStatusBarExpansionDisabled(true);
                    break;
                case "system-icons":
                    info.setSystemIconsDisabled(true);
                    break;
                case "clock":
                    info.setClockDisabled(true);
                    break;
                case "notification-icons":
                    info.setNotificationIconsDisabled(true);
                    break;
                case "quick-settings":
                    info.setQuickSettingsDisabled(true);
                    break;
                default:
                    break;
            }

            arg = getNextArg();
        }

        Pair<Integer, Integer> flagPair = info.toFlags();

        mInterface.disable(flagPair.first, sToken, pkg);
        mInterface.disable2(flagPair.second, sToken, pkg);

        return 0;
    }

    private int runPassArgsToStatusBar() {
        mInterface.passThroughShellCommand(getAllArgs(), getOutFileDescriptor());
        return 0;
    }

    private int runTracing() {
        switch (getNextArg()) {
            case "start":
                mInterface.startTracing();
                break;
            case "stop":
                mInterface.stopTracing();
                break;
        }
        return 0;
    }

    private int runGc() {
        mInterface.runGcForTest();
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Status bar commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  expand-notifications");
        pw.println("    Open the notifications panel.");
        pw.println("");
        pw.println("  expand-settings");
        pw.println("    Open the notifications panel and expand quick settings if present.");
        pw.println("");
        pw.println("  collapse");
        pw.println("    Collapse the notifications and settings panel.");
        pw.println("");
        pw.println("  add-tile COMPONENT");
        pw.println("    Add a TileService of the specified component");
        pw.println("");
        pw.println("  remove-tile COMPONENT");
        pw.println("    Remove a TileService of the specified component");
        pw.println("");
        pw.println("  set-tiles LIST-OF-TILES");
        pw.println("    Sets the list of tiles as the current Quick Settings tiles");
        pw.println("");
        pw.println("  click-tile COMPONENT");
        pw.println("    Click on a TileService of the specified component");
        pw.println("");
        pw.println("  check-support");
        pw.println("    Check if this device supports QS + APIs");
        pw.println("");
        pw.println("  get-status-icons");
        pw.println("    Print the list of status bar icons and the order they appear in");
        pw.println("");
        pw.println("  disable-for-setup DISABLE");
        pw.println("    If true, disable status bar components unsuitable for device setup");
        pw.println("");
        pw.println("  send-disable-flag FLAG...");
        pw.println("    Send zero or more disable flags (parsed individually) to StatusBarManager");
        pw.println("    Valid options:");
        pw.println("        <blank>             - equivalent to \"none\"");
        pw.println("        none                - re-enables all components");
        pw.println("        search              - disable search");
        pw.println("        home                - disable naviagation home");
        pw.println("        recents             - disable recents/overview");
        pw.println("        notification-peek   - disable notification peeking");
        pw.println("        statusbar-expansion - disable status bar expansion");
        pw.println("        system-icons        - disable system icons appearing in status bar");
        pw.println("        clock               - disable clock appearing in status bar");
        pw.println("        notification-icons  - disable notification icons from status bar");
        pw.println("        quick-settings      - disable Quick Settings");
        pw.println("");
        pw.println("  tracing (start | stop)");
        pw.println("    Start or stop SystemUI tracing");
        pw.println("");
        pw.println("  NOTE: any command not listed here will be passed through to IStatusBar");
        pw.println("");
        pw.println("  Commands implemented in SystemUI:");
        pw.flush();
        // Sending null args to systemui will print help
        mInterface.passThroughShellCommand(new String[] {}, getOutFileDescriptor());
    }

    /**
     * Token to send to StatusBarManagerService for disable* commands
     */
    private static final class StatusBarShellCommandToken extends Binder {
    }
}
