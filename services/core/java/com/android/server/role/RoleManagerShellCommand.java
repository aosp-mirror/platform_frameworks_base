/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.role;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.role.IRoleManager;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.util.Log;

import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class RoleManagerShellCommand extends ShellCommand {

    @NonNull
    private final IRoleManager mRoleManager;

    RoleManagerShellCommand(@NonNull IRoleManager roleManager) {
        mRoleManager = roleManager;
    }

    private class CallbackFuture extends CompletableFuture<Void> {

        @NonNull
        public RemoteCallback createCallback() {
            return new RemoteCallback(result -> {
                boolean successful = result != null;
                if (successful) {
                    complete(null);
                } else {
                    completeExceptionally(new RuntimeException("Failed"));
                }
            });
        }

        public int waitForResult() {
            try {
                get(5, TimeUnit.SECONDS);
                return 0;
            } catch (Exception e) {
                getErrPrintWriter().println("Error: see logcat for details.\n"
                        + Log.getStackTraceString(e));
                return -1;
            }
        }
    }

    @Override
    public int onCommand(@Nullable String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "add-role-holder":
                    return runAddRoleHolder();
                case "remove-role-holder":
                    return runRemoveRoleHolder();
                case "clear-role-holders":
                    return runClearRoleHolders();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int getUserIdMaybe() {
        int userId = UserHandle.USER_SYSTEM;
        String option = getNextOption();
        if (option != null && option.equals("--user")) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }
        return userId;
    }

    private int getFlagsMaybe() {
        String flags = getNextArg();
        if (flags == null) {
            return 0;
        }
        return Integer.parseInt(flags);
    }

    private int runAddRoleHolder() throws RemoteException {
        int userId = getUserIdMaybe();
        String roleName = getNextArgRequired();
        String packageName = getNextArgRequired();
        int flags = getFlagsMaybe();

        CallbackFuture future = new CallbackFuture();
        mRoleManager.addRoleHolderAsUser(roleName, packageName, flags, userId,
                future.createCallback());
        return future.waitForResult();
    }

    private int runRemoveRoleHolder() throws RemoteException {
        int userId = getUserIdMaybe();
        String roleName = getNextArgRequired();
        String packageName = getNextArgRequired();
        int flags = getFlagsMaybe();

        CallbackFuture future = new CallbackFuture();
        mRoleManager.removeRoleHolderAsUser(roleName, packageName, flags, userId,
                future.createCallback());
        return future.waitForResult();
    }

    private int runClearRoleHolders() throws RemoteException {
        int userId = getUserIdMaybe();
        String roleName = getNextArgRequired();
        int flags = getFlagsMaybe();

        CallbackFuture future = new CallbackFuture();
        mRoleManager.clearRoleHoldersAsUser(roleName, flags, userId, future.createCallback());
        return future.waitForResult();
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Role manager (role) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  add-role-holder [--user USER_ID] ROLE PACKAGE [FLAGS]");
        pw.println("  remove-role-holder [--user USER_ID] ROLE PACKAGE [FLAGS]");
        pw.println("  clear-role-holders [--user USER_ID] ROLE [FLAGS]");
        pw.println();
    }
}
