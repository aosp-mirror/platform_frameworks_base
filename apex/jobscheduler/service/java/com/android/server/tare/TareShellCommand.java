/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.tare;

import android.Manifest;
import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.os.Binder;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;

/**
 * Shell command handler for TARE.
 */
public class TareShellCommand extends BasicShellCommandHandler {
    static final int COMMAND_ERROR = -1;
    static final int COMMAND_SUCCESS = 0;

    private final InternalResourceService mIrs;

    public TareShellCommand(@NonNull InternalResourceService irs) {
        mIrs = irs;
    }

    @Override
    public int onCommand(String cmd) {
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd != null ? cmd : "") {
                case "clear-vip":
                    return runClearVip(pw);
                case "set-vip":
                    return runSetVip(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Exception: " + e);
        }
        return COMMAND_ERROR;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();

        pw.println("TARE commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  clear-vip");
        pw.println("    Clears all VIP settings resulting from previous calls using `set-vip` and");
        pw.println("    resets them all to default.");
        pw.println("  set-vip <USER_ID> <PACKAGE> <true|false|default>");
        pw.println("    Designate the app as a Very Important Package or not. A VIP is allowed to");
        pw.println("    do as much work as it wants, regardless of TARE state.");
        pw.println("    The user ID must be an explicit user ID. USER_ALL, CURRENT, etc. are not");
        pw.println("    supported.");
        pw.println();
    }

    private void checkPermission(@NonNull String operation) throws Exception {
        final int perm = mIrs.getContext()
                .checkCallingOrSelfPermission(Manifest.permission.CHANGE_APP_IDLE_STATE);
        if (perm != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Uid " + Binder.getCallingUid()
                    + " not permitted to " + operation);
        }
    }

    private int runClearVip(@NonNull PrintWriter pw) throws Exception {
        checkPermission("clear vip");

        final long ident = Binder.clearCallingIdentity();
        try {
            return mIrs.executeClearVip(pw);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int runSetVip(@NonNull PrintWriter pw) throws Exception {
        checkPermission("modify vip");

        final int userId = Integer.parseInt(getNextArgRequired());
        final String pkgName = getNextArgRequired();
        final String vipState = getNextArgRequired();
        final Boolean isVip = "default".equals(vipState) ? null : Boolean.valueOf(vipState);

        final long ident = Binder.clearCallingIdentity();
        try {
            return mIrs.executeSetVip(pw, userId, pkgName, isVip);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
