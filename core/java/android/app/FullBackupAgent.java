/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.app;

import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FileBackupHelper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * Backs up an application's entire /data/data/&lt;package&gt;/... file system.  This
 * class is used by the desktop full backup mechanism and is not intended for direct
 * use by applications.
 * 
 * {@hide}
 */

public class FullBackupAgent extends BackupAgent {
    // !!! TODO: turn off debugging
    private static final String TAG = "FullBackupAgent";
    private static final boolean DEBUG = true;

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        LinkedList<File> dirsToScan = new LinkedList<File>();
        ArrayList<String> allFiles = new ArrayList<String>();

        // build the list of files in the app's /data/data tree
        dirsToScan.add(getFilesDir());
        if (DEBUG) Log.v(TAG, "Backing up dir tree @ " + getFilesDir().getAbsolutePath() + " :");
        while (dirsToScan.size() > 0) {
            File dir = dirsToScan.removeFirst();
            File[] contents = dir.listFiles();
            if (contents != null) {
                for (File f : contents) {
                    if (f.isDirectory()) {
                        dirsToScan.add(f);
                    } else if (f.isFile()) {
                        if (DEBUG) Log.v(TAG, "    " + f.getAbsolutePath());
                        allFiles.add(f.getAbsolutePath());
                    }
                }
            }
        }

        // That's the file set; now back it all up
        FileBackupHelper helper = new FileBackupHelper(this,
                allFiles.toArray(new String[allFiles.size()]));
        helper.performBackup(oldState, data, newState);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState) {
    }
}
