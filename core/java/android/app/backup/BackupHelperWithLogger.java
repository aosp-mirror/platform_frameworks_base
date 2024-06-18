/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.ParcelFileDescriptor;

/**
 * Utility class for writing BackupHelpers with added logging capabilities.
 * Used for passing a logger object to Helper in key shared backup agents
 *
 * @hide
 */
public abstract class BackupHelperWithLogger implements BackupHelper {
    private BackupRestoreEventLogger mLogger;
    private boolean mIsLoggerSet = false;

    public abstract void writeNewStateDescription(ParcelFileDescriptor newState);

    public abstract void restoreEntity(BackupDataInputStream data);

    public abstract void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState);

    /**
     * Gets the logger so that the backuphelper can log success/error for each datatype handled
     */
    public BackupRestoreEventLogger getLogger() {
        return mLogger;
    }

    /**
     * Allow the shared backup agent to pass a logger to each of its backup helper
     */
    public void setLogger(BackupRestoreEventLogger logger) {
        mLogger = logger;
        mIsLoggerSet = true;
    }

    /**
     * Allow the helper to check if its shared backup agent has passed a logger
     */
    public boolean isLoggerSet() {
        return mIsLoggerSet;
    }
}
