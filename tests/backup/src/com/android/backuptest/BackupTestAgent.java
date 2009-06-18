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

package com.android.backuptest;

import android.app.BackupAgent;
import android.backup.BackupDataInput;
import android.backup.BackupDataOutput;
import android.backup.FileBackupHelper;
import android.backup.FileRestoreHelper;
import android.backup.RestoreHelperDispatcher;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

public class BackupTestAgent extends BackupAgent
{
    static final String TAG = "BackupTestAgent";

    static final String SHARED_PREFS = "shared_prefs";
    static final String DATA_FILES = "data_files";
    static final String[] FILES = new String[] {
                    BackupTestActivity.FILE_NAME
                };

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
             ParcelFileDescriptor newState) {
        Log.d(TAG, "onBackup");

        (new FileBackupHelper(this, DATA_FILES)).performBackup(oldState, data, newState, FILES);
    }

    @Override
    public void onRestore(BackupDataInput data, ParcelFileDescriptor newState)
            throws IOException {
        Log.d(TAG, "onRestore");

        RestoreHelperDispatcher dispatch = new RestoreHelperDispatcher();

        // dispatch.addHelper(SHARED_PREFS, new SharedPrefsRestoreHelper(this));
        dispatch.addHelper(DATA_FILES, new FileRestoreHelper(this));

        dispatch.dispatch(data);
    }
}

