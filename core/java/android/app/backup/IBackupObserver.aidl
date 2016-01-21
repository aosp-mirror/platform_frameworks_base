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

package android.app.backup;

import android.app.backup.BackupProgress;

/**
 * Callback class for receiving progress reports during a backup operation.  These
 * methods will all be called on your application's main thread.
 *
 * @hide
 */
oneway interface IBackupObserver {
    /**
     * This method could be called several times for packages with full data backup.
     * It will tell how much of backup data is already saved and how much is expected.
     *
     * @param currentBackupPackage The name of the package that now being backuped.
     * @param backupProgress Current progress of backup for the package.
     */
    void onUpdate(String currentPackage, in BackupProgress backupProgress);

    /**
     * The backup of single package has completed.  This method will be called at most one time
     * for each package and could be not called if backup is failed before and
     * backupFinished() is called.
     *
     * @param currentBackupPackage The name of the package that was backuped.
     * @param status Zero on success; a nonzero error code if the backup operation failed.
     */
    void onResult(String currentPackage, int status);

    /**
     * The backup process has completed.  This method will always be called,
     * even if no individual package backup operations were attempted.
     *
     * @param status Zero on success; a nonzero error code if the backup operation
     *   as a whole failed.
     */
    void backupFinished(int status);
}
