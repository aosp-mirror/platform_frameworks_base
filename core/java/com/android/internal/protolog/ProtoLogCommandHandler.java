/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.protolog;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.ShellCommand;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ProtoLogCommandHandler extends ShellCommand {
    @NonNull
    private final ProtoLogConfigurationService mProtoLogConfigurationService;
    @Nullable
    private final PrintWriter mPrintWriter;

    public ProtoLogCommandHandler(
            @NonNull ProtoLogConfigurationService protoLogConfigurationService) {
        this(protoLogConfigurationService, null);
    }

    @VisibleForTesting
    public ProtoLogCommandHandler(
            @NonNull ProtoLogConfigurationService protoLogConfigurationService,
            @Nullable PrintWriter printWriter) {
        this.mProtoLogConfigurationService = protoLogConfigurationService;
        this.mPrintWriter = printWriter;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            onHelp();
            return 0;
        }

        return switch (cmd) {
            case "groups" -> handleGroupsCommands(getNextArg());
            case "logcat" -> handleLogcatCommands(getNextArg());
            default -> handleDefaultCommands(cmd);
        };
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("ProtoLog commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  groups (list | status)");
        pw.println("    list - lists all ProtoLog groups registered with ProtoLog service");
        pw.println("    status <group> - print the status of a ProtoLog group");
        pw.println();
        pw.println("  logcat (enable | disable) <group>");
        pw.println("    enable or disable ProtoLog to logcat");
        pw.println();
    }

    @NonNull
    @Override
    public PrintWriter getOutPrintWriter() {
        if (mPrintWriter != null) {
            return mPrintWriter;
        }

        return super.getOutPrintWriter();
    }

    private int handleGroupsCommands(@Nullable String cmd) {
        PrintWriter pw = getOutPrintWriter();

        if (cmd == null) {
            pw.println("Incomplete command. Use 'cmd protolog help' for guidance.");
            return 0;
        }

        switch (cmd) {
            case "list": {
                final String[] availableGroups = mProtoLogConfigurationService.getGroups();
                if (availableGroups.length == 0) {
                    pw.println("No ProtoLog groups registered with ProtoLog service.");
                    return 0;
                }

                pw.println("ProtoLog groups registered with service:");
                for (String group : availableGroups) {
                    pw.println("- " + group);
                }

                return 0;
            }
            case "status": {
                final String group = getNextArg();

                if (group == null) {
                    pw.println("Incomplete command. Use 'cmd protolog help' for guidance.");
                    return 0;
                }

                pw.println("ProtoLog group " + group + "'s status:");

                if (!Set.of(mProtoLogConfigurationService.getGroups()).contains(group)) {
                    pw.println("UNREGISTERED");
                    return 0;
                }

                pw.println("LOG_TO_LOGCAT = "
                        + mProtoLogConfigurationService.isLoggingToLogcat(group));
                return 0;
            }
            default: {
                pw.println("Unknown command: " + cmd);
                return -1;
            }
        }
    }

    private int handleLogcatCommands(@Nullable String cmd) {
        PrintWriter pw = getOutPrintWriter();

        if (cmd == null || peekNextArg() == null) {
            pw.println("Incomplete command. Use 'cmd protolog help' for guidance.");
            return 0;
        }

        switch (cmd) {
            case "enable" -> {
                mProtoLogConfigurationService.enableProtoLogToLogcat(processGroups());
                return 0;
            }
            case "disable" -> {
                mProtoLogConfigurationService.disableProtoLogToLogcat(processGroups());
                return 0;
            }
            default -> {
                pw.println("Unknown command: " + cmd);
                return -1;
            }
        }
    }

    @NonNull
    private String[] processGroups() {
        if (getRemainingArgsCount() == 0) {
            return mProtoLogConfigurationService.getGroups();
        }

        final List<String> groups = new ArrayList<>();
        while (getRemainingArgsCount() > 0) {
            groups.add(getNextArg());
        }

        return groups.toArray(new String[0]);
    }
}
