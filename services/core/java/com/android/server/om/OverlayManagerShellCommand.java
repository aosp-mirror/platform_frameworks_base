/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.om;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * Implementation of 'cmd overlay' commands.
 *
 * This class provides an interface to the OverlayManagerService via adb.
 * Intended only for manual debugging. Execute 'adb exec-out cmd overlay help'
 * for a list of available commands.
 */
final class OverlayManagerShellCommand extends ShellCommand {
    private final IOverlayManager mInterface;

    OverlayManagerShellCommand(@NonNull final IOverlayManager iom) {
        mInterface = iom;
    }

    @Override
    public int onCommand(@Nullable final String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter err = getErrPrintWriter();
        try {
            switch (cmd) {
                case "list":
                    return runList();
                case "enable":
                    return runEnableDisable(true);
                case "disable":
                    return runEnableDisable(false);
                case "enable-exclusive":
                    return runEnableExclusive();
                case "set-priority":
                    return runSetPriority();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
        } catch (RemoteException e) {
            err.println("Remote exception: " + e);
        }
        return -1;
    }

    @Override
    public void onHelp() {
        final PrintWriter out = getOutPrintWriter();
        out.println("Overlay manager (overlay) commands:");
        out.println("  help");
        out.println("    Print this help text.");
        out.println("  dump [--verbose] [--user USER_ID] [PACKAGE [PACKAGE [...]]]");
        out.println("    Print debugging information about the overlay manager.");
        out.println("  list [--user USER_ID] [PACKAGE [PACKAGE [...]]]");
        out.println("    Print information about target and overlay packages.");
        out.println("    Overlay packages are printed in priority order. With optional");
        out.println("    parameters PACKAGEs, limit output to the specified packages");
        out.println("    but include more information about each package.");
        out.println("  enable [--user USER_ID] PACKAGE");
        out.println("    Enable overlay package PACKAGE.");
        out.println("  disable [--user USER_ID] PACKAGE");
        out.println("    Disable overlay package PACKAGE.");
        out.println("  enable-exclusive [--user USER_ID] [--category] PACKAGE");
        out.println("    Enable overlay package PACKAGE and disable all other overlays for");
        out.println("    its target package. If the --category option is given, only disables");
        out.println("    other overlays in the same category.");
        out.println("  set-priority [--user USER_ID] PACKAGE PARENT|lowest|highest");
        out.println("    Change the priority of the overlay PACKAGE to be just higher than");
        out.println("    the priority of PACKAGE_PARENT If PARENT is the special keyword");
        out.println("    'lowest', change priority of PACKAGE to the lowest priority.");
        out.println("    If PARENT is the special keyword 'highest', change priority of");
        out.println("    PACKAGE to the highest priority.");
    }

    private int runList() throws RemoteException {
        final PrintWriter out = getOutPrintWriter();
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final Map<String, List<OverlayInfo>> allOverlays = mInterface.getAllOverlays(userId);
        for (final String targetPackageName : allOverlays.keySet()) {
            out.println(targetPackageName);
            List<OverlayInfo> overlaysForTarget = allOverlays.get(targetPackageName);
            final int N = overlaysForTarget.size();
            for (int i = 0; i < N; i++) {
                final OverlayInfo oi = overlaysForTarget.get(i);
                String status;
                switch (oi.state) {
                    case OverlayInfo.STATE_ENABLED_STATIC:
                    case OverlayInfo.STATE_ENABLED:
                        status = "[x]";
                        break;
                    case OverlayInfo.STATE_DISABLED:
                        status = "[ ]";
                        break;
                    default:
                        status = "---";
                        break;
                }
                out.println(String.format("%s %s", status, oi.packageName));
            }
            out.println();
        }
        return 0;
    }

    private int runEnableDisable(final boolean enable) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArgRequired();
        return mInterface.setEnabled(packageName, enable, userId) ? 0 : 1;
    }

    private int runEnableExclusive() throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        boolean inCategory = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--category":
                    inCategory = true;
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }
        final String overlay = getNextArgRequired();
        if (inCategory) {
            return mInterface.setEnabledExclusiveInCategory(overlay, userId) ? 0 : 1;
        } else {
            return mInterface.setEnabledExclusive(overlay, true, userId) ? 0 : 1;
        }
    }

    private int runSetPriority() throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArgRequired();
        final String newParentPackageName = getNextArgRequired();

        if ("highest".equals(newParentPackageName)) {
            return mInterface.setHighestPriority(packageName, userId) ? 0 : 1;
        } else if ("lowest".equals(newParentPackageName)) {
            return mInterface.setLowestPriority(packageName, userId) ? 0 : 1;
        } else {
            return mInterface.setPriority(packageName, newParentPackageName, userId) ? 0 : 1;
        }
    }
}
