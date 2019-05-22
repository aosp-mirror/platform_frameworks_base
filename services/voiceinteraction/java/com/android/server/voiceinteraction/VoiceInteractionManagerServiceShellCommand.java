/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.voiceinteraction;

import android.annotation.NonNull;
import android.os.ShellCommand;

import java.io.PrintWriter;

final class VoiceInteractionManagerServiceShellCommand extends ShellCommand {

    private final VoiceInteractionManagerService mService;

    VoiceInteractionManagerServiceShellCommand(@NonNull VoiceInteractionManagerService service) {
        this.mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        switch (cmd) {
            case "set bind-instant-service-allowed":
            case "set":
                return requestSet(pw);
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        try (PrintWriter pw = getOutPrintWriter();) {
            pw.println("VoiceInteraction Service (voiceinteraction) commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  set bind-instant-service-allowed [true | false]");
            pw.println("    Sets whether binding to services provided by instant apps is allowed");
            pw.println("");
        }
    }

    private int requestSet(@NonNull PrintWriter pw) {
        final String what = getNextArgRequired();

        switch(what) {
            case "bind-instant-service-allowed":
                return setBindInstantService(pw);
            default:
                pw.println("Invalid set: " + what);
                return -1;
        }
    }

    private int setBindInstantService(@NonNull PrintWriter pw) {
        final String mode = getNextArgRequired();
        switch (mode.toLowerCase()) {
            case "true":
                mService.setAllowInstantService(true);
                return 0;
            case "false":
                mService.setAllowInstantService(false);
                return 0;
            default:
                pw.println("Invalid mode: " + mode);
                return -1;
        }
    }
}
