/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.musicrecognition;

import android.os.ShellCommand;

import java.io.PrintWriter;

/** Handles adb shell commands send to MusicRecognitionManagerService. */
class MusicRecognitionManagerServiceShellCommand extends ShellCommand {

    private final MusicRecognitionManagerService mService;

    MusicRecognitionManagerServiceShellCommand(MusicRecognitionManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        if ("set".equals(cmd)) {
            return requestSet(pw);
        }
        return handleDefaultCommands(cmd);
    }

    private int requestSet(PrintWriter pw) {
        final String what = getNextArgRequired();
        if ("temporary-service".equals(what)) {
            return setTemporaryService(pw);
        }
        pw.println("Invalid set: " + what);
        return -1;
    }

    private int setTemporaryService(PrintWriter pw) {
        final int userId = Integer.parseInt(getNextArgRequired());
        final String serviceName = getNextArg();
        if (serviceName == null) {
            mService.resetTemporaryService(userId);
            return 0;
        }
        final int duration = Integer.parseInt(getNextArgRequired());
        mService.setTemporaryService(userId, serviceName, duration);
        pw.println("MusicRecognitionService temporarily set to " + serviceName + " for "
                + duration + "ms");
        return 0;
    }

    @Override
    public void onHelp() {
        try (PrintWriter pw = getOutPrintWriter();) {
            pw.println("MusicRecognition Service (music_recognition) commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  set temporary-service USER_ID [COMPONENT_NAME DURATION]");
            pw.println("    Temporarily (for DURATION ms) changes the service implementation.");
            pw.println("    To reset, call with just the USER_ID argument.");
            pw.println("");
        }
    }
}
