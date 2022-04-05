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

import android.app.ActivityManager;
import android.app.blob.BlobHandle;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.Base64;

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
            case "delete-blob":
                return runDeleteBlob(pw);
            case "idle-maintenance":
                return runIdleMaintenance(pw);
            case "query-blob-existence":
                return runQueryBlobExistence(pw);
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

    private int runDeleteBlob(PrintWriter pw) {
        final ParsedArgs args = new ParsedArgs();

        if (parseOptions(pw, args) < 0) {
            return -1;
        }

        mService.deleteBlob(args.getBlobHandle(), args.userId);
        return 0;
    }

    private int runIdleMaintenance(PrintWriter pw) {
        mService.runIdleMaintenance();
        return 0;
    }

    private int runQueryBlobExistence(PrintWriter pw) {
        final ParsedArgs args = new ParsedArgs();
        if (parseOptions(pw, args) < 0) {
            return -1;
        }

        pw.println(mService.isBlobAvailable(args.blobId, args.userId) ? 1 : 0);
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
        pw.println("      -u or --user: specify which user's sessions to be removed.");
        pw.println("                    If not specified, sessions in all users are removed.");
        pw.println();
        pw.println("clear-all-blobs [-u | --user USER_ID]");
        pw.println("    Remove all blobs.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's blobs to be removed.");
        pw.println("                    If not specified, blobs in all users are removed.");
        pw.println("delete-blob [-u | --user USER_ID] [--digest DIGEST] [--expiry EXPIRY_TIME] "
                + "[--label LABEL] [--tag TAG]");
        pw.println("    Delete a blob.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's blobs to be removed;");
        pw.println("                    If not specified, blobs in all users are removed.");
        pw.println("      --digest: Base64 encoded digest of the blob to delete.");
        pw.println("      --expiry: Expiry time of the blob to delete, in milliseconds.");
        pw.println("      --label: Label of the blob to delete.");
        pw.println("      --tag: Tag of the blob to delete.");
        pw.println("idle-maintenance");
        pw.println("    Run idle maintenance which takes care of removing stale data.");
        pw.println("query-blob-existence [-b BLOB_ID]");
        pw.println("    Prints 1 if blob exists, otherwise 0.");
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
                case "--algo":
                    args.algorithm = getNextArgRequired();
                    break;
                case "--digest":
                    args.digest = Base64.getDecoder().decode(getNextArgRequired());
                    break;
                case "--label":
                    args.label = getNextArgRequired();
                    break;
                case "--expiry":
                    args.expiryTimeMillis = Long.parseLong(getNextArgRequired());
                    break;
                case "--tag":
                    args.tag = getNextArgRequired();
                    break;
                case "-b":
                    args.blobId = Long.parseLong(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }
        if (args.userId == UserHandle.USER_CURRENT) {
            args.userId = ActivityManager.getCurrentUser();
        }
        return 0;
    }

    private static class ParsedArgs {
        public int userId = UserHandle.USER_CURRENT;

        public String algorithm = BlobHandle.ALGO_SHA_256;
        public byte[] digest;
        public long expiryTimeMillis;
        public CharSequence label;
        public String tag;
        public long blobId;

        public BlobHandle getBlobHandle() {
            return BlobHandle.create(algorithm, digest, label, expiryTimeMillis, tag);
        }
    }
}
