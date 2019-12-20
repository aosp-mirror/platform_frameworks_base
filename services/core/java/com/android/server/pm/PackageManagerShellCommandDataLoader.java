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

package com.android.server.pm;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.pm.DataLoaderParams;
import android.content.pm.InstallationFile;
import android.os.ParcelFileDescriptor;
import android.os.ShellCommand;
import android.service.dataloader.DataLoaderService;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import libcore.io.IoUtils;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collection;

/**
 * Callback data loader for PackageManagerShellCommand installations.
 */
public class PackageManagerShellCommandDataLoader extends DataLoaderService {
    public static final String TAG = "PackageManagerShellCommandDataLoader";

    private static final String PACKAGE = "android";
    private static final String CLASS = PackageManagerShellCommandDataLoader.class.getName();

    static final SecureRandom sRandom = new SecureRandom();
    static final SparseArray<WeakReference<ShellCommand>> sShellCommands = new SparseArray<>();

    private static final char ARGS_DELIM = '&';
    private static final String SHELL_COMMAND_ID_PREFIX = "shellCommandId=";
    private static final int INVALID_SHELL_COMMAND_ID = -1;
    private static final int TOO_MANY_PENDING_SHELL_COMMANDS = 10;

    private static final String STDIN_PATH = "-";

    static DataLoaderParams getDataLoaderParams(ShellCommand shellCommand) {
        int commandId;
        synchronized (sShellCommands) {
            // Clean up old references.
            for (int i = sShellCommands.size() - 1; i >= 0; i--) {
                WeakReference<ShellCommand> oldRef = sShellCommands.valueAt(i);
                if (oldRef.get() == null) {
                    sShellCommands.removeAt(i);
                }
            }

            // Sanity check.
            if (sShellCommands.size() > TOO_MANY_PENDING_SHELL_COMMANDS) {
                Slog.e(TAG, "Too many pending shell commands: " + sShellCommands.size());
            }

            // Generate new id and put ref to the array.
            do {
                commandId = sRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
            } while (sShellCommands.contains(commandId));

            sShellCommands.put(commandId, new WeakReference<>(shellCommand));
        }

        final String args = SHELL_COMMAND_ID_PREFIX + commandId;
        return DataLoaderParams.forStreaming(new ComponentName(PACKAGE, CLASS), args);
    }

    private static int extractShellCommandId(String args) {
        int sessionIdIdx = args.indexOf(SHELL_COMMAND_ID_PREFIX);
        if (sessionIdIdx < 0) {
            Slog.e(TAG, "Missing shell command id param.");
            return INVALID_SHELL_COMMAND_ID;
        }
        sessionIdIdx += SHELL_COMMAND_ID_PREFIX.length();
        int delimIdx = args.indexOf(ARGS_DELIM, sessionIdIdx);
        try {
            if (delimIdx < 0) {
                return Integer.parseInt(args.substring(sessionIdIdx));
            } else {
                return Integer.parseInt(args.substring(sessionIdIdx, delimIdx));
            }
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Incorrect shell command id format.", e);
            return INVALID_SHELL_COMMAND_ID;
        }
    }

    static class DataLoader implements DataLoaderService.DataLoader {
        private DataLoaderParams mParams = null;
        private FileSystemConnector mConnector = null;

        @Override
        public boolean onCreate(@NonNull DataLoaderParams dataLoaderParams,
                @NonNull FileSystemConnector connector) {
            mParams = dataLoaderParams;
            mConnector = connector;
            return true;
        }

        @Override
        public boolean onPrepareImage(Collection<InstallationFile> addedFiles,
                Collection<String> removedFiles) {
            final int commandId = extractShellCommandId(mParams.getArguments());
            if (commandId == INVALID_SHELL_COMMAND_ID) {
                return false;
            }

            final WeakReference<ShellCommand> shellCommandRef;
            synchronized (sShellCommands) {
                shellCommandRef = sShellCommands.get(commandId, null);
            }
            final ShellCommand shellCommand =
                    shellCommandRef != null ? shellCommandRef.get() : null;
            if (shellCommand == null) {
                Slog.e(TAG, "Missing shell command.");
                return false;
            }
            try {
                for (InstallationFile fileInfo : addedFiles) {
                    String filePath = new String(fileInfo.getMetadata(), StandardCharsets.UTF_8);
                    if (STDIN_PATH.equals(filePath) || TextUtils.isEmpty(filePath)) {
                        final ParcelFileDescriptor inFd = ParcelFileDescriptor.dup(
                                shellCommand.getInFileDescriptor());
                        mConnector.writeData(fileInfo.getName(), 0, fileInfo.getSize(), inFd);
                    } else {
                        ParcelFileDescriptor incomingFd = null;
                        try {
                            incomingFd = shellCommand.openFileForSystem(filePath, "r");
                            mConnector.writeData(fileInfo.getName(), 0, incomingFd.getStatSize(),
                                    incomingFd);
                        } finally {
                            IoUtils.closeQuietly(incomingFd);
                        }
                    }
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    @Override
    public DataLoaderService.DataLoader onCreateDataLoader() {
        return new DataLoader();
    }
}
