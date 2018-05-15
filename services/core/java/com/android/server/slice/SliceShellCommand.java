/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.slice;

import android.app.slice.SliceProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.ShellCommand;
import android.util.ArraySet;

import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

public class SliceShellCommand extends ShellCommand {

    private final SliceManagerService mService;

    public SliceShellCommand(SliceManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        switch (cmd) {
            case "get-permissions":
                return runGetPermissions(getNextArgRequired());
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
        pw.println("  get-permissions <authority>");
        pw.println("    List the pkgs that have permission to an authority.");
        pw.println("");

    }

    private int runGetPermissions(String authority) {
        if (Binder.getCallingUid() != Process.SHELL_UID
                && Binder.getCallingUid() != Process.ROOT_UID) {
            getOutPrintWriter().println("Only shell can get permissions");
            return -1;
        }
        Context context = mService.getContext();
        long ident = Binder.clearCallingIdentity();
        try {
            Uri uri = new Uri.Builder()
                    .scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(authority)
                    .build();
            if (!SliceProvider.SLICE_TYPE.equals(context.getContentResolver().getType(uri))) {
                getOutPrintWriter().println(authority + " is not a slice provider");
                return -1;
            }
            Bundle b = context.getContentResolver().call(uri, SliceProvider.METHOD_GET_PERMISSIONS,
                    null, null);
            if (b == null) {
                getOutPrintWriter().println("An error occurred getting permissions");
                return -1;
            }
            String[] permissions = b.getStringArray(SliceProvider.EXTRA_RESULT);
            final PrintWriter pw = getOutPrintWriter();
            Set<String> listedPackages = new ArraySet<>();
            if (permissions != null && permissions.length != 0) {
                List<PackageInfo> apps =
                        context.getPackageManager().getPackagesHoldingPermissions(permissions, 0);
                for (PackageInfo app : apps) {
                    pw.println(app.packageName);
                    listedPackages.add(app.packageName);
                }
            }
            for (String pkg : mService.getAllPackagesGranted(authority)) {
                if (!listedPackages.contains(pkg)) {
                    pw.println(pkg);
                    listedPackages.add(pkg);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return 0;
    }
}
