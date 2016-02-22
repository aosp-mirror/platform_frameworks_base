/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.os.IRecoverySystem;
import android.os.IRecoverySystemProgressListener;
import android.os.RecoverySystem;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * The recovery system service is responsible for coordinating recovery related
 * functions on the device. It sets up (or clears) the bootloader control block
 * (BCB), which will be read by the bootloader and the recovery image. It also
 * triggers /system/bin/uncrypt via init to de-encrypt an OTA package on the
 * /data partition so that it can be accessed under the recovery image.
 */
public final class RecoverySystemService extends SystemService {
    private static final String TAG = "RecoverySystemService";
    private static final boolean DEBUG = false;

    // A pipe file to monitor the uncrypt progress.
    private static final String UNCRYPT_STATUS_FILE = "/cache/recovery/uncrypt_status";
    // Temporary command file to communicate between the system server and uncrypt.
    private static final String COMMAND_FILE = "/cache/recovery/command";

    private Context mContext;

    public RecoverySystemService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.RECOVERY_SERVICE, new BinderService());
    }

    private final class BinderService extends IRecoverySystem.Stub {
        @Override // Binder call
        public boolean uncrypt(String filename, IRecoverySystemProgressListener listener) {
            if (DEBUG) Slog.d(TAG, "uncrypt: " + filename);

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.RECOVERY, null);

            // Write the filename into UNCRYPT_PACKAGE_FILE to be read by
            // uncrypt.
            RecoverySystem.UNCRYPT_PACKAGE_FILE.delete();

            try (FileWriter uncryptFile = new FileWriter(RecoverySystem.UNCRYPT_PACKAGE_FILE)) {
                uncryptFile.write(filename + "\n");
            } catch (IOException e) {
                Slog.e(TAG, "IOException when writing \"" + RecoverySystem.UNCRYPT_PACKAGE_FILE +
                        "\": " + e.getMessage());
                return false;
            }

            // Create the status pipe file to communicate with uncrypt.
            new File(UNCRYPT_STATUS_FILE).delete();
            try {
                Os.mkfifo(UNCRYPT_STATUS_FILE, 0600);
            } catch (ErrnoException e) {
                Slog.e(TAG, "ErrnoException when creating named pipe \"" + UNCRYPT_STATUS_FILE +
                        "\": " + e.getMessage());
                return false;
            }

            // Trigger uncrypt via init.
            SystemProperties.set("ctl.start", "uncrypt");

            // Read the status from the pipe.
            try (BufferedReader reader = new BufferedReader(new FileReader(UNCRYPT_STATUS_FILE))) {
                int lastStatus = Integer.MIN_VALUE;
                while (true) {
                    String str = reader.readLine();
                    try {
                        int status = Integer.parseInt(str);

                        // Avoid flooding the log with the same message.
                        if (status == lastStatus && lastStatus != Integer.MIN_VALUE) {
                            continue;
                        }
                        lastStatus = status;

                        if (status >= 0 && status <= 100) {
                            // Update status
                            Slog.i(TAG, "uncrypt read status: " + status);
                            if (listener != null) {
                                try {
                                    listener.onProgress(status);
                                } catch (RemoteException unused) {
                                    Slog.w(TAG, "RemoteException when posting progress");
                                }
                            }
                            if (status == 100) {
                                Slog.i(TAG, "uncrypt successfully finished.");
                                break;
                            }
                        } else {
                            // Error in /system/bin/uncrypt.
                            Slog.e(TAG, "uncrypt failed with status: " + status);
                            return false;
                        }
                    } catch (NumberFormatException unused) {
                        Slog.e(TAG, "uncrypt invalid status received: " + str);
                        return false;
                    }
                }
            } catch (IOException unused) {
                Slog.e(TAG, "IOException when reading \"" + UNCRYPT_STATUS_FILE + "\".");
                return false;
            }

            return true;
        }

        @Override // Binder call
        public boolean clearBcb() {
            if (DEBUG) Slog.d(TAG, "clearBcb");
            return setupOrClearBcb(false, null);
        }

        @Override // Binder call
        public boolean setupBcb(String command) {
            if (DEBUG) Slog.d(TAG, "setupBcb: [" + command + "]");
            return setupOrClearBcb(true, command);
        }

        private boolean setupOrClearBcb(boolean isSetup, String command) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.RECOVERY, null);

            if (isSetup) {
                // Set up the command file to be read by uncrypt.
                try (FileWriter commandFile = new FileWriter(COMMAND_FILE)) {
                    commandFile.write(command + "\n");
                } catch (IOException e) {
                    Slog.e(TAG, "IOException when writing \"" + COMMAND_FILE +
                            "\": " + e.getMessage());
                    return false;
                }
            }

            // Create the status pipe file to communicate with uncrypt.
            new File(UNCRYPT_STATUS_FILE).delete();
            try {
                Os.mkfifo(UNCRYPT_STATUS_FILE, 0600);
            } catch (ErrnoException e) {
                Slog.e(TAG, "ErrnoException when creating named pipe \"" +
                        UNCRYPT_STATUS_FILE + "\": " + e.getMessage());
                return false;
            }

            if (isSetup) {
                SystemProperties.set("ctl.start", "setup-bcb");
            } else {
                SystemProperties.set("ctl.start", "clear-bcb");
            }

            // Read the status from the pipe.
            try (BufferedReader reader = new BufferedReader(new FileReader(UNCRYPT_STATUS_FILE))) {
                while (true) {
                    String str = reader.readLine();
                    try {
                        int status = Integer.parseInt(str);

                        if (status == 100) {
                            Slog.i(TAG, "uncrypt " + (isSetup ? "setup" : "clear") +
                                    " bcb successfully finished.");
                            break;
                        } else {
                            // Error in /system/bin/uncrypt.
                            Slog.e(TAG, "uncrypt failed with status: " + status);
                            return false;
                        }
                    } catch (NumberFormatException unused) {
                        Slog.e(TAG, "uncrypt invalid status received: " + str);
                        return false;
                    }
                }
            } catch (IOException unused) {
                Slog.e(TAG, "IOException when reading \"" + UNCRYPT_STATUS_FILE + "\".");
                return false;
            }

            // Delete the command file as we don't need it anymore.
            new File(COMMAND_FILE).delete();
            return true;
        }
    }
}
