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

import android.content.ComponentName;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.service.quicksettings.TileService;

import com.android.internal.statusbar.IStatusBarService;

import java.io.PrintWriter;

public class StatusBarShellCommand extends ShellCommand {

    private final StatusBarManagerService mInterface;

    public StatusBarShellCommand(StatusBarManagerService service) {
        mInterface = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
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
                case "click-tile":
                    return runClickTile();
                case "check-support":
                    final PrintWriter pw = getOutPrintWriter();
                    pw.println(String.valueOf(TileService.isQuickSettingsSupported()));
                    return 0;
                case "get-status-icons":
                    return runGetStatusIcons();
                default:
                    return handleDefaultCommands(cmd);
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
        pw.println("  click-tile COMPONENT");
        pw.println("    Click on a TileService of the specified component");
        pw.println("");
        pw.println("  check-support");
        pw.println("    Check if this device supports QS + APIs");
        pw.println("");
        pw.println("  get-status-icons");
        pw.println("    Print the list of status bar icons and the order they appear in");
        pw.println("");
    }
}
