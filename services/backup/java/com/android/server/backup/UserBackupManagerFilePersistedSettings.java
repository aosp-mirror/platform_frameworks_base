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

package com.android.server.backup;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;

import android.util.Slog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** User settings which are persisted across reboots. */
final class UserBackupManagerFilePersistedSettings {
    // File containing backup-enabled state. Contains a single byte to denote enabled status.
    // Nonzero is enabled; file missing or a zero byte is disabled.
    private static final String BACKUP_ENABLE_FILE = "backup_enabled";

    static boolean readBackupEnableState(int userId) {
        return readBackupEnableState(UserBackupManagerFiles.getBaseStateDir(userId));
    }

    static void writeBackupEnableState(int userId, boolean enable) {
        writeBackupEnableState(UserBackupManagerFiles.getBaseStateDir(userId), enable);
    }

    private static boolean readBackupEnableState(File baseDir) {
        File enableFile = new File(baseDir, BACKUP_ENABLE_FILE);
        if (enableFile.exists()) {
            try (FileInputStream fin = new FileInputStream(enableFile)) {
                int state = fin.read();
                return state != 0;
            } catch (IOException e) {
                // can't read the file; fall through to assume disabled
                Slog.e(TAG, "Cannot read enable state; assuming disabled");
            }
        } else {
            if (DEBUG) {
                Slog.i(TAG, "isBackupEnabled() => false due to absent settings file");
            }
        }
        return false;
    }

    private static void writeBackupEnableState(File baseDir, boolean enable) {
        File enableFile = new File(baseDir, BACKUP_ENABLE_FILE);
        File stage = new File(baseDir, BACKUP_ENABLE_FILE + "-stage");
        try (FileOutputStream fout = new FileOutputStream(stage)) {
            fout.write(enable ? 1 : 0);
            fout.close();
            stage.renameTo(enableFile);
            // will be synced immediately by the try-with-resources call to close()
        } catch (IOException | RuntimeException e) {
            Slog.e(
                    TAG,
                    "Unable to record backup enable state; reverting to disabled: "
                            + e.getMessage());
            enableFile.delete();
            stage.delete();
        }
    }
}
