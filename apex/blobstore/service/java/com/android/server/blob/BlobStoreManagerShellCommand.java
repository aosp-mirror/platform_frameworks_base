/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.blob;

import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;

class BlobStoreManagerShellCommand extends ShellCommand {

    private final BlobStoreManagerService mService;

    BlobStoreManagerShellCommand(BlobStoreManagerService blobStoreManagerService) {
        mService = blobStoreManagerService;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }
        final PrintWriter pw = getOutPrintWriter();
        switch (cmd) {
            case "clear-all-sessions":
                return runClearAllSessions(pw);
            case "clear-all-blobs":
                return runClearAllBlobs(pw);
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int runClearAllSessions(PrintWriter pw) {
        final ParsedArgs args = new ParsedArgs();
        args.userId = UserHandle.USER_ALL;

        if (parseOptions(pw, args) < 0) {
            return -1;
        }

        mService.runClearAllSessions(args.userId);
        return 0;
    }

    private int runClearAllBlobs(PrintWriter pw) {
        final ParsedArgs args = new ParsedArgs();
        args.userId = UserHandle.USER_ALL;

        if (parseOptions(pw, args) < 0) {
            return -1;
        }

        mService.runClearAllBlobs(args.userId);
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("BlobStore service (blob_store) commands:");
        pw.println("help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("clear-all-sessions [-u | --user USER_ID]");
        pw.println("    Remove all sessions.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's sessions to be removed;");
        pw.println("                    If not specified, sessions in all users are removed.");
        pw.println();
        pw.println("clear-all-blobs [-u | --user USER_ID]");
        pw.println("    Remove all blobs.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's blobs to be removed;");
        pw.println("                    If not specified, blobs in all users are removed.");
        pw.println();
    }

    private int parseOptions(PrintWriter pw, ParsedArgs args) {
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-u":
                case "--user":
                    args.userId = Integer.parseInt(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }
        return 0;
    }

    private static class ParsedArgs {
        public int userId;
    }
}
