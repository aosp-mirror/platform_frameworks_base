/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.location.contexthub;

import android.content.Context;
import android.os.ShellCommand;

import java.io.PrintWriter;

/**
 * ShellCommands for ContextHubService.
 *
 * Use with {@code adb shell cmd contexthub ...}.
 *
 * @hide
 */
public class ContextHubShellCommand extends ShellCommand {

    // Internal service impl -- must perform security checks before touching.
    private final ContextHubService mInternal;
    private final Context mContext;

    public ContextHubShellCommand(Context context, ContextHubService service) {
        mInternal = service;
        mContext = context;
    }

    @Override
    public int onCommand(String cmd) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_CONTEXT_HUB, "ContextHubShellCommand");

        if ("deny".equals(cmd)) {
            return runDisableAuth();
        }

        return handleDefaultCommands(cmd);
    }

    private int runDisableAuth() {
        int contextHubId = Integer.decode(getNextArgRequired());
        String packageName = getNextArgRequired();
        long nanoAppId = Long.decode(getNextArgRequired());

        mInternal.denyClientAuthState(contextHubId, packageName, nanoAppId);
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("ContextHub commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  deny [contextHubId] [packageName] [nanoAppId]");
        pw.println("    Immediately transitions the package's authentication state to denied so");
        pw.println("    can no longer communciate with the nanoapp.");
    }
}
