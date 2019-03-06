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

package com.android.server.contentsuggestions;

import android.annotation.NonNull;
import android.os.ShellCommand;

import java.io.PrintWriter;

/**
 * The shell command implementation for the ContentSuggestionsManagerService.
 */
public class ContentSuggestionsManagerServiceShellCommand extends ShellCommand {

    private static final String TAG =
            ContentSuggestionsManagerServiceShellCommand.class.getSimpleName();

    private final ContentSuggestionsManagerService mService;

    public ContentSuggestionsManagerServiceShellCommand(
            @NonNull ContentSuggestionsManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        switch (cmd) {
            case "set":
                return requestSet(pw);
            case "get":
                return requestGet(pw);
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        try (PrintWriter pw = getOutPrintWriter()) {
            pw.println("ContentSuggestionsManagerService commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  set temporary-service USER_ID [COMPONENT_NAME DURATION]");
            pw.println("    Temporarily (for DURATION ms) changes the service implementation.");
            pw.println("    To reset, call with just the USER_ID argument.");
            pw.println("");
            pw.println("  set default-service-enabled USER_ID [true|false]");
            pw.println("    Enable / disable the default service for the user.");
            pw.println("");
            pw.println("  get default-service-enabled USER_ID");
            pw.println("    Checks whether the default service is enabled for the user.");
            pw.println("");
        }
    }

    private int requestSet(PrintWriter pw) {
        final String what = getNextArgRequired();

        switch(what) {
            case "temporary-service":
                return setTemporaryService(pw);
            case "default-service-enabled":
                return setDefaultServiceEnabled();
            default:
                pw.println("Invalid set: " + what);
                return -1;
        }
    }

    private int requestGet(PrintWriter pw) {
        final String what = getNextArgRequired();
        switch(what) {
            case "default-service-enabled":
                return getDefaultServiceEnabled(pw);
            default:
                pw.println("Invalid get: " + what);
                return -1;
        }
    }

    private int setTemporaryService(PrintWriter pw) {
        final int userId = Integer.parseInt(getNextArgRequired());
        String serviceName = getNextArg();
        if (serviceName == null) {
            mService.resetTemporaryService(userId);
            return 0;
        }
        final int duration = Integer.parseInt(getNextArgRequired());
        mService.setTemporaryService(userId, serviceName, duration);
        pw.println("ContentSuggestionsService temporarily set to " + serviceName
                + " for " + duration + "ms");
        return 0;
    }

    private int setDefaultServiceEnabled() {
        final int userId = getNextIntArgRequired();
        final boolean enabled = Boolean.parseBoolean(getNextArg());
        mService.setDefaultServiceEnabled(userId, enabled);
        return 0;
    }

    private int getDefaultServiceEnabled(PrintWriter pw) {
        final int userId = getNextIntArgRequired();
        final boolean enabled = mService.isDefaultServiceEnabled(userId);
        pw.println(enabled);
        return 0;
    }

    private int getNextIntArgRequired() {
        return Integer.parseInt(getNextArgRequired());
    }
}
