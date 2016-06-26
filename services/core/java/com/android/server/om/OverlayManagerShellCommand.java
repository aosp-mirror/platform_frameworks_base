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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

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
            final PrintWriter out = getOutPrintWriter();
            out.println("The overlay manager has already been initialized.");
            return -1;
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
                case "disable-all":
                	return runDisableAll();
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
        out.println("  enable [--user USER_ID] [PACKAGE [PACKAGE [...]]]");
        out.println("    Enable overlay package PACKAGE or subsequent counts of PACKAGE.");
        out.println("  disable [--user USER_ID] [PACKAGE [PACKAGE [...]]]");
        out.println("    Disable overlay package PACKAGE or subsequent counts of PACKAGE.");
        out.println("  disable-all [--user USER_ID]");
        out.println("    Disable all overlay packages.");
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
            for (final OverlayInfo oi : allOverlays.get(targetPackageName)) {
                String status = "---";
                if (oi.isApproved()) {
                    status = "[ ]";
                }
                if (oi.isEnabled()) {
                    status = "[x]";
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

        int argc = 0;
        String packageName = getNextArgRequired();
        ArrayList<String> packages = new ArrayList<>();
        if (packageName == null) {
            System.err.println("Error: no packages specified");
            return 1;
        }
        while (packageName != null) {
            argc++;
            packages.add(packageName);
            packageName = getNextArg();
        }
        if (argc > 1) {
            for (String pkg : packages) {
                boolean ret = mInterface.setEnabled(pkg, enable, userId, false);
                if (!ret) {
                    System.err.println("Error: Failed to " + ((enable) ? "enable ": "disable ") + pkg);
                }
            }
            return 0;
        } else if (argc == 1) {
            return mInterface.setEnabled(packages.get(0), enable, userId, false) ? 0 : 1;
        } else {
            System.err.println("Error: A fatal exception has occurred.");
            return 1;
        }
    }

    private int runDisableAll() {
        int userId = UserHandle.USER_OWNER;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                	break;
                default:
                    System.err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        try {
            Map<String, List<OverlayInfo>> targetsAndOverlays = mInterface.getAllOverlays(userId);
            int iterator = 0;
            int overlaySize = targetsAndOverlays.entrySet().size();
            for (Entry<String, List<OverlayInfo>> targetEntry : targetsAndOverlays.entrySet()) {
                int iterator_nested = 0;
                int targetSize_nested = targetEntry.getValue().size();
                iterator++;
                for (OverlayInfo oi : targetEntry.getValue()) {
                    if (iterator_nested < targetSize_nested) {
                        if (oi.isEnabled()) {
                            boolean worked = mInterface.setEnabled(oi.packageName, false, userId, true);
                            if (!worked) {
                                System.err.println("Failed to disable " + oi.packageName);
                            }
                        }
                    } else {
                        if (iterator == overlaySize) {
                            if (oi.isEnabled()) {
                                boolean worked = mInterface.setEnabled(oi.packageName, false, userId, false);
                                if (!worked) {
                                    System.err.println("Failed to disable " + oi.packageName);
                                }
                            }
                        } else {
                            if (oi.isEnabled()) {
                                boolean worked = mInterface.setEnabled(oi.packageName, false, userId, true);
                                if (!worked) {
                                    System.err.println("Failed to disable " + oi.packageName);
                                }
                            }
                        }
                    }
                    iterator_nested++;
                }
            }
            mInterface.refresh(userId);
        } catch (RemoteException re) {
            System.err.println(re.toString());
            System.err.println("Error: A fatal exception has occurred.");
        }
        return 0;
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
