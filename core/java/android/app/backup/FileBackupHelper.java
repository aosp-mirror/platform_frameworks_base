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

package android.app.backup;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;

/**
 * A helper class which can be used in conjunction with
 * {@link android.app.backup.BackupAgentHelper} to manage the backup of a set of
 * files. Whenever backup is performed, all files changed since the last backup
 * will be saved in their entirety. During the first time the backup happens,
 * all the files in the list will be backed up. Note that this should only be
 * used with small configuration files and not with large binary files.
 * <p>
 * Any files not present in the list of files during the restore procedure will
 * be ignored. If files present in a previous version of an application are
 * removed in subsequent versions, it is the responsibility of the developer to
 * design a mechanism to remove those files. Otherwise files no longer needed
 * will linger and consume space on the device.
 * <p>
 * STOPSHIP: document! [manages backup of a set of files; restore is totally
 * opaque]
 */
public class FileBackupHelper extends FileBackupHelperBase implements BackupHelper {
    private static final String TAG = "FileBackupHelper";
    private static final boolean DEBUG = false;

    Context mContext;
    File mFilesDir;
    String[] mFiles;

    /**
     * Construct a helper to manage backup/restore of entire files within the
     * application's data directory hierarchy.
     *
     * @param context The backup agent's Context object
     * @param files A list of the files to be backed up or restored.
     */
    public FileBackupHelper(Context context, String... files) {
        super(context);

        mContext = context;
        mFilesDir = context.getFilesDir();
        mFiles = files;
    }

    /**
     * Based on <code>oldState</code>, determine which of the files from the
     * application's data directory need to be backed up, write them to the data
     * stream, and fill in <code>newState</code> with the state as it exists
     * now. When <code>oldState</code> is <code>null</code>, all the files will
     * be backed up.
     * <p>
     * This should be called from {@link android.app.backup.BackupAgentHelper}
     * directly. See
     * {@link android.app.backup.BackupAgent#onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor)}
     * for a description of parameter meanings.
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

    /**
     * Restore one record [representing a single file] from the restore dataset.
     */
    public void restoreEntity(BackupDataInputStream data) {
        if (DEBUG) Log.d(TAG, "got entity '" + data.getKey() + "' size=" + data.size());
        String key = data.getKey();
        if (isKeyInList(key, mFiles)) {
            File f = new File(mFilesDir, key);
            writeFile(f, data);
        }
    }
}

