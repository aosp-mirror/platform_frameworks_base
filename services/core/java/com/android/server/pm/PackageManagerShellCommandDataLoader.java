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
import android.content.pm.PackageInstaller;
import android.os.ParcelFileDescriptor;
import android.os.ShellCommand;
import android.service.dataloader.DataLoaderService;
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

    private static String getDataLoaderParamsArgs(ShellCommand shellCommand) {
        nativeInitialize();

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

        return SHELL_COMMAND_ID_PREFIX + commandId;
    }

    static DataLoaderParams getStreamingDataLoaderParams(ShellCommand shellCommand) {
        return DataLoaderParams.forStreaming(new ComponentName(PACKAGE, CLASS),
                getDataLoaderParamsArgs(shellCommand));
    }

    static DataLoaderParams getIncrementalDataLoaderParams(ShellCommand shellCommand) {
        return DataLoaderParams.forIncremental(new ComponentName(PACKAGE, CLASS),
                getDataLoaderParamsArgs(shellCommand));
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

    static class Metadata {
        /**
         * Full files read from stdin.
         */
        static final byte STDIN = 0;
        /**
         * Full files read from local file.
         */
        static final byte LOCAL_FILE = 1;
        /**
         * Signature tree read from stdin, data streamed.
         */
        static final byte DATA_ONLY_STREAMING = 2;
        /**
         * Everything streamed.
         */
        static final byte STREAMING = 3;

        private final byte mMode;
        private final String mData;

        static Metadata forStdIn(String fileId) {
            return new Metadata(STDIN, fileId);
        }

        static Metadata forLocalFile(String filePath) {
            return new Metadata(LOCAL_FILE, filePath);
        }

        static Metadata forDataOnlyStreaming(String fileId) {
            return new Metadata(DATA_ONLY_STREAMING, fileId);
        }

        static Metadata forStreaming(String fileId) {
            return new Metadata(STREAMING, fileId);
        }

        private Metadata(byte mode, String data) {
            this.mMode = mode;
            this.mData = (data == null) ? "" : data;
        }

        static Metadata fromByteArray(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            byte mode = bytes[0];
            String data = new String(bytes, 1, bytes.length - 1, StandardCharsets.UTF_8);
            return new Metadata(mode, data);
        }

        byte[] toByteArray() {
            byte[] dataBytes = this.mData.getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[1 + dataBytes.length];
            result[0] = this.mMode;
            System.arraycopy(dataBytes, 0, result, 1, dataBytes.length);
            return result;
        }

        byte getMode() {
            return this.mMode;
        }

        String getData() {
            return this.mData;
        }
    }

    private static class DataLoader implements DataLoaderService.DataLoader {
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
        public boolean onPrepareImage(@NonNull Collection<InstallationFile> addedFiles,
                @NonNull Collection<String> removedFiles) {
            ShellCommand shellCommand = lookupShellCommand(mParams.getArguments());
            if (shellCommand == null) {
                Slog.e(TAG, "Missing shell command.");
                return false;
            }
            try {
                for (InstallationFile file : addedFiles) {
                    Metadata metadata = Metadata.fromByteArray(file.getMetadata());
                    if (metadata == null) {
                        Slog.e(TAG, "Invalid metadata for file: " + file.getName());
                        return false;
                    }
                    switch (metadata.getMode()) {
                        case Metadata.STDIN: {
                            final ParcelFileDescriptor inFd = getStdInPFD(shellCommand);
                            mConnector.writeData(file.getName(), 0, file.getLengthBytes(), inFd);
                            break;
                        }
                        case Metadata.LOCAL_FILE: {
                            ParcelFileDescriptor incomingFd = null;
                            try {
                                incomingFd = getLocalFilePFD(shellCommand, metadata.getData());
                                mConnector.writeData(file.getName(), 0, incomingFd.getStatSize(),
                                        incomingFd);
                            } finally {
                                IoUtils.closeQuietly(incomingFd);
                            }
                            break;
                        }
                        default:
                            Slog.e(TAG, "Unsupported metadata mode: " + metadata.getMode());
                            return false;
                    }
                }
                return true;
            } catch (IOException e) {
                Slog.e(TAG, "Exception while streaming files", e);
                return false;
            }
        }
    }

    static ShellCommand lookupShellCommand(String args) {
        final int commandId = extractShellCommandId(args);
        if (commandId == INVALID_SHELL_COMMAND_ID) {
            return null;
        }

        final WeakReference<ShellCommand> shellCommandRef;
        synchronized (sShellCommands) {
            shellCommandRef = sShellCommands.get(commandId, null);
        }
        final ShellCommand shellCommand =
                shellCommandRef != null ? shellCommandRef.get() : null;

        return shellCommand;
    }

    static ParcelFileDescriptor getStdInPFD(ShellCommand shellCommand) {
        try {
            return ParcelFileDescriptor.dup(shellCommand.getInFileDescriptor());
        } catch (IOException e) {
            Slog.e(TAG, "Exception while obtaining STDIN fd", e);
            return null;
        }
    }

    static ParcelFileDescriptor getLocalFilePFD(ShellCommand shellCommand, String filePath) {
        return shellCommand.openFileForSystem(filePath, "r");
    }

    static int getStdIn(ShellCommand shellCommand) {
        ParcelFileDescriptor pfd = getStdInPFD(shellCommand);
        return pfd == null ? -1 : pfd.detachFd();
    }

    static int getLocalFile(ShellCommand shellCommand, String filePath) {
        ParcelFileDescriptor pfd = getLocalFilePFD(shellCommand, filePath);
        return pfd == null ? -1 : pfd.detachFd();
    }

    @Override
    public DataLoaderService.DataLoader onCreateDataLoader(
            @NonNull DataLoaderParams dataLoaderParams) {
        if (dataLoaderParams.getType() == PackageInstaller.DATA_LOADER_TYPE_STREAMING) {
            // This DataLoader only supports streaming installations.
            return new DataLoader();
        }
        return null;
    }

    /* Native methods */
    private static native void nativeInitialize();
}
