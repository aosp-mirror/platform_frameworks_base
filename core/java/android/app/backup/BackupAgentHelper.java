/*
 * Copyright (C) 2007 The Android Open Source Project
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

import java.io.IOException;

/**
 * A convenient BackupAgent wrapper class that automatically manages
 * heterogeneous data sets within the backup data, each identified by a unique
 * key prefix. An application will typically extend this class in their own
 * backup agent. Then, within the agent's onBackup() and onRestore() methods, it
 * will call {@link #addHelper(String, BackupHelper)} one or more times to
 * specify the data sets, then invoke super.onBackup() or super.onRestore() to
 * have the BackupAgentHelper implementation process the data.
 * <p>
 * STOPSHIP: document!
 */
public class BackupAgentHelper extends BackupAgent {
    static final String TAG = "BackupAgentHelper";

    BackupHelperDispatcher mDispatcher = new BackupHelperDispatcher();

    /**
     * Run the backup process on each of the configured handlers.
     */
    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
             ParcelFileDescriptor newState) throws IOException {
        mDispatcher.performBackup(oldState, data, newState);
    }

    /**
     * Run the restore process on each of the configured handlers.
     */
    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        mDispatcher.performRestore(data, appVersionCode, newState);
    }

    /** @hide */
    public BackupHelperDispatcher getDispatcher() {
        return mDispatcher;
    }

    /**
     * Add a helper for a given data subset to the agent's configuration.  Each helper
     * must have a prefix string that is unique within this backup agent's set of
     * helpers.
     *
     * @param keyPrefix A string used to disambiguate the various helpers within this agent
     * @param helper A backup/restore helper object to be invoked during backup and restore
     *    operations.
     */
    public void addHelper(String keyPrefix, BackupHelper helper) {
        mDispatcher.addHelper(keyPrefix, helper);
    }
}


