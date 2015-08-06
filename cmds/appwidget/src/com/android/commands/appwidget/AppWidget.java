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

package com.android.commands.appwidget;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.internal.appwidget.IAppWidgetService;

/**
 * This class is a command line utility for manipulating app widgets. A client
 * can grant or revoke the permission for a given package to bind app widgets.
 */
public class AppWidget {

    private static final String USAGE =
        "usage: adb shell appwidget [subcommand] [options]\n"
        + "\n"
        + "usage: adb shell appwidget grantbind --package <PACKAGE> "
                + " [--user <USER_ID> | current]\n"
        + "  <PACKAGE> an Android package name.\n"
        + "  <USER_ID> The user id under which the package is installed.\n"
        + "  Example:\n"
        + "  # Grant the \"foo.bar.baz\" package to bind app widgets for the current user.\n"
        + "  adb shell grantbind --package foo.bar.baz --user current\n"
        + "\n"
        + "usage: adb shell appwidget revokebind --package <PACKAGE> "
                + "[--user <USER_ID> | current]\n"
        + "  <PACKAGE> an Android package name.\n"
        + "  <USER_ID> The user id under which the package is installed.\n"
        + "  Example:\n"
        + "  # Revoke the permisison to bind app widgets from the \"foo.bar.baz\" package.\n"
        + "  adb shell revokebind --package foo.bar.baz --user current\n"
        + "\n";

    private static class Parser {
        private static final String ARGUMENT_GRANT_BIND = "grantbind";
        private static final String ARGUMENT_REVOKE_BIND = "revokebind";
        private static final String ARGUMENT_PACKAGE = "--package";
        private static final String ARGUMENT_USER = "--user";
        private static final String ARGUMENT_PREFIX = "--";
        private static final String VALUE_USER_CURRENT = "current";

        private final Tokenizer mTokenizer;

        public Parser(String[] args) {
            mTokenizer = new Tokenizer(args);
        }

        public Runnable parseCommand() {
            try {
                String operation = mTokenizer.nextArg();
                if (ARGUMENT_GRANT_BIND.equals(operation)) {
                    return parseSetGrantBindAppWidgetPermissionCommand(true);
                } else if (ARGUMENT_REVOKE_BIND.equals(operation)) {
                    return parseSetGrantBindAppWidgetPermissionCommand(false);
                } else {
                    throw new IllegalArgumentException("Unsupported operation: " + operation);
                }
            } catch (IllegalArgumentException iae) {
                System.out.println(USAGE);
                System.out.println("[ERROR] " + iae.getMessage());
                return null;
            }
        }

        private SetBindAppWidgetPermissionCommand parseSetGrantBindAppWidgetPermissionCommand(
                boolean granted) {
            String packageName = null;
            int userId = UserHandle.USER_SYSTEM;
            for (String argument; (argument = mTokenizer.nextArg()) != null;) {
                if (ARGUMENT_PACKAGE.equals(argument)) {
                    packageName = argumentValueRequired(argument);
                } else if (ARGUMENT_USER.equals(argument)) {
                    String user = argumentValueRequired(argument);
                    if (VALUE_USER_CURRENT.equals(user)) {
                        userId = UserHandle.USER_CURRENT;
                    } else {
                        userId = Integer.parseInt(user);
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
            if (packageName == null) {
                throw new IllegalArgumentException("Package name not specified."
                        + " Did you specify --package argument?");
            }
            return new SetBindAppWidgetPermissionCommand(packageName, granted, userId);
        }

        private String argumentValueRequired(String argument) {
            String value = mTokenizer.nextArg();
            if (TextUtils.isEmpty(value) || value.startsWith(ARGUMENT_PREFIX)) {
                throw new IllegalArgumentException("No value for argument: " + argument);
            }
            return value;
        }
    }

    private static class Tokenizer {
        private final String[] mArgs;
        private int mNextArg;

        public Tokenizer(String[] args) {
            mArgs = args;
        }

        private String nextArg() {
            if (mNextArg < mArgs.length) {
                return mArgs[mNextArg++];
            } else {
                return null;
            }
        }
    }

    private static class SetBindAppWidgetPermissionCommand implements Runnable {
        final String mPackageName;
        final boolean mGranted;
        final int mUserId;

        public SetBindAppWidgetPermissionCommand(String packageName, boolean granted,
                int userId) {
            mPackageName = packageName;
            mGranted = granted;
            mUserId = userId;
        }

        @Override
        public void run() {
            IBinder binder = ServiceManager.getService(Context.APPWIDGET_SERVICE);
            IAppWidgetService appWidgetService = IAppWidgetService.Stub.asInterface(binder);
            try {
                appWidgetService.setBindAppWidgetPermission(mPackageName, mUserId, mGranted);
            } catch (RemoteException re) {
                re.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        Parser parser = new Parser(args);
        Runnable command = parser.parseCommand();
        if (command != null) {
            command.run();
        }
    }
}
