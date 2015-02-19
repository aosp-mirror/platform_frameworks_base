/*
** Copyright 2014, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.commands.appops;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.os.ServiceManager;
import android.os.UserHandle;

import android.util.TimeUtils;
import com.android.internal.app.IAppOpsService;
import com.android.internal.os.BaseCommand;

import java.io.PrintStream;
import java.util.List;

/**
 * This class is a command line utility for manipulating AppOps permissions.
 */
public class AppOpsCommand extends BaseCommand {

    public static void main(String[] args) {
        new AppOpsCommand().run(args);
    }

    @Override
    public void onShowUsage(PrintStream out) {
        out.println("usage: appops set [--user <USER_ID>] <PACKAGE> <OP> <MODE>\n"
                + "       appops get [--user <USER_ID>] <PACKAGE> [<OP>]\n"
                + "       appops reset [--user <USER_ID>] [<PACKAGE>]\n"
                + "  <PACKAGE> an Android package name.\n"
                + "  <OP>      an AppOps operation.\n"
                + "  <MODE>    one of allow, ignore, deny, or default\n"
                + "  <USER_ID> the user id under which the package is installed. If --user is not\n"
                + "            specified, the current user is assumed.\n");
    }

    private static final String COMMAND_SET = "set";
    private static final String COMMAND_GET = "get";
    private static final String COMMAND_RESET = "reset";

    @Override
    public void onRun() throws Exception {
        String command = nextArgRequired();
        switch (command) {
            case COMMAND_SET:
                runSet();
                break;

            case COMMAND_GET:
                runGet();
                break;

            case COMMAND_RESET:
                runReset();
                break;

            default:
                System.err.println("Error: Unknown command: '" + command + "'.");
                break;
        }
    }

    private static final String ARGUMENT_USER = "--user";

    // Modes
    private static final String MODE_ALLOW = "allow";
    private static final String MODE_DENY = "deny";
    private static final String MODE_IGNORE = "ignore";
    private static final String MODE_DEFAULT = "default";

    private int strOpToOp(String op) {
        try {
            return AppOpsManager.strOpToOp(op);
        } catch (IllegalArgumentException e) {
        }
        try {
            return Integer.parseInt(op);
        } catch (NumberFormatException e) {
        }
        try {
            return AppOpsManager.strDebugOpToOp(op);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return -1;
        }
    }

    private void runSet() throws Exception {
        String packageName = null;
        String op = null;
        String mode = null;
        int userId = UserHandle.USER_CURRENT;
        for (String argument; (argument = nextArg()) != null;) {
            if (ARGUMENT_USER.equals(argument)) {
                userId = Integer.parseInt(nextArgRequired());
            } else {
                if (packageName == null) {
                    packageName = argument;
                } else if (op == null) {
                    op = argument;
                } else if (mode == null) {
                    mode = argument;
                } else {
                    System.err.println("Error: Unsupported argument: " + argument);
                    return;
                }
            }
        }

        if (packageName == null) {
            System.err.println("Error: Package name not specified.");
            return;
        } else if (op == null) {
            System.err.println("Error: Operation not specified.");
            return;
        } else if (mode == null) {
            System.err.println("Error: Mode not specified.");
            return;
        }

        final int opInt = strOpToOp(op);
        if (opInt < 0) {
            return;
        }
        final int modeInt;
        switch (mode) {
            case MODE_ALLOW:
                modeInt = AppOpsManager.MODE_ALLOWED;
                break;
            case MODE_DENY:
                modeInt = AppOpsManager.MODE_ERRORED;
                break;
            case MODE_IGNORE:
                modeInt = AppOpsManager.MODE_IGNORED;
                break;
            case MODE_DEFAULT:
                modeInt = AppOpsManager.MODE_DEFAULT;
                break;
            default:
                System.err.println("Error: Mode " + mode + " is not valid,");
                return;
        }

        // Parsing complete, let's execute the command.

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final IPackageManager pm = ActivityThread.getPackageManager();
        final IAppOpsService appOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        final int uid = pm.getPackageUid(packageName, userId);
        if (uid < 0) {
            System.err.println("Error: No UID for " + packageName + " in user " + userId);
            return;
        }
        appOpsService.setMode(opInt, uid, packageName, modeInt);
    }

    private void runGet() throws Exception {
        String packageName = null;
        String op = null;
        int userId = UserHandle.USER_CURRENT;
        for (String argument; (argument = nextArg()) != null;) {
            if (ARGUMENT_USER.equals(argument)) {
                userId = Integer.parseInt(nextArgRequired());
            } else {
                if (packageName == null) {
                    packageName = argument;
                } else if (op == null) {
                    op = argument;
                } else {
                    System.err.println("Error: Unsupported argument: " + argument);
                    return;
                }
            }
        }

        if (packageName == null) {
            System.err.println("Error: Package name not specified.");
            return;
        }

        final int opInt = op != null ? strOpToOp(op) : 0;

        // Parsing complete, let's execute the command.

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final IPackageManager pm = ActivityThread.getPackageManager();
        final IAppOpsService appOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        final int uid = pm.getPackageUid(packageName, userId);
        if (uid < 0) {
            System.err.println("Error: No UID for " + packageName + " in user " + userId);
            return;
        }
        List<AppOpsManager.PackageOps> ops = appOpsService.getOpsForPackage(uid, packageName,
                op != null ? new int[] {opInt} : null);
        if (ops == null || ops.size() <= 0) {
            System.out.println("No operations.");
            return;
        }
        final long now = System.currentTimeMillis();
        for (int i=0; i<ops.size(); i++) {
            List<AppOpsManager.OpEntry> entries = ops.get(i).getOps();
            for (int j=0; j<entries.size(); j++) {
                AppOpsManager.OpEntry ent = entries.get(j);
                System.out.print(AppOpsManager.opToName(ent.getOp()));
                System.out.print(": ");
                switch (ent.getMode()) {
                    case AppOpsManager.MODE_ALLOWED:
                        System.out.print("allow");
                        break;
                    case AppOpsManager.MODE_IGNORED:
                        System.out.print("ignore");
                        break;
                    case AppOpsManager.MODE_ERRORED:
                        System.out.print("deny");
                        break;
                    case AppOpsManager.MODE_DEFAULT:
                        System.out.print("default");
                        break;
                    default:
                        System.out.print("mode=");
                        System.out.print(ent.getMode());
                        break;
                }
                if (ent.getTime() != 0) {
                    System.out.print("; time=");
                    StringBuilder sb = new StringBuilder();
                    TimeUtils.formatDuration(now - ent.getTime(), sb);
                    System.out.print(sb);
                    System.out.print(" ago");
                }
                if (ent.getRejectTime() != 0) {
                    System.out.print("; rejectTime=");
                    StringBuilder sb = new StringBuilder();
                    TimeUtils.formatDuration(now - ent.getRejectTime(), sb);
                    System.out.print(sb);
                    System.out.print(" ago");
                }
                if (ent.getDuration() == -1) {
                    System.out.print(" (running)");
                } else if (ent.getDuration() != 0) {
                    System.out.print("; duration=");
                    StringBuilder sb = new StringBuilder();
                    TimeUtils.formatDuration(ent.getDuration(), sb);
                    System.out.print(sb);
                }
                System.out.println();
            }
        }
    }

    private void runReset() throws Exception {
        String packageName = null;
        int userId = UserHandle.USER_CURRENT;
        for (String argument; (argument = nextArg()) != null;) {
            if (ARGUMENT_USER.equals(argument)) {
                String userStr = nextArgRequired();
                if ("all".equals(userStr)) {
                    userId = UserHandle.USER_ALL;
                } else if ("current".equals(userStr)) {
                    userId = UserHandle.USER_CURRENT;
                } else if ("owner".equals(userStr)) {
                    userId = UserHandle.USER_OWNER;
                } else {
                    userId = Integer.parseInt(nextArgRequired());
                }
            } else {
                if (packageName == null) {
                    packageName = argument;
                } else {
                    System.err.println("Error: Unsupported argument: " + argument);
                    return;
                }
            }
        }

        // Parsing complete, let's execute the command.

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final IAppOpsService appOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        appOpsService.resetAllModes(userId, packageName);
        System.out.print("Reset all modes for: ");
        if (userId == UserHandle.USER_ALL) {
            System.out.print("all users");
        } else {
            System.out.print("user "); System.out.print(userId);
        }
        System.out.print(", ");
        if (packageName == null) {
            System.out.println("all packages");
        } else {
            System.out.print("package "); System.out.println(packageName);
        }
    }
}
