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

import com.android.internal.app.IAppOpsService;
import com.android.internal.os.BaseCommand;

import java.io.PrintStream;

/**
 * This class is a command line utility for manipulating AppOps permissions.
 */
public class AppOpsCommand extends BaseCommand {

    public static void main(String[] args) {
        new AppOpsCommand().run(args);
    }

    @Override
    public void onShowUsage(PrintStream out) {
        out.println("usage: adb shell appops set <PACKAGE> <OP> "
                + "<allow|ignore|deny|default> [--user <USER_ID>]\n"
                + "  <PACKAGE> an Android package name.\n"
                + "  <OP>      an AppOps operation.\n"
                + "  <USER_ID> the user id under which the package is installed. If --user is not\n"
                + "            specified, the current user is assumed.\n");
    }

    private static final String COMMAND_SET = "set";

    @Override
    public void onRun() throws Exception {
        String command = nextArgRequired();
        switch (command) {
            case COMMAND_SET:
                runSet();
                break;

            default:
                throw new IllegalArgumentException("Unknown command '" + command + "'.");
        }
    }

    private static final String ARGUMENT_USER = "--user";

    // Modes
    private static final String MODE_ALLOW = "allow";
    private static final String MODE_DENY = "deny";
    private static final String MODE_IGNORE = "ignore";
    private static final String MODE_DEFAULT = "default";

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
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
        }

        if (packageName == null) {
            throw new IllegalArgumentException("Package name not specified.");
        } else if (op == null) {
            throw new IllegalArgumentException("Operation not specified.");
        } else if (mode == null) {
            throw new IllegalArgumentException("Mode not specified.");
        }

        final int opInt = AppOpsManager.strOpToOp(op);
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
                throw new IllegalArgumentException("Mode is invalid.");
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
            throw new Exception("No UID for " + packageName + " for user " + userId);
        }
        appOpsService.setMode(opInt, uid, packageName, modeInt);
    }
}
