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

package android.backup;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;

/** @hide */
public class FileBackupHelper extends FileBackupHelperBase implements BackupHelper {
    private static final String TAG = "FileBackupHelper";

    Context mContext;
    File mFilesDir;
    String[] mFiles;

    public FileBackupHelper(Context context, String... files) {
        super(context);

        mContext = context;
        mFilesDir = context.getFilesDir();
        mFiles = files;
    }

    /**
     * Based on oldState, determine which of the files from the application's data directory
     * need to be backed up, write them to the data stream, and fill in newState with the
     * state as it exists now.
     */
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        // file names
        String[] files = mFiles;
        File base = mContext.getFilesDir();
        final int N = files.length;
        String[] fullPaths = new String[N];
        for (int i=0; i<N; i++) {
            fullPaths[i] = (new File(base, files[i])).getAbsolutePath();
        }

        // go
        performBackup_checked(oldState, data, newState, fullPaths, files);
    }

    public void restoreEntity(BackupDataInputStream data) {
        // TODO: turn this off before ship
        Log.d(TAG, "got entity '" + data.getKey() + "' size=" + data.size());
        String key = data.getKey();
        if (isKeyInList(key, mFiles)) {
            File f = new File(mFilesDir, key);
            writeFile(f, data);
        }
    }
}

