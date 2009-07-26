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

package android.backup;

import android.app.BackupAgent;
import android.backup.BackupHelper;
import android.backup.BackupHelperDispatcher;
import android.backup.BackupDataInput;
import android.backup.BackupDataOutput;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

/** @hide */
public class BackupHelperAgent extends BackupAgent {
    static final String TAG = "BackupHelperAgent";

    BackupHelperDispatcher mDispatcher = new BackupHelperDispatcher();

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
             ParcelFileDescriptor newState) throws IOException {
        mDispatcher.performBackup(oldState, data, newState);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        mDispatcher.performRestore(data, appVersionCode, newState);
    }

    public BackupHelperDispatcher getDispatcher() {
        return mDispatcher;
    }

    public void addHelper(String keyPrefix, BackupHelper helper) {
        mDispatcher.addHelper(keyPrefix, helper);
    }
}


