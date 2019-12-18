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

package com.android.server.incremental;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.DataLoaderParams;
import android.content.pm.InstallationFile;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ShellCommand;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Defines actions to handle adb commands like "adb abb incremental ...".
 */
public final class IncrementalManagerShellCommand extends ShellCommand {
    private static final String TAG = "IncrementalShellCommand";
    // Assuming the adb data loader is always installed on the device
    private static final String LOADER_PACKAGE_NAME = "com.android.incremental.nativeadb";
    private final @NonNull Context mContext;

    private static final int ERROR_INVALID_ARGUMENTS = -1;
    private static final int ERROR_DATA_LOADER_INIT = -2;
    private static final int ERROR_COMMAND_EXECUTION = -3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ERROR_INVALID_ARGUMENTS, ERROR_DATA_LOADER_INIT, ERROR_COMMAND_EXECUTION})
    public @interface IncrementalShellCommandErrorCode {
    }

    IncrementalManagerShellCommand(@NonNull Context context) {
        mContext = context;
    }

    @Override
    public int onCommand(@Nullable String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }
        switch (cmd) {
            case "install-start":
                return runInstallStart();
            case "install-finish":
                return runInstallFinish();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Incremental Service Commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  install-start");
        pw.println("    Opens an installation session");
        pw.println("  install-finish SESSION_ID --file NAME:SIZE:INDEX --file NAME:SIZE:INDEX ...");
        pw.println("    Commits an installation session specified by session ID for an APK ");
        pw.println("      or a bundle of splits. Configures lib dirs or OBB files if specified.");
    }

    private int runInstallStart() {
        final PrintWriter pw = getOutPrintWriter();
        final PackageInstaller packageInstaller =
                mContext.getPackageManager().getPackageInstaller();
        if (packageInstaller == null) {
            pw.println("Failed to get PackageInstaller.");
            return ERROR_COMMAND_EXECUTION;
        }

        final Map<String, ParcelFileDescriptor> dataLoaderDynamicArgs = getDataLoaderDynamicArgs();
        if (dataLoaderDynamicArgs == null) {
            pw.println("File names and sizes don't match.");
            return ERROR_DATA_LOADER_INIT;
        }
        final DataLoaderParams params = new DataLoaderParams(
                "", LOADER_PACKAGE_NAME, dataLoaderDynamicArgs);
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sessionParams.installFlags |= PackageManager.INSTALL_ALL_USERS;
        // Replace existing if same package is already installed
        sessionParams.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
        sessionParams.setIncrementalParams(params);

        try {
            int sessionId = packageInstaller.createSession(sessionParams);
            pw.println("Successfully opened session: sessionId = " + sessionId);
        } catch (Exception ex) {
            pw.println("Failed to create session.");
            return ERROR_COMMAND_EXECUTION;
        } finally {
            try {
                for (Map.Entry<String, ParcelFileDescriptor> nfd
                        : dataLoaderDynamicArgs.entrySet()) {
                    nfd.getValue().close();
                }
            } catch (IOException ignored) {
            }
        }
        return 0;
    }

    private int runInstallFinish() {
        final PrintWriter pw = getOutPrintWriter();
        final int sessionId = parseInt(getNextArgRequired());
        final List<InstallationFile> installationFiles = parseFileArgs(pw);
        if (installationFiles == null) {
            pw.println("Must specify at least one file to install.");
            return ERROR_INVALID_ARGUMENTS;
        }
        final int numFiles = installationFiles.size();
        if (numFiles == 0) {
            pw.println("Must specify at least one file to install.");
            return ERROR_INVALID_ARGUMENTS;
        }

        final PackageInstaller packageInstaller = mContext.getPackageManager()
                .getPackageInstaller();
        if (packageInstaller == null) {
            pw.println("Failed to get PackageInstaller.");
            return ERROR_COMMAND_EXECUTION;
        }

        final LocalIntentReceiver localReceiver = new LocalIntentReceiver();
        boolean success = false;

        PackageInstaller.Session session = null;
        try {
            session = packageInstaller.openSession(sessionId);
            for (int i = 0; i < numFiles; i++) {
                InstallationFile file = installationFiles.get(i);
                session.addFile(file.getName(), file.getSize(), file.getMetadata());
            }
            session.commit(localReceiver.getIntentSender());
            final Intent result = localReceiver.getResult();
            final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                success = true;
                pw.println("Success");
                return 0;
            } else {
                pw.println("Failure ["
                        + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
                return ERROR_COMMAND_EXECUTION;
            }
        } catch (Exception e) {
            e.printStackTrace(pw);
            return ERROR_COMMAND_EXECUTION;
        } finally {
            if (!success) {
                try {
                    if (session != null) {
                        session.abandon();
                    }
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static class LocalIntentReceiver {
        private final LinkedBlockingQueue<Intent> mResult = new LinkedBlockingQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission,
                    Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Helpers. */
    private Map<String, ParcelFileDescriptor> getDataLoaderDynamicArgs() {
        Map<String, ParcelFileDescriptor> dataLoaderDynamicArgs = new HashMap<>();
        final FileDescriptor outFd = getOutFileDescriptor();
        final FileDescriptor inFd = getInFileDescriptor();
        try {
            dataLoaderDynamicArgs.put("inFd", ParcelFileDescriptor.dup(inFd));
            dataLoaderDynamicArgs.put("outFd", ParcelFileDescriptor.dup(outFd));
            return dataLoaderDynamicArgs;
        } catch (Exception ex) {
            Slog.e(TAG, "Failed to dup FDs");
            return null;
        }
    }

    private long parseLong(String arg) {
        long result = -1;
        try {
            result = Long.parseLong(arg);
        } catch (NumberFormatException e) {
        }
        return result;
    }

    private int parseInt(String arg) {
        int result = -1;
        try {
            result = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
        }
        return result;
    }

    private List<InstallationFile> parseFileArgs(PrintWriter pw) {
        List<InstallationFile> fileList = new ArrayList<>();
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--file": {
                    final String fileArgs = getNextArgRequired();
                    final String[] args = fileArgs.split(":");
                    if (args.length != 3) {
                        pw.println("Invalid file args: " + fileArgs);
                        return null;
                    }
                    final String name = args[0];
                    final long size = parseLong(args[1]);
                    if (size < 0) {
                        pw.println("Invalid file size in: " + fileArgs);
                        return null;
                    }
                    final long index = parseLong(args[2]);
                    if (index < 0) {
                        pw.println("Invalid file index in: " + fileArgs);
                        return null;
                    }
                    final byte[] metadata = String.valueOf(index).getBytes(StandardCharsets.UTF_8);
                    fileList.add(new InstallationFile(name, size, metadata));
                    break;
                }
                default:
                    break;
            }
        }
        return fileList;
    }
}
